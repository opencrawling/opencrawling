/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.googledrive;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent crawl state for the Google Drive repository connector (issue #339):
 * <ul>
 *   <li>{@code startPageToken}: Google Drive Changes API token for incremental delta sync;</li>
 *   <li>{@code lastFullScanAt}: ISO-8601 timestamp of the last complete enumeration (for periodic reconciliation);</li>
 *   <li>{@code folders}: known in-scope folders for breadcrumb/path resolution and cascading folder deletions/moves;</li>
 *   <li>{@code files}: known in-scope files with their OIS document ID, version key, ACL fingerprint, and render
 *       signature so unchanged files are skipped without downloading them.</li>
 * </ul>
 *
 * <p>Persisted behind {@link StateStore}: in {@code oc-runtime} this delegates to the shared control-plane store
 * ({@code ControlPlaneStore}, supporting multi-replica JDBC deployments per #395), with an atomic local JSON file
 * fallback when {@code stateFile} is explicitly configured or when running outside the control plane.
 */
public final class GoogleDriveCrawlState {

    /** Persistence boundary for Google Drive crawl state (control plane or local file). */
    public interface StateStore {
        State load(String scopeKey) throws IOException;

        void save(String scopeKey, State state) throws IOException;
    }

    /** Cached folder metadata for path/breadcrumb resolution and cascading deletions. */
    public record FolderEntry(String parentId, String name, String driveId) {
    }

    /** Cached file snapshot for skip-unchanged checks and tombstone emission. */
    public record FileEntry(
            String docId,
            String driveId,
            String parentId,
            String name,
            String path,
            String versionKey,
            String aclFingerprint,
            String renderSignature,
            String status
    ) {
    }

    /** Full crawl state for one Google Drive connector scope. */
    public record State(
            String startPageToken,
            String lastFullScanAt,
            Map<String, FolderEntry> folders,
            Map<String, FileEntry> files
    ) {
        public State {
            folders = folders == null ? new LinkedHashMap<>() : new LinkedHashMap<>(folders);
            files = files == null ? new LinkedHashMap<>() : new LinkedHashMap<>(files);
        }

        public static State empty() {
            return new State(null, null, new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private GoogleDriveCrawlState() {
    }

    /** Deterministic storage key name safe for both control-plane rows and filenames. */
    public static String stateName(String scopeKey) {
        return "connector-state/googledrive-" + hash(scopeKey == null ? "" : scopeKey) + ".json";
    }

    public static String toJson(State state) throws IOException {
        return MAPPER.writeValueAsString(state == null ? State.empty() : state);
    }

    public static State fromJson(String json) throws IOException {
        if (json == null || json.isBlank()) {
            return State.empty();
        }
        State loaded = MAPPER.readValue(json, State.class);
        if (loaded == null) {
            return State.empty();
        }
        return new State(
                loaded.startPageToken(),
                loaded.lastFullScanAt(),
                loaded.folders() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(loaded.folders()),
                loaded.files() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(loaded.files())
        );
    }

    /** Resolves the local file path when using local file-backed state persistence. */
    public static Path file(GoogleDriveProperties settings, String scopeKey) {
        String fileName = "googledrive-" + hash(scopeKey == null ? "" : scopeKey) + ".json";
        String configured = settings.stateFile();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.strip());
            if (configured.strip().endsWith(".json")
                    || (!Files.isDirectory(p) && p.getFileName() != null && p.getFileName().toString().contains("."))) {
                return p;
            }
            return p.resolve(fileName);
        }
        return Path.of("data/googledrive-state").resolve(fileName);
    }

    public static StateStore localFileStore(GoogleDriveProperties settings) {
        return new StateStore() {
            @Override
            public State load(String scopeKey) throws IOException {
                return GoogleDriveCrawlState.load(file(settings, scopeKey));
            }

            @Override
            public void save(String scopeKey, State state) throws IOException {
                GoogleDriveCrawlState.save(file(settings, scopeKey), state);
            }
        };
    }

    public static State load(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return State.empty();
        }
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
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
        Path tmp = Files.createTempFile(dir != null ? dir : Path.of("."), abs.getFileName().toString(), ".tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), state == null ? State.empty() : state);
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Returns every known folder ID (including {@code folderId} itself) in the subtree rooted at {@code folderId}. */
    public static Set<String> subtreeFolderIds(Map<String, FolderEntry> folders, String folderId) {
        Map<String, List<String>> children = new HashMap<>();
        folders.forEach((id, entry) -> {
            if (entry.parentId() != null && !entry.parentId().isBlank()) {
                children.computeIfAbsent(entry.parentId(), k -> new ArrayList<>()).add(id);
            }
        });
        Set<String> result = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        result.add(folderId);
        queue.add(folderId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : children.getOrDefault(current, List.of())) {
                if (result.add(child)) {
                    queue.add(child);
                }
            }
        }
        return result;
    }

    /** Returns every known file ID directly or indirectly located under {@code folderId}. */
    public static List<String> descendantFileIds(
            Map<String, FolderEntry> folders,
            Map<String, FileEntry> files,
            String folderId
    ) {
        Set<String> affectedFolders = subtreeFolderIds(folders, folderId);
        List<String> out = new ArrayList<>();
        files.forEach((fileId, entry) -> {
            if (entry.parentId() != null && affectedFolders.contains(entry.parentId())) {
                out.add(fileId);
            }
        });
        return out;
    }

    /**
     * Resolves folder breadcrumb segments from root to {@code parentId} using known folders, or {@code null} when an
     * ancestor is missing.
     */
    public static List<String> breadcrumbs(Map<String, FolderEntry> folders, String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = parentId;
        while (current != null && !current.isBlank() && visited.add(current)) {
            FolderEntry entry = folders.get(current);
            if (entry == null) {
                return null;
            }
            if (entry.name() != null && !entry.name().isBlank()) {
                segments.add(entry.name());
            }
            if (entry.parentId() == null || entry.parentId().isBlank()) {
                Collections.reverse(segments);
                return List.copyOf(segments);
            }
            current = entry.parentId();
        }
        Collections.reverse(segments);
        return List.copyOf(segments);
    }

    private static String hash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
