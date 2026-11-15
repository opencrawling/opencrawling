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
package org.opencrawling.runtime.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.messaging.ChunkingSpec;

class ChunkingDefaultsTest {

    private final ChunkingDefaults defaults = new ChunkingDefaults();

    @Test
    void withoutAGlobalChunkSizeTheJobsChunkingIsUnchanged() {
        ChunkingSpec job = new ChunkingSpec(300, null, null, false);
        assertThat(defaults.apply(null)).isNull();
        assertThat(defaults.apply(job)).isSameAs(job);
    }

    @Test
    void theSplittersOwnDefaultIsNotStamped() {
        defaults.setChunkSize(ChunkingSpec.DEFAULT_CHUNK_SIZE);
        assertThat(defaults.apply(null)).isNull();
    }

    @Test
    void jobsWithoutChunkingGetTheGlobalChunkSize() {
        defaults.setChunkSize(400);
        assertThat(defaults.apply(null)).isEqualTo(new ChunkingSpec(400, null, null, null));
        assertThat(defaults.apply(new ChunkingSpec(null, null, null, null))).isEqualTo(new ChunkingSpec(400, null, null, null));
    }

    @Test
    void aJobsOwnChunkSizeWins() {
        defaults.setChunkSize(400);
        ChunkingSpec job = new ChunkingSpec(300, null, null, false);
        assertThat(defaults.apply(job)).isSameAs(job);
    }

    @Test
    void aJobThatSetsOnlyOtherSettingsGetsTheGlobalChunkSize() {
        defaults.setChunkSize(400);
        assertThat(defaults.apply(new ChunkingSpec(null, 200, 10, false)))
                .isEqualTo(new ChunkingSpec(400, 200, 10, false));
    }

    @Test
    void aChunkSizeOutsideTheLimitsIsIgnored() {
        defaults.setChunkSize(400);
        defaults.setChunkSize(ChunkingSpec.LIMIT_CHUNK_SIZE + 1);
        assertThat(defaults.apply(null)).isNull();
        defaults.setChunkSize(0);
        assertThat(defaults.apply(null)).isNull();
        assertThat(ChunkingDefaults.isValidChunkSize(1)).isTrue();
        assertThat(ChunkingDefaults.isValidChunkSize(ChunkingSpec.LIMIT_CHUNK_SIZE)).isTrue();
    }
}
