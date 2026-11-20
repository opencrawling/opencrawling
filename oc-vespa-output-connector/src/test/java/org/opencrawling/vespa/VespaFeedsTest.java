/*
 * Copyright © 2026 Rafa Haro (piergiorgio@apache.org)
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ingestion goes on against an application deployed before the schemas gained a field. */
class VespaFeedsTest {

    private final FeedClient client = mock(FeedClient.class);
    private final VespaDocumentMapper.VespaDocument document = new VespaDocumentMapper().toDocument("opencrawling",
            "opencrawling_chunk_1024", "chunk-1", "text", "file:///doc.txt", "public", "2026-01-01T00:00:00Z", null,
            new float[]{0.1f}, Map.of("documentId", "/doc.txt", "chunk_index", 0, "total_chunks", 1));

    @Test
    void aChunkRejectedForALaterFieldIsFedAgainWithoutTheLaterFields() throws Exception {
        // Vespa's own message for a field the deployed schema does not declare.
        when(client.put(any(DocumentId.class), anyString(), any(OperationParameters.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(
                        "Field 'document_id' is not defined in document type 'opencrawling_chunk_1024'")))
                .thenReturn(CompletableFuture.completedFuture(mock(Result.class)));

        VespaFeeds.put(client, document).get();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).put(eq(document.id()), json.capture(), any(OperationParameters.class));
        // Field names, not text: metadata_json keeps its own copy of the metadata either way.
        assertThat(fieldNames(json.getAllValues().get(0))).contains("document_id");
        assertThat(fieldNames(json.getAllValues().get(1))).doesNotContain("document_id", "chunk_index", "total_chunks")
                .contains("chunk_id", "text", "metadata_json");
    }

    @Test
    void anyOtherRejectionFailsTheChunk() {
        when(client.put(any(DocumentId.class), anyString(), any(OperationParameters.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(
                        "Field 'text' is not defined in document type 'opencrawling_chunk_1024'")));

        assertThatThrownBy(() -> VespaFeeds.put(client, document).get()).hasMessageContaining("'text'");
        verify(client, times(1)).put(any(DocumentId.class), anyString(), any(OperationParameters.class));
    }

    @Test
    void aDirectWriteAgainstAnOlderSchemaFailsSayingWhatToDeploy() {
        // Without document_id a tombstone could not find the chunks of a direct write (they carry the claim-check URI).
        when(client.put(any(DocumentId.class), anyString(), any(OperationParameters.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(
                        "Field 'document_id' is not defined in document type 'opencrawling_chunk_1024'")));

        assertThatThrownBy(() -> VespaFeeds.putWithAllFields(client, document).get())
                .hasMessageContaining("deploy the updated schemas");
        verify(client, times(1)).put(any(DocumentId.class), anyString(), any(OperationParameters.class));
    }

    private static java.util.List<String> fieldNames(String json) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("fields").fieldNames().forEachRemaining(names::add);
        return names;
    }
}
