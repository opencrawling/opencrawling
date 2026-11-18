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

import org.opencrawling.runtime.mcp.McpVectorServer.DocumentDetailsResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Puts a document back together from its stored chunks, for {@code getDocumentContent}: the chunks in
 * {@code chunk_index} order, joined into one text.
 *
 * <p>It only ever sees chunks the caller may read: each backend passes its candidates through the access gate first.
 *
 * <p>A chunk's position comes from its {@code chunk_index} metadata, which the text splitter sets. Stores that do not
 * keep it still have the chunk id, which the pipeline derives from the position
 * ({@code UUID.nameUUIDFromBytes(documentId + "#" + index)}, see {@code IngestionConsumer}), so the position is
 * recovered from that: with the stored document id (Luxir), or, for a store that keeps none (Milvus), with the
 * requested URI or the chunk's URI, which for most sources is the document id.
 */
public final class DocumentChunks {

    public static final String CHUNK_INDEX = "chunk_index";
    public static final String TOTAL_CHUNKS = "total_chunks";

    /** What separates two chunks in the joined text; the splitter trims each chunk. */
    static final String SEPARATOR = "\n";

    private DocumentChunks() {}

    /**
     * One stored chunk as a backend read it.
     *
     * @param id         the chunk id
     * @param documentId the source document's id, or {@code null} when the store does not keep it
     * @param uri        the document's URI as the backend reports it
     * @param text       the chunk's text
     * @param metadata   the chunk's metadata, without its vector
     * @param acl        the ACL summary the backend reports
     */
    public record Chunk(String id, String documentId, String uri, String text, Map<String, Object> metadata, String acl) {
        public Chunk {
            metadata = metadata != null ? metadata : Map.of();
        }
    }

    /** How many chunks to ask the store for: one more than are returned, which tells a document that has more. */
    public static int fetchSize(int maxChunks) {
        return maxChunks + 1;
    }

    /**
     * The document the readable chunks make up.
     *
     * @param readable   the chunks the caller may read, in any order
     * @param requested  the URI or id the caller asked for, which picks the document if chunks of several matched
     * @param maxChunks  how many chunks at most go into the text
     * @param storeHadMore whether the store returned the whole fetch size, so it may hold chunks that were not read
     * @return the document, or empty when no chunk is readable
     */
    public static Optional<DocumentDetailsResult> assemble(List<Chunk> readable, String requested, int maxChunks,
                                                           boolean storeHadMore) {
        if (readable == null || readable.isEmpty()) {
            return Optional.empty();
        }
        List<Chunk> chunks = ofOneDocument(readable, requested);
        String storedDocumentId = chunks.stream().map(Chunk::documentId).filter(DocumentChunks::present).findFirst()
                .orElse(null);
        // A store that keeps no document id (Milvus) is asked by URI, which for most sources is the document id.
        List<String> documentIds = new ArrayList<>();
        if (present(storedDocumentId)) {
            documentIds.add(storedDocumentId);
        } else {
            for (String candidate : List.of(Objects.toString(requested, ""), Objects.toString(chunks.getFirst().uri(), ""))) {
                if (present(candidate) && !documentIds.contains(candidate)) {
                    documentIds.add(candidate);
                }
            }
        }
        Map<String, Placement> placementsById = placementsById(documentIds, maxChunks + chunks.size());

        List<Positioned> positioned = new ArrayList<>();
        String derivedDocumentId = null;
        for (Chunk chunk : chunks) {
            Integer index = intValue(chunk.metadata().get(CHUNK_INDEX));
            if (index == null || index < 0) {
                Placement placement = chunk.id() != null ? placementsById.get(chunk.id()) : null;
                index = placement != null ? placement.index() : null;
                if (placement != null && derivedDocumentId == null) {
                    derivedDocumentId = placement.documentId();
                }
            }
            positioned.add(new Positioned(chunk, index));
        }
        String documentId = present(storedDocumentId) ? storedDocumentId : derivedDocumentId;
        Integer totalChunks = totalChunks(positioned);

        // One chunk per position (a store may hold the same chunk twice), and none past the document's length (rows
        // of a longer earlier version that the store's writer has not dropped).
        positioned.sort(Comparator.comparing((Positioned p) -> p.index() == null)
                .thenComparing(p -> p.index() == null ? 0 : p.index())
                .thenComparing(p -> Objects.toString(p.chunk().id(), "")));
        List<Positioned> ordered = distinctWithin(positioned, totalChunks);
        if (ordered.isEmpty()) {
            // Every chunk lies past the recorded length: the record is wrong, not the chunks.
            totalChunks = null;
            ordered = distinctWithin(positioned, null);
        }

        boolean truncated = ordered.size() > maxChunks;
        if (truncated) {
            ordered = ordered.subList(0, maxChunks);
        }
        boolean complete = !truncated && !storeHadMore && isWhole(ordered, totalChunks);

        Chunk first = ordered.getFirst().chunk();
        Map<String, Object> metadata = new LinkedHashMap<>(first.metadata());
        metadata.remove(CHUNK_INDEX);
        String content = ordered.stream()
                .map(p -> Objects.toString(p.chunk().text(), ""))
                .collect(Collectors.joining(SEPARATOR));
        String uri = present(first.uri()) ? first.uri() : documentId;
        return Optional.of(new DocumentDetailsResult(
                present(documentId) ? documentId : first.id(),
                uri,
                content,
                metadata,
                first.acl(),
                ordered.size(),
                totalChunks,
                complete));
    }

