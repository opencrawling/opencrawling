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
package org.opencrawling.core.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceRefTest {

    @Test
    void takesTheSourceDocumentIdNeverAChunkId() {
        assertThat(ResourceRef.fromMetadata(Map.of("documentId", "doc-1", "chunk_id", "c-9")).documentId()).isEqualTo("doc-1");
        assertThat(ResourceRef.fromMetadata(Map.of("document_id", "doc-2")).documentId()).isEqualTo("doc-2");
        assertThat(ResourceRef.fromMetadata(Map.of("parent_document_id", "doc-3")).documentId()).isEqualTo("doc-3");
        assertThat(ResourceRef.fromMetadata(Map.of("chunk_id", "c-9", "id", "c-9")).documentId()).isNull();
        assertThat(ResourceRef.fromMetadata(null).documentId()).isNull();
    }

    @Test
    void readsMultiValuedAndBlankIds() {
        assertThat(ResourceRef.fromMetadata(Map.of("documentId", List.of("doc-1"))).documentId()).isEqualTo("doc-1");
        Map<String, Object> blank = new HashMap<>();
        blank.put("documentId", " ");
        blank.put("document_id", "doc-2");
        assertThat(ResourceRef.fromMetadata(blank).documentId()).isEqualTo("doc-2");
        assertThat(ResourceRef.idValue(List.of())).isNull();
        assertThat(ResourceRef.idValue(null)).isNull();
    }
}
