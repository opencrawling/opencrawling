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
package org.opencrawling.core.text;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Service for extracting text and metadata from document content streams.
 * Implementations may use in-process Tika or process-isolated PipesForkParser.
 */
public interface TextExtractionService extends AutoCloseable {

    /**
     * Extract text and metadata from an input stream.
     *
     * @param inputStream the document content stream
     * @param initialMetadata initial repository metadata (mimeType, filename, etc.)
     * @return the extraction result
     */
    TextExtractionResult extractText(InputStream inputStream, Map<String, List<String>> initialMetadata);

    /**
     * Extract text and metadata from raw bytes.
     *
     * @param contentBytes the document content bytes
     * @param initialMetadata initial repository metadata
     * @return the extraction result
     */
    TextExtractionResult extractText(byte[] contentBytes, Map<String, List<String>> initialMetadata);

    @Override
    default void close() {
        // Default no-op for closeable
    }
}
