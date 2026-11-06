/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.runtime.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.AuthorizationProvider;
import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.springframework.http.HttpStatus;

class SidecarServiceTest {

    private static final String SCOPE = "oc:sidecars:read";
    private static final ConnectorDTO S3 = new ConnectorDTO("Lake_S3", "Migration lake", "output",
            "org.opencrawling.s3.S3OutputConnector", 10, Map.of("bucket", "lake", "keyPrefix", "migration/v1/"));
    private static final ConnectorDTO OZONE = new ConnectorDTO("Lake_Ozone", "Ozone lake", "output",
            "org.opencrawling.ozone.OzoneOutputConnector", 10, Map.of("bucket", "migration"));
    private static final ConnectorDTO UNLISTED = new ConnectorDTO("Other_S3", "Not listed", "output",
            "org.opencrawling.s3.S3OutputConnector", 10, Map.of());
    private static final ConnectorDTO PGVECTOR = new ConnectorDTO("PGVector_Output", "Vectors", "output",
            "org.opencrawling.vector.VectorOutputConnector", 10, Map.of());

    /** An in-memory bucket. */
    static final class MemoryStore implements SidecarStore {
        final NavigableMap<String, byte[]> objects = new TreeMap<>();
        final List<String> reads = new ArrayList<>();
        int lists;
        boolean closed;
        RuntimeException failure;

        @Override
        public Page list(String prefix, String startAfter, int maxKeys) {
            if (failure != null) {
                throw failure;
            }
            lists++;
            List<String> keys = new ArrayList<>();
            for (String key : startAfter == null ? objects.navigableKeySet() : objects.tailMap(startAfter, false).navigableKeySet()) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                if (keys.size() == maxKeys) {
                    return new Page(keys, true);
                }
                keys.add(key);
            }
            return new Page(keys, false);
        }

