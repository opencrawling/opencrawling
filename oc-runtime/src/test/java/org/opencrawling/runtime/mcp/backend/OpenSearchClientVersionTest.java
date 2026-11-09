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
package org.opencrawling.runtime.mcp.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * Guards the runtime classpath: the unrelocated OpenSearch client must be the 3.x one that
 * oc-opensearch3-output-connector is built against. The 2.x client used to win by Maven's
 * nearest-first rule (through oc-opensearch2-output-connector's POM); its transport gunzips
 * responses that HttpClient 5.6 has already decompressed, so every call to OpenSearch failed
 * with "Not in GZIP format".
 */
class OpenSearchClientVersionTest {

    @Test
    void runtimeUsesTheOpenSearch3Client() {
        String location = OpenSearchClient.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertThat(location).contains("opensearch-java-3.");
    }
}
