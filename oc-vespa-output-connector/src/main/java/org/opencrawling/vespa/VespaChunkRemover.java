/*
 * Copyright © ${year} the original author or authors (michael@michaelcizmar.com)
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
package org.opencrawling.vespa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.vespa.config.VespaOutputProperties;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes every chunk Vespa holds for a source document, through Vespa's delete by selection
 * ({@code DELETE /document/v1/<namespace>/<type>/docid?selection=…&cluster=…}): the content nodes remove exactly the
 * documents the selection matches. Nothing is searched, so the result does not depend on search visibility, coverage,
 * paging or case-insensitive matching, and the namespace and document type in the path keep other outputs' documents
 * out of reach. Only the document types this output writes are addressed: its configured type and the
 * dimension-specific ones its embeddings are routed to.
 *
 * <p>A document's chunks are matched by {@code document_id}, which every chunk carries whatever URI it was written with
 * (a direct write may carry the claim-check URI, a tombstone the source's). Chunks without one (fed before the field
 * existed) are matched by URI. A selection naming a field the deployed schema lacks matches nothing for
 * {@code document_id == "…"} and everything for {@code document_id == null}, so an application deployed before the
 * field existed is handled by the URI selection alone.
 */
public final class VespaChunkRemover {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final VespaOutputProperties properties;
    private final Set<String> documentTypes;
    /** Built on first use: a connector is constructed even when its TLS files are unreadable (a deletion then fails). */
    private volatile HttpClient http;

    public VespaChunkRemover(VespaOutputProperties properties) {
        this.properties = properties;
        this.documentTypes = new LinkedHashSet<>(List.of(properties.documentType(), VespaFields.DOCUMENT_TYPE_384,
                VespaFields.DOCUMENT_TYPE_768, VespaFields.DOCUMENT_TYPE_1024));
    }

    /**
     * Removes the document's chunks.
     *
     * @param documentId the source document's id
     * @param uri        the URI it was stored under, for chunks that predate {@code document_id}; may be null
     * @return how many chunks Vespa removed
     * @throws Exception when Vespa refuses or cannot be reached: the document is then not deleted
     */
    public int removeDocument(String documentId, String uri) throws Exception {
        int removed = 0;
        int deployed = 0;
        for (String type : documentTypes) {
            try {
                removed += deleteWhere(type, type + "." + VespaFields.FIELD_DOCUMENT_ID + " == " + literal(documentId));
                if (uri != null && !uri.isBlank()) {
                    removed += deleteWhere(type, type + "." + VespaFields.FIELD_URI + " == " + literal(uri) + " and "
                            + type + "." + VespaFields.FIELD_DOCUMENT_ID + " == null");
                }
                deployed++;
            } catch (TypeNotDeployed e) {
                // A custom application package may deploy only some of the types: nothing was written to this one.
            }
        }
        if (deployed == 0) {
            throw new IllegalStateException("None of the document types " + documentTypes + " is deployed in content cluster '"
                    + properties.contentCluster() + "' at " + properties.endpoint());
        }
        return removed;
    }

    /** Deletes the type's documents matching the selection; a large set is removed over several calls. */
    int deleteWhere(String type, String selection) throws Exception {
        int removed = 0;
        String continuation = null;
        do {
            StringBuilder path = new StringBuilder(properties.endpoint()).append("/document/v1/")
                    .append(encode(properties.namespace())).append('/').append(encode(type)).append("/docid")
                    .append("?selection=").append(encode(selection))
                    .append("&cluster=").append(encode(properties.contentCluster()))
                    // Each call works at most this long and returns a continuation, well before the client gives up
                    // (Vespa's own default slice, 60 s, outlasts the usual timeout).
                    .append("&timeChunk=").append(timeChunkSeconds()).append('s');
            if (continuation != null) {
                path.append("&continuation=").append(encode(continuation));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(path.toString()))
                    .timeout(Duration.ofSeconds(properties.timeoutSeconds() > 0 ? properties.timeoutSeconds() : 30))
                    .DELETE()
                    .build();
            HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                if (response.statusCode() == 400 && response.body().contains("There is no document type '" + type + "'")) {
                    throw new TypeNotDeployed(type);
                }
                throw new IllegalStateException("Vespa refused to delete " + selection + " (" + response.statusCode()
                        + "): " + response.body());
            }
            JsonNode answer = JSON.readTree(response.body());
            removed += answer.path("documentCount").asInt(0);
            continuation = answer.hasNonNull("continuation") ? answer.path("continuation").asText() : null;
        } while (continuation != null);
        return removed;
    }

    /** Half the client's timeout, so a slice's answer always arrives in time. */
    int timeChunkSeconds() {
        int timeout = properties.timeoutSeconds() > 0 ? properties.timeoutSeconds() : 30;
        return Math.max(1, timeout / 2);
    }

    /** Vespa answered that the application has no such document type. */
    static final class TypeNotDeployed extends IllegalStateException {
        TypeNotDeployed(String type) {
            super("Document type " + type + " is not deployed");
        }
    }

    /** A string literal of Vespa's document selection language. */
    static String literal(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpClient http() {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                if (http == null) {
                    http = VespaTls.httpClient(properties);
                }
                client = http;
            }
        }
        return client;
    }
}
