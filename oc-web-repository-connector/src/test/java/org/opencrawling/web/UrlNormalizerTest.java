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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UrlNormalizerTest {

    private final UrlNormalizer normalizer = new UrlNormalizer(WebCrawlSettings.DEFAULT_STRIP_QUERY_PARAMS);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "HTTP://Example.COM/Path              | http://example.com/Path",
        "https://example.com:443/a            | https://example.com/a",
        "http://example.com:80/a              | http://example.com/a",
        "http://example.com:8080/a            | http://example.com:8080/a",
        "https://example.com                  | https://example.com/",
        "https://example.com/a/./b/../c       | https://example.com/a/c",
        "https://example.com/a#section        | https://example.com/a",
        "https://user:pw@example.com/a        | https://example.com/a",
        "https://example.com/a?utm_source=x&id=1&fbclid=y | https://example.com/a?id=1",
        "https://example.com/a?utm_source=x   | https://example.com/a",
        "https://example.com/a;jsessionid=ABC?x=1 | https://example.com/a?x=1",
        "https://example.com/%7euser/%2f      | https://example.com/~user/%2F",
        "https://example.com/a b              | https://example.com/a%20b",
        "https://bücher.example/straße        | https://xn--bcher-kva.example/stra%C3%9Fe",
        "https://example.com./a               | https://example.com/a",
    })
    void normalizesUrls(String input, String expected) {
        assertThat(normalizer.normalize(input)).contains(expected);
    }

    @Test
    void resolvesRelativeLinksAgainstBase() {
        assertThat(normalizer.normalize("../b?q=1#top", "https://example.com/docs/a/page.html"))
            .contains("https://example.com/docs/b?q=1");
        assertThat(normalizer.normalize("//cdn.example.com/x", "https://example.com/"))
            .contains("https://cdn.example.com/x");
    }

    @Test
    void rejectsNonHttpUrls() {
        assertThat(normalizer.normalize("mailto:someone@example.com")).isEmpty();
        assertThat(normalizer.normalize("javascript:alert(1)")).isEmpty();
        assertThat(normalizer.normalize("ftp://example.com/file")).isEmpty();
        assertThat(normalizer.normalize("file:///etc/passwd")).isEmpty();
        assertThat(normalizer.normalize("")).isEmpty();
    }

    @Test
    void stripsUserConfiguredQueryParameters() {
        UrlNormalizer custom = new UrlNormalizer(List.of("ref", "session*"));
        assertThat(custom.normalize("https://example.com/?ref=home&sessionKey=1&page=2"))
            .contains("https://example.com/?page=2");
    }
}
