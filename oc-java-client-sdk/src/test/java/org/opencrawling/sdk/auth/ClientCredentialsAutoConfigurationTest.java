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
package org.opencrawling.sdk.auth;

import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.spring.OpenCrawlingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ClientCredentialsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenCrawlingAutoConfiguration.class));

    @Test
    void withAClientIdTheClientGetsItsOwnTokens() throws Exception {
        try (FakeIdentityProvider idp = new FakeIdentityProvider()) {
            idp.respondAccessToken("cc-token", 300);
            idp.acceptedApiToken = "cc-token";
            contextRunner.withPropertyValues(
                    "opencrawling.client.base-url=" + idp.base,
                    "opencrawling.client.bearer-token=ignored-when-client-credentials-are-set",
                    "opencrawling.client.oauth2.token-uri=" + idp.base + "/token",
                    "opencrawling.client.oauth2.client-id=reporting-service",
                    "opencrawling.client.oauth2.client-secret=from-the-environment",
                    "opencrawling.client.oauth2.scope=jobs"
            ).run(context -> {
                assertThat(context.getBean(OpenCrawlingClient.class).jobs().list()).isEmpty();
                assertThat(idp.requestsTo("/token")).hasSize(1);
                assertThat(idp.requestsTo("/api/jobs").getFirst().authorization()).isEqualTo("Bearer cc-token");
            });
        }
    }

    @Test
    void withoutOneTheBearerTokenIsSent() throws Exception {
        try (FakeIdentityProvider idp = new FakeIdentityProvider()) {
            idp.acceptedApiToken = "static-token";
            contextRunner.withPropertyValues(
                    "opencrawling.client.base-url=" + idp.base,
                    "opencrawling.client.bearer-token=static-token"
            ).run(context -> {
                assertThat(context.getBean(OpenCrawlingClient.class).jobs().list()).isEmpty();
                assertThat(idp.requestsTo("/token")).isEmpty();
            });
        }
    }
}
