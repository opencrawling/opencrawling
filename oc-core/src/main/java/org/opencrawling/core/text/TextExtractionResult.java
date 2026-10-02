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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of text extraction from document content streams via Apache Tika.
 *
 * @param text extracted plain text content, stripped of null characters
 * @param metadata extracted metadata properties
 * @param trimmed whether extraction was truncated due to write limit or partial timeout
 * @param success whether parsing completed successfully
 * @param errorMessage error or crash description if parsing failed, null otherwise
 */
public record TextExtractionResult(
        String text,
        Map<String, List<String>> metadata,
        boolean trimmed,
        boolean success,
        String errorMessage
) {
    public static TextExtractionResult success(String text, Map<String, List<String>> metadata, boolean trimmed) {
        return new TextExtractionResult(
                text != null ? text : "",
                metadata != null ? metadata : Collections.emptyMap(),
                trimmed,
                true,
                null
        );
    }

    public static TextExtractionResult failure(String errorMessage, Map<String, List<String>> metadata) {
        return new TextExtractionResult(
                "",
                metadata != null ? metadata : Collections.emptyMap(),
                false,
                false,
                errorMessage
        );
    }
}
