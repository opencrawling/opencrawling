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
package org.opencrawling.core.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RequiredEmbeddingsTest {

    @Test
    void aMissingModelFailsTheDocument() {
        assertThatThrownBy(() -> RequiredEmbeddings.requireModel(null, "doc-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("doc-1").hasMessageContaining("nothing was stored");
        RequiredEmbeddings.requireModel(new Object(), "doc-1");
    }

    @Test
    void aFailedOrEmptyEmbeddingFailsTheDocumentAndARealOneIsReturned() {
        assertThatThrownBy(() -> RequiredEmbeddings.embed(text -> { throw new RuntimeException("model down"); }, "t", "doc-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("model down").hasRootCauseMessage("model down");
        assertThatThrownBy(() -> RequiredEmbeddings.embed(text -> null, "t", "doc-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RequiredEmbeddings.embed(text -> new float[0], "t", "doc-1")).isInstanceOf(IllegalStateException.class);
        assertThat(RequiredEmbeddings.embed(text -> new float[] {0.2f, 0.4f}, "t", "doc-1")).containsExactly(0.2f, 0.4f);
    }

    @Test
    void aMessageWithoutAVectorIsRefused() {
        assertThatThrownBy(() -> RequiredEmbeddings.ofMessage(null, "c1", "doc-1")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chunk c1 of document doc-1 has no embedding");
        assertThatThrownBy(() -> RequiredEmbeddings.ofMessage(new float[0], "c1", "doc-1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(RequiredEmbeddings.ofMessage(new float[] {1f}, "c1", "doc-1")).containsExactly(1f);
    }
}
