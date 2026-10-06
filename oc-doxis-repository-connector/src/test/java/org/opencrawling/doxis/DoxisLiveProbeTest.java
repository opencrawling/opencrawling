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
package org.opencrawling.doxis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.doxis.client.DoxisApiException;
import org.opencrawling.doxis.client.DoxisClient;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Read-only probe against a live Doxis CSB, in ONE session. Skipped unless {@code DOXIS_LIVE_PROBE=1}; nothing about the
 * target is stored in the repository — it comes from the environment:
 * {@code DOXIS_URL}, {@code DOXIS_CUSTOMER}, {@code DOXIS_USER}, {@code DOXIS_PASSWORD}, {@code DOXIS_ROLE},
 * {@code DOXIS_REPOSITORY}, {@code DOXIS_QUERY} (a CQL condition), {@code DOXIS_PROBE_REPORT} (report file).
 *
 * <p>Order: (a) raw search paging with pages of 2 and the result closed; (b+c) a connector scan capped at 3 documents
 * (versions, content, permissions); (d) one audit-trail search and one CQL on {@code DXE_MODDATE}; logout. The connector's
 * own logout is deferred to the end so the whole probe stays one CSB session.
 */
@EnabledIfEnvironmentVariable(named = "DOXIS_LIVE_PROBE", matches = "1")
class DoxisLiveProbeTest {

