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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight, non-forking text extraction service for environments where Tika is disabled,
 * connector unit tests, or unmanaged constructor fallbacks.
 * Performs safe plain-text fallback for text/json/xml/csv files without launching child JVMs.
 */
public class NoOpTextExtractionService implements TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(NoOpTextExtractionService.class);

    @Override
    public TextExtractionResult extractText(InputStream inputStream, Map<String, List<String>> initialMetadata) {
        if (inputStream == null) {
            return TextExtractionResult.success("", initialMetadata, false);
        }
        try {
            byte[] bytes = inputStream.readAllBytes();
            return extractText(bytes, initialMetadata);
        } catch (IOException e) {
            log.error("Failed to read input stream in NoOpTextExtractionService: {}", e.getMessage(), e);
            return TextExtractionResult.failure("IO error reading document stream: " + e.getMessage(), initialMetadata);
        }
    }

    @Override
    public TextExtractionResult extractText(byte[] contentBytes, Map<String, List<String>> initialMetadata) {
        if (contentBytes == null || contentBytes.length == 0) {
            return TextExtractionResult.success("", initialMetadata != null ? initialMetadata : Collections.emptyMap(), false);
        }

        String text = fallbackPlainText(contentBytes, initialMetadata);
        return TextExtractionResult.success(text, initialMetadata != null ? initialMetadata : Collections.emptyMap(), false);
    }

    private String fallbackPlainText(byte[] contentBytes, Map<String, List<String>> metadata) {
        if (metadata != null) {
            String mimeType = String.valueOf(metadata.getOrDefault("mimeType", List.of("text/plain")));
            if (mimeType.contains("text") || mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("csv")) {
                return new String(contentBytes, StandardCharsets.UTF_8).replace("\u0000", "");
            }
        }
        return "";
    }

    @Override
    public boolean isForkActive() {
        return false;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public String getEngineName() {
        return "No-Op / Plain Text Fallback";
    }

    @Override
    public String getVersion() {
        return "N/A";
    }

    @Override
    public void close() {
        // No resources to close
    }
}
