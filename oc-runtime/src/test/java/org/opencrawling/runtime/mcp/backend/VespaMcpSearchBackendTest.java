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

import org.junit.jupiter.api.Test;
import org.opencrawling.vespa.config.VespaOutputProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VespaMcpSearchBackendTest {

    private static final VespaOutputProperties UNREACHABLE = new VespaOutputProperties(
            "http://127.0.0.1:59999", "opencrawling", "opencrawling_chunk", 1024, 2, false, null, null, null);

    private final VespaMcpSearchBackend backend = new VespaMcpSearchBackend(UNREACHABLE, null, null);

    @Test
    void testBackendType() {
        assertThat(backend.backendType()).isEqualTo("vespa");
    }

    @Test
    void testSecureVectorSearchUnreachableThrows() {
        assertThatThrownBy(() -> backend.secureVectorSearch("query", "user", List.of(), 5, 0.0, null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testGetDocumentContentUnreachableThrows() {
        assertThatThrownBy(() -> backend.getDocumentContent("file:///doc.txt", "user", List.of(), null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testListAccessibleSourcesUnreachableThrows() {
        assertThatThrownBy(() -> backend.listAccessibleSources("user", List.of(), null))
                .isInstanceOf(RuntimeException.class);
    }
}