        @Override
        public byte[] read(String key, int maxBytes) {
            reads.add(key);
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                return null;
            }
            return bytes.length > maxBytes ? java.util.Arrays.copyOf(bytes, maxBytes + 1) : bytes;
        }

        @Override
        public void close() {
            closed = true;
        }

        void put(String key, String json) {
            objects.put(key, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final MemoryStore s3 = new MemoryStore();
    private final MemoryStore ozone = new MemoryStore();
    private SidecarProperties properties;
    private Instant now = Instant.parse("2026-11-06T12:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    @BeforeEach
    void setUp() {
        properties = new SidecarProperties();
        properties.setEnabled(true);
        properties.setConnectors(List.of("Lake_S3", "Lake_Ozone", "PGVector_Output"));
    }

    private SidecarService service() {
        return service(DocumentAccessGate.metadataDefault());
    }

    private SidecarService service(DocumentAccessGate gate) {
        return new SidecarService(properties, () -> List.of(S3, OZONE, UNLISTED, PGVECTOR),
                connector -> connector.name().equals("Lake_S3") ? s3 : ozone, gate, clock);
    }

    static Caller user(String principal, String... groups) {
        return new Caller(principal, List.of(groups), null, true, Map.of(JwtCallerMapper.SCOPES, List.of("openid", SCOPE)));
    }

    private static final Caller ALICE = user("alice", "finance");

    static String sidecar(String id, String readers) {
        return sidecar(id, "UPSERT", readers, "application/pdf", "repository");
    }

    static String sidecar(String id, String action, String readers, String mimeType, String sourceType) {
        String security = readers == null ? "null" : "{\"inheritanceEnabled\":false,\"permissions\":[{\"identity\":\""
                + readers + "\",\"identityType\":\"GROUP\",\"displayName\":\"x\",\"access\":\"read\"}]}";
        return "{\"$schema\":\"https://opencrawling.org/schemas/v1/ois-document.json\",\"id\":\"" + id + "\",\"action\":\""
                + action + "\",\"uri\":\"s3://lake/" + id + "\",\"lastModified\":\"2026-01-01T00:00:00Z\","
                + "\"source\":{\"type\":\"" + sourceType + "\",\"originalUri\":\"cmis://repo/" + id + "\",\"originalPath\":\"/Finance/" + id + "\"},"
                + "\"contentRef\":{\"key\":\"" + id + "\",\"bucket\":\"lake\",\"region\":\"eu\",\"filename\":\"" + id + ".pdf\",\"mimeType\":\""
                + mimeType + "\",\"contentLength\":1000,\"checksumSha256\":\"abc\",\"storageClass\":\"STANDARD\"},"
                + "\"metadata\":{\"title\":[\"" + id + "\"],\"acl\":[\"" + readers + "\"],\"security_allowed_read\":[\"x\"]},"
                + "\"security\":" + security + ",\"acl\":\"\"}";
    }

    @Test
    void onlyReadableSidecarsAreReturnedAndWithoutPermissions() {
        s3.put("migration/v1/a.pdf", "binary");
        s3.put("migration/v1/a.pdf.ois.json", sidecar("a", "finance"));
        s3.put("migration/v1/b.pdf.ois.json", sidecar("b", "hr"));
        s3.put("migration/v1/c.pdf.ois.json", sidecar("c", "UPSERT", "public", "text/plain", "cmis"));

        SidecarService.SidecarPage page = service().read(ALICE, "Lake_S3", null, null, null);

        assertThat(page.sidecars()).extracting(SidecarService.SidecarView::documentId).containsExactly("a", "c");
        SidecarService.SidecarView a = page.sidecars().getFirst();
        assertThat(a.key()).isEqualTo("migration/v1/a.pdf.ois.json");
        assertThat(a.filename()).isEqualTo("a.pdf");
        assertThat(a.originalPath()).isEqualTo("/Finance/a");
        assertThat(a.contentLength()).isEqualTo(1000L);
        assertThat(a.metadata()).containsOnlyKeys("title");
        assertThat(page.aggregates().count()).isEqualTo(2);
        assertThat(page.aggregates().contentBytes()).isEqualTo(2000);
        assertThat(page.aggregates().byMimeType()).containsOnly(Map.entry("application/pdf", 1L), Map.entry("text/plain", 1L));
        assertThat(page.aggregates().bySourceType()).containsEntry("cmis", 1L).containsEntry("repository", 1L);
        assertThat(page.nextCursor()).isNull();
        // The binary is never read; the store is closed.
        assertThat(s3.reads).doesNotContain("migration/v1/a.pdf");
        assertThat(s3.closed).isTrue();
        // Bob in hr sees b and the public c, not a.
        assertThat(service().read(user("bob", "hr"), "Lake_S3", null, null, null).sidecars())
                .extracting(SidecarService.SidecarView::documentId).containsExactly("b", "c");
    }

    @Test
    void tombstonesTheIndexAndSidecarsThatCannotBeReturnedAreSkipped() {
        properties.setMaxSidecarBytes(2048);
        ozone.put(".tombstones/x.pdf.ois.json", sidecar("x", "DELETE", "public", "a/b", "r"));
        ozone.put(".opencrawling/index/abc", "x.pdf");
        ozone.put("d/deleted.pdf.ois.json", sidecar("deleted", "DELETE", "public", "a/b", "r"));
        ozone.put("d/invalid.pdf.ois.json", "{not json");
        ozone.put("d/no-permissions.pdf.ois.json", sidecar("no-permissions", null));
        ozone.put("d/no-id.pdf.ois.json", sidecar("", "public"));
        ozone.put("d/large.pdf.ois.json", sidecar("large", "public").replace("\"title\":[", "\"pad\":[\"" + "p".repeat(4000) + "\"],\"title\":["));
        ozone.put("d/ok.pdf.ois.json", sidecar("ok", "public"));

        SidecarService.SidecarPage page = service().read(ALICE, "Lake_Ozone", null, null, null);

        assertThat(page.sidecars()).extracting(SidecarService.SidecarView::documentId).containsExactly("ok");
        assertThat(ozone.reads).doesNotContain(".tombstones/x.pdf.ois.json", ".opencrawling/index/abc");
    }

    @Test
    void theAuthorizationGateDecidesOnTheDocumentIdAndSourcePermissions() {
        List<ResourceRef> asked = new ArrayList<>();
        AuthorizationProvider relationships = new AuthorizationProvider() {
            @Override
            public String name() {
                return "relationships";
            }

            @Override
            public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
                asked.addAll(candidates);
                boolean[] decisions = new boolean[candidates.size()];
                for (int i = 0; i < decisions.length; i++) {
                    decisions[i] = "b".equals(candidates.get(i).documentId());
                }
                return decisions;
            }
        };
        s3.put("migration/v1/a.pdf.ois.json", sidecar("a", "finance"));
        s3.put("migration/v1/b.pdf.ois.json", sidecar("b", "hr"));

        SidecarService.SidecarPage page = service(new DocumentAccessGate(relationships)).read(ALICE, "Lake_S3", null, null, null);

        assertThat(page.sidecars()).extracting(SidecarService.SidecarView::documentId).containsExactly("b");
        assertThat(asked).extracting(ResourceRef::documentId).containsExactly("a", "b");
        assertThat(asked.getFirst().metadata()).containsKey("security").containsEntry("documentId", "a");
    }

    @Test
    void aSidecarWithNoPermissionsIsHiddenEvenWhenTheProviderWouldAllowIt() {
        AuthorizationProvider allowsAll = new AuthorizationProvider() {
            @Override
            public String name() {
                return "allows-all";
            }

            @Override
            public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
                boolean[] decisions = new boolean[candidates.size()];
                java.util.Arrays.fill(decisions, true);
                return decisions;
            }
        };
        s3.put("migration/v1/a.ois.json", sidecar("a", null));
        s3.put("migration/v1/b.ois.json", sidecar("b", "hr"));

        assertThat(service(new DocumentAccessGate(allowsAll)).read(ALICE, "Lake_S3", null, null, null).sidecars())
                .extracting(SidecarService.SidecarView::documentId).containsExactly("b");
    }

    @Test
    void aValidSidecarOneByteOverTheLimitIsSkipped() {
        properties.setMaxSidecarBytes(2048);
        String base = sidecar("big", "public");
        String exact = base.replace("\"title\":[", "\"pad\":[\"" + "p".repeat(2048 + 1 - base.length() - 11) + "\"],\"title\":[");
        assertThat(exact.getBytes(StandardCharsets.UTF_8)).hasSize(2049);
        s3.put("migration/v1/big.ois.json", exact);
        s3.put("migration/v1/fits.ois.json", sidecar("fits", "public"));

        assertThat(service().read(ALICE, "Lake_S3", null, null, null).sidecars())
                .extracting(SidecarService.SidecarView::documentId).containsExactly("fits");
    }

    @Test
    void aCursorDoesNotCarryOverToAnotherStoreWithTheSamePrefix() {
        ConnectorDTO twin = new ConnectorDTO("Lake_Ozone_2", "Twin", "output", "org.opencrawling.ozone.OzoneOutputConnector",
                10, Map.of("bucket", "other"));
        properties.setConnectors(List.of("Lake_Ozone", "Lake_Ozone_2"));
        for (int i = 0; i < 3; i++) {
            ozone.put("f/k" + i + ".ois.json", sidecar("doc" + i, "public"));
        }
        SidecarService service = new SidecarService(properties, () -> List.of(OZONE, twin), connector -> ozone,
                DocumentAccessGate.metadataDefault(), clock);
        String cursor = service.read(ALICE, "Lake_Ozone", "f/", 1, null).nextCursor();

        assertThatThrownBy(() -> service.read(ALICE, "Lake_Ozone_2", "f/", 1, cursor))
                .isInstanceOfSatisfying(SidecarException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void anUndecidableAnswerAndAnUnreadableStoreAreErrorsWithoutDetails() {
        s3.put("migration/v1/a.pdf.ois.json", sidecar("a", "finance"));
        AuthorizationProvider down = new AuthorizationProvider() {
            @Override
            public String name() {
                return "down";
            }

            @Override
            public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
                throw new AuthorizationUnavailableException("openfga timed out");
            }
        };
        assertThatThrownBy(() -> service(new DocumentAccessGate(down)).read(ALICE, "Lake_S3", null, null, null))
                .isInstanceOfSatisfying(SidecarException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        s3.failure = new IllegalStateException("AccessDenied for arn:aws:iam::123:role/secret");
        assertThatThrownBy(() -> service().read(ALICE, "Lake_S3", null, null, null))
                .isInstanceOfSatisfying(SidecarException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).doesNotContain("arn").doesNotContain("AccessDenied");
                });
        assertThat(s3.closed).isTrue();
    }

    @Test
    void pagesFollowEachOtherWithASealedCursor() {
        for (int i = 0; i < 12; i++) {
            s3.put("migration/v1/k" + (char) ('a' + i) + ".ois.json", sidecar("doc" + i, i % 2 == 0 ? "finance" : "secret-board"));
        }
        SidecarService service = service();
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            SidecarService.SidecarPage page = service.read(ALICE, "Lake_S3", null, 2, cursor);
            page.sidecars().forEach(sidecar -> seen.add(sidecar.documentId()));
            cursor = page.nextCursor();
            if (cursor != null) {
                // The cursor may point at a hidden sidecar, so its key must not be readable in it.
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.ISO_8859_1);
                assertThat(decoded).doesNotContain("migration/v1/");
            }
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).containsExactly("doc0", "doc2", "doc4", "doc6", "doc8", "doc10");
        // The third page ends on doc10 with a key after it, so a fourth (empty) page confirms the end.
        assertThat(pages).isEqualTo(4);
    }

    @Test
    void aCursorOnlyContinuesTheSameRequestOfTheSameCaller() {
        for (int i = 0; i < 4; i++) {
            s3.put("migration/v1/f/k" + i + ".ois.json", sidecar("doc" + i, "public"));
        }
        SidecarService service = service();
        String cursor = service.read(ALICE, "Lake_S3", "f/", 1, null).nextCursor();
        assertThat(cursor).isNotNull();

        assertThat(service.read(ALICE, "Lake_S3", "f/", 1, cursor).sidecars())
                .extracting(SidecarService.SidecarView::documentId).containsExactly("doc1");
        for (Runnable misuse : List.<Runnable>of(
                () -> service.read(user("mallory", "finance"), "Lake_S3", "f/", 1, cursor),
                () -> service.read(ALICE, "Lake_S3", "g/", 1, cursor),
                () -> service.read(ALICE, "Lake_Ozone", "f/", 1, cursor),
                () -> service.read(ALICE, "Lake_S3", "f/", 1, cursor.substring(0, cursor.length() - 2) + "AA"),
                () -> service.read(ALICE, "Lake_S3", "f/", 1, "not-a-cursor"),
                // Another instance without the same secret.
                () -> service().read(ALICE, "Lake_S3", "f/", 1, cursor))) {
            assertThatThrownBy(misuse::run).isInstanceOfSatisfying(SidecarException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        now = now.plusSeconds(3601);
        assertThatThrownBy(() -> service.read(ALICE, "Lake_S3", "f/", 1, cursor)).isInstanceOf(SidecarException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void aSharedCursorSecretLetsAnotherInstanceContinue() {
        properties.setCursorSecret("a-cursor-secret-shared-by-every-instance-0123");
        for (int i = 0; i < 3; i++) {
            s3.put("migration/v1/k" + i + ".ois.json", sidecar("doc" + i, "public"));
        }
        String cursor = service().read(ALICE, "Lake_S3", null, 1, null).nextCursor();

        assertThat(service().read(ALICE, "Lake_S3", null, 1, cursor).sidecars())
                .extracting(SidecarService.SidecarView::documentId).containsExactly("doc1");
        properties.setCursorSecret("short");
        assertThatThrownBy(this::service).hasMessageContaining("at least 32");
    }

    @Test
    void theListingIsBoundedAndSaysSo() {
        properties.setMaxScan(3);
        for (int i = 0; i < 7; i++) {
            s3.put("migration/v1/k" + i + ".ois.json", sidecar("doc" + i, i == 5 ? "finance" : "secret-board"));
        }
        SidecarService service = service();

        SidecarService.SidecarPage first = service.read(ALICE, "Lake_S3", null, 10, null);
        assertThat(first.sidecars()).isEmpty();
        assertThat(first.scanLimitReached()).isTrue();
        assertThat(first.nextCursor()).isNotNull();
        assertThat(s3.reads).hasSize(3);

        SidecarService.SidecarPage second = service.read(ALICE, "Lake_S3", null, 10, first.nextCursor());
        assertThat(second.sidecars()).extracting(SidecarService.SidecarView::documentId).containsExactly("doc5");
        assertThat(second.nextCursor()).isNotNull();
        SidecarService.SidecarPage third = service.read(ALICE, "Lake_S3", null, 10, second.nextCursor());
        assertThat(third.sidecars()).isEmpty();
        assertThat(third.nextCursor()).isNull();
        assertThat(third.scanLimitReached()).isFalse();
    }

    @Test
    void limitsAreBounded() {
        properties.setMaxLimit(3);
        for (int i = 0; i < 6; i++) {
            s3.put("migration/v1/k" + i + ".ois.json", sidecar("doc" + i, "public"));
        }
        assertThat(service().read(ALICE, "Lake_S3", null, 5000, null).sidecars()).hasSize(3);
        assertThat(service().read(ALICE, "Lake_S3", null, 0, null).sidecars()).hasSize(1);
        properties.setDefaultLimit(2);
        assertThat(service().read(ALICE, "Lake_S3", null, null, null).sidecars()).hasSize(2);
    }

    @Test
    void anAnswerStopsAtItsByteBudget() {
        properties.setMaxSidecarBytes(2 * 1024 * 1024);
        String pad = "p".repeat(1024 * 1024);
        for (int i = 0; i < 12; i++) {
            s3.put("migration/v1/k" + String.format("%02d", i) + ".ois.json",
                    sidecar("doc" + i, "public").replace("\"title\":[", "\"pad\":[\"" + pad + "\"],\"title\":["));
        }
        SidecarService.SidecarPage page = service().read(ALICE, "Lake_S3", null, 50, null);

        assertThat(page.sidecars()).hasSize(8);
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void thePrefixIsRelativeToWhereTheConnectorWrites() {
        s3.put("elsewhere/x.ois.json", sidecar("x", "public"));
        s3.put("migration/v1/finance/a.ois.json", sidecar("a", "public"));
        s3.put("migration/v1/hr/b.ois.json", sidecar("b", "public"));

        assertThat(service().read(ALICE, "Lake_S3", "/finance/", null, null))
                .satisfies(page -> {
                    assertThat(page.sidecars()).extracting(SidecarService.SidecarView::documentId).containsExactly("a");
                    assertThat(page.prefix()).isEqualTo("finance/");
                });
        assertThat(service().read(ALICE, "Lake_S3", null, null, null).sidecars())
                .extracting(SidecarService.SidecarView::documentId).containsExactly("a", "b");
        assertThatThrownBy(() -> service().read(ALICE, "Lake_S3", "a\nb", null, null)).isInstanceOf(SidecarException.class);
        assertThatThrownBy(() -> service().read(ALICE, "Lake_S3", "x".repeat(1025), null, null)).isInstanceOf(SidecarException.class);
    }

    @Test
    void onlyListedOzoneAndS3ConnectorsAreStores() {
        assertThat(service().listStores(ALICE)).extracting(SidecarService.StoreInfo::name, SidecarService.StoreInfo::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Lake_S3", "s3"), org.assertj.core.groups.Tuple.tuple("Lake_Ozone", "ozone"));
        for (String name : List.of("Other_S3", "PGVector_Output", "Nope")) {
            assertThatThrownBy(() -> service().read(ALICE, name, null, null, null))
                    .isInstanceOfSatisfying(SidecarException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        assertThatThrownBy(() -> service().read(ALICE, " ", null, null, null))
                .isInstanceOfSatisfying(SidecarException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void theCallerMustBeVerifiedNamedAndScoped() {
        SidecarService service = service();
        Caller asserted = Caller.asserted("alice", List.of("finance"));
        assertThatThrownBy(() -> service.read(asserted, "Lake_S3", null, null, null))
                .isInstanceOfSatisfying(SidecarException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> service.listStores(null))
                .isInstanceOfSatisfying(SidecarException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        Caller unscoped = new Caller("alice", List.of("finance"), null, true, Map.of(JwtCallerMapper.SCOPES, List.of("openid")));
        assertThatThrownBy(() -> service.read(unscoped, "Lake_S3", null, null, null))
                .isInstanceOfSatisfying(SidecarException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        Caller nameless = new Caller(" ", List.of(), null, true, Map.of(JwtCallerMapper.SCOPES, List.of(SCOPE)));
        assertThatThrownBy(() -> service.read(nameless, "Lake_S3", null, null, null))
                .isInstanceOfSatisfying(SidecarException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));

        properties.setAllowAssertedIdentity(true);
        properties.setRequiredScope("");
        s3.put("migration/v1/a.ois.json", sidecar("a", "finance"));
        assertThat(service().read(asserted, "Lake_S3", null, null, null).sidecars()).hasSize(1);
        assertThat(service().read(unscoped, "Lake_S3", null, null, null).sidecars()).hasSize(1);
    }
}
