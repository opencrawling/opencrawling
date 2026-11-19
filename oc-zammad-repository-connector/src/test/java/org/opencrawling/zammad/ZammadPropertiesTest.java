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
package org.opencrawling.zammad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.zammad.ZammadProperties.ArticleGranularity;
import org.opencrawling.zammad.ZammadProperties.CustomerIdentity;
import org.opencrawling.zammad.ZammadProperties.IdentityRendering;
import org.opencrawling.zammad.ZammadProperties.ParticipantMetadata;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZammadPropertiesTest {

    private static ZammadProperties props(String... pairs) {
        Map<String, String> config = new HashMap<>();
        config.put("baseUrl", "https://helpdesk.example.com");
        config.put("accessToken", "token");
        for (int i = 0; i < pairs.length; i += 2) {
            config.put(pairs[i], pairs[i + 1]);
        }
        return ZammadProperties.fromConfiguration(config);
    }

    @Test
    void defaultsKeepExistingBehaviourAndLeaveSystemArticlesOut() {
        ZammadProperties p = props();
        assertThat(p.articleGranularity()).isEqualTo(ArticleGranularity.SPLIT_PUBLIC_AND_INTERNAL);
        assertThat(p.excludedArticleSenders()).containsExactly("System");
        assertThat(p.skipAutoReplies()).isTrue();
        assertThat(p.identityRendering()).isEqualTo(IdentityRendering.EMAIL);
        assertThat(p.customerIdentity()).isEqualTo(CustomerIdentity.EMAIL);
        assertThat(p.participantMetadata()).isEqualTo(ParticipantMetadata.NAMES);
        assertThat(p.includeCustomerInPublicAcl()).isTrue();
        assertThat(p.fullScanInterval()).isNull();
        assertThat(p.validate()).isEmpty();
    }

    @Test
    void kebabCaseKeysAndAnEmptySenderListAreHonoured() {
        ZammadProperties p = props("article-granularity", "ticket", "excluded-article-senders", "",
                "identity-rendering", "name", "participant-metadata", "names-and-ids");
        assertThat(p.articleGranularity()).isEqualTo(ArticleGranularity.TICKET);
        assertThat(p.includeInternalArticles()).isTrue();
        assertThat(p.excludedArticleSenders()).isEmpty();
        assertThat(p.identityRendering()).isEqualTo(IdentityRendering.NAME);
        assertThat(p.participantMetadata()).isEqualTo(ParticipantMetadata.NAMES_AND_IDS);
    }

    @Test
    void strictPrivacyProfileOverridesIdentitySettings() {
        ZammadProperties p = props("privacyProfile", "strict", "includeCustomerInPublicAcl", "true",
                "identityRendering", "email");
        assertThat(p.identityRendering()).isEqualTo(IdentityRendering.NAME);
        assertThat(p.customerIdentity()).isEqualTo(CustomerIdentity.ID);
        assertThat(p.includeCustomerInPublicAcl()).isFalse();
        assertThat(p.includeSharedOrganizationInPublicAcl()).isFalse();
        assertThat(props("privacyProfile", "strict", "identityRendering", "none").identityRendering())
                .isEqualTo(IdentityRendering.NONE);
    }

    @Test
    void createdAfterAcceptsDatesInstantsAndPeriods() {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        assertThat(props("createdAfter", "2024-01-01").createdAfterInstant(now))
                .contains(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(props("createdAfter", "2024-06-01T08:00:00Z").createdAfterInstant(now))
                .contains(Instant.parse("2024-06-01T08:00:00Z"));
        assertThat(props("createdAfter", "P2Y").createdAfterInstant(now))
                .contains(Instant.parse("2024-11-20T12:00:00Z"));
        assertThat(props("createdAfter", "PT36H").createdAfterInstant(now))
                .contains(Instant.parse("2026-11-19T00:00:00Z"));
        assertThat(props("createdAfter", "last year").validate()).anyMatch(m -> m.contains("created-after"));
    }

    @Test
    void tagPriorityOrganizationAndTitleFilters() {
        ZammadProperties p = props("includedTags", "kb, faq", "excludedTags", "Spam",
                "priorities", "3 high", "organizations", "Example Org",
                "excludedTitlePatterns", "^\\[ALERT\\]\n(?i)out of office");
        assertThat(p.acceptsTags(List.of("faq"))).isTrue();
        assertThat(p.acceptsTags(List.of("faq", "spam"))).isFalse();
        assertThat(p.acceptsTags(List.of("other"))).isFalse();
        assertThat(p.filtersByTags()).isTrue();
        assertThat(p.acceptsPriority("3 High")).isTrue();
        assertThat(p.acceptsPriority("2 normal")).isFalse();
        assertThat(p.acceptsOrganization(null)).isFalse();
        assertThat(p.compiledTitlePatterns()).hasSize(2);
        assertThat(props("excludedTitlePatterns", "([unclosed").validate()).anyMatch(m -> m.contains("excluded-title-patterns"));
    }

    @Test
    void roleLabelsAreLocalizableAndUnknownRolesIgnored() {
        ZammadProperties p = props("articleRoleLabels", "customer=Kunde, internal=Interne Notiz, bogus=x");
        assertThat(p.roleLabel("customer")).isEqualTo("Kunde");
        assertThat(p.roleLabel("internal")).isEqualTo("Interne Notiz");
        assertThat(p.roleLabel("agent")).isEqualTo("Agent");
        assertThat(p.articleRoleLabels()).doesNotContainKey("bogus");
    }

    @Test
    void fullScanIsDueAfterTheIntervalAndInsideTheWindow() {
        ZammadProperties p = props("fullScanInterval", "P1D", "fullScanWindow", "22:00-04:00",
                "fullScanTimezone", "Europe/Berlin");
        assertThat(p.fullScanInterval()).isEqualTo(Duration.ofDays(1));
        Instant nightInBerlin = Instant.parse("2026-11-20T23:30:00Z");   // 00:30 in Berlin
        Instant dayInBerlin = Instant.parse("2026-11-20T11:00:00Z");     // 12:00 in Berlin
        assertThat(p.fullScanDue(null, nightInBerlin)).isTrue();
        assertThat(p.fullScanDue(null, dayInBerlin)).isFalse();
        assertThat(p.fullScanDue(nightInBerlin.minus(Duration.ofHours(23)), nightInBerlin)).isFalse();
        assertThat(p.fullScanDue(nightInBerlin.minus(Duration.ofHours(25)), nightInBerlin)).isTrue();
        assertThat(props().fullScanDue(null, nightInBerlin)).isFalse();
        assertThat(props("fullScanWindow", "nightly").validate()).anyMatch(m -> m.contains("full-scan-window"));
        assertThat(props("fullScanTimezone", "Mars/Olympus").validate()).anyMatch(m -> m.contains("full-scan-timezone"));
    }

    @Test
    void renderSignatureChangesWithRenderingOptionsButTheStateKeyDoesNot() {
        ZammadProperties split = props("stateFile", "data/zammad-state");
        ZammadProperties ticket = props("stateFile", "data/zammad-state", "articleGranularity", "TICKET");
        ZammadProperties otherGroup = props("stateFile", "data/zammad-state", "groupNames", "Billing");
        assertThat(split.renderSignature()).isNotEqualTo(ticket.renderSignature());
        assertThat(ZammadCrawlState.file(split)).isEqualTo(ZammadCrawlState.file(ticket));
        assertThat(ZammadCrawlState.file(split)).isNotEqualTo(ZammadCrawlState.file(otherGroup));
        assertThat(ZammadCrawlState.legacyFile(split)).isNotEqualTo(ZammadCrawlState.legacyFile(ticket));
    }

    @Test
    void stateEntriesWrittenBeforeSignaturesAndHashesStillLoad() throws Exception {
        String legacyJson = """
                {"watermark":"2026-10-01T00:00:00Z","tickets":{"1":{"updatedAt":"u","aclFingerprint":"a","ids":["x"]}},"kbAnswers":{}}
                """;
        ZammadCrawlState.State state = new ObjectMapper().readValue(legacyJson, ZammadCrawlState.State.class);
        assertThat(state.lastFullScanAt()).isNull();
        assertThat(state.tickets().get("1").renderSignature()).isNull();
        assertThat(state.tickets().get("1").hashes()).isEmpty();
        assertThat(state.tickets().get("1").ids()).containsExactly("x");
    }

    @Test
    void articleHelpers() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(ZammadDocumentBuilder.articleRole(json.readTree("{\"sender\":\"Customer\"}"))).isEqualTo("customer");
        assertThat(ZammadDocumentBuilder.articleRole(json.readTree("{\"sender\":\"Agent\",\"internal\":true}"))).isEqualTo("internal");
        assertThat(ZammadDocumentBuilder.articleRole(json.readTree("{\"sender\":\"System\"}"))).isEqualTo("system");
        assertThat(ZammadDocumentBuilder.isAutoReply(json.readTree("{\"preferences\":{\"is-auto-response\":true}}"))).isTrue();
        assertThat(ZammadDocumentBuilder.isAutoReply(json.readTree("{\"from\":\"MAILER-DAEMON@mx.example.com\"}"))).isTrue();
        assertThat(ZammadDocumentBuilder.isAutoReply(json.readTree("{\"from\":\"jane@example.com\"}"))).isFalse();
        assertThat(ZammadDocumentBuilder.displayNameFromAddress("\"Doe, Jane\" <jane@example.com>")).isEqualTo("Doe, Jane");
        assertThat(ZammadDocumentBuilder.displayNameFromAddress("jane@example.com")).isNull();
        assertThat(ZammadDocumentBuilder.displayNameFromAddress("Mark Agent")).isEqualTo("Mark Agent");
    }
}
