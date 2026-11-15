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
package org.opencrawling.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChunkingSpecTest {

    @Test
    void unsetSettingsKeepTheDefaults() {
        ChunkingSpec none = new ChunkingSpec(null, null, null, null);
        assertThat(none.isEmpty()).isTrue();
        assertThat(none.effectiveChunkSize()).isEqualTo(800);
        assertThat(none.effectiveMinChunkSizeChars()).isEqualTo(350);
        assertThat(none.effectiveMinChunkLengthToEmbed()).isEqualTo(5);
        assertThat(none.effectiveKeepSeparator()).isTrue();

        ChunkingSpec some = new ChunkingSpec(300, null, null, false);
        assertThat(some.isEmpty()).isFalse();
        assertThat(some.effectiveChunkSize()).isEqualTo(300);
        assertThat(some.effectiveMinChunkSizeChars()).isEqualTo(350);
        assertThat(some.effectiveKeepSeparator()).isFalse();
        assertThat(new ChunkingSpec(null, null, null, false).isEmpty()).isFalse();
    }

    @Test
    void noJobMayExceedTheHardLimits() {
        assertThat(new ChunkingSpec(1, 0, 0, true).isWithinLimits()).isTrue();
        assertThat(new ChunkingSpec(8192, 100_000, 100_000, null).isWithinLimits()).isTrue();
        assertThat(new ChunkingSpec(null, null, null, null).isWithinLimits()).isTrue();

        assertThat(new ChunkingSpec(0, null, null, null).isWithinLimits()).isFalse();
        assertThat(new ChunkingSpec(8193, null, null, null).isWithinLimits()).isFalse();
        assertThat(new ChunkingSpec(null, -1, null, null).isWithinLimits()).isFalse();
        assertThat(new ChunkingSpec(null, 100_001, null, null).isWithinLimits()).isFalse();
        assertThat(new ChunkingSpec(null, null, -1, null).isWithinLimits()).isFalse();
        assertThat(new ChunkingSpec(null, null, 100_001, null).isWithinLimits()).isFalse();
    }

    @Test
    void anEmptySpecOnAMessageMeansTheDefaultChunking() {
        IngestionMessage message = new IngestionMessage("doc", "file:///c", java.util.Map.of(), "public", null, "t",
                null, null, java.util.Map.of(), null, null, new ChunkingSpec(null, null, null, null));
        assertThat(message.chunking()).isNull();

        IngestionMessage older = new IngestionMessage("doc", "file:///c", java.util.Map.of(), "public", null, "t",
                null, null, java.util.Map.of(), null, null);
        assertThat(older.chunking()).isNull();
    }
}
