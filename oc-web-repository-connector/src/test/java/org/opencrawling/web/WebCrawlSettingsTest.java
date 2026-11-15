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
package org.opencrawling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.JobContext;

class WebCrawlSettingsTest {

    @Test
    void appliesIssue38Defaults() {
        WebCrawlSettings settings = WebCrawlSettings.resolve(Map.of(), Map.of(), key -> null, "https://example.com/");
        assertThat(settings.seeds()).containsExactly("https://example.com/");
        assertThat(settings.maxDepth()).isEqualTo(3);
        assertThat(settings.delayMs()).isEqualTo(1000);
        assertThat(settings.scope()).isEqualTo(WebCrawlSettings.Scope.DOMAIN);
        assertThat(settings.respectRobots()).isTrue();
        assertThat(settings.maxPages()).isEqualTo(1000);
        assertThat(settings.userAgent()).isEqualTo(WebCrawlSettings.DEFAULT_USER_AGENT);
        assertThat(settings.robotsAgentName()).isEqualTo("OpenCrawlingBot");
        assertThat(settings.aclMode()).isEqualTo(WebCrawlSettings.AclMode.PUBLIC);
    }

    @Test
    void crawlSpecOverridesConnectorConfigWhichOverridesSpringProperties() {
        Map<String, String> properties = Map.of(
            "spring.opencrawling.connector.web.max-depth", "7",
            "spring.opencrawling.connector.web.delay-ms", "300",
            "spring.opencrawling.connector.web.max-pages", "50");
        WebCrawlSettings settings = WebCrawlSettings.resolve(
            Map.of("maxDepth", 2),
            Map.of("maxDepth", "5", "delayMs", "200"),
            properties::get,
            "https://example.com/");
        assertThat(settings.maxDepth()).isEqualTo(2);
        assertThat(settings.delayMs()).isEqualTo(200);
        assertThat(settings.maxPages()).isEqualTo(50);
    }

    @Test
    void acceptsListsAsJsonArraysOrStrings() {
        WebCrawlSettings settings = WebCrawlSettings.resolve(
            Map.of("seeds", List.of("https://a.example/", "https://b.example/")),
            Map.of("excludePatterns", "\\.pdf$\n/a{1,3}/"),
            key -> null, null);
        assertThat(settings.seeds()).hasSize(2);
        assertThat(settings.excludePatterns()).hasSize(2);
        assertThat(WebCrawlSettings.resolve(Map.of(), Map.of(), key -> null, "https://a.example/ https://b.example/").seeds())
            .hasSize(2);
    }

    @Test
    void reportsEveryInvalidSetting() {
        assertThatThrownBy(() -> WebCrawlSettings.resolve(
            Map.of("maxDepth", 99, "includePatterns", List.of("(unclosed"), "userAgent", "bot"),
            Map.of(), key -> null, "ftp://example.com/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("seed is not an absolute http(s) URL")
            .hasMessageContaining("maxDepth must be between 0 and 50")
            .hasMessageContaining("includePatterns has an invalid pattern")
            .hasMessageContaining("userAgent must include a contact");
    }

    @Test
    void requiresSeeds() {
        assertThatThrownBy(() -> WebCrawlSettings.resolve(Map.of(), Map.of(), key -> null, null))
            .hasMessageContaining("at least one seed URL is required");
    }

    @Test
    void privateNetworksRequireAnExplicitAclDecision() {
        Map<String, Object> privateCrawl = Map.of("allowPrivateNetworks", true, "privateAllowlist", List.of("10.0.0.0/8"));
        assertThatThrownBy(() -> WebCrawlSettings.resolve(privateCrawl, Map.of(), key -> null, "https://intranet.local/"))
            .hasMessageContaining("aclMode is required");

        Map<String, Object> publicWithoutConfirm = new java.util.HashMap<>(privateCrawl);
        publicWithoutConfirm.put("aclMode", "public");
        assertThatThrownBy(() -> WebCrawlSettings.resolve(publicWithoutConfirm, Map.of(), key -> null, "https://intranet.local/"))
            .hasMessageContaining("confirmPublicExposure");

        Map<String, Object> staticAcl = new java.util.HashMap<>(privateCrawl);
        staticAcl.put("aclMode", "static");
        staticAcl.put("aclIdentity", "employees");
        assertThat(WebCrawlSettings.resolve(staticAcl, Map.of(), key -> null, "https://intranet.local/").aclMode())
            .isEqualTo(WebCrawlSettings.AclMode.STATIC);
    }

    @Test
    void privateNetworksRequireAnAllowlist() {
        assertThatThrownBy(() -> WebCrawlSettings.resolve(
            Map.of("allowPrivateNetworks", true, "aclMode", "static", "aclIdentity", "staff"),
            Map.of(), key -> null, "https://intranet.local/"))
            .hasMessageContaining("privateAllowlist");
    }

    @Test
    void factoryCreatesConnectorFromJobContext() {
        WebRepositoryConnectorFactory factory = new WebRepositoryConnectorFactory();
        assertThat(factory.className()).isEqualTo(WebRepositoryConnector.class.getName());
        WebRepositoryConnector connector = (WebRepositoryConnector) factory.create(
            Map.of("userAgent", "TestBot/1.0 (+https://example.org/bot)"),
            new JobContext("42", "https://example.com/", Map.of("maxPages", 10), key -> null));
        assertThat(connector.settings().maxPages()).isEqualTo(10);
        assertThat(connector.settings().robotsAgentName()).isEqualTo("TestBot");
        assertThat(java.util.ServiceLoader.load(org.opencrawling.core.connector.RepositoryConnectorFactory.class))
            .anyMatch(f -> f instanceof WebRepositoryConnectorFactory);
    }
}
