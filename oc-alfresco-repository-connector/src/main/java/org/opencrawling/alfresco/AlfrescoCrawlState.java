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
package org.opencrawling.alfresco;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
 * Persistent incremental crawl state for the Alfresco Content Services repository connector (#425).
 *
 * <p>Persists watermarks, {@code lastFullScanAt}, and per-node fingerprints (modification timestamp, version label,
 * size, ACL fingerprint, render signature, folder path, and emitted OIS document IDs) atomically as JSON.
 * Encapsulated behind {@link StateStore} so it can later delegate directly to the control-plane
 * {@code ConnectorStateStore} (#395).
 */
public final class AlfrescoCrawlState {

    /** Minimal state persistence boundary for straightforward migration to #395 control-plane state store. */
    public interface StateStore {
        State load(AlfrescoProperties settings) throws IOException;

        void save(AlfrescoProperties settings, State state) throws IOException;
    }

    /** Per-node snapshot of what was last indexed. */
    public record Entry(
            String modifiedAt,
            String versionLabel,
            long sizeInBytes,
            String aclFingerprint,
            String renderSignature,
            List<String> ids,
            String path
    ) {
        public Entry {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }
    }

    /** Full crawl state for one Alfresco connector scope. */
    public record State(
            String watermark,
            String trashWatermark,
            String auditWatermark,
            String lastFullScanAt,
            Map<String, Entry> nodes
    ) {
        public State {
            nodes = nodes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(nodes);
        }

        public static State empty() {
            return new State(null, null, null, null, new LinkedHashMap<>());
        }
    }

    /** Default local JSON file-backed implementation. */
    public static final StateStore LOCAL_FILE_STORE = new StateStore() {
        @Override
        public State load(AlfrescoProperties settings) throws IOException {
            return AlfrescoCrawlState.load(file(settings));
        }

        @Override
        public void save(AlfrescoProperties settings, State state) throws IOException {
            AlfrescoCrawlState.save(file(settings), state);
        }
    };

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private AlfrescoCrawlState() {
    }

    public static Path file(AlfrescoProperties settings) {
        String configured = settings.stateFile();
        String fileName = defaultFileName(settings);
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.strip());
            if (configured.strip().endsWith(".json") || (!Files.isDirectory(p) && p.getFileName().toString().contains("."))) {
                return p;
            }
            return p.resolve(fileName);
        }
        return Path.of("data/alfresco-state").resolve(fileName);
    }

    private static String defaultFileName(AlfrescoProperties settings) {
        return (settings.instanceKey() + "-" + hash(settings.scopeKey())).replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
    }

    public static State load(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return State.empty();
        }
        State loaded = MAPPER.readValue(file.toFile(), State.class);
        if (loaded == null) {
            return State.empty();
        }
        return new State(
                loaded.watermark(),
                loaded.trashWatermark(),
                loaded.auditWatermark(),
                loaded.lastFullScanAt(),
                loaded.nodes() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(loaded.nodes())
        );
    }

    public static void save(Path file, State state) throws IOException {
        Path abs = file.toAbsolutePath();
        Path dir = abs.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir != null ? dir : Path.of("."), abs.getFileName().toString(), ".tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), state);
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String hash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
