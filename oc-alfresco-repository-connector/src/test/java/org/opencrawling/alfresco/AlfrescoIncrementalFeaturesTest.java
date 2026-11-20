/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.alfresco;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.EffectiveAcl;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end in-process tests for Alfresco incremental crawling, tombstones, periodic full scans,
 * Trashcan & Audit feeds, versioning, renditions, OAuth2/Ticket/Bearer auth, retries, filters,
 * multi-valued properties, timestamps, and Zero-Trust security policies (#425).
 */
class AlfrescoIncrementalFeaturesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Node(
            String id,
            String parentId,
            String name,
            boolean folder,
            String nodeType,
            String mimeType,
            String body,
            String createdAt,
            String modifiedAt,
            String versionLabel,
            List<String> aspects,
            Map<String, Object> properties,
            String permissionsJson
    ) {}

    private record VersionRec(String id, String versionType, String comment, String body, String modifiedAt) {}

    private record DeletedRec(String id, String name, boolean folder, String path, String archivedAt) {}

    private HttpServer server;
    private String baseUrl;
    private String oauthTokenUrl;

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> tagsByNode = new ConcurrentHashMap<>();
    private final Map<String, List<String>> commentsByNode = new ConcurrentHashMap<>();
    private final Map<String, List<VersionRec>> versionsByNode = new ConcurrentHashMap<>();
    private final Map<String, byte[]> pdfRenditions = new ConcurrentHashMap<>();
    private final List<DeletedRec> deletedNodes = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> auditEntries = new CopyOnWriteArrayList<>();
    private final List<String> searchQueries = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger contentDownloads = new AtomicInteger();
    private final AtomicInteger ticketCreates = new AtomicInteger();
    private final AtomicInteger ticketDeletes = new AtomicInteger();
    private final AtomicInteger oauthCalls = new AtomicInteger();
    private final AtomicInteger failNextWith429 = new AtomicInteger();
    private volatile String expectedAuth;

    private static final String FINANCE_ACL = "{\"isInheritanceEnabled\":false,\"locallySet\":["
            + "{\"authorityId\":\"GROUP_FINANCE\",\"name\":\"Consumer\",\"accessStatus\":\"ALLOWED\"}]}";
    private static final String LEGAL_ACL = "{\"isInheritanceEnabled\":false,\"locallySet\":["
            + "{\"authorityId\":\"GROUP_LEGAL\",\"name\":\"Consumer\",\"accessStatus\":\"ALLOWED\"}]}";
    private static final String EVERYONE_ACL = "{\"isInheritanceEnabled\":true,\"inherited\":["
            + "{\"authorityId\":\"GROUP_EVERYONE\",\"name\":\"Consumer\",\"accessStatus\":\"ALLOWED\"}]}";

    @BeforeEach
    void setUp() throws Exception {
        nodes.put("root", new Node("root", null, "Company Home", true, "cm:folder", null, null,
                "2026-01-01T00:00:00.000+0000", "2026-01-01T00:00:00.000+0000", "", List.of(), Map.of(), EVERYONE_ACL));
        nodes.put("sites", new Node("sites", "root", "Sites", true, "cm:folder", null, null,
                "2026-01-01T00:00:00.000+0000", "2026-01-01T00:00:00.000+0000", "", List.of(), Map.of(), EVERYONE_ACL));
        nodes.put("finSite", new Node("finSite", "sites", "finance", true, "st:site", null, null,
                "2026-01-01T00:00:00.000+0000", "2026-01-01T00:00:00.000+0000", "", List.of(), Map.of(), EVERYONE_ACL));
        nodes.put("docLib", new Node("docLib", "finSite", "documentLibrary", true, "cm:folder", null, null,
                "2026-01-01T00:00:00.000+0000", "2026-01-01T00:00:00.000+0000", "", List.of(), Map.of(), FINANCE_ACL));

        nodes.put("doc-1", new Node("doc-1", "docLib", "budget-2026.txt", false, "cm:content", "text/plain",
                "Budget v1 content", "2026-10-01T09:00:00.000+0000", "2026-10-15T10:30:00.000+0000", "1.0",
                List.of("cm:titled", "cm:versionable"),
                Map.of("cm:title", "Annual Budget", "cm:versionLabel", "1.0",
                        "cm:categories", List.of("Finance", "FY2026"), "sys:node-dbid", 42),
                FINANCE_ACL));
        nodes.put("doc-2", new Node("doc-2", "docLib", "policy.docx", false, "cm:content",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "DOCX-RAW-BYTES", "2026-10-02T09:00:00.000+0000", "2026-10-16T11:00:00.000+0000", "2.1",
                List.of("cm:titled"),
                Map.of("cm:title", "Security Policy", "cm:versionLabel", "2.1"),
                EVERYONE_ACL));
        tagsByNode.put("doc-1", List.of("approved", "budget"));
        tagsByNode.put("doc-2", List.of("policy"));
        commentsByNode.put("doc-1", List.of("Reviewed by CFO"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port + "/alfresco/api/-default-/public/alfresco/versions/1";
        oauthTokenUrl = "http://127.0.0.1:" + port + "/oauth/token";

        server.createContext("/oauth/token", this::handleOAuth);
        server.createContext("/alfresco/api/-default-/public/authentication/versions/1/tickets", this::handleTickets);
        server.createContext("/alfresco/api/-default-/public/search/versions/1/search", this::handleSearch);
        server.createContext("/alfresco/api/-default-/public/alfresco/versions/1", this::handleCore);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void folderModeSkipsUnchangedReindexesAclAndContentChangesAndTombstonesVanished(@TempDir Path tempDir) throws Exception {
        Map<String, String> cfg = config(tempDir,
                "crawlMode", "folder",
                "siteId", "finance",
                "includeTags", "true",
                "includeComments", "true",
                "webAppUrl", "https://alfresco.example.com/workspace");

        // Run 1: indexes both docs with accurate modifiedAt (+0000), multi-valued properties, path, site, tags, comments, webUrl
        List<RepositoryDocument> first = scan(cfg);
        assertThat(first).hasSize(2).allMatch(d -> d.action() == DocumentAction.UPSERT);
        RepositoryDocument doc1 = first.stream().filter(d -> d.id().equals("doc-1")).findFirst().orElseThrow();
        assertThat(doc1.lastModified()).isEqualTo(Instant.parse("2026-10-15T10:30:00Z"));
        assertThat(doc1.metadata().get("cm:categories")).containsExactly("Finance", "FY2026");
        assertThat(doc1.metadata().get("alfresco_path")).containsExactly("/Company Home/Sites/finance/documentLibrary/budget-2026.txt");
        assertThat(doc1.metadata().get("alfresco_site")).containsExactly("finance");
        assertThat(doc1.metadata().get("alfresco_tags")).containsExactly("approved", "budget");
        assertThat(doc1.metadata().get("alfresco_comments")).containsExactly("Reviewed by CFO");
        assertThat(doc1.metadata().get("alfresco_web_url")).containsExactly("https://alfresco.example.com/workspace/#/libraries/viewer/doc-1");
        assertThat(contentDownloads.get()).isEqualTo(2);

        // Run 2: nothing changed -> 0 emitted documents and 0 content downloads
        contentDownloads.set(0);
        assertThat(scan(cfg)).isEmpty();
        assertThat(contentDownloads.get()).isZero();

        // Run 3: ACL-only change on doc-1 (same modifiedAt!) + doc-2 deleted -> doc-1 re-indexed with new ACL, doc-2 tombstoned
        Node old1 = nodes.get("doc-1");
        nodes.put("doc-1", new Node(old1.id(), old1.parentId(), old1.name(), false, old1.nodeType(), old1.mimeType(),
                old1.body(), old1.createdAt(), old1.modifiedAt(), old1.versionLabel(), old1.aspects(), old1.properties(), LEGAL_ACL));
        nodes.remove("doc-2");

        List<RepositoryDocument> third = scan(cfg);
        assertThat(third).filteredOn(d -> d.action() == DocumentAction.UPSERT)
                .singleElement().satisfies(d -> {
                    assertThat(d.id()).isEqualTo("doc-1");
                    assertThat(d.acl()).contains("GROUP_LEGAL");
                });
        assertThat(third).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(RepositoryDocument::id).containsExactly("doc-2");
    }

    @Test
    void searchDeltaUsesWatermarkTrashcanAndPeriodicFullScan(@TempDir Path tempDir) throws Exception {
        Map<String, String> cfg = config(tempDir,
                "crawlMode", "search_delta",
                "siteId", "finance",
                "deletionTracking", "reconcile",
                "fullScanInterval", "PT1H",
                "clockSkewSeconds", "60");

        // Run 1: initial full enumeration
        List<RepositoryDocument> initial = scan(cfg);
        assertThat(initial).hasSize(2);
        assertThat(searchQueries.getFirst()).contains("SITE:'finance'").doesNotContain("cm:modified:");

        // Run 2: incremental delta + Trashcan delete for doc-2
        searchQueries.clear();
        nodes.remove("doc-2");
        deletedNodes.add(new DeletedRec("doc-2", "policy.docx", false,
                "/Company Home/Sites/finance/documentLibrary/policy.docx",
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()));

        List<RepositoryDocument> delta = scan(cfg);
        assertThat(searchQueries).anyMatch(q -> q.contains("cm:modified:["));
        assertThat(delta).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(RepositoryDocument::id).containsExactly("doc-2");

        // Run 3: force periodic full scan due + hard-delete doc-1 (bypassing trashcan) + add doc-3
        Path stateFile = AlfrescoCrawlState.file(AlfrescoProperties.fromConfiguration(cfg));
        ObjectNode stateJson = (ObjectNode) JSON.readTree(stateFile.toFile());
        stateJson.put("lastFullScanAt", Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString());
        JSON.writeValue(stateFile.toFile(), stateJson);

        nodes.remove("doc-1");
        nodes.put("doc-3", new Node("doc-3", "docLib", "new.txt", false, "cm:content", "text/plain", "Fresh",
                "2026-11-19T09:00:00.000+0000", "2026-11-19T10:00:00.000+0000", "1.0", List.of(), Map.of(), FINANCE_ACL));
        searchQueries.clear();

        List<RepositoryDocument> periodic = scan(cfg);
        assertThat(searchQueries).noneMatch(q -> q.contains("cm:modified:["));
        assertThat(periodic).filteredOn(d -> d.action() == DocumentAction.UPSERT)
                .extracting(RepositoryDocument::id).containsExactly("doc-3");
        assertThat(periodic).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(RepositoryDocument::id).containsExactly("doc-1");
    }

    @Test
    void auditDeletionTrackingHandlesDeletesAndAclOnlyChanges(@TempDir Path tempDir) {
        Map<String, String> cfg = config(tempDir,
                "crawlMode", "search_delta",
                "siteId", "finance",
                "deletionTracking", "audit");

        assertThat(scan(cfg)).hasSize(2);

        // Simulate ACL change on doc-1 (without bumping modifiedAt) and delete on doc-2 via Audit API
        Node old1 = nodes.get("doc-1");
        nodes.put("doc-1", new Node(old1.id(), old1.parentId(), old1.name(), false, old1.nodeType(), old1.mimeType(),
                old1.body(), old1.createdAt(), old1.modifiedAt(), old1.versionLabel(), old1.aspects(), old1.properties(), LEGAL_ACL));
        nodes.remove("doc-2");
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        auditEntries.add(Map.of("createdAt", now, "values", Map.of(
                "/alfresco-access/transaction/action", "setPermission",
                "/alfresco-access/transaction/node", "workspace://SpacesStore/doc-1")));
        auditEntries.add(Map.of("createdAt", now, "values", Map.of(
                "/alfresco-access/transaction/action", "DELETE",
                "/alfresco-access/transaction/node", "workspace://SpacesStore/doc-2")));

        // Search returns nothing (neither modifiedAt changed), audit detects BOTH!
        searchQueries.clear();
        List<RepositoryDocument> second = scan(cfg);
        assertThat(second).filteredOn(d -> d.action() == DocumentAction.UPSERT)
                .singleElement().satisfies(d -> {
                    assertThat(d.id()).isEqualTo("doc-1");
                    assertThat(d.acl()).contains("GROUP_LEGAL");
                });
        assertThat(second).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(RepositoryDocument::id).containsExactly("doc-2");
    }

    @Test
    void emptyFullEnumerationGuardPreventsMassDeletion(@TempDir Path tempDir) {
        Map<String, String> cfg = config(tempDir, "crawlMode", "folder", "siteId", "finance");
        assertThat(scan(cfg)).hasSize(2);

        nodes.remove("doc-1");
        nodes.remove("doc-2");
        assertThat(scan(cfg)).isEmpty();
    }

    @Test
    void versionModesAndRenditionSelectionWorkAndTombstoneStaleVersions(@TempDir Path tempDir) throws Exception {
        versionsByNode.put("doc-2", List.of(
                new VersionRec("2.1", "MINOR", "draft edit", "V2.1-MINOR", "2026-10-16T11:00:00.000+0000"),
                new VersionRec("2.0", "MAJOR", "approved release", "V2.0-MAJOR", "2026-10-15T11:00:00.000+0000"),
                new VersionRec("1.0", "MAJOR", "initial", "V1.0-MAJOR", "2026-10-10T11:00:00.000+0000")));
        pdfRenditions.put("doc-2", "PDF-RENDITION-BYTES".getBytes(StandardCharsets.UTF_8));

        // 1. preferRendition=pdf downloads PDF rendition for doc-2 and falls back to primary for doc-1
        List<RepositoryDocument> withPdf = scan(config(tempDir, "siteId", "finance", "preferRendition", "pdf"));
        RepositoryDocument d2Pdf = withPdf.stream().filter(d -> d.id().equals("doc-2")).findFirst().orElseThrow();
        assertThat(d2Pdf.metadata().get("alfresco_rendition")).containsExactly("pdf");
        assertThat(d2Pdf.metadata().get("mimeType")).containsExactly("application/pdf");
        assertThat(readBody(d2Pdf)).isEqualTo("PDF-RENDITION-BYTES");
        RepositoryDocument d1Primary = withPdf.stream().filter(d -> d.id().equals("doc-1")).findFirst().orElseThrow();
        assertThat(d1Primary.metadata()).doesNotContainKey("alfresco_rendition");
        assertThat(readBody(d1Primary)).isEqualTo("Budget v1 content");

        // 2. LATEST_MAJOR indexes version 2.0 of doc-2
        List<RepositoryDocument> major = scan(config(tempDir, "siteId", "finance", "versionMode", "latest_major",
                "stateFile", tempDir.resolve("major.json").toString()));
        RepositoryDocument d2Major = major.stream().filter(d -> d.id().equals("doc-2")).findFirst().orElseThrow();
        assertThat(d2Major.metadata().get("alfresco_version_label")).containsExactly("2.0");
        assertThat(d2Major.metadata().get("alfresco_version_comment")).containsExactly("approved release");
        assertThat(readBody(d2Major)).isEqualTo("V2.0-MAJOR");

        // 3. ALL emits every version, then switching back to CURRENT tombstones versioned IDs
        Path sharedState = tempDir.resolve("versions.json");
        List<RepositoryDocument> all = scan(config(tempDir, "siteId", "finance", "versionMode", "all",
                "stateFile", sharedState.toString()));
        assertThat(all).extracting(RepositoryDocument::id)
                .contains("doc-2;v=2.1", "doc-2;v=2.0", "doc-2;v=1.0", "doc-1");

        List<RepositoryDocument> switched = scan(config(tempDir, "siteId", "finance", "versionMode", "current",
                "stateFile", sharedState.toString()));
        assertThat(switched).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(RepositoryDocument::id)
                .containsExactlyInAnyOrder("doc-2;v=2.1", "doc-2;v=2.0", "doc-2;v=1.0");
    }

    @Test
    void filtersExcludeWorkingCopiesPatternsTagsAndTombstoneNodesLeavingScope(@TempDir Path tempDir) {
        nodes.put("wc-1", new Node("wc-1", "docLib", "budget (Working Copy).txt", false, "cm:content", "text/plain",
                "draft", "2026-10-16T00:00:00.000+0000", "2026-10-16T00:00:00.000+0000", "1.0",
                List.of("cm:workingcopy"), Map.of(), FINANCE_ACL));
        nodes.put("tmp-1", new Node("tmp-1", "docLib", "~$lockfile.docx", false, "cm:content", "text/plain",
                "lock", "2026-10-16T00:00:00.000+0000", "2026-10-16T00:00:00.000+0000", "1.0",
                List.of(), Map.of(), FINANCE_ACL));

        Map<String, String> cfg = config(tempDir,
                "siteId", "finance",
                "excludedTags", "archived",
                "excludedNamePatterns", "^~\\$.*");

        List<RepositoryDocument> first = scan(cfg);
        assertThat(first).extracting(RepositoryDocument::id).containsExactlyInAnyOrder("doc-1", "doc-2");

        // Tag doc-1 as archived -> tombstoned on next run
        tagsByNode.put("doc-1", List.of("approved", "archived"));
        List<RepositoryDocument> second = scan(cfg);
        assertThat(second).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo("doc-1");
            assertThat(d.action()).isEqualTo(DocumentAction.DELETE);
        });
    }

    @Test
    void securityPoliciesControlEveryoneIdentityFallbackAndNormalization(@TempDir Path tempDir) {
        nodes.put("noperm", new Node("noperm", "docLib", "orphan.txt", false, "cm:content", "text/plain", "x",
                "2026-10-16T00:00:00.000+0000", "2026-10-16T00:00:00.000+0000", "1.0", List.of(), Map.of(), null));

        List<RepositoryDocument> docs = scan(config(tempDir,
                "siteId", "finance",
                "everyonePublic", "false",
                "everyoneIdentity", "all-employees",
                "fallbackPrincipals", "group:acs-admins",
                "principalFormat", "normalized"));

        RepositoryDocument d1 = docs.stream().filter(d -> d.id().equals("doc-1")).findFirst().orElseThrow();
        assertThat(EffectiveAcl.resolve(d1.security(), d1.acl()).allowedRead()).containsExactly("group:finance");

        RepositoryDocument d2 = docs.stream().filter(d -> d.id().equals("doc-2")).findFirst().orElseThrow();
        EffectiveAcl d2Acl = EffectiveAcl.resolve(d2.security(), d2.acl());
        assertThat(d2Acl.isPublic()).isFalse();
        assertThat(d2Acl.allowedRead()).containsExactly("group:all-employees");

        RepositoryDocument orphan = docs.stream().filter(d -> d.id().equals("noperm")).findFirst().orElseThrow();
        assertThat(EffectiveAcl.resolve(orphan.security(), orphan.acl()).allowedRead()).containsExactly("group:acs-admins");
    }

    @Test
    void ticketOAuth2BearerAndRetriesWorkEndToEnd(@TempDir Path tempDir) throws Exception {
        // 1. Ticket auth + 429 retry + logout on disconnect
        String expectedTicketHeader = "Basic " + Base64.getEncoder().encodeToString("TICKET_live1".getBytes(StandardCharsets.UTF_8));
        expectedAuth = expectedTicketHeader;
        failNextWith429.set(1);

        AlfrescoRepositoryConnector ticketConn = new AlfrescoRepositoryConnector(AlfrescoProperties.fromConfiguration(
                config(tempDir, "siteId", "finance", "authType", "ticket",
                        "maxRetries", "2", "initialBackoffMs", "10", "incrementalEnabled", "false")));
        ticketConn.connect();
        List<RepositoryDocument> docs = ticketConn.scan("/").collectList().block();
        ticketConn.disconnect();

        assertThat(docs).hasSize(2);
        assertThat(ticketCreates.get()).isEqualTo(1);
        assertThat(ticketDeletes.get()).isEqualTo(1);

        // 2. OAuth2 client-credentials auth
        expectedAuth = "Bearer oauth-access-token-42";
        AlfrescoRepositoryConnector oauthConn = new AlfrescoRepositoryConnector(AlfrescoProperties.fromConfiguration(
                config(tempDir, "siteId", "finance", "authType", "oauth2",
                        "oauth2TokenUrl", oauthTokenUrl, "oauth2ClientId", "oc", "oauth2ClientSecret", "sec",
                        "incrementalEnabled", "false")));
        oauthConn.connect();
        assertThat(oauthConn.scan("/").collectList().block()).hasSize(2);
        oauthConn.disconnect();
        assertThat(oauthCalls.get()).isEqualTo(1);
        assertThat(oauthConn.getSchema("/").fields()).isNotEmpty();
        assertThat(oauthConn.supportsConcurrentProcessing()).isTrue();
    }

    // ------------------------------------------------------------------ Mock ACS HTTP Handlers

    private void handleOAuth(HttpExchange ex) throws IOException {
        oauthCalls.incrementAndGet();
        sendJson(ex, 200, "{\"access_token\":\"oauth-access-token-42\",\"expires_in\":3600}");
    }

    private void handleTickets(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {
            ticketCreates.incrementAndGet();
            sendJson(ex, 201, "{\"entry\":{\"id\":\"TICKET_live1\",\"userId\":\"admin\"}}");
        } else if ("DELETE".equals(ex.getRequestMethod())) {
            ticketDeletes.incrementAndGet();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        } else {
            sendJson(ex, 405, "{}");
        }
    }

    private void handleSearch(HttpExchange ex) throws IOException {
        if (!checkAuthAndThrottle(ex)) {
            return;
        }
        JsonNode req = JSON.readTree(ex.getRequestBody());
        String q = req.path("query").path("query").asText("");
        searchQueries.add(q);

        List<Node> matches = new ArrayList<>();
        boolean hasDelta = q.contains("cm:modified:[");
        for (Node n : nodes.values()) {
            if (n.folder()) {
                continue;
            }
            if (hasDelta) {
                // Only return nodes modified after watermark if delta query is active
                int start = q.indexOf("cm:modified:[\"") + "cm:modified:[\"".length();
                int end = q.indexOf("\" TO MAX]", start);
                if (start > 0 && end > start) {
                    Instant wm = AlfrescoDocumentBuilder.parseTimestamp(q.substring(start, end));
                    Instant mod = AlfrescoDocumentBuilder.parseTimestamp(n.modifiedAt());
                    if (wm != null && mod != null && mod.isBefore(wm)) {
                        continue;
                    }
                }
            }
            matches.add(n);
        }
        sendJson(ex, 200, nodePage(matches, req.path("paging").path("skipCount").asInt(0),
                req.path("paging").path("maxItems").asInt(100)));
    }

    private void handleCore(HttpExchange ex) throws IOException {
        if (!checkAuthAndThrottle(ex)) {
            return;
        }
        String fullPath = URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
        String rel = fullPath.substring("/alfresco/api/-default-/public/alfresco/versions/1".length());
        Map<String, String> params = queryParams(ex.getRequestURI().getRawQuery());

        if (rel.equals("/deleted-nodes")) {
            ObjectNode root = JSON.createObjectNode();
            ObjectNode list = root.putObject("list");
            list.putObject("pagination").put("count", deletedNodes.size()).put("hasMoreItems", false);
            ArrayNode arr = list.putArray("entries");
            for (DeletedRec d : deletedNodes) {
                ObjectNode e = arr.addObject().putObject("entry");
                e.put("id", d.id()).put("name", d.name()).put("isFolder", d.folder()).put("archivedAt", d.archivedAt());
                if (d.path() != null) {
                    int lastSlash = d.path().lastIndexOf('/');
                    e.putObject("path").put("name", lastSlash > 0 ? d.path().substring(0, lastSlash) : "/");
                }
            }
            sendJson(ex, 200, JSON.writeValueAsString(root));
            return;
        }

        if (rel.startsWith("/audit-applications/")) {
            ObjectNode root = JSON.createObjectNode();
            ObjectNode list = root.putObject("list");
            list.putObject("pagination").put("count", auditEntries.size()).put("hasMoreItems", false);
            ArrayNode arr = list.putArray("entries");
            for (Map<String, Object> a : auditEntries) {
                arr.addObject().set("entry", JSON.valueToTree(a));
            }
            sendJson(ex, 200, JSON.writeValueAsString(root));
            return;
        }

        if (!rel.startsWith("/nodes/")) {
            sendJson(ex, 404, "{}");
            return;
        }
        String[] segs = rel.substring("/nodes/".length()).split("/");
        String id = "-root-".equals(segs[0]) ? "root" : segs[0];
        Node target = resolveNode(id, params.get("relativePath"));
        if (target == null) {
            sendJson(ex, 404, "{\"error\":{\"statusCode\":404}}");
            return;
        }

        if (segs.length == 1) {
            ObjectNode body = JSON.createObjectNode();
            body.set("entry", toJson(target));
            sendJson(ex, 200, JSON.writeValueAsString(body));
            return;
        }
        switch (segs[1]) {
            case "children" -> {
                Node parent = target;
                List<Node> children = nodes.values().stream().filter(n -> parent.id().equals(n.parentId())).toList();
                sendJson(ex, 200, nodePage(children,
                        Integer.parseInt(params.getOrDefault("skipCount", "0")),
                        Integer.parseInt(params.getOrDefault("maxItems", "100"))));
            }
            case "content" -> {
                contentDownloads.incrementAndGet();
                sendBytes(ex, 200, target.mimeType(), target.body().getBytes(StandardCharsets.UTF_8));
            }
            case "tags" -> {
                ObjectNode root = JSON.createObjectNode();
                ArrayNode arr = root.putObject("list").putArray("entries");
                for (String t : tagsByNode.getOrDefault(target.id(), List.of())) {
                    arr.addObject().putObject("entry").put("tag", t);
                }
                sendJson(ex, 200, JSON.writeValueAsString(root));
            }
            case "comments" -> {
                ObjectNode root = JSON.createObjectNode();
                ArrayNode arr = root.putObject("list").putArray("entries");
                for (String c : commentsByNode.getOrDefault(target.id(), List.of())) {
                    arr.addObject().putObject("entry").put("content", c);
                }
                sendJson(ex, 200, JSON.writeValueAsString(root));
            }
            case "versions" -> {
                List<VersionRec> vers = versionsByNode.getOrDefault(target.id(), List.of());
                if (segs.length == 2) {
                    ObjectNode root = JSON.createObjectNode();
                    ObjectNode list = root.putObject("list");
                    list.putObject("pagination").put("count", vers.size()).put("hasMoreItems", false);
                    ArrayNode arr = list.putArray("entries");
                    for (VersionRec v : vers) {
                        ObjectNode e = arr.addObject().putObject("entry");
                        e.put("id", v.id()).put("versionComment", v.comment()).put("modifiedAt", v.modifiedAt());
                        e.putObject("content").put("mimeType", target.mimeType()).put("sizeInBytes", v.body().length());
                        e.putObject("properties").put("cm:versionType", v.versionType()).put("cm:versionLabel", v.id());
                    }
                    sendJson(ex, 200, JSON.writeValueAsString(root));
                } else if (segs.length >= 4 && "content".equals(segs[3])) {
                    String vid = segs[2];
                    VersionRec found = vers.stream().filter(v -> v.id().equals(vid)).findFirst().orElse(null);
                    if (found == null) {
                        sendJson(ex, 404, "{}");
                    } else {
                        contentDownloads.incrementAndGet();
                        sendBytes(ex, 200, target.mimeType(), found.body().getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            case "renditions" -> {
                if (segs.length == 3) {
                    String rid = segs[2];
                    byte[] pdf = "pdf".equals(rid) ? pdfRenditions.get(target.id()) : null;
                    if (pdf == null) {
                        sendJson(ex, 404, "{}");
                    } else {
                        sendJson(ex, 200, "{\"entry\":{\"id\":\"pdf\",\"status\":\"CREATED\",\"content\":{\"mimeType\":\"application/pdf\"}}}");
                    }
                } else if (segs.length >= 4 && "content".equals(segs[3])) {
                    byte[] pdf = "pdf".equals(segs[2]) ? pdfRenditions.get(target.id()) : null;
                    if (pdf == null) {
                        sendJson(ex, 404, "{}");
                    } else {
                        contentDownloads.incrementAndGet();
                        sendBytes(ex, 200, "application/pdf", pdf);
                    }
                }
            }
            default -> sendJson(ex, 404, "{}");
        }
    }

    private boolean checkAuthAndThrottle(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            authHeaders.add(auth);
        }
        if (expectedAuth != null && !expectedAuth.equals(auth)) {
            sendJson(ex, 401, "{\"error\":\"unauthorized\"}");
            return false;
        }
        if (failNextWith429.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            ex.getResponseHeaders().add("Retry-After", "0");
            sendJson(ex, 429, "{\"error\":\"rate limited\"}");
            return false;
        }
        return true;
    }

    private Node resolveNode(String id, String relativePath) {
        Node target = nodes.get(id);
        if (target != null && relativePath != null && !relativePath.isBlank()) {
            for (String seg : relativePath.split("/")) {
                if (seg.isBlank()) {
                    continue;
                }
                Node parent = target;
                target = nodes.values().stream()
                        .filter(n -> parent.id().equals(n.parentId()) && seg.equals(n.name()))
                        .findFirst().orElse(null);
                if (target == null) {
                    return null;
                }
            }
        }
        return target;
    }

    private ObjectNode toJson(Node n) throws IOException {
        ObjectNode e = JSON.createObjectNode();
        e.put("id", n.id()).put("name", n.name()).put("isFolder", n.folder()).put("isFile", !n.folder())
                .put("nodeType", n.nodeType()).put("createdAt", n.createdAt()).put("modifiedAt", n.modifiedAt());
        if (n.parentId() != null) {
            e.put("parentId", n.parentId());
        }
        if (!n.folder() && n.body() != null) {
            e.putObject("content").put("mimeType", n.mimeType()).put("sizeInBytes", n.body().length()).put("encoding", "UTF-8");
        }
        // Path
        List<Node> chain = new ArrayList<>();
        for (Node cur = n.parentId() == null ? null : nodes.get(n.parentId()); cur != null;
             cur = cur.parentId() == null ? null : nodes.get(cur.parentId())) {
            chain.add(0, cur);
        }
        ObjectNode path = e.putObject("path");
        StringBuilder pName = new StringBuilder();
        ArrayNode els = path.putArray("elements");
        for (Node a : chain) {
            pName.append('/').append(a.name());
            els.addObject().put("id", a.id()).put("name", a.name());
        }
        path.put("name", pName.toString());
        if (!n.aspects().isEmpty()) {
            ArrayNode asp = e.putArray("aspectNames");
            n.aspects().forEach(asp::add);
        }
        if (!n.properties().isEmpty()) {
            e.set("properties", JSON.valueToTree(n.properties()));
        }
        if (n.permissionsJson() != null) {
            e.set("permissions", JSON.readTree(n.permissionsJson()));
        }
        return e;
    }

    private String nodePage(List<Node> all, int skip, int max) throws IOException {
        List<Node> slice = all.subList(Math.min(skip, all.size()), Math.min(skip + max, all.size()));
        ObjectNode root = JSON.createObjectNode();
        ObjectNode list = root.putObject("list");
        list.putObject("pagination").put("count", slice.size()).put("hasMoreItems", skip + max < all.size())
                .put("totalItems", all.size()).put("skipCount", skip).put("maxItems", max);
        ArrayNode entries = list.putArray("entries");
        for (Node n : slice) {
            entries.addObject().set("entry", toJson(n));
        }
        return JSON.writeValueAsString(root);
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        sendBytes(ex, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int status, String contentType, byte[] bytes) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.getResponseHeaders().add("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)));
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> queryParams(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(kv[0], kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }

    private Map<String, String> config(Path tempDir, String... pairs) {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("url", baseUrl);
        cfg.put("username", "admin");
        cfg.put("password", "admin");
        cfg.put("stateFile", tempDir.toString());
        cfg.put("incrementalEnabled", "true");
        for (int i = 0; i < pairs.length; i += 2) {
            cfg.put(pairs[i], pairs[i + 1]);
        }
        return cfg;
    }

    private static List<RepositoryDocument> scan(Map<String, String> cfg) {
        return new AlfrescoRepositoryConnector(AlfrescoProperties.fromConfiguration(cfg))
                .scan("/").collectList().block();
    }

    private static String readBody(RepositoryDocument doc) throws IOException {
        try (var in = doc.contentStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
