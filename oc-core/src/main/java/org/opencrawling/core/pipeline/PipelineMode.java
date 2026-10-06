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
package org.opencrawling.core.pipeline;

/**
 * Pipeline execution mode for OpenCrawling.
 * <ul>
 *   <li><b>RAG</b> (default): Follows the AI ingestion pipeline (Tika extraction, narrativization, chunking, vector embedding).</li>
 *   <li><b>MIGRATION</b>: Migrates content as-is to target systems, skipping narrativization and embedding, while producing OIS Zero-Trust metadata sidecars.</li>
 * </ul>
 */
public enum PipelineMode {
    RAG,
    MIGRATION;

    public static PipelineMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return RAG;
        }
        try {
            return PipelineMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RAG;
        }
    }

    /**
     * Strict variant of {@link #fromString(String)}: blank values resolve to {@code null}
     * (meaning "inherit the global default"), while unknown values are rejected instead of
     * silently falling back to {@link #RAG}.
     *
     * @param value the pipeline mode name (case-insensitive), e.g. {@code rag} or {@code migration}
     * @return the matching mode, or {@code null} when the value is blank
     * @throws IllegalArgumentException when the value is not a known pipeline mode
     */
    public static PipelineMode parseStrict(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (PipelineMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown pipeline mode '" + value + "' (expected: rag or migration)");
    }

    /**
     * Lower-case external representation used in job definitions, REST payloads and the CLI.
     *
     * @return the lower-case mode name
     */
    public String externalName() {
        return name().toLowerCase();
    }

    public boolean isMigration() {
        return this == MIGRATION;
    }

    public boolean isRag() {
        return this == RAG;
    }
}
