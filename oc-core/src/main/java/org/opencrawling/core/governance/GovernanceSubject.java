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
package org.opencrawling.core.governance;

import org.opencrawling.core.security.SecurityConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link PreEmbeddingGovernanceHook} sees of a document before it is chunked and embedded.
 *
 * <p>The security is given so a hook can take it into account (for example, be stricter with public documents),
 * but no decision can change it: the stored permissions always come from the source.
 *
 * @param documentId the document's id
 * @param uri        where the document came from
 * @param text       the extracted text that would be chunked
 * @param metadata   the document's metadata (unmodifiable)
 * @param security   the document's source permissions; {@code null} when it has none
 */
public record GovernanceSubject(String documentId, String uri, String text, Map<String, List<String>> metadata,
                                SecurityConfig security) {

    public GovernanceSubject {
        Objects.requireNonNull(documentId, "documentId");
        text = text != null ? text : "";
        metadata = copy(metadata);
    }

    /** An unmodifiable copy that keeps the order, with {@code null} keys, values and list entries left out. */
    static Map<String, List<String>> copy(Map<String, List<String>> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        metadata.forEach((key, values) -> {
            if (key != null && values != null) {
                List<String> kept = new ArrayList<>(values.size());
                for (String value : values) {
                    if (value != null) {
                        kept.add(value);
                    }
                }
                copy.put(key, List.copyOf(kept));
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
