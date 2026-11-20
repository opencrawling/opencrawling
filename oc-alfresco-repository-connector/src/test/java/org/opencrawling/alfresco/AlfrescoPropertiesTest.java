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
import org.opencrawling.alfresco.AlfrescoProperties.AuthType;
import org.opencrawling.alfresco.AlfrescoProperties.DeletionTracking;
import org.opencrawling.alfresco.AlfrescoProperties.PrincipalFormat;
import org.opencrawling.alfresco.AlfrescoProperties.VersionMode;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AlfrescoPropertiesTest {

    private static AlfrescoProperties props(String... pairs) {
        Map<String, String> config = new HashMap<>();
        config.put("url", "https://alfresco.example.com/alfresco/api/-default-/public/alfresco/versions/1");
        config.put("username", "admin");
        config.put("password", "secret");
        for (int i = 0; i < pairs.length; i += 2) {
            config.put(pairs[i], pairs[i + 1]);
        }
        return AlfrescoProperties.fromConfiguration(config);
    }

    @Test
    void defaultsAreBackwardCompatibleAndValid() {
        AlfrescoProperties p = props();
        assertThat(p.authType()).isEqualTo(AuthType.BASIC);
        assertThat(p.crawlMode()).isEqualTo(AlfrescoCrawlMode.FOLDER);
        assertThat(p.excludedFolders()).containsExactly("Data Dictionary");
        assertThat(p.excludeWorkingCopies()).isTrue();
        assertThat(p.versionMode()).isEqualTo(VersionMode.CURRENT);
        assertThat(p.includeAcls()).isTrue();
        assertThat(p.everyonePublic()).isTrue();
        assertThat(p.principalFormat()).isEqualTo(PrincipalFormat.RAW);
        assertThat(p.incrementalEnabled()).isTrue();
        assertThat(p.deletionTracking()).isEqualTo(DeletionTracking.RECONCILE);
        assertThat(p.clockSkewSeconds()).isEqualTo(300);
        assertThat(p.batchSize()).isEqualTo(100);
        assertThat(p.parallelism()).isEqualTo(4);
        assertThat(p.validate()).isEmpty();
    }

    @Test
    void kebabCaseAndEnvironmentBindingWork() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.opencrawling.connector.alfresco.url", "https://acs.acme.com/alfresco/api/-default-/public/alfresco/versions/1")
                .withProperty("spring.opencrawling.connector.alfresco.auth-type", "bearer")
                .withProperty("spring.opencrawling.connector.alfresco.oauth2-access-token", "tok-123")
                .withProperty("spring.opencrawling.connector.alfresco.crawl-mode", "search_delta")
                .withProperty("spring.opencrawling.connector.alfresco.site-ids[0]", "finance")
                .withProperty("spring.opencrawling.connector.alfresco.site-ids[1]", "legal")
                .withProperty("spring.opencrawling.connector.alfresco.excluded-folders", "")
                .withProperty("spring.opencrawling.connector.alfresco.version-mode", "all")
                .withProperty("spring.opencrawling.connector.alfresco.prefer-rendition", "pdf")
                .withProperty("spring.opencrawling.connector.alfresco.everyone-public", "false")
                .withProperty("spring.opencrawling.connector.alfresco.everyone-identity", "employees")
                .withProperty("spring.opencrawling.connector.alfresco.principal-format", "normalized");

        AlfrescoProperties p = AlfrescoProperties.fromEnvironment(env);
        assertThat(p.authType()).isEqualTo(AuthType.BEARER);
        assertThat(p.crawlMode()).isEqualTo(AlfrescoCrawlMode.SEARCH_DELTA);
        assertThat(p.siteIds()).containsExactly("finance", "legal");
        assertThat(p.excludedFolders()).isEmpty();
        assertThat(p.versionMode()).isEqualTo(VersionMode.ALL);
        assertThat(p.preferRendition()).isEqualTo("pdf");
        assertThat(p.everyonePublic()).isFalse();
        assertThat(p.everyoneIdentity()).isEqualTo("employees");
        assertThat(p.principalFormat()).isEqualTo(PrincipalFormat.NORMALIZED);
        assertThat(p.validate()).isEmpty();
    }

    @Test
    void authValidationCoversAllModes() {
        assertThat(props("authType", "bearer").validate()).anyMatch(m -> m.contains("bearer"));
        assertThat(props("authType", "bearer", "accessToken", "t").validate()).isEmpty();
        assertThat(props("authType", "oauth2").validate()).anyMatch(m -> m.contains("oauth2"));
        assertThat(props("authType", "oauth2", "oauth2TokenUrl", "https://idp/token",
                "oauth2ClientId", "c", "oauth2ClientSecret", "s").validate()).isEmpty();
        assertThat(props("authType", "ticket", "username", "", "password", "").validate()).anyMatch(m -> m.contains("ticket"));
        assertThat(props("authType", "ticket", "ticket", "TICKET_abc").validate()).isEmpty();
    }

    @Test
    void folderExclusionIsExactOrPathBasedNotSubstring() {
        AlfrescoProperties p = props("excludedFolders", "temp, Data Dictionary, /Sites/trash");
        assertThat(p.isFolderExcluded("temp", "id-1", "/Company Home/temp")).isTrue();
        assertThat(p.isFolderExcluded("Templates", "id-2", "/Company Home/Templates")).isFalse();
        assertThat(p.isFolderExcluded("Contemporary", "id-3", "/Company Home/Contemporary")).isFalse();
        assertThat(p.isFolderExcluded("Data Dictionary", "id-4", "/Company Home/Data Dictionary")).isTrue();
        assertThat(p.isFolderExcluded("trash", "id-5", "/Company Home/Sites/trash")).isTrue();
        assertThat(p.isFolderExcluded("trash", "id-6", "/Company Home/Other/trash")).isFalse();
    }

    @Test
    void dateAndPeriodThresholdsParseAndValidate() {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        assertThat(props("createdAfter", "2024-01-01").createdAfterInstant(now))
                .contains(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(props("modifiedAfter", "2026-06-01T08:30:00Z").modifiedAfterInstant(now))
                .contains(Instant.parse("2026-06-01T08:30:00Z"));
        assertThat(props("createdAfter", "P2Y").createdAfterInstant(now))
                .contains(Instant.parse("2024-11-20T12:00:00Z"));
        assertThat(props("modifiedAfter", "PT12H").modifiedAfterInstant(now))
                .contains(Instant.parse("2026-11-20T00:00:00Z"));
        assertThat(props("createdAfter", "not-a-date").validate()).anyMatch(m -> m.contains("created-after"));
        assertThat(props("modifiedAfter", "yesterday").validate()).anyMatch(m -> m.contains("modified-after"));
    }

    @Test
    void aspectTagTypeAndPatternFiltersWork() {
        AlfrescoProperties p = props(
                "nodeTypes", "cm:content, my:invoice",
                "includedAspects", "cm:titled, my:published",
                "excludedAspects", "rn:hidden",
                "includedTags", "approved, kb",
                "excludedTags", "draft",
                "excludedNamePatterns", "^~\\$.*\n\\.tmp$");

        assertThat(p.acceptsNodeType("my:invoice")).isTrue();
        assertThat(p.acceptsNodeType("cm:folder")).isFalse();
        assertThat(p.acceptsAspects(List.of("cm:titled"))).isTrue();
        assertThat(p.acceptsAspects(List.of("cm:titled", "cm:workingcopy"))).isFalse();
        assertThat(p.acceptsAspects(List.of("cm:titled", "rn:hidden"))).isFalse();
        assertThat(p.acceptsAspects(List.of("cm:author"))).isFalse();
        assertThat(p.acceptsTags(List.of("kb"))).isTrue();
        assertThat(p.acceptsTags(List.of("kb", "draft"))).isFalse();
        assertThat(p.acceptsTags(List.of("other"))).isFalse();
        assertThat(p.compiledNamePatterns()).hasSize(2);
        assertThat(props("excludedNamePatterns", "([broken").validate())
                .anyMatch(m -> m.contains("excluded-name-patterns"));
    }

    @Test
    void periodicFullScanHonorsIntervalWindowAndMidnightWrap() {
        AlfrescoProperties p = props("fullScanInterval", "P1D", "fullScanWindow", "22:00-04:00",
                "fullScanTimezone", "Europe/Berlin");
        assertThat(p.fullScanInterval()).isEqualTo(Duration.ofDays(1));
        Instant nightInBerlin = Instant.parse("2026-11-20T23:30:00Z"); // 00:30 CET
        Instant noonInBerlin = Instant.parse("2026-11-20T11:00:00Z");  // 12:00 CET
        assertThat(p.fullScanDue(null, nightInBerlin)).isTrue();
        assertThat(p.fullScanDue(null, noonInBerlin)).isFalse();
        assertThat(p.fullScanDue(nightInBerlin.minus(Duration.ofHours(20)), nightInBerlin)).isFalse();
        assertThat(p.fullScanDue(nightInBerlin.minus(Duration.ofHours(25)), nightInBerlin)).isTrue();
        assertThat(props("fullScanWindow", "bad").validate()).anyMatch(m -> m.contains("full-scan-window"));
        assertThat(props("fullScanTimezone", "Mars/Olympus").validate()).anyMatch(m -> m.contains("full-scan-timezone"));
    }

    @Test
    void renderSignatureChangesWithRenderingOptionsButScopeKeyStaysStable() {
        AlfrescoProperties current = props("stateFile", "data/alfresco-state", "versionMode", "current");
        AlfrescoProperties all = props("stateFile", "data/alfresco-state", "versionMode", "all");
        AlfrescoProperties otherSite = props("stateFile", "data/alfresco-state", "siteId", "hr");
        assertThat(current.renderSignature()).isNotEqualTo(all.renderSignature());
        assertThat(AlfrescoCrawlState.file(current)).isEqualTo(AlfrescoCrawlState.file(all));
        assertThat(AlfrescoCrawlState.file(current)).isNotEqualTo(AlfrescoCrawlState.file(otherSite));
    }

    @Test
    void endpointUrlsResolveCleanly() {
        AlfrescoProperties p = props();
        assertThat(p.resolveSearchUrl()).isEqualTo("https://alfresco.example.com/alfresco/api/-default-/public/search/versions/1/search");
        assertThat(p.resolveTicketsUrl()).isEqualTo("https://alfresco.example.com/alfresco/api/-default-/public/authentication/versions/1/tickets");
    }
}