    private static String env(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " must be set");
        return value;
    }

    /** Passive recorder: method, path, status of every call; a trimmed body for permissions and versions responses. */
    static final class RecordingHttpClient extends HttpClient {
        private final HttpClient delegate;
        final ArrayNode calls;

        RecordingHttpClient(HttpClient delegate, ArrayNode calls) {
            this.delegate = delegate;
            this.calls = calls;
        }

        @Override
        public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request,
                                                      java.net.http.HttpResponse.BodyHandler<T> handler)
                throws java.io.IOException, InterruptedException {
            ObjectNode call = calls.addObject();
            call.put("n", calls.size());
            call.put("method", request.method());
            String path = request.uri().getRawPath();
            int api = path.indexOf("/api/v1");
            call.put("path", (api >= 0 ? path.substring(api + 7) : path)
                    + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
            long start = System.nanoTime();
            try {
                java.net.http.HttpResponse<T> response = delegate.send(request, handler);
                call.put("status", response.statusCode());
                call.put("ms", (System.nanoTime() - start) / 1_000_000);
                if (response.body() instanceof String body && (path.endsWith("/permissions") || path.endsWith("/versions"))) {
                    call.put("body", body.length() > 4000 ? body.substring(0, 4000) + "…" : body);
                }
                return response;
            } catch (java.io.IOException e) {
                call.put("error", e.toString());
                throw e;
            }
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest r,
                java.net.http.HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest r,
                java.net.http.HttpResponse.BodyHandler<T> h, java.net.http.HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }

        @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public java.util.Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public java.util.Optional<java.net.ProxySelector> proxy() { return delegate.proxy(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return delegate.sslContext(); }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public java.util.Optional<java.net.Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return delegate.executor(); }
        @Override public void close() { delegate.close(); }
    }

    /**
     * Follow-up (DOXIS_PROBE_MODE=paging): offset base of /documents/searchResults and DXE_MODDATE literal formats.
     * login; search limit 2; searchResults offset=1 and offset=3; DELETE; two DXE_MODDATE searches + DELETE each; logout.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "DOXIS_PROBE_MODE", matches = "paging")
    void pagingAndModDateFollowUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode report = mapper.createObjectNode();
        String cql = "SELECT * FROM " + env("DOXIS_REPOSITORY") + " WHERE " + env("DOXIS_QUERY");
        DoxisClient client = new DoxisClient(env("DOXIS_URL"), env("DOXIS_CUSTOMER"), env("DOXIS_USER"), env("DOXIS_PASSWORD"),
                env("DOXIS_ROLE"), "OpenCrawling-LiveProbe", Duration.ofSeconds(180), 0, Duration.ofSeconds(5),
                new RecordingHttpClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build(),
                        report.putArray("calls")), new ObjectMapper());
        try (client) {
            DoxisClient.SearchPage first = client.searchDocuments(cql, "NON_DELETED_OBJECTS", 2);
            report.set("page_limit2", pageReport(mapper, first));
            if (first.searchId() != null) {
                try {
                    report.set("offset1", pageReport(mapper, client.nextSearchResults(first.searchId(), 1, 2)));
                    report.set("offset3", pageReport(mapper, client.nextSearchResults(first.searchId(), 3, 2)));
                } finally {
                    client.closeSearch(first.searchId());
                }
            }
            for (String literal : List.of("20261001", "2026-10-01T00:00:00.000Z")) {
                ObjectNode variant = report.putObject("dxeModDate_" + literal);
                String modCql = cql + " AND DXE_MODDATE >= '" + literal + "'";
                variant.put("cql", modCql);
                try {
                    DoxisClient.SearchPage page = client.searchDocuments(modCql, "NON_DELETED_OBJECTS", 1);
                    variant.put("totalHitCount", page.totalHitCount());
                    variant.put("hits", page.hits().size());
                    client.closeSearch(page.searchId());
                } catch (DoxisApiException e) {
                    variant.put("error", e.getStatusCode() + " " + e.getErrorCode() + " " + e.getMessage());
                }
            }
        } finally {
            String path = System.getenv("DOXIS_PROBE_REPORT");
            if (path != null) {
                Files.writeString(Path.of(path), mapper.writeValueAsString(report));
            }
        }
    }

    private static ObjectNode pageReport(ObjectMapper mapper, DoxisClient.SearchPage page) {
        ObjectNode node = mapper.createObjectNode();
        node.put("start", page.start());
        node.put("totalHitCount", page.totalHitCount());
        node.put("restrictionMode", page.restrictionMode());
        ArrayNode uuids = node.putArray("uuids");
        page.hits().forEach(h -> uuids.add(h.path("uuid").asText()));
        return node;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DOXIS_PROBE_MODE", matches = "full")
    void readOnlyProbe() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode report = mapper.createObjectNode();
        String repository = env("DOXIS_REPOSITORY");
        String query = env("DOXIS_QUERY");

        // logout() is a no-op until the probe ends: one session for everything
        DoxisClient client = new DoxisClient(env("DOXIS_URL"), env("DOXIS_CUSTOMER"), env("DOXIS_USER"), env("DOXIS_PASSWORD"),
                env("DOXIS_ROLE"), "OpenCrawling-LiveProbe", Duration.ofSeconds(180), 0, Duration.ofSeconds(5),
                new RecordingHttpClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build(),
                        report.putArray("calls")), new ObjectMapper()) {
            boolean finished;

            @Override
            public void logout() {
                if (finished) {
                    super.logout();
                }
            }

            @Override
            public void close() {
                finished = true;
                super.close();
            }
        };

        try (client) {
            JsonNode repo = client.getRepository(repository);
            String shortName = repo.path("shortName").asText(repo.path("name").asText(repository));
            report.put("repository", shortName);

            // (a) paging semantics: pages of 2, then offset 2, then close
            String cql = "SELECT * FROM " + shortName + " WHERE " + query;
            DoxisClient.SearchPage first = client.searchDocuments(cql, "NON_DELETED_OBJECTS", 2);
            ObjectNode paging = report.putObject("a_paging");
            paging.put("cql", cql);
            paging.put("searchId", first.searchId());
            paging.put("totalHitCount", first.totalHitCount());
            paging.put("restrictionMode", first.restrictionMode());
            paging.put("maxSearchResults", first.maxSearchResults());
            paging.put("page1Hits", first.hits().size());
            if (!first.hits().isEmpty()) {
                ArrayNode keys = paging.putArray("hitFields");
                first.hits().getFirst().fieldNames().forEachRemaining(keys::add);
                paging.put("hitHasVersions", first.hits().getFirst().has("versions"));
            }
            if (first.searchId() != null) {
                try {
                    DoxisClient.SearchPage second = client.nextSearchResults(first.searchId(), first.hits().size(), 2);
                    Set<String> page1 = new HashSet<>();
                    first.hits().forEach(h -> page1.add(h.path("uuid").asText()));
                    long overlap = second.hits().stream().filter(h -> page1.contains(h.path("uuid").asText())).count();
                    paging.put("page2Hits", second.hits().size());
                    paging.put("page2OverlapWithPage1", overlap);
                    paging.put("offsetLooksAbsolute", !second.hits().isEmpty() && overlap == 0);
                } finally {
                    client.closeSearch(first.searchId());
                }
            }

            // (b + c) the connector itself, 3 documents, one at a time
            DoxisRepositorySettings settings = DoxisRepositorySettings.fromConfiguration(Map.of(
                    "url", env("DOXIS_URL"), "customerName", env("DOXIS_CUSTOMER"), "username", env("DOXIS_USER"),
                    "password", env("DOXIS_PASSWORD"), "role", env("DOXIS_ROLE"), "repositoryId", repository,
                    "searchQuery", query, "batchSize", "3", "parallelism", "1", "maxDocuments", "3"));
            List<RepositoryDocument> docs = new DoxisRepositoryConnector(settings, client).scan("default").collectList().block();
            assertNotNull(docs);
            ArrayNode documents = report.putArray("bc_documents");
            for (RepositoryDocument doc : docs) {
                ObjectNode d = documents.addObject();
                d.put("id", doc.id());
                d.put("action", doc.action().name());
                d.put("lastModified", doc.lastModified().toString());
                ObjectNode metadata = d.putObject("metadata");
                doc.metadata().forEach((key, values) -> metadata.putPOJO(key, values));
                long bytes = -1;
                byte[] head = new byte[0];
                if (doc.contentStream() != null) {
                    try (InputStream in = doc.contentStream()) {
                        byte[] all = in.readAllBytes();
                        bytes = all.length;
                        head = java.util.Arrays.copyOf(all, Math.min(8, all.length));
                    }
                }
                d.put("contentBytes", bytes);
                d.put("contentStartsWith", new String(head, java.nio.charset.StandardCharsets.ISO_8859_1).replaceAll("[^\\x20-\\x7E]", "."));
                d.put("inheritanceEnabled", doc.security().inheritanceEnabled());
                ArrayNode rules = d.putArray("permissions");
                for (PermissionRule rule : doc.security().permissions()) {
                    rules.addObject().put("identity", rule.identity()).put("type", rule.identityType()).put("access", rule.access());
                }
            }

            // (d1) audit trail, newest 50 for this repository
            ObjectNode audit = report.putObject("d_auditTrail");
            Map<String, Object> auditQuery = new LinkedHashMap<>();
            auditQuery.put("contentRepositoryIds", List.of(repo.path("uuid").asText()));
            auditQuery.put("maxHits", 50);
            try {
                audit.put("records", client.searchAuditTrail(auditQuery).size());
            } catch (DoxisApiException e) {
                audit.put("error", e.getStatusCode() + " " + e.getErrorCode() + " " + e.getMessage());
            }

            // (d2) can CQL filter on DXE_MODDATE?
            ObjectNode modDate = report.putObject("d_dxeModDate");
            String modCql = cql + " AND DXE_MODDATE >= '2026-10-01T00:00:00.000+01:00'";
            modDate.put("cql", modCql);
            try {
                DoxisClient.SearchPage page = client.searchDocuments(modCql, "NON_DELETED_OBJECTS", 1);
                modDate.put("totalHitCount", page.totalHitCount());
                modDate.put("hits", page.hits().size());
                client.closeSearch(page.searchId());
            } catch (DoxisApiException e) {
                modDate.put("error", e.getStatusCode() + " " + e.getErrorCode() + " " + e.getMessage());
            }
        } finally {
            String path = System.getenv("DOXIS_PROBE_REPORT");
            if (path != null) {
                Files.writeString(Path.of(path), mapper.writeValueAsString(report));
            }
        }
    }
}
