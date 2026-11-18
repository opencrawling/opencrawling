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
package org.opencrawling.runtime.mcp.backend;

import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.mcp.McpVectorServer.DocumentDetailsResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code getDocumentContent} returns the whole readable document: its chunks in order, joined. */
class DocumentChunksTest {

    private static final String DOC = "file:///reports/q3.txt";

    @Test
    void chunksAreJoinedInChunkIndexOrderWhateverOrderTheStoreReturnsThem() {
        DocumentDetailsResult doc = assemble(List.of(chunk(2, 3), chunk(0, 3), chunk(1, 3)));

        assertThat(doc.content()).isEqualTo("part 0\npart 1\npart 2");
        assertThat(doc.id()).isEqualTo(DOC);
        assertThat(doc.uri()).isEqualTo(DOC);
        assertThat(doc.chunkCount()).isEqualTo(3);
        assertThat(doc.totalChunks()).isEqualTo(3);
        assertThat(doc.complete()).isTrue();
        // The document's metadata is its first chunk's, without that chunk's position.
        assertThat(doc.metadata()).containsEntry("title", "Q3").doesNotContainKey(DocumentChunks.CHUNK_INDEX);
    }

    @Test
    void positionsAreReadAsStoresReturnThem() {
        // Text (Vespa's string metadata), a decimal (JSON parsers) and a one-value list (Solr multi-valued fields).
        List<DocumentChunks.Chunk> chunks = List.of(
                chunk("c2", DOC, "part 2", Map.of(DocumentChunks.CHUNK_INDEX, List.of(2L))),
                chunk("c0", DOC, "part 0", Map.of(DocumentChunks.CHUNK_INDEX, "0", DocumentChunks.TOTAL_CHUNKS, "3")),
                chunk("c1", DOC, "part 1", Map.of(DocumentChunks.CHUNK_INDEX, 1.0)));

        DocumentDetailsResult doc = assemble(chunks);

        assertThat(doc.content()).isEqualTo("part 0\npart 1\npart 2");
        assertThat(doc.complete()).isTrue();
    }

    @Test
    void aStoreThatKeepsNoPositionIsOrderedByThePipelinesChunkIds() {
        // Luxir keeps the document id but not chunk_index.
        List<DocumentChunks.Chunk> chunks = List.of(
                chunk(pipelineId(DOC, 1), DOC, "part 1", Map.of()),
                chunk(pipelineId(DOC, 0), DOC, "part 0", Map.of()));

        DocumentDetailsResult doc = assemble(chunks);

        assertThat(doc.content()).isEqualTo("part 0\npart 1");
        assertThat(doc.totalChunks()).isNull();
        // The ids give the order, not the length: a third chunk may still be missing.
        assertThat(doc.complete()).isFalse();
    }

    @Test
    void aStoreThatKeepsNeitherPositionNorDocumentIdIsOrderedByTheRequestedId() {
        // Milvus keeps only the chunk id, URI and text; a file's URI is its document id.
        List<DocumentChunks.Chunk> chunks = List.of(
                chunk(pipelineId(DOC, 1), null, "part 1", Map.of()),
                chunk(pipelineId(DOC, 0), null, "part 0", Map.of()));

        DocumentDetailsResult doc = DocumentChunks.assemble(chunks, DOC, 200).orElseThrow();

        assertThat(doc.content()).isEqualTo("part 0\npart 1");
        assertThat(doc.id()).isEqualTo(DOC);
        assertThat(doc.complete()).isFalse();
    }

    @Test
    void aChunkStoredTwiceIsReturnedOnce() {
        DocumentDetailsResult doc = assemble(List.of(chunk(0, 2), chunk(1, 2), chunk("copy-of-1", DOC, "part 1",
                Map.of(DocumentChunks.CHUNK_INDEX, 1, DocumentChunks.TOTAL_CHUNKS, 2))));

        assertThat(doc.content()).isEqualTo("part 0\npart 1");
        assertThat(doc.chunkCount()).isEqualTo(2);
        assertThat(doc.complete()).isTrue();
    }

    @Test
    void chunksPastTheDocumentsLengthAreLeftOut() {
        // A shorter new version (2 chunks) over rows of an older, longer one that the writer has not dropped.
        DocumentDetailsResult doc = assemble(List.of(chunk(0, 2), chunk(1, 2), chunk(2, 3), chunk(3, 4)));

        assertThat(doc.content()).isEqualTo("part 0\npart 1");
        assertThat(doc.totalChunks()).isEqualTo(2);
        assertThat(doc.complete()).isTrue();
    }

    @Test
    void chunksOfAnotherVersionAreLeftOutSoAHalfRewrittenDocumentIsIncomplete() {
        // A re-crawl shortened the document to 2 chunks: the new chunk 0 is stored, the old chunk 1 (of 3) is not
        // rewritten yet. Joining them would mix two versions.
        DocumentDetailsResult doc = assemble(List.of(chunk(0, 2), chunk(1, 3)));

        assertThat(doc.content()).isEqualTo("part 0");
        assertThat(doc.totalChunks()).isEqualTo(2);
        assertThat(doc.complete()).isFalse();
    }

    @Test
    void aCountThatIsNotPositiveIsAStoresUnsetValueNotAnotherLength() {
        // Vespa reads an unset int field as a large negative value.
        DocumentDetailsResult doc = assemble(List.of(chunk(0, 2), chunk("c1", DOC, "part 1",
                Map.of(DocumentChunks.CHUNK_INDEX, 1, DocumentChunks.TOTAL_CHUNKS, Integer.MIN_VALUE))));

        assertThat(doc.content()).isEqualTo("part 0\npart 1");
        assertThat(doc.complete()).isTrue();
    }

