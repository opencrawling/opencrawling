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
package org.opencrawling.migrator.mcf.report;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @ParameterizedTest
    @ValueSource(strings = {"password", "vespaPassword", "PASSWORD", "vespaBearerToken", "apiSecret", "authToken", "dbCredential",
        "apiKey", "ocApiKey", "openCrawlingApiKey", "accessKey", "access_key", "privateKey", "passphrase"})
    void secretLookingKeys_areRedacted(String key) {
        assertThat(SecretRedactor.isSecretKey(key)).isTrue();
        assertThat(SecretRedactor.redact(key, "raw-value")).isEqualTo("***REDACTED***");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hostname", "vespaEndpoint", "protocol", "port", "username", "storeid"})
    void nonSecretKeys_passThroughUnchanged(String key) {
        assertThat(SecretRedactor.isSecretKey(key)).isFalse();
        assertThat(SecretRedactor.redact(key, "raw-value")).isEqualTo("raw-value");
    }

    @Test
    void nullValue_staysNull() {
        assertThat(SecretRedactor.redact("password", null)).isNull();
    }

    @Test
    void redactMap_onlyTouchesSecretEntries_preservesOrder() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("hostname", "repo2.localhost");
        input.put("password", "sekrit123");
        input.put("port", "80");

        Map<String, String> result = SecretRedactor.redact(input);

        assertThat(result.keySet()).containsExactly("hostname", "password", "port");
        assertThat(result.get("hostname")).isEqualTo("repo2.localhost");
        assertThat(result.get("password")).isEqualTo("***REDACTED***");
        assertThat(result.get("port")).isEqualTo("80");
    }
}
