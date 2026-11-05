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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class UrlPolicyTest {

    private static UrlPolicy policy(Map<String, Object> spec) {
        Map<String, Object> withSeeds = new HashMap<>(spec);
        withSeeds.putIfAbsent("seeds", List.of("https://docs.example.co.uk/guide/start.html"));
        WebCrawlSettings settings = WebCrawlSettings.resolve(withSeeds, Map.of(), key -> null, null);
        return new UrlPolicy(settings, List.of("https://docs.example.co.uk/guide/start.html"));
    }

    @Test
    void stayOnDomainAllowsSubdomainsOfTheRegistrableDomain() {
        UrlPolicy policy = policy(Map.of());
        assertThat(policy.reject("https://www.example.co.uk/about", 1)).isNull();
        assertThat(policy.reject("https://example.co.uk/", 1)).isNull();
        assertThat(policy.reject("https://other.co.uk/", 1)).isEqualTo("scope");
    }

    @Test
    void hostScopeAllowsOnlySeedHosts() {
        UrlPolicy policy = policy(Map.of("scope", "host"));
        assertThat(policy.reject("https://docs.example.co.uk/other", 1)).isNull();
        assertThat(policy.reject("https://www.example.co.uk/", 1)).isEqualTo("scope");
    }

    @Test
    void prefixScopeRestrictsToTheSeedDirectory() {
        UrlPolicy policy = policy(Map.of("scope", "prefix"));
        assertThat(policy.reject("https://docs.example.co.uk/guide/next.html", 1)).isNull();
        assertThat(policy.reject("https://docs.example.co.uk/blog/", 1)).isEqualTo("scope");
    }

    @Test
    void stayOnDomainFalseAllowsAnyHost() {
        assertThat(policy(Map.of("stayOnDomain", false)).reject("https://elsewhere.org/", 1)).isNull();
    }

    @Test
    void excludesWinOverIncludes() {
        UrlPolicy policy = policy(Map.of(
            "includePatterns", List.of("^/guide/"),
            "excludePatterns", List.of("\\.pdf$")));
        assertThat(policy.reject("https://docs.example.co.uk/guide/a.html", 1)).isNull();
        assertThat(policy.reject("https://docs.example.co.uk/guide/a.pdf", 1)).isEqualTo("exclude-pattern");
        assertThat(policy.reject("https://docs.example.co.uk/blog/a.html", 1)).isEqualTo("include-pattern");
    }

    @Test
    void enforcesDepthAndTrapLimits() {
        UrlPolicy policy = policy(Map.of("maxDepth", 2));
        assertThat(policy.reject("https://docs.example.co.uk/a", 2)).isNull();
        assertThat(policy.reject("https://docs.example.co.uk/a", 3)).isEqualTo("depth");
        assertThat(policy.reject("https://docs.example.co.uk/a/b/a/b/a/b/a/b", 1)).isEqualTo("trap-repeated-segments");
        assertThat(policy.reject("https://docs.example.co.uk/a?" + "p=1&".repeat(11), 1)).isEqualTo("trap-query-params");
        assertThat(policy.reject("https://docs.example.co.uk/" + "x".repeat(3000), 1)).isEqualTo("url-length");
    }
}
