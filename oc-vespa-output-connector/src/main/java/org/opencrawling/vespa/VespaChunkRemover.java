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

import ai.vespa.feed.client.DocumentId;
import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.OperationParameters;
import ai.vespa.feed.client.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opencrawling.vespa.config.VespaOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Removes every chunk Vespa holds for a source document, in the document types this output writes (its configured
 * type and the dimension-specific ones its embeddings are routed to) and in its namespace only, so an output sharing
 * the endpoint keeps its own chunks.
 *
 * <p>Chunks are found by {@code document_id}, which every chunk carries whatever URI it was written with (a direct
 * write may carry the claim-check URI, a tombstone the source's). Chunks fed before that field existed, or an
 * application not yet redeployed with it, are found by URI instead.
 */
public final class VespaChunkRemover {

    private static final Logger log = LoggerFactory.getLogger(VespaChunkRemover.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Vespa's default query profile answers at most this many hits per query, from offsets below 1000. */
    static final int PAGE = 400;
    static final int MAX_OFFSET = 1000;
    /** Rounds of looking up and removing; each removes what it finds (up to ~1000 chunks), then looks again. */
    static final int MAX_ROUNDS = 50;

    private final FeedClient feedClient;
    private final VespaOutputProperties properties;
    private final Set<String> documentTypes;
    /** Built on first use: a connector is constructed even when its TLS files are unreadable (a send then fails). */
    private volatile HttpClient http;

    public VespaChunkRemover(FeedClient feedClient, VespaOutputProperties properties) {
        this.feedClient = feedClient;
        this.properties = properties;
        this.documentTypes = new LinkedHashSet<>(List.of(properties.documentType(), VespaFields.DOCUMENT_TYPE_384,
                VespaFields.DOCUMENT_TYPE_768, VespaFields.DOCUMENT_TYPE_1024));
    }

    /**
     * Removes the document's chunks.
     *
     * @param documentId the source document's id
     * @param uri        the URI it was stored under, for chunks that predate {@code document_id}; may be null
     * @return how many chunks were removed
     * @throws Exception when Vespa cannot be asked or a removal fails: the document is then not deleted
     */
    public int removeDocument(String documentId, String uri) throws Exception {
        int removed = 0;
        try {
            removed = remove(VespaFields.FIELD_DOCUMENT_ID, documentId);
        } catch (QueryRejected e) {
            // An application deployed before document_id existed rejects the field.
            log.debug("Vespa rejected the document_id lookup of {}, looking up by URI: {}", documentId, e.getMessage());
        }
        if (removed == 0 && uri != null && !uri.isBlank()) {
            removed = remove(VespaFields.FIELD_URI, uri);
        }
        return removed;
    }

    private int remove(String field, String value) throws Exception {
        Set<String> removed = new HashSet<>();
        for (int round = 0; ; round++) {
            List<String> ids = ownedChunkIds(field, value);
            ids.removeAll(removed);
            if (ids.isEmpty()) {
                return removed.size();
            }
            if (round == MAX_ROUNDS) {
                throw new IllegalStateException("Chunks of " + field + " " + value + " are still found after "
                        + MAX_ROUNDS + " rounds of removal");
            }
            List<CompletableFuture<Result>> removals = new ArrayList<>(ids.size());
            for (String id : ids) {
                removals.add(feedClient.remove(DocumentId.of(id), OperationParameters.empty()));
            }
            for (CompletableFuture<Result> removal : removals) {
                removal.get();
            }
            removed.addAll(ids);
        }
    }

    /**
     * The ids of the chunks with this field value that this output owns. Every full page is read, so hits of other
     * namespaces cannot end the lookup before this output's chunks are found.
     */
    List<String> ownedChunkIds(String field, String value) throws Exception {
        List<String> owned = new ArrayList<>();
        for (int offset = 0; offset < MAX_OFFSET; offset += PAGE) {
            List<String> page = chunkIds(field, value, offset);
            for (String id : page) {
                if (owns(id)) {
                    owned.add(id);
                }
            }
            if (page.size() < PAGE) {
                return owned;
            }
        }
        if (owned.isEmpty()) {
            // Every page Vespa lets us read is other outputs' chunks: this output's may lie beyond them.
            throw new IllegalStateException("More than " + MAX_OFFSET + " chunks of other outputs share " + field + " "
                    + value + ": this output's chunks cannot be reached");
        }
        return owned;
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

    /** Whether a Vespa document id ({@code id:<namespace>:<type>::<local>}) is in this output's namespace and types. */
    boolean owns(String id) {
        String[] parts = id.split(":", 5);
        return parts.length == 5 && "id".equals(parts[0]) && properties.namespace().equals(parts[1])
                && documentTypes.contains(parts[2]);
    }

    private List<String> chunkIds(String field, String value, int offset) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("yql", "select documentid from sources " + String.join(", ", documentTypes) + " where " + field
                + " contains \"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        body.put("hits", PAGE);
        body.put("offset", offset);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.endpoint() + "/search/"))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds() > 0 ? properties.timeoutSeconds() : 30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = JSON.readTree(response.body()).path("root");
        if (root.path("errors").isArray() && !root.path("errors").isEmpty()) {
            String errors = root.path("errors").toString();
            // Only an application deployed before the field existed falls back to URIs; a timeout or any other error
            // must fail the deletion, or a document could be reported deleted while its chunks stay.
            if (errors.contains("Field '" + field + "' does not exist")) {
                throw new QueryRejected(errors);
            }
            throw new IllegalStateException("Vespa could not look up the chunks with " + field + " " + value + ": " + errors);
        }
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("Vespa answered " + response.statusCode() + " to a chunk lookup: "
                    + response.body());
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode hit : root.path("children")) {
            String id = hit.path("id").asText("");
            if (id.startsWith("id:")) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Vespa answered the lookup with errors (an unknown field, for instance). */
    static final class QueryRejected extends IllegalStateException {
        QueryRejected(String errors) {
            super("Vespa rejected the chunk lookup: " + errors);
        }
    }
}
