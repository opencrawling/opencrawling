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

/**
 * How one job's documents are split into chunks, overriding the ingestion consumer's defaults. Each setting is
 * optional: one left {@code null} keeps the default, which is Spring AI's {@code TokenTextSplitter} default. There is
 * no overlap setting, because that splitter does not overlap chunks, and no setting for the most chunks per
 * document, because past that number the splitter puts the rest of the text into one last, oversized chunk.
 *
 * @param chunkSize             target chunk size in tokens (default {@value #DEFAULT_CHUNK_SIZE})
 * @param minChunkSizeChars     minimum chunk size in characters before the splitter cuts at the last sentence end
 *                              (default {@value #DEFAULT_MIN_CHUNK_SIZE_CHARS})
 * @param minChunkLengthToEmbed chunks shorter than this many characters are dropped
 *                              (default {@value #DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED})
 * @param keepSeparator         whether line separators are kept in the chunk text (default {@code true})
 */
public record ChunkingSpec(Integer chunkSize, Integer minChunkSizeChars, Integer minChunkLengthToEmbed,
                           Boolean keepSeparator) {

    public static final int DEFAULT_CHUNK_SIZE = 800;
    public static final int DEFAULT_MIN_CHUNK_SIZE_CHARS = 350;
    public static final int DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED = 5;

    /** The largest chunk size any job may ask for, whatever the runtime allows. */
    public static final int LIMIT_CHUNK_SIZE = 8192;
    /** The largest character minimums any job may ask for. */
    public static final int LIMIT_CHARS = 100_000;

    /** {@code true} when no setting is given, so the defaults apply. */
    public boolean isEmpty() {
        return chunkSize == null && minChunkSizeChars == null && minChunkLengthToEmbed == null
                && keepSeparator == null;
    }

    /**
     * {@code true} when every setting given is within the limits no job may exceed: a chunk size from 1 to
     * {@value #LIMIT_CHUNK_SIZE} tokens and character minimums from 0 to {@value #LIMIT_CHARS}.
     */
    public boolean isWithinLimits() {
        return within(chunkSize, 1, LIMIT_CHUNK_SIZE)
                && within(minChunkSizeChars, 0, LIMIT_CHARS)
                && within(minChunkLengthToEmbed, 0, LIMIT_CHARS);
    }

    public int effectiveChunkSize() {
        return chunkSize != null ? chunkSize : DEFAULT_CHUNK_SIZE;
    }

    public int effectiveMinChunkSizeChars() {
        return minChunkSizeChars != null ? minChunkSizeChars : DEFAULT_MIN_CHUNK_SIZE_CHARS;
    }

    public int effectiveMinChunkLengthToEmbed() {
        return minChunkLengthToEmbed != null ? minChunkLengthToEmbed : DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED;
    }

    public boolean effectiveKeepSeparator() {
        return keepSeparator == null || keepSeparator;
    }

    private static boolean within(Integer value, int min, int max) {
        return value == null || (value >= min && value <= max);
    }
}