    /**
     * The chunks of one document. A lookup can match chunks of several (the URI of one is the id of another): the
     * one the caller named by id wins, then the one it named by URI, then the one with the most chunks.
     */
    private static List<Chunk> ofOneDocument(List<Chunk> readable, String requested) {
        Map<String, List<Chunk>> byDocument = new LinkedHashMap<>();
        for (Chunk chunk : readable) {
            String key = present(chunk.documentId()) ? chunk.documentId() : Objects.toString(chunk.uri(), "");
            byDocument.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
        }
        if (byDocument.size() == 1) {
            return readable;
        }
        return byDocument.values().stream()
                .min(Comparator.comparing((List<Chunk> doc) -> !Objects.equals(doc.getFirst().documentId(), requested))
                        .thenComparing(doc -> doc.stream().noneMatch(c -> Objects.equals(c.uri(), requested)))
                        .thenComparing(doc -> -doc.size())
                        .thenComparing(doc -> Objects.toString(doc.getFirst().documentId(), "")))
                .orElse(readable);
    }

    /**
     * The first chunk at each position of the document's current version. When {@code totalChunks} is known, a chunk
     * at or past it, or one that records another length, belongs to another version (a re-crawl changed the
     * document's length and not every chunk has been rewritten yet) and is left out, so the document reads as
     * incomplete rather than as a mix of versions.
     */
    private static List<Positioned> distinctWithin(List<Positioned> sorted, Integer totalChunks) {
        Set<Integer> seen = new HashSet<>();
        List<Positioned> kept = new ArrayList<>();
        for (Positioned p : sorted) {
            if (p.index() != null) {
                if (totalChunks != null && (p.index() >= totalChunks || recordsAnotherLength(p, totalChunks))) {
                    continue;
                }
                if (!seen.add(p.index())) {
                    continue;
                }
            }
            kept.add(p);
        }
        return kept;
    }

    private static boolean recordsAnotherLength(Positioned p, int totalChunks) {
        Integer recorded = intValue(p.chunk().metadata().get(TOTAL_CHUNKS));
        // A count that is not positive is a store's "unset" value (Vespa's undefined int), not another length.
        return recorded != null && recorded > 0 && recorded != totalChunks;
    }

    /**
     * The chunk ids the pipeline gives the first positions of each candidate document id, to place chunks stored
     * without their index.
     */
    private static Map<String, Placement> placementsById(List<String> documentIds, int positions) {
        Map<String, Placement> ids = new HashMap<>();
        for (String documentId : documentIds) {
            for (int i = 0; i < positions; i++) {
                String id = UUID.nameUUIDFromBytes((documentId + "#" + i).getBytes(StandardCharsets.UTF_8)).toString();
                ids.putIfAbsent(id, new Placement(documentId, i));
            }
        }
        return ids;
    }

    /** The document's chunk count, as its lowest-positioned chunk that records one says. */
    private static Integer totalChunks(List<Positioned> positioned) {
        return positioned.stream()
                .filter(p -> intValue(p.chunk().metadata().get(TOTAL_CHUNKS)) != null)
                .min(Comparator.comparing((Positioned p) -> p.index() == null ? Integer.MAX_VALUE : p.index()))
                .map(p -> intValue(p.chunk().metadata().get(TOTAL_CHUNKS)))
                .filter(total -> total > 0)
                .orElse(null);
    }

    /**
     * Whether the chunks are the whole document: positions 0 to n-1 with nothing missing, and n the chunk count the
     * document records. Without a recorded count nothing tells that no chunk is missing after the last one read (still
     * being written, or not readable by the caller), so the document is never reported complete.
     */
    private static boolean isWhole(List<Positioned> ordered, Integer totalChunks) {
        if (totalChunks == null || ordered.size() != totalChunks) {
            return false;
        }
        for (int i = 0; i < ordered.size(); i++) {
            if (!Objects.equals(ordered.get(i).index(), i)) {
                return false;
            }
        }
        return true;
    }

    /** A number as stores return it: a number, its text, or a one-value list (Solr's multi-valued fields). */
    static Integer intValue(Object value) {
        if (value instanceof List<?> list) {
            return list.size() == 1 ? intValue(list.getFirst()) : null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return (int) Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private record Positioned(Chunk chunk, Integer index) {}

    private record Placement(String documentId, int index) {}
}
