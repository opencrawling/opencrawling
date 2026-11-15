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
package org.opencrawling.mfiles;

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
 * The incremental crawl state of one vault and crawl scope.
 *
 * <ul>
 *   <li>{@code watermark}: the next search starts at this last-modified time (the previous scan's start minus the
 *       clock-skew margin); it only advances after a scan that processed every listed object;</li>
 *   <li>{@code vafCursor}: the VAF change-feed cursor;</li>
 *   <li>{@code objects}: per object ({@code <type>/<id>}), the last-modified time (or VAF version string) last indexed and
 *       the OIS ids emitted for it, so unchanged objects are skipped and ids an object no longer produces (a file removed
 *       from a multi-file document, a deleted object) get DELETE tombstones.</li>
 * </ul>
 * Stored as JSON, written atomically, one file per vault and scope (crawl mode, object type, classes, query, view).
 */
final class MFilesCrawlState {

    /** One object: the last-modified time (or version string) last indexed and the OIS ids emitted for it. */
    record Entry(String modified, List<String> ids) {
    }

    record State(String watermark, String vafCursor, Map<String, Entry> objects) {
        static State empty() {
            return new State(null, null, new LinkedHashMap<>());
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private MFilesCrawlState() {
    }

    static Path file(MFilesProperties settings) {
        String scope = settings.crawlMode() + "|" + settings.objectType() + "|" + settings.classIds() + "|"
                + settings.searchQuery() + "|" + settings.viewId() + "|" + settings.versionMode();
        String name = (settings.vaultKey() + "-" + hash(scope)).replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
        return Path.of(settings.stateDirectory()).resolve(name);
    }

    /** The saved state, or an empty state before the first incremental crawl. */
    static State load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return State.empty();
        }
        State state = MAPPER.readValue(file.toFile(), State.class);
        if (state == null) {
            return State.empty();
        }
        return new State(state.watermark(), state.vafCursor(),
                state.objects() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(state.objects()));
    }

    static void save(Path file, State state) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), state);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
