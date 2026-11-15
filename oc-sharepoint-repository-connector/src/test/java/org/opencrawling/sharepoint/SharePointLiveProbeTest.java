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
package org.opencrawling.sharepoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Read-only probe of a real SharePoint Online tenant. Runs only when {@code SHAREPOINT_LIVE_PROBE=1}; the target comes from
 * the environment ({@code SHAREPOINT_TENANT_ID}, {@code SHAREPOINT_CLIENT_ID}, {@code SHAREPOINT_CLIENT_SECRET},
 * {@code SHAREPOINT_SITE_URL}, optional {@code SHAREPOINT_DRIVE_NAMES}, {@code SHAREPOINT_CRAWL_MODE} and
 * {@code SHAREPOINT_MAX_DOCUMENTS}, default 25). It only issues GET requests; content is read and discarded, and the
 * crawl state goes to a temporary directory. It prints what the app registration sees, so a reviewer can compare the
 * permissions with SharePoint's own "Manage access" view.
 */
@EnabledIfEnvironmentVariable(named = "SHAREPOINT_LIVE_PROBE", matches = "1|true")
class SharePointLiveProbeTest {

    @TempDir
    Path state;

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void readOnlyCrawl() throws Exception {
        Map<String, String> config = new HashMap<>();
        config.put("tenantId", env("SHAREPOINT_TENANT_ID", null));
        config.put("clientId", env("SHAREPOINT_CLIENT_ID", null));
        config.put("clientSecret", env("SHAREPOINT_CLIENT_SECRET", null));
        config.put("siteUrl", env("SHAREPOINT_SITE_URL", null));
        config.put("driveNames", env("SHAREPOINT_DRIVE_NAMES", ""));
        config.put("crawlMode", env("SHAREPOINT_CRAWL_MODE", "delta"));
        config.put("maxDocuments", env("SHAREPOINT_MAX_DOCUMENTS", "25"));
        config.put("stateDirectory", state.toString());
        config.values().removeIf(v -> v == null);
        for (String required : List.of("tenantId", "clientId", "clientSecret", "siteUrl")) {
            assertTrue(config.containsKey(required), "set the SHAREPOINT_* variable for " + required);
        }

        List<RepositoryDocument> documents = new SharePointRepositoryConnector(SharePointProperties.fromConfiguration(config))
                .scan("default").collectList().block();
        assertNotNull(documents);
        Map<String, Integer> identities = new TreeMap<>();
        int closed = 0;
        long bytes = 0;
        for (RepositoryDocument d : documents) {
            if (d.contentStream() != null) {
                try (InputStream in = d.contentStream()) {
                    bytes += in.transferTo(OutputStream.nullOutputStream());
                }
            }
            if (d.action() != DocumentAction.UPSERT) {
                continue;
            }
            List<PermissionRule> rules = d.security() == null ? List.of() : d.security().permissions();
            if (rules.isEmpty()) {
                closed++;
            }
            rules.forEach(r -> identities.merge(r.identityType() + " " + r.identity() + " (" + r.access() + ")", 1, Integer::sum));
            System.out.printf("%s %s -> %s%n", d.metadata().getOrDefault("sharepoint.path", List.of("?")).getFirst(),
                    d.metadata().getOrDefault("sharepoint.driveName", List.of("?")).getFirst(),
                    rules.stream().map(r -> r.identity() + ":" + r.access()).toList());
        }
        System.out.printf("%d documents, %d bytes of content, %d without readers.%n", documents.size(), bytes, closed);
        identities.forEach((identity, count) -> System.out.printf("  %4d x %s%n", count, identity));
    }
}
