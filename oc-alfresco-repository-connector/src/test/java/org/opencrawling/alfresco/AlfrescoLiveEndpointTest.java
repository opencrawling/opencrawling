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
package org.opencrawling.alfresco;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live integration test executed by {@code scripts/test-alfresco-connector.sh} against a real Alfresco Content
 * Services instance. Automatically skipped during offline unit test runs when {@code alfresco.live.folderId} is unset.
 */
@EnabledIfSystemProperty(named = "alfresco.live.folderId", matches = ".+")
class AlfrescoLiveEndpointTest {

    @Test
    void exercisesTicketAuthIncrementalFiltersMetadataVersioningAndTombstonesAgainstLiveAlfresco(@TempDir Path tempDir)
            throws Exception {
        String url = System.getProperty("spring.opencrawling.connector.alfresco.url", AlfrescoProperties.DEFAULT_URL);
        String username = System.getProperty("spring.opencrawling.connector.alfresco.username", "admin");
        String password = System.getProperty("spring.opencrawling.connector.alfresco.password", "admin");
        String folderId = System.getProperty("alfresco.live.folderId");
        String primaryDocId = System.getProperty("alfresco.live.docId", "");
        String expectedTag = System.getProperty("alfresco.live.tag", "");
        String expectedComment = System.getProperty("alfresco.live.comment", "Reviewed for OpenCrawling v1.0.5");
        String expectedMajorVersion = System.getProperty("alfresco.live.majorVersion", "");
        String expectedTipVersion = System.getProperty("alfresco.live.tipVersion", "");
        String expectedModifiedAt = System.getProperty("alfresco.live.docModifiedAt", "");

        Path stateFile = tempDir.resolve("live-state.json");
        Map<String, String> baseConfig = new HashMap<>();
        baseConfig.put("url", url);
        baseConfig.put("authType", "ticket");
        baseConfig.put("username", username);
        baseConfig.put("password", password);
        baseConfig.put("crawlMode", "folder");
        baseConfig.put("rootFolderId", folderId);
        baseConfig.put("includeSubfolders", "true");
        baseConfig.put("excludedFolders", "Data Dictionary,temp");
        baseConfig.put("excludedNamePatterns", "^~\\$.*");
        baseConfig.put("includeTags", String.valueOf(!expectedTag.isBlank()));
        baseConfig.put("includeComments", "true");
        baseConfig.put("webAppUrl", "http://localhost:8081/workspace");
        baseConfig.put("incrementalEnabled", "true");
        baseConfig.put("stateFile", stateFile.toString());
        baseConfig.put("deletionTracking", "reconcile");

        // 1. Initial crawl over Ticket auth: indexes primary doc + Templates/template-guide.txt, skips temp/ and ~$*
        List<RepositoryDocument> initial = runScan(baseConfig);
        assertThat(initial).isNotEmpty();
        assertThat(initial).allMatch(d -> d.action() == DocumentAction.UPSERT);
        assertThat(initial).extracting(d -> firstMeta(d, "name"))
                .contains("opencrawling-sample.txt", "template-guide.txt")
                .doesNotContain("~$draft-lock.txt", "scratch.txt");

        RepositoryDocument primary = initial.stream()
                .filter(d -> primaryDocId.isBlank()
                        ? "opencrawling-sample.txt".equals(firstMeta(d, "name"))
                        : primaryDocId.equals(d.id()))
                .findFirst()
                .orElseThrow();

        assertThat(firstMeta(primary, "alfresco_path")).contains("opencrawling-sample.txt");
        if (!expectedTag.isBlank()) {
            assertThat(primary.metadata().get("alfresco_tags")).contains(expectedTag);
        }
        assertThat(primary.metadata().get("alfresco_comments")).anyMatch(c -> c.contains(expectedComment));
        assertThat(firstMeta(primary, "alfresco_web_url"))
                .isEqualTo("http://localhost:8081/workspace/#/libraries/viewer/" + primary.id());
        if (!expectedTipVersion.isBlank()) {
            assertThat(firstMeta(primary, "alfresco_version_label")).isEqualTo(expectedTipVersion);
        }
        if (!expectedModifiedAt.isBlank()) {
            Instant expectedInstant = AlfrescoDocumentBuilder.parseTimestamp(expectedModifiedAt);
            assertThat(expectedInstant).isNotNull();
            assertThat(primary.lastModified()).isEqualTo(expectedInstant);
        }
        assertThat(readContent(primary)).contains("Minor Draft");

        // 2. Second crawl with identical state -> 0 documents emitted (unchanged skip)
        List<RepositoryDocument> noOp = runScan(baseConfig);
        assertThat(noOp).isEmpty();

        // 3. Switch to LATEST_MAJOR on a separate state file -> fetches latest major version content
        if (!expectedMajorVersion.isBlank()) {
            Map<String, String> majorCfg = new HashMap<>(baseConfig);
            majorCfg.put("versionMode", "latest_major");
            majorCfg.put("stateFile", tempDir.resolve("major-state.json").toString());
            List<RepositoryDocument> majorDocs = runScan(majorCfg);
            RepositoryDocument majorPrimary = majorDocs.stream()
                    .filter(d -> primary.id().equals(d.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(firstMeta(majorPrimary, "alfresco_version_label")).isEqualTo(expectedMajorVersion);
            assertThat(readContent(majorPrimary)).contains("Major Release");
        }

        // 4. Switch to ALL versions on the shared state file -> emits <id>;v=... and tombstones unversioned IDs
        Map<String, String> allVersionsCfg = new HashMap<>(baseConfig);
        allVersionsCfg.put("versionMode", "all");
        List<RepositoryDocument> allDocs = runScan(allVersionsCfg);
        assertThat(allDocs).filteredOn(d -> d.action() == DocumentAction.UPSERT)
                .extracting(RepositoryDocument::id)
                .anyMatch(id -> id.startsWith(primary.id() + ";v="));
        assertThat(allDocs).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(RepositoryDocument::id)
                .contains(primary.id());

        // 5. Switch back to CURRENT -> restores unversioned ID and tombstones all <id>;v=... documents
        List<RepositoryDocument> restored = runScan(baseConfig);
        assertThat(restored).filteredOn(d -> d.action() == DocumentAction.UPSERT)
                .extracting(RepositoryDocument::id)
                .contains(primary.id());
        assertThat(restored).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(RepositoryDocument::id)
                .anyMatch(id -> id.startsWith(primary.id() + ";v="));

        // 6. Exclude primary doc via excludedTags (when Solr tagging is active) or excludedAspects (cm:versionable)
        Map<String, String> scopeFilterCfg = new HashMap<>(baseConfig);
        if (!expectedTag.isBlank()) {
            scopeFilterCfg.put("excludedTags", expectedTag);
        } else {
            scopeFilterCfg.put("excludedAspects", "cm:versionable");
        }
        List<RepositoryDocument> scopeFiltered = runScan(scopeFilterCfg);
        assertThat(scopeFiltered).filteredOn(d -> d.action() == DocumentAction.DELETE)
                .extracting(RepositoryDocument::id)
                .contains(primary.id());
    }

    private static List<RepositoryDocument> runScan(Map<String, String> config) throws Exception {
        AlfrescoRepositoryConnector connector = new AlfrescoRepositoryConnector(
                AlfrescoProperties.fromConfiguration(config));
        connector.connect();
        try {
            List<RepositoryDocument> docs = connector.scan("-root-").collectList().block();
            return docs != null ? docs : List.of();
        } finally {
            connector.disconnect();
        }
    }

    private static String firstMeta(RepositoryDocument doc, String key) {
        List<String> values = doc.metadata().get(key);
        return (values == null || values.isEmpty()) ? "" : values.getFirst();
    }

    private static String readContent(RepositoryDocument doc) throws IOException {
        if (doc.contentStream() == null) {
            return "";
        }
        try (var in = doc.contentStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
