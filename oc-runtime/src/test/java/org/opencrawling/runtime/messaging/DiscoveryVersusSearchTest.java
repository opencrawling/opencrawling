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
package org.opencrawling.runtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.connector.discovery.DiscoveryNode;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentChunkMessage;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.ReadKeys;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.core.text.TextExtractionResult;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.runtime.discovery.SourceAclVisibility;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Discovery and search must agree on who may read what. Discovery decides on the ACL a repository reports now
 * ({@link SourceAclVisibility}); search decides on the metadata an indexed chunk was stored with. Here the same
 * ACLs go both ways: once as discovery nodes, once through the real ingestion consumer, the writer's permission
 * fields and a JSON round trip (as a store keeps them), and then the search gate in {@code metadata} mode.
 *
 * <p>The corpus is generated from a fixed seed, so a failure always reproduces, and covers case variants,
 * {@code user:}/{@code group:} prefixes and bare identities (#224), denies, write grants, public, inheritance, and
 * documents with no or empty ACLs.
 */
class DiscoveryVersusSearchTest {

    private static final long SEED = 203L;
    private static final int DOCUMENTS = 400;
    private static final List<String> IDENTITIES = List.of("alice", "Alice", "user:alice", "USER:Alice", "bob",
            "user:bob", "carol", "finance", "Finance", "group:finance", "GROUP:Finance", "user:finance", "hr",
            "group:hr", "public", "PUBLIC", "group:board", "nobody");
    private static final List<String> ACCESS = List.of("read", "read", "read", "write", "deny");

    /** Callers, as principal and groups. */
    private static final Map<String, List<String>> CALLERS = new LinkedHashMap<>();

    static {
        CALLERS.put("alice", List.of("finance"));
        CALLERS.put("ALICE", List.of("Finance"));
        CALLERS.put("user:alice", List.of("group:finance"));
        CALLERS.put("bob", List.of("finance", "hr"));
        CALLERS.put("carol", List.of());
        CALLERS.put("dave", List.of("hr"));
        CALLERS.put("finance", List.of());
        CALLERS.put("eve", List.of("board", "group:hr"));
    }

    private record Doc(String id, SecurityConfig security) {}

    private static final List<Doc> CORPUS = new ArrayList<>();
    /** Each document's chunk metadata, as a vector store returns it. */
    private static final Map<String, Map<String, Object>> STORED = new LinkedHashMap<>();
    /** Documents the ingestion consumer did not index. */
    private static final List<String> UNINDEXED = new ArrayList<>();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void ingestTheCorpus() throws Exception {
        Random random = new Random(SEED);
        for (int i = 0; i < DOCUMENTS; i++) {
            CORPUS.add(new Doc("doc-" + i, randomSecurity(random)));
        }

        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(anyString(), anyString(), any());
        ClaimCheckStore claims = mock(ClaimCheckStore.class);
        when(claims.supports(any())).thenReturn(true);
        when(claims.get(any())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[] {1}));
        TextExtractionService extraction = mock(TextExtractionService.class);
        when(extraction.extractText(any(byte[].class), any())).thenReturn(TextExtractionResult.success("Some text.", Map.of(), false));
        IngestionConsumer consumer = new IngestionConsumer(kafka, claims, new ClaimCheckProperties(), null, extraction);
        JsonMapper json = JsonMapper.builder().build();

        for (Doc doc : CORPUS) {
            clearInvocations(kafka);
            // Connectors send an empty legacy acl alongside the security rules.
            consumer.consume(new IngestionMessage(doc.id(), "file:///claims/" + doc.id(), Map.of("title", List.of(doc.id())),
                    "", doc.security(), "2026-01-01T00:00:00Z", null, null, Map.of(), DocumentAction.UPSERT, null, null));
            ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
            verify(kafka, org.mockito.Mockito.atLeast(0)).send(eq(KafkaConfig.CHUNKS_TOPIC_NAME), anyString(), sent.capture());
            if (sent.getAllValues().isEmpty()) {
                // Not indexed at all (a document with no permissions cannot be stored), so never searchable.
                UNINDEXED.add(doc.id());
                continue;
            }
            DocumentChunkMessage chunk = (DocumentChunkMessage) sent.getValue();
            Map<String, Object> metadata = new HashMap<>(chunk.metadata());
            VectorStoreWriterConsumer.ensureSecurityMetadata(metadata);
            String stored = json.writeValueAsString(metadata);
            STORED.put(doc.id(), json.readValue(stored, new TypeReference<Map<String, Object>>() {}));
        }
    }

    private static SecurityConfig randomSecurity(Random random) {
        int roll = random.nextInt(100);
        if (roll < 5) {
            return null;
        }
        if (roll < 8) {
            return new SecurityConfig(random.nextBoolean(), List.of());
        }
        int rules = 1 + random.nextInt(3);
        List<PermissionRule> permissions = new ArrayList<>();
        for (int r = 0; r < rules; r++) {
            String identity = IDENTITIES.get(random.nextInt(IDENTITIES.size()));
            String type = identity.toLowerCase().startsWith("group:") || identity.equalsIgnoreCase("finance")
                    || identity.equals("hr") ? "GROUP" : identity.equalsIgnoreCase("public") ? "PUBLIC" : "USER";
            permissions.add(new PermissionRule(identity, type, identity, ACCESS.get(random.nextInt(ACCESS.size()))));
        }
        return new SecurityConfig(random.nextBoolean(), permissions);
    }

    /** The documents whose source reports permissions (possibly an empty list). */
    private static List<Doc> withSecurity() {
        return CORPUS.stream().filter(doc -> doc.security() != null).toList();
    }

    private static TreeSet<String> withoutSecurityIds() {
        return new TreeSet<>(CORPUS.stream().filter(doc -> doc.security() == null).map(Doc::id).toList());
    }

    private static TreeSet<String> discoveryView(String principal, List<String> groups) {
        List<DiscoveryNode> nodes = CORPUS.stream().map(doc -> new DiscoveryNode("fake://" + doc.id(), doc.id(), "root",
                doc.id(), "/" + doc.id(), DiscoveryNode.NodeType.DOCUMENT, 1, "text/plain", 1L, null, null, doc.security()))
                .toList();
        boolean[] visible = SourceAclVisibility.forCaller(new Caller(principal, groups, null, true, Map.of())).visible(nodes);
        TreeSet<String> ids = new TreeSet<>();
        for (int i = 0; i < visible.length; i++) {
            if (visible[i]) {
                ids.add(CORPUS.get(i).id());
            }
        }
        return ids;
    }

    private static TreeSet<String> searchView(String principal, List<String> groups) {
        return searchView(principal, groups, STORED);
    }

    /** As a store that keeps only the flattened permission fields (no {@code security} object) returns them. */
    private static Map<String, Map<String, Object>> flattenedOnly() {
        Map<String, Map<String, Object>> flattened = new LinkedHashMap<>();
        STORED.forEach((id, metadata) -> {
            Map<String, Object> copy = new HashMap<>(metadata);
            copy.remove("security");
            flattened.put(id, copy);
        });
        return flattened;
    }

    private static TreeSet<String> searchView(String principal, List<String> groups, Map<String, Map<String, Object>> stored) {
        List<Map.Entry<String, Map<String, Object>>> candidates = new ArrayList<>(stored.entrySet());
        return new TreeSet<>(DocumentAccessGate.metadataDefault()
                .filter(Caller.asserted(principal, groups), candidates, entry -> ResourceRef.fromMetadata(entry.getValue()))
                .stream().map(Map.Entry::getKey).toList());
    }

    @Test
    void discoveryAndSearchShowEachCallerTheSameDocuments() {
        int compared = 0;
        for (Map.Entry<String, List<String>> caller : CALLERS.entrySet()) {
            TreeSet<String> discovered = discoveryView(caller.getKey(), caller.getValue());
            TreeSet<String> searchable = searchView(caller.getKey(), caller.getValue());
            searchable.removeAll(withoutSecurityIds());
            assertThat(searchable).as("search vs discovery for %s %s", caller.getKey(), caller.getValue())
                    .containsExactlyElementsOf(discovered);
            compared += discovered.size();
        }
        // The corpus is mixed enough to mean something: callers see some documents, and not all of them.
        int documents = withSecurity().size();
        assertThat(compared).isBetween(documents / 4, documents * CALLERS.size() - documents / 4);
        assertThat(discoveryView("carol", List.of())).isNotEmpty();
        assertThat(discoveryView("alice", List.of("finance"))).isNotEqualTo(discoveryView("carol", List.of()));
    }

    @Test
    void storesKeepingOnlyTheFlattenedFieldsAgreeToo() {
        Map<String, Map<String, Object>> flattened = flattenedOnly();
        for (Map.Entry<String, List<String>> caller : CALLERS.entrySet()) {
            assertThat(searchView(caller.getKey(), caller.getValue(), flattened))
                    .as("flattened-only search for %s %s", caller.getKey(), caller.getValue())
                    .containsExactlyElementsOf(searchView(caller.getKey(), caller.getValue()));
        }
    }

    @Test
    void documentsWithAnEmptyAclAreHiddenBothWays() {
        List<String> empty = CORPUS.stream()
                .filter(doc -> doc.security() != null && doc.security().permissions().isEmpty()).map(Doc::id).toList();
        assertThat(empty).isNotEmpty();
        for (Map.Entry<String, List<String>> caller : CALLERS.entrySet()) {
            assertThat(discoveryView(caller.getKey(), caller.getValue())).doesNotContainAnyElementsOf(empty);
            assertThat(searchView(caller.getKey(), caller.getValue())).doesNotContainAnyElementsOf(empty);
        }
    }

    /**
     * A document whose source reports no security and no acl is hidden both ways: search must not read silence as
     * public (#261).
     */
    @Test
    void documentsWhoseSourceReportsNoSecurityAreHiddenBothWays() {
        TreeSet<String> withoutSecurity = withoutSecurityIds();
        assertThat(withoutSecurity).isNotEmpty();
        for (Map.Entry<String, List<String>> caller : CALLERS.entrySet()) {
            assertThat(discoveryView(caller.getKey(), caller.getValue())).doesNotContainAnyElementsOf(withoutSecurity);
            assertThat(searchView(caller.getKey(), caller.getValue())).doesNotContainAnyElementsOf(withoutSecurity);
        }
        assertThat(STORED.get(withoutSecurity.first()).get("security_allowed_read")).isEqualTo(List.of());
    }

    @Test
    void aJsonMessageWithNoSecurityFieldIsNotPublic() {
        // Older producers and external ones may leave the field out.
        IngestionMessage message = JsonMapper.builder().build().readValue(
                "{\"documentId\":\"doc-1\",\"uri\":\"fake://doc-1\",\"acl\":\"\"}", IngestionMessage.class);

        assertThat(message.security()).isEqualTo(SecurityConfig.none());
        assertThat(EffectiveAcl.resolve(message.security(), message.acl()).allowedRead()).isEmpty();
    }

    @Test
    void everyDocumentIsIndexed() {
        assertThat(UNINDEXED).isEmpty();
        assertThat(STORED).hasSize(DOCUMENTS);
    }

    @Test
    void theStorePrefilterNeverHidesADocumentTheGateAllows() {
        for (Map.Entry<String, List<String>> caller : CALLERS.entrySet()) {
            List<String> callerKeys = ReadKeys.forCaller(caller.getKey(), caller.getValue());
            for (String id : searchView(caller.getKey(), caller.getValue())) {
                Object keys = STORED.get(id).get(ReadKeys.FIELD);
                assertThat(keys).as("read keys of %s", id).isInstanceOf(List.class);
                List<String> stored = ((List<?>) keys).stream().map(String::valueOf).toList();
                assertThat(stored).as("%s readable by %s %s but filtered out by its read keys %s", id,
                        caller.getKey(), caller.getValue(), keys).containsAnyElementsOf(callerKeys);
            }
        }
    }
}
