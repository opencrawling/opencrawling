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

import ai.vespa.feed.client.FeedClient;
import ai.vespa.feed.client.OperationParameters;
import ai.vespa.feed.client.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feeds a chunk to Vespa. An application deployed before the schemas gained a field rejects every document that
 * carries it ({@code Field 'x' is not defined in document type 'y'}); for the fields added after the schemas' first
 * release the chunk is fed again without them, so ingestion goes on until the application is redeployed.
 */
public final class VespaFeeds {

    private static final Logger log = LoggerFactory.getLogger(VespaFeeds.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UNDECLARED = Pattern.compile("Field '([^']+)' is not defined in document type");
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** Fields the schemas gained after their first release: what a chunk can be fed without. */
    static final List<String> LATER_FIELDS = List.of(VespaFields.FIELD_DOCUMENT_ID, VespaFields.FIELD_CHUNK_INDEX,
            VespaFields.FIELD_TOTAL_CHUNKS);

    private VespaFeeds() {}

    public static CompletableFuture<Result> put(FeedClient client, VespaDocumentMapper.VespaDocument document) {
        return client.put(document.id(), document.json(), OperationParameters.empty())
                .exceptionallyCompose(failure -> {
                    String field = undeclaredLaterField(failure);
                    if (field == null) {
                        return CompletableFuture.failedFuture(failure);
                    }
                    if (WARNED.compareAndSet(false, true)) {
                        log.warn("The deployed Vespa application has no field '{}': chunks are fed without {} until "
                                + "oc-vespa-output-connector/vespa-app is redeployed (deletions and document reads "
                                + "then fall back to URIs).", field, LATER_FIELDS);
                    }
                    return client.put(document.id(), withoutLaterFields(document.json()), OperationParameters.empty());
                });
    }

    /**
     * Feeds a chunk that must keep every field: a direct write's chunks carry the claim-check URI, so without
     * {@code document_id} a later tombstone (which carries the source URI) could not find them. Against an
     * application deployed before the schemas gained a field this fails, saying so, instead of feeding without it.
     */
    public static CompletableFuture<Result> putWithAllFields(FeedClient client, VespaDocumentMapper.VespaDocument document) {
        return client.put(document.id(), document.json(), OperationParameters.empty())
                .exceptionallyCompose(failure -> {
                    String field = undeclaredLaterField(failure);
                    return CompletableFuture.failedFuture(field == null ? failure : new IllegalStateException(
                            "The deployed Vespa application has no field '" + field + "': deploy the updated schemas "
                                    + "(oc-vespa-output-connector/vespa-app) before writing to it directly", failure));
                });
    }

    /** The later field Vespa reports as undeclared, or {@code null} when the failure is anything else. */
    static String undeclaredLaterField(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                Matcher matcher = UNDECLARED.matcher(cause.getMessage());
                if (matcher.find() && LATER_FIELDS.contains(matcher.group(1))) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }

    static String withoutLaterFields(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (root.path("fields") instanceof ObjectNode fields) {
                fields.remove(LATER_FIELDS);
            }
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable Vespa document: " + e.getMessage(), e);
        }
    }
}
