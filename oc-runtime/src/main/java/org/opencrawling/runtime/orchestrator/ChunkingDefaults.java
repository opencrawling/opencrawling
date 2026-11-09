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

import java.util.concurrent.atomic.AtomicReference;
import org.opencrawling.core.messaging.ChunkingSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The chunk size an administrator sets in the global settings. Every job's documents carry it on
 * {@link org.opencrawling.core.messaging.IngestionMessage#chunking()} unless the job sets its own, so the ingestion
 * consumer applies it wherever it runs (also in decoupled deployments, where it does not see the settings).
 *
 * <p>The splitter's own default ({@value ChunkingSpec#DEFAULT_CHUNK_SIZE} tokens) is not stamped, so messages stay
 * as they were until an administrator changes it.
 */
@Component
public class ChunkingDefaults {

    private static final Logger log = LoggerFactory.getLogger(ChunkingDefaults.class);

    private final AtomicReference<Integer> chunkSize = new AtomicReference<>();

    /** Whether {@code size} is a chunk size the global settings may hold. */
    public static boolean isValidChunkSize(int size) {
        return size >= 1 && size <= ChunkingSpec.LIMIT_CHUNK_SIZE;
    }

    /**
     * Sets the global chunk size. A size outside 1 to {@value ChunkingSpec#LIMIT_CHUNK_SIZE} tokens (for example in an
     * old settings file) is ignored with a warning, and the splitter's default applies.
     */
    public void setChunkSize(int size) {
        if (!isValidChunkSize(size)) {
            log.warn("Ignoring the global chunk size {}: it must be from 1 to {} tokens. Using the default ({}).",
                    size, ChunkingSpec.LIMIT_CHUNK_SIZE, ChunkingSpec.DEFAULT_CHUNK_SIZE);
            chunkSize.set(null);
        } else {
            chunkSize.set(size == ChunkingSpec.DEFAULT_CHUNK_SIZE ? null : size);
        }
    }

    /**
     * The chunking a job's documents carry: the job's own, with the global chunk size where the job gives none.
     * {@code null} when neither sets anything.
     */
    public ChunkingSpec apply(ChunkingSpec job) {
        Integer global = chunkSize.get();
        if (global == null) {
            return job;
        }
        if (job == null || job.isEmpty()) {
            return new ChunkingSpec(global, null, null, null);
        }
        if (job.chunkSize() != null) {
            return job;
        }
        return new ChunkingSpec(global, job.minChunkSizeChars(), job.minChunkLengthToEmbed(), job.keepSeparator());
    }
}
