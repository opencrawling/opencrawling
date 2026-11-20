/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
package org.opencrawling.sdk.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Asks to ingest one document of {@code connector} ({@code UPSERT}), or to remove it from the index ({@code DELETE},
 * only when the repository reports it gone).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentIngestRequest(String connector, String document, String action, Map<String, Object> chunking) {

    public static DocumentIngestRequest upsert(String connector, String document) {
        return new DocumentIngestRequest(connector, document, "UPSERT", null);
    }

    public static DocumentIngestRequest delete(String connector, String document) {
        return new DocumentIngestRequest(connector, document, "DELETE", null);
    }
}
