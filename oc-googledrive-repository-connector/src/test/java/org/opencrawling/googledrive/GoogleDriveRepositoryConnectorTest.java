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
package org.opencrawling.googledrive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.discovery.DiscoveryNode;
import org.opencrawling.core.connector.discovery.DiscoveryResult;
import org.opencrawling.core.connector.discovery.DryRunRequest;
import org.opencrawling.core.connector.discovery.DryRunResult;
import org.opencrawling.core.connector.discovery.ExploreRequest;
import org.opencrawling.core.connector.discovery.FetchedDocument;
import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleDriveRepositoryConnectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PARENT_QUERY = Pattern.compile("'([^']+)'\\s+in\\s+parents");

    private HttpServer server;
    private String apiBaseUrl;
    private InMemoryStateStore stateStore;
    private FakeDriveBackend drive;

    @BeforeEach
    void setUp() throws Exception {
        drive = new FakeDriveBackend();
        stateStore = new InMemoryStateStore();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/drive/v3";

        server.createContext("/drive/v3/", ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
            Map<String, String> params = parseQuery(query);

            if (path.equals("/drive/v3/about")) {
                respondJson(ex, 200, Map.of("user", Map.of("emailAddress", "crawler@example.com")));
                return;
            }
            if (path.startsWith("/drive/v3/drives/")) {
                String driveId = path.substring("/drive/v3/drives/".length());
                String name = drive.drives.getOrDefault(driveId, driveId);
                respondJson(ex, 200, Map.of("id", driveId, "name", name));
                return;
            }
            if (path.equals("/drive/v3/changes/startPageToken")) {
                respondJson(ex, 200, Map.of("startPageToken", drive.startPageToken));
                return;
            }
            if (path.equals("/drive/v3/changes")) {
                String token = params.getOrDefault("pageToken", "");
                if ("expired-token".equals(token)) {
                    respondJson(ex, 410, Map.of("error", Map.of(
                            "code", 410,
                            "message", "Invalid pageToken",
                            "errors", List.of(Map.of("reason", "invalid"))
                    )));
                    return;
                }
                List<Map<String, Object>> changes = drive.changesByToken.getOrDefault(token, List.of());
                String nextNewToken = drive.nextTokenByToken.getOrDefault(token, token);
                respondJson(ex, 200, Map.of("changes", changes, "newStartPageToken", nextNewToken));
                return;
            }
            if (path.equals("/drive/v3/files")) {
                String q = params.getOrDefault("q", "");
                Matcher m = PARENT_QUERY.matcher(q);
                String parentId = m.find() ? m.group(1) : "";
                drive.listCallsByParent.merge(parentId, 1, Integer::sum);
                if (drive.returnEmptyListings) {
                    respondJson(ex, 200, Map.of("files", List.of()));
                    return;
                }
                List<Map<String, Object>> children = new ArrayList<>();
                for (Map<String, Object> f : drive.files.values()) {
                    if (Boolean.TRUE.equals(f.get("trashed"))) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    List<String> parents = (List<String>) f.getOrDefault("parents", List.of());
                    if (parents.contains(parentId)) {
                        children.add(f);
                    }
                }
                respondJson(ex, 200, Map.of("files", children));
                return;
            }
            if (path.startsWith("/drive/v3/files/") && path.endsWith("/permissions")) {
                String fileId = path.substring("/drive/v3/files/".length(), path.length() - "/permissions".length());
                if (drive.forbiddenPermissions.contains(fileId)) {
                    respondJson(ex, 403, Map.of("error", Map.of(
                            "code", 403,
                            "message", "Insufficient permissions",
                            "errors", List.of(Map.of("reason", "insufficientFilePermissions"))
                    )));
                    return;
                }
                List<Map<String, Object>> perms = drive.permissions.getOrDefault(fileId, List.of());
                respondJson(ex, 200, Map.of("permissions", perms));
                return;
            }
            if (path.startsWith("/drive/v3/files/") && path.endsWith("/export")) {
                String fileId = path.substring("/drive/v3/files/".length(), path.length() - "/export".length());
                drive.exportCalls.incrementAndGet();
                if (drive.exportSizeLimitFiles.contains(fileId)) {
                    respondJson(ex, 403, Map.of("error", Map.of(
                            "code", 403,
                            "message", "This file is too large to be exported.",
                            "errors", List.of(Map.of("reason", "exportSizeLimitExceeded"))
                    )));
                    return;
                }
                String mime = params.getOrDefault("mimeType", "text/plain");
                byte[] body = drive.exportContent.getOrDefault(fileId + "|" + mime,
                        ("exported:" + fileId + ":" + mime).getBytes(StandardCharsets.UTF_8));
                ex.getResponseHeaders().add("Content-Type", mime);
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            if (path.startsWith("/drive/v3/files/")) {
                String fileId = path.substring("/drive/v3/files/".length());
                if ("media".equals(params.get("alt"))) {
                    drive.downloadCalls.incrementAndGet();
                    byte[] body = drive.binaryContent.getOrDefault(fileId, new byte[0]);
                    ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
                    ex.sendResponseHeaders(200, body.length);
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(body);
                    }
                    return;
                }
                Map<String, Object> f = drive.files.get(fileId);
                if (f == null) {
                    respondJson(ex, 404, Map.of("error", Map.of("code", 404, "message", "Not found")));
                } else {
                    respondJson(ex, 200, f);
                }
                return;
            }
            respondJson(ex, 404, Map.of("error", Map.of("code", 404, "message", "Unknown path: " + path)));
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void crawlsSharedDriveExportsDocsAsHtmlSlidesAsTextExcludesSheetsFormsAndReportsTooLarge() throws Exception {
        String driveId = "0AO_SHARED_1";
        drive.drives.put(driveId, "Support Knowledge");
        drive.addFolder("folder-proc", "Procedures", driveId, List.of(driveId));
        drive.addFile("doc-1", "Onboarding Guide", GoogleDriveProperties.MIME_GOOGLE_DOC, driveId,
                List.of("folder-proc"), "10", null, null, -1,
                "https://docs.google.com/document/d/doc-1/edit", Map.of("department", "Support"));
        drive.exportContent.put("doc-1|text/html",
                "<h1>Onboarding</h1><p>Welcome to support.</p>".getBytes(StandardCharsets.UTF_8));

        drive.addFile("slides-1", "Incident Deck", GoogleDriveProperties.MIME_GOOGLE_SLIDES, driveId,
                List.of("folder-proc"), "4", null, null, -1,
                "https://docs.google.com/presentation/d/slides-1/edit", Map.of());
        drive.exportContent.put("slides-1|text/plain",
                "Slide 1: Incident Escalation".getBytes(StandardCharsets.UTF_8));

        // Excluded by default (Sheets & Forms)
        drive.addFile("sheet-1", "Metrics", GoogleDriveProperties.MIME_GOOGLE_SHEET, driveId,
                List.of("folder-proc"), "2", null, null, -1, "https://docs.google.com/spreadsheets/d/sheet-1", Map.of());
        drive.addFile("form-1", "Feedback Form", GoogleDriveProperties.MIME_GOOGLE_FORM, driveId,
                List.of("folder-proc"), "1", null, null, -1, "https://docs.google.com/forms/d/form-1", Map.of());

        // Normal binary PDF + oversized binary (> 100 bytes cap)
        drive.addFile("pdf-1", "Runbook.pdf", "application/pdf", driveId,
                List.of("folder-proc"), "7", "rev-7", "md5-abc", 24,
                "https://drive.google.com/file/d/pdf-1/view", Map.of());
        drive.binaryContent.put("pdf-1", "%PDF-1.7 runbook content".getBytes(StandardCharsets.UTF_8));

        drive.addFile("big-1", "HugeDump.zip", "application/zip", driveId,
                List.of("folder-proc"), "1", "rev-1", "md5-big", 5000,
                "https://drive.google.com/file/d/big-1/view", Map.of());

        // Huge native Google Doc hitting Drive's 10MB export cap
        drive.addFile("big-doc", "Giant Spec", GoogleDriveProperties.MIME_GOOGLE_DOC, driveId,
                List.of("folder-proc"), "3", null, null, -1,
                "https://docs.google.com/document/d/big-doc/edit", Map.of());
        drive.exportSizeLimitFiles.add("big-doc");

        List<Map<String, Object>> acl = List.of(
                Map.of("id", "p1", "type", "user", "role", "writer", "emailAddress", "alice@example.com"),
                Map.of("id", "p2", "type", "group", "role", "reader", "emailAddress", "support@example.com"),
                Map.of("id", "p3", "type", "anyone", "role", "reader") // must NOT become public by default
        );
        for (String fid : List.of("doc-1", "slides-1", "pdf-1", "big-1", "big-doc")) {
            drive.permissions.put(fid, acl);
        }

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", apiBaseUrl,
                "authType", "bearer",
                "accessToken", "test-tok",
                "driveId", driveId,
                "crawlMode", "changes",
                "maxFileSizeBytes", "100",
                "parallelism", "1"
        ));

        GoogleDriveRepositoryConnector connector = new GoogleDriveRepositoryConnector(props, stateStore);
        List<MaterializedDoc> docs = materialize(connector.scan("default").collectList().block());

        // Sheets & Forms are excluded; 5 documents emitted (3 content + 2 TOO_LARGE reports)
        assertThat(docs).extracting(MaterializedDoc::id).containsExactlyInAnyOrder(
                "googledrive://drives/0AO_SHARED_1/files/doc-1",
                "googledrive://drives/0AO_SHARED_1/files/slides-1",
                "googledrive://drives/0AO_SHARED_1/files/pdf-1",
                "googledrive://drives/0AO_SHARED_1/files/big-1",
                "googledrive://drives/0AO_SHARED_1/files/big-doc"
        );

        MaterializedDoc gdoc = byId(docs, "googledrive://drives/0AO_SHARED_1/files/doc-1");
        assertThat(gdoc.content()).contains("<h1>Onboarding</h1>");
        assertThat(gdoc.metadata().get("mimeType")).containsExactly("text/html");
        assertThat(gdoc.metadata().get("title")).containsExactly("Onboarding Guide");
        assertThat(gdoc.metadata().get("url")).containsExactly("https://docs.google.com/document/d/doc-1/edit");
        assertThat(gdoc.metadata().get("breadcrumbs")).containsExactly("Procedures");
        assertThat(gdoc.metadata().get("path")).containsExactly("/Procedures/Onboarding Guide.html");
        assertThat(gdoc.metadata().get("googledrive_prop_department")).containsExactly("Support");
        assertThat(gdoc.acl()).doesNotContain("public").contains("alice@example.com").contains("support@example.com");

        MaterializedDoc slides = byId(docs, "googledrive://drives/0AO_SHARED_1/files/slides-1");
        assertThat(slides.content()).isEqualTo("Slide 1: Incident Escalation");
        assertThat(slides.metadata().get("mimeType")).containsExactly("text/plain");

        MaterializedDoc pdf = byId(docs, "googledrive://drives/0AO_SHARED_1/files/pdf-1");
        assertThat(pdf.content()).isEqualTo("%PDF-1.7 runbook content");
        assertThat(pdf.metadata().get("googledrive.versionKey")).containsExactly("v:7|rev:rev-7|md5:md5-abc");

        // Oversized binary & oversized Google Doc export are explicitly reported as TOO_LARGE (not silently skipped!)
        MaterializedDoc bigBin = byId(docs, "googledrive://drives/0AO_SHARED_1/files/big-1");
        assertThat(bigBin.hasContent()).isFalse();
        assertThat(bigBin.metadata().get(GoogleDriveDocumentBuilder.STATUS_KEY)).containsExactly("TOO_LARGE");
        assertThat(bigBin.metadata().get(GoogleDriveDocumentBuilder.REASON_KEY).getFirst()).contains("exceeds max-file-size-bytes");

        MaterializedDoc bigDoc = byId(docs, "googledrive://drives/0AO_SHARED_1/files/big-doc");
        assertThat(bigDoc.hasContent()).isFalse();
        assertThat(bigDoc.metadata().get(GoogleDriveDocumentBuilder.STATUS_KEY)).containsExactly("TOO_LARGE");

        // Changes API startPageToken persisted in StateStore
        assertThat(stateStore.load(props.scopeKey()).startPageToken()).isEqualTo("token-1");
    }

    @Test
    void unchangedFilesAreSkippedWithoutDownloadingAndAclChangeReindexes() throws Exception {
        drive.addFolder("root", "My Drive", "", List.of());
        drive.addFile("f1", "Manual.txt", "text/plain", "", List.of("root"),
                "5", "rev-5", "md5-5", 11, "https://drive.google.com/file/d/f1/view", Map.of());
        drive.binaryContent.put("f1", "hello world".getBytes(StandardCharsets.UTF_8));
        drive.permissions.put("f1", List.of(
                Map.of("id", "p1", "type", "user", "role", "reader", "emailAddress", "alice@example.com")
        ));

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", apiBaseUrl,
                "authType", "bearer",
                "accessToken", "tok",
                "crawlMode", "tree",
                "parallelism", "1"
        ));

        GoogleDriveRepositoryConnector connector = new GoogleDriveRepositoryConnector(props, stateStore);
        // Run 1: downloads once
        List<MaterializedDoc> run1 = materialize(connector.scan("default").collectList().block());
        assertThat(run1).hasSize(1);
        assertThat(drive.downloadCalls.get()).isEqualTo(1);

        // Run 2: unchanged versionKey & ACL -> 0 emitted, 0 downloads!
        List<MaterializedDoc> run2 = materialize(connector.scan("default").collectList().block());
        assertThat(run2).isEmpty();
        assertThat(drive.downloadCalls.get()).isEqualTo(1);

        // Run 3: ACL changes (bob added), version unchanged -> re-indexed with updated ACL!
        drive.permissions.put("f1", List.of(
                Map.of("id", "p1", "type", "user", "role", "reader", "emailAddress", "alice@example.com"),
                Map.of("id", "p2", "type", "user", "role", "reader", "emailAddress", "bob@example.com")
        ));
        List<MaterializedDoc> run3 = materialize(connector.scan("default").collectList().block());
        assertThat(run3).hasSize(1);
        assertThat(run3.getFirst().acl()).contains("alice@example.com").contains("bob@example.com");
        assertThat(drive.downloadCalls.get()).isEqualTo(2);
    }

    @Test
    void changesApiHandlesAdditionsDeletionsMovesOutOfScopeAndFolderCascade() throws Exception {
        drive.addFolder("folder-in", "Manuals", "", List.of("root"));
        drive.addFolder("folder-sub", "Hardware", "", List.of("folder-in"));
        drive.addFolder("folder-out", "Archive", "", List.of("root"));

        drive.addFile("f-stay", "Stay.txt", "text/plain", "", List.of("folder-in"), "1", null, "m1", 4, "https://drive/f-stay", Map.of());
        drive.addFile("f-trash", "TrashMe.txt", "text/plain", "", List.of("folder-in"), "1", null, "m2", 4, "https://drive/f-trash", Map.of());
        drive.addFile("f-move", "MoveMe.txt", "text/plain", "", List.of("folder-in"), "1", null, "m3", 4, "https://drive/f-move", Map.of());
        drive.addFile("f-child", "Child.txt", "text/plain", "", List.of("folder-sub"), "1", null, "m4", 4, "https://drive/f-child", Map.of());
        for (String id : List.of("f-stay", "f-trash", "f-move", "f-child")) {
            drive.binaryContent.put(id, "data".getBytes(StandardCharsets.UTF_8));
            drive.permissions.put(id, List.of(Map.of("id", "p", "type", "user", "role", "reader", "emailAddress", "a@example.com")));
        }

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", apiBaseUrl,
                "authType", "bearer",
                "accessToken", "tok",
                "rootFolderIds", "folder-in",
                "crawlMode", "changes",
                "parallelism", "1"
        ));

        GoogleDriveRepositoryConnector connector = new GoogleDriveRepositoryConnector(props, stateStore);
        // Run 1: initial full bootstrap
        List<MaterializedDoc> run1 = materialize(connector.scan("default").collectList().block());
        assertThat(run1).hasSize(4);

        // Prepare Changes API events for token-1 -> token-2:
        // 1) New file added in folder-in
        drive.addFile("f-new", "New.txt", "text/plain", "", List.of("folder-in"), "1", null, "m5", 3, "https://drive/f-new", Map.of());
        drive.binaryContent.put("f-new", "new".getBytes(StandardCharsets.UTF_8));
        drive.permissions.put("f-new", List.of(Map.of("id", "p", "type", "user", "role", "reader", "emailAddress", "a@example.com")));

        // 2) f-trash moved to trash
        Map<String, Object> trashed = new LinkedHashMap<>(drive.files.get("f-trash"));
        trashed.put("trashed", true);

        // 3) f-move moved out of scope into folder-out
        Map<String, Object> movedOut = new LinkedHashMap<>(drive.files.get("f-move"));
        movedOut.put("parents", List.of("folder-out"));
        drive.files.put("f-move", movedOut);

        // 4) folder-sub moved out of scope into folder-out -> cascades tombstone to f-child!
        Map<String, Object> movedFolder = new LinkedHashMap<>(drive.files.get("folder-sub"));
        movedFolder.put("parents", List.of("folder-out"));
        drive.files.put("folder-sub", movedFolder);

        drive.changesByToken.put("token-1", List.of(
                Map.of("fileId", "f-new", "removed", false, "file", drive.files.get("f-new")),
                Map.of("fileId", "f-trash", "removed", false, "file", trashed),
                Map.of("fileId", "f-move", "removed", false, "file", movedOut),
                Map.of("fileId", "folder-sub", "removed", false, "file", movedFolder)
        ));
        drive.nextTokenByToken.put("token-1", "token-2");

        // Run 2: incremental Changes API
        List<MaterializedDoc> run2 = materialize(connector.scan("default").collectList().block());

        assertThat(run2).filteredOn(d -> d.action() == DocumentAction.UPSERT)
                .extracting(MaterializedDoc::id)
                .containsExactly("googledrive://drives/my-drive/files/f-new");

        assertThat(run2).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(MaterializedDoc::id)
                .containsExactlyInAnyOrder(
                        "googledrive://drives/my-drive/files/f-trash",
                        "googledrive://drives/my-drive/files/f-move",
                        "googledrive://drives/my-drive/files/f-child"
                );
        assertThat(stateStore.load(props.scopeKey()).startPageToken()).isEqualTo("token-2");
    }

    @Test
    void multiFolderScopeRespectsIncludeSubfoldersFalseAndFollowShortcuts() throws Exception {
        drive.addFolder("fa", "FolderA", "", List.of("root"));
        drive.addFolder("fb", "FolderB", "", List.of("root"));
        drive.addFolder("fa-sub", "Nested", "", List.of("fa"));
        drive.addFolder("ext", "External", "", List.of("root"));

        drive.addFile("a1", "A1.txt", "text/plain", "", List.of("fa"), "1", null, "m1", 2, "https://drive/a1", Map.of());
        drive.addFile("b1", "B1.txt", "text/plain", "", List.of("fb"), "1", null, "m2", 2, "https://drive/b1", Map.of());
        drive.addFile("nested", "Nested.txt", "text/plain", "", List.of("fa-sub"), "1", null, "m3", 2, "https://drive/nested", Map.of());
        drive.addFile("target", "Target.txt", "text/plain", "", List.of("ext"), "1", null, "m4", 6, "https://drive/target", Map.of());
        drive.addShortcut("sc1", "LinkToTarget", "", List.of("fb"), "target", "text/plain");

        for (String id : List.of("a1", "b1", "nested", "target")) {
            drive.binaryContent.put(id, "ok".getBytes(StandardCharsets.UTF_8));
            drive.permissions.put(id, List.of(Map.of("id", "p", "type", "user", "role", "reader", "emailAddress", "u@example.com")));
        }

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", apiBaseUrl,
                "authType", "bearer",
                "accessToken", "tok",
                "rootFolderIds", "fa,fb",
                "includeSubfolders", "false",
                "followShortcuts", "true",
                "crawlMode", "tree",
                "parallelism", "1"
        ));

        GoogleDriveRepositoryConnector connector = new GoogleDriveRepositoryConnector(props, stateStore);
        List<MaterializedDoc> docs = materialize(connector.scan("default").collectList().block());

        assertThat(docs).extracting(MaterializedDoc::id).containsExactlyInAnyOrder(
                "googledrive://drives/my-drive/files/a1",
                "googledrive://drives/my-drive/files/b1",
                "googledrive://drives/my-drive/files/target"
        );
        MaterializedDoc shortcutDoc = byId(docs, "googledrive://drives/my-drive/files/target");
        assertThat(shortcutDoc.metadata().get("googledrive.shortcutSourceId")).containsExactly("sc1");
        assertThat(shortcutDoc.metadata().get("breadcrumbs")).containsExactly("FolderB");
    }

    @Test
    void emptyListingGuardPreventsMassTombstoningAndExpiredTokenFallsBackToFullReconciliation() throws Exception {
        drive.addFolder("root", "My Drive", "", List.of());
        drive.addFile("f1", "One.txt", "text/plain", "", List.of("root"), "1", null, "m1", 2, "https://drive/f1", Map.of());
        drive.addFile("f2", "Two.txt", "text/plain", "", List.of("root"), "1", null, "m2", 2, "https://drive/f2", Map.of());
        for (String id : List.of("f1", "f2")) {
            drive.binaryContent.put(id, "ok".getBytes(StandardCharsets.UTF_8));
            drive.permissions.put(id, List.of(Map.of("id", "p", "type", "user", "role", "reader", "emailAddress", "u@example.com")));
        }

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", apiBaseUrl,
                "authType", "bearer",
                "accessToken", "tok",
                "crawlMode", "changes",
                "parallelism", "1"
        ));

        GoogleDriveRepositoryConnector connector = new GoogleDriveRepositoryConnector(props, stateStore);
        assertThat(materialize(connector.scan("default").collectList().block())).hasSize(2);

        // Simulate expired pageToken + temporary empty listing (e.g. revoked permission) -> guard emits 0 tombstones!
        GoogleDriveCrawlState.State prior = stateStore.load(props.scopeKey());
        stateStore.save(props.scopeKey(), new GoogleDriveCrawlState.State(
                "expired-token", prior.lastFullScanAt(), prior.folders(), prior.files()));
        drive.returnEmptyListings = true;
        drive.startPageToken = "token-fresh";

        List<MaterializedDoc> guardedRun = materialize(connector.scan("default").collectList().block());
        assertThat(guardedRun).isEmpty();
        // State preserved (neither wiped nor falsely advanced)
        assertThat(stateStore.load(props.scopeKey()).files()).containsKeys("f1", "f2");

        // Now listing recovers with f2 deleted -> expired-token fallback reconciles and tombstones only f2!
        drive.returnEmptyListings = false;
        drive.files.remove("f2");

        List<MaterializedDoc> reconcileRun = materialize(connector.scan("default").collectList().block());
        assertThat(reconcileRun).hasSize(1);
        assertThat(reconcileRun.getFirst().action()).isEqualTo(DocumentAction.DELETE);
        assertThat(reconcileRun.getFirst().id()).isEqualTo("googledrive://drives/my-drive/files/f2");
        assertThat(stateStore.load(props.scopeKey()).startPageToken()).isEqualTo("token-fresh");
    }

    @Test
    void discoveryEnforcesVisibilityFilterPageByPageAndFetchDocumentHandlesFoundGoneAndHidden() throws Exception {
        drive.addFolder("root-kb", "KB", "", List.of("root"));
        drive.addFolder("pub-folder", "PublicDocs", "", List.of("root-kb"));
        drive.addFolder("secret-folder", "HRSecret", "", List.of("root-kb"));

        drive.addFile("pub-doc", "FAQ.txt", "text/plain", "", List.of("pub-folder"), "1", null, "m1", 9, "https://drive/pub-doc", Map.of());
        drive.addFile("secret-doc", "Salaries.txt", "text/plain", "", List.of("secret-folder"), "1", null, "m2", 9, "https://drive/secret-doc", Map.of());
        drive.addFile("unreadable-acl", "BrokenAcl.txt", "text/plain", "", List.of("pub-folder"), "1", null, "m3", 9, "https://drive/broken", Map.of());
        drive.binaryContent.put("pub-doc", "faq text!".getBytes(StandardCharsets.UTF_8));
        drive.binaryContent.put("secret-doc", "topsecret".getBytes(StandardCharsets.UTF_8));

        List<Map<String, Object>> alicePerm = List.of(Map.of("id", "p1", "type", "user", "role", "reader", "emailAddress", "alice@example.com"));
        List<Map<String, Object>> hrPerm = List.of(Map.of("id", "p2", "type", "group", "role", "reader", "emailAddress", "hr@example.com"));

        drive.permissions.put("root-kb", alicePerm);
        drive.permissions.put("pub-folder", alicePerm);
        drive.permissions.put("pub-doc", alicePerm);
        drive.permissions.put("secret-folder", hrPerm);
        drive.permissions.put("secret-doc", hrPerm);
        drive.forbiddenPermissions.add("unreadable-acl"); // permissions API returns 403 -> fail closed!

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "apiBaseUrl", apiBaseUrl,
                "authType", "bearer",
                "accessToken", "tok",
                "rootFolderId", "root-kb",
                "crawlMode", "tree"
        ));

        VisibilityFilter aliceOnly = nodes -> {
            boolean[] vis = new boolean[nodes.size()];
            for (int i = 0; i < nodes.size(); i++) {
                DiscoveryNode n = nodes.get(i);
                vis[i] = n.security() != null && n.security().permissions().stream()
                        .anyMatch(r -> "alice@example.com".equals(r.identity()));
            }
            return vis;
        };

        GoogleDriveRepositoryConnector connector = new GoogleDriveRepositoryConnector(props, stateStore);

        // explore: secret-folder is hidden and NEVER opened; unreadable-acl has null security and is denied!
        DiscoveryResult explored = connector.explore(new ExploreRequest(null, 3, 50, true, Duration.ofSeconds(10)), aliceOnly);
        assertThat(explored.nodes()).extracting(DiscoveryNode::sourceId)
                .containsExactlyInAnyOrder("pub-folder", "pub-doc");
        assertThat(explored.hiddenCount()).isEqualTo(2); // secret-folder + unreadable-acl
        assertThat(drive.listCallsByParent.getOrDefault("secret-folder", 0)).isZero();

        // hidden start folder reads as not found (IllegalArgumentException)
        assertThatThrownBy(() -> connector.crawlStart("secret-folder", aliceOnly))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(connector.crawlStart("pub-folder", aliceOnly).sourceId()).isEqualTo("pub-folder");

        // dryRun counts only visible documents without downloading content
        DryRunResult preview = connector.dryRun(new DryRunRequest(null, 5, 50, Duration.ofSeconds(10)), aliceOnly);
        assertThat(preview.matchCount()).isEqualTo(1);
        assertThat(preview.samples()).extracting(DiscoveryNode::sourceId).containsExactly("pub-doc");
        assertThat(drive.downloadCalls.get()).isZero();

        // fetchDocument: Found for visible, Gone for missing, IllegalArgumentException for hidden / fail-closed
        FetchedDocument found = connector.fetchDocument("pub-doc", aliceOnly);
        assertThat(found).isInstanceOf(FetchedDocument.Found.class);
        try (InputStream in = ((FetchedDocument.Found) found).document().contentStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("faq text!");
        }

        FetchedDocument gone = connector.fetchDocument("does-not-exist", aliceOnly);
        assertThat(gone).isInstanceOf(FetchedDocument.Gone.class);

        assertThatThrownBy(() -> connector.fetchDocument("secret-doc", aliceOnly))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> connector.fetchDocument("unreadable-acl", aliceOnly))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ Helpers

    private record MaterializedDoc(
            String id,
            DocumentAction action,
            String acl,
            Map<String, List<String>> metadata,
            boolean hasContent,
            String content
    ) {
    }

    private static List<MaterializedDoc> materialize(List<RepositoryDocument> docs) throws IOException {
        List<MaterializedDoc> out = new ArrayList<>();
        if (docs == null) {
            return out;
        }
        for (RepositoryDocument doc : docs) {
            String text = null;
            boolean hasContent = doc.contentStream() != null;
            if (hasContent) {
                try (InputStream in = doc.contentStream()) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            out.add(new MaterializedDoc(doc.id(), doc.action(), doc.acl(), doc.metadata(), hasContent, text));
        }
        return out;
    }

    private static MaterializedDoc byId(List<MaterializedDoc> docs, String id) {
        return docs.stream().filter(d -> id.equals(d.id())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing doc " + id));
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void respondJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class InMemoryStateStore implements GoogleDriveCrawlState.StateStore {
        private final Map<String, String> store = new ConcurrentHashMap<>();

        @Override
        public GoogleDriveCrawlState.State load(String scopeKey) throws IOException {
            String json = store.get(scopeKey);
            return json == null ? GoogleDriveCrawlState.State.empty() : GoogleDriveCrawlState.fromJson(json);
        }

        @Override
        public void save(String scopeKey, GoogleDriveCrawlState.State state) throws IOException {
            store.put(scopeKey, GoogleDriveCrawlState.toJson(state));
        }
    }

    private static final class FakeDriveBackend {
        final Map<String, String> drives = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> permissions = new ConcurrentHashMap<>();
        final Map<String, byte[]> binaryContent = new ConcurrentHashMap<>();
        final Map<String, byte[]> exportContent = new ConcurrentHashMap<>();
        final java.util.Set<String> forbiddenPermissions = ConcurrentHashMap.newKeySet();
        final java.util.Set<String> exportSizeLimitFiles = ConcurrentHashMap.newKeySet();
        final Map<String, List<Map<String, Object>>> changesByToken = new ConcurrentHashMap<>();
        final Map<String, String> nextTokenByToken = new ConcurrentHashMap<>();
        final Map<String, Integer> listCallsByParent = new ConcurrentHashMap<>();
        final AtomicInteger downloadCalls = new AtomicInteger();
        final AtomicInteger exportCalls = new AtomicInteger();
        volatile String startPageToken = "token-1";
        volatile boolean returnEmptyListings = false;

        void addFolder(String id, String name, String driveId, List<String> parents) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", id);
            f.put("name", name);
            f.put("mimeType", GoogleDriveProperties.MIME_FOLDER);
            f.put("parents", parents);
            if (driveId != null && !driveId.isBlank()) {
                f.put("driveId", driveId);
            }
            f.put("trashed", false);
            f.put("modifiedTime", "2026-11-20T10:00:00Z");
            files.put(id, f);
        }

        void addFile(String id, String name, String mimeType, String driveId, List<String> parents,
                     String version, String headRevisionId, String md5, long size,
                     String webViewLink, Map<String, String> props) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", id);
            f.put("name", name);
            f.put("mimeType", mimeType);
            f.put("parents", parents);
            if (driveId != null && !driveId.isBlank()) {
                f.put("driveId", driveId);
            }
            if (version != null) {
                f.put("version", version);
            }
            if (headRevisionId != null) {
                f.put("headRevisionId", headRevisionId);
            }
            if (md5 != null) {
                f.put("md5Checksum", md5);
            }
            if (size >= 0) {
                f.put("size", Long.toString(size));
            }
            if (webViewLink != null) {
                f.put("webViewLink", webViewLink);
            }
            if (props != null && !props.isEmpty()) {
                f.put("properties", props);
            }
            f.put("trashed", false);
            f.put("createdTime", "2026-11-01T08:00:00Z");
            f.put("modifiedTime", "2026-11-20T12:00:00Z");
            f.put("owners", List.of(Map.of("emailAddress", "owner@example.com", "displayName", "Owner")));
            f.put("lastModifyingUser", Map.of("emailAddress", "editor@example.com", "displayName", "Editor"));
            files.put(id, f);
        }

        void addShortcut(String id, String name, String driveId, List<String> parents, String targetId, String targetMime) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", id);
            f.put("name", name);
            f.put("mimeType", GoogleDriveProperties.MIME_SHORTCUT);
            f.put("parents", parents);
            if (driveId != null && !driveId.isBlank()) {
                f.put("driveId", driveId);
            }
            f.put("shortcutDetails", Map.of("targetId", targetId, "targetMimeType", targetMime));
            f.put("trashed", false);
            files.put(id, f);
        }
    }
}
