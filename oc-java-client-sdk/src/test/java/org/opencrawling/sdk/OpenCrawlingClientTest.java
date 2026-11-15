/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.sdk;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenCrawlingClientTest {

    @Test
    void testClientBuilderInitialization() {
        OpenCrawlingClient client = OpenCrawlingClient.builder()
                .baseUrl("http://localhost:9090")
                .apiKey("test-api-key")
                .bearerToken("test-bearer-token")
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))
                .build();

        assertNotNull(client);
        assertNotNull(client.jobs());
        assertNotNull(client.connectors());
        assertNotNull(client.system());
        assertNotNull(client.observability());
        assertNotNull(client.narrativization());
        assertNotNull(client.reports());
        assertNotNull(client.openApi());
    }
}
