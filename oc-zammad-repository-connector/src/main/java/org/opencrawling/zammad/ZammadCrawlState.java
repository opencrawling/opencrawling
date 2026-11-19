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
package org.opencrawling.zammad;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent incremental crawl state for a Zammad instance and crawl scope.
 *
 * <ul>
 *   <li>{@code watermark}: the high-water {@code updated_at} timestamp used for incremental ticket search
 *       ({@code GET /api/v1/tickets/search?query=updated_at:[<watermark> TO *]});</li>
 *   <li>{@code tickets}: per ticket ID, the last indexed {@code updated_at} timestamp, ACL fingerprint, and the list of
 *       emitted OIS document IDs (public thread, internal thread, per-article docs, and attachments);</li>
 *   <li>{@code kbAnswers}: per Knowledge Base answer ID, the last indexed {@code updated_at} timestamp, ACL fingerprint,
 *       and emitted OIS document IDs.</li>
 * </ul>
 */
public final class ZammadCrawlState {

    public record Entry(String updatedAt, String aclFingerprint, List<String> ids) {
    }

    public record State(String watermark, Map<String, Entry> tickets, Map<String, Entry> kbAnswers) {
        public static State empty() {
            return new State(null, new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ZammadCrawlState() {
    }

    public static Path file(ZammadProperties settings) {
        String configured = settings.stateFile();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.strip());
            if (configured.strip().endsWith(".json") || (!Files.isDirectory(p) && p.getFileName().toString().contains("."))) {
                return p;
            }
            return p.resolve(defaultFileName(settings));
        }
        return Path.of("data/zammad-state").resolve(defaultFileName(settings));
    }

    private static String defaultFileName(ZammadProperties settings) {
        String scope = settings.baseUrl() + "|" + settings.groupNames() + "|" + settings.ticketStates() + "|"
                + settings.includeTickets() + "|" + settings.articleGranularity() + "|"
                + settings.includeInternalArticles() + "|" + settings.includeAttachments() + "|"
                + settings.includeKnowledgeBase();
        return (settings.instanceKey() + "-" + hash(scope)).replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
    }

    public static State load(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return State.empty();
        }
        State state = MAPPER.readValue(file.toFile(), State.class);
        if (state == null) {
            return State.empty();
        }
        return new State(
                state.watermark(),
                state.tickets() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(state.tickets()),
                state.kbAnswers() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(state.kbAnswers())
        );
    }

    public static void save(Path file, State state) throws IOException {
        if (file == null) {
            return;
        }
        Path abs = file.toAbsolutePath();
        Path dir = abs.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir, abs.getFileName().toString(), ".tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), state);
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String hash(String scope) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
