/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.sdk.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * Textual content payload of an {@link OisDocument} pushed to the OIS Direct Ingestion Gateway.
 *
 * @param mimeType MIME type of the content (e.g. {@code text/plain}, {@code text/markdown}, {@code text/html})
 * @param text     raw text content to be chunked and embedded
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OisContent(String mimeType, String text) {

    public OisContent {
        Objects.requireNonNull(text, "text is required");
        mimeType = (mimeType == null || mimeType.isBlank()) ? "text/plain" : mimeType.trim();
    }

    public static OisContent of(String mimeType, String text) {
        return new OisContent(mimeType, text);
    }

    public static OisContent text(String text) {
        return new OisContent("text/plain", text);
    }

    public static OisContent markdown(String text) {
        return new OisContent("text/markdown", text);
    }

    public static OisContent html(String text) {
        return new OisContent("text/html", text);
    }
}