    @Test
    void aMissingChunkMakesTheDocumentIncomplete() {
        // Chunk 1 is still being written, or the caller may not read it.
        DocumentDetailsResult doc = assemble(List.of(chunk(0, 3), chunk(2, 3)));

        assertThat(doc.content()).isEqualTo("part 0\npart 2");
        assertThat(doc.chunkCount()).isEqualTo(2);
        assertThat(doc.totalChunks()).isEqualTo(3);
        assertThat(doc.complete()).isFalse();
    }

    @Test
    void aDocumentWithMoreChunksThanTheLimitReturnsItsFirstOnes() {
        List<DocumentChunks.Chunk> chunks = new ArrayList<>();
        for (int i = 4; i >= 0; i--) {
            chunks.add(chunk(i, 5));
        }

        DocumentDetailsResult doc = DocumentChunks.assemble(chunks, DOC, 3).orElseThrow();

        assertThat(doc.content()).isEqualTo("part 0\npart 1\npart 2");
        assertThat(doc.chunkCount()).isEqualTo(3);
        assertThat(doc.totalChunks()).isEqualTo(5);
        assertThat(doc.complete()).isFalse();
    }

    @Test
    void staleChunksFillingTheFetchDoNotHideACompleteDocument() {
        // The store returned as many chunks as were asked for, most of them left over from a longer earlier version:
        // every position of the current one is still here.
        List<DocumentChunks.Chunk> chunks = new ArrayList<>(List.of(chunk(0, 2), chunk(1, 2)));
        for (int i = 2; i < 4; i++) {
            chunks.add(chunk(i, 4));
        }

        DocumentDetailsResult doc = DocumentChunks.assemble(chunks, DOC, 3).orElseThrow();

        assertThat(doc.content()).isEqualTo("part 0\npart 1");
        assertThat(doc.complete()).isTrue();
    }

    @Test
    void whenALookupMatchesSeveralDocumentsTheRequestedOneIsReturned() {
        String other = "file:///reports/q4.txt";
        List<DocumentChunks.Chunk> chunks = List.of(
                chunk("o0", other, "other 0", Map.of(DocumentChunks.CHUNK_INDEX, 0)),
                chunk("o1", other, "other 1", Map.of(DocumentChunks.CHUNK_INDEX, 1)),
                chunk(0, 1));

        DocumentDetailsResult doc = assemble(chunks);

        assertThat(doc.id()).isEqualTo(DOC);
        assertThat(doc.content()).isEqualTo("part 0");
    }

    @Test
    void aChunkWithNeitherPositionNorCountIsReturnedButNotReportedComplete() {
        DocumentDetailsResult doc = assemble(List.of(chunk("only", DOC, "the whole text", Map.of())));

        assertThat(doc.content()).isEqualTo("the whole text");
        assertThat(doc.id()).isEqualTo(DOC);
        assertThat(doc.complete()).isFalse();
    }

    @Test
    void aChunkWhosePositionCannotBeToldIsLeftOutRatherThanAppended() {
        // Appended in id order it would scramble the text (a store's unordered fetch can return any chunk).
        DocumentDetailsResult doc = assemble(List.of(chunk("legacy", DOC, "unplaced", Map.of()), chunk(0, 2), chunk(1, 2)));

        assertThat(doc.content()).isEqualTo("part 0\npart 1");
        assertThat(doc.complete()).isTrue();
    }

    @Test
    void theCountComesFromTheLowestChunkThatRecordsOne() {
        // Chunk 0 holds Vespa's unset count, chunk 1 the real one.
        DocumentDetailsResult doc = assemble(List.of(
                chunk("c0", DOC, "part 0", Map.of(DocumentChunks.CHUNK_INDEX, 0, DocumentChunks.TOTAL_CHUNKS, Integer.MIN_VALUE)),
                chunk(1, 2)));

        assertThat(doc.totalChunks()).isEqualTo(2);
        assertThat(doc.complete()).isTrue();
    }

    @Test
    void theIdsOfADocumentsFirstPositionsAreThePipelines() {
        assertThat(DocumentChunks.pipelineChunkIds(DOC, 3))
                .containsExactly(pipelineId(DOC, 0), pipelineId(DOC, 1), pipelineId(DOC, 2));
    }

    @Test
    void noReadableChunkIsNoDocument() {
        assertThat(DocumentChunks.assemble(List.of(), DOC, 200)).isEmpty();
    }

    private static DocumentDetailsResult assemble(List<DocumentChunks.Chunk> chunks) {
        return DocumentChunks.assemble(chunks, DOC, 200).orElseThrow();
    }

    private static DocumentChunks.Chunk chunk(int index, int total) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", "Q3");
        metadata.put(DocumentChunks.CHUNK_INDEX, index);
        metadata.put(DocumentChunks.TOTAL_CHUNKS, total);
        return chunk(pipelineId(DOC, index), DOC, "part " + index, metadata);
    }

    private static DocumentChunks.Chunk chunk(String id, String documentId, String text, Map<String, Object> metadata) {
        return new DocumentChunks.Chunk(id, documentId, DOC, text, metadata, "public");
    }

    /** The id the ingestion pipeline gives a document's chunk at {@code index}. */
    static String pipelineId(String documentId, int index) {
        return UUID.nameUUIDFromBytes((documentId + "#" + index).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
