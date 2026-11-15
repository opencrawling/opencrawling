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
package org.opencrawling.sharepoint;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The crawl state of one document library (or of a narrower tree-crawl scope):
 * <ul>
 *   <li>{@code deltaLink}: where the next delta query continues (delta mode);</li>
 *   <li>{@code items}: every folder and file seen, with its parent, name, eTag and (files) ACL fingerprint. Folders give
 *       paths (delta pages omit them) and let a deleted folder take its known files with it; files let unchanged ones be
 *       skipped while a permission-only change is still noticed.</li>
 * </ul>
 * Stored as JSON, written atomically, one file per library (see {@link #file}).
 */
final class SharePointCrawlState {

    /** A folder or file: parent item id, name, whether it is a folder, eTag, and the ACL fingerprint (files only). */
    record Item(String parent, String name, boolean folder, String eTag, String acl) {
    }

    /** The persisted state. */
    record Data(String deltaLink, Map<String, Item> items) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private volatile String deltaLink;
    private final Map<String, Item> items;

    private SharePointCrawlState(String deltaLink, Map<String, Item> items) {
        this.deltaLink = deltaLink;
        this.items = items;
    }

    static SharePointCrawlState empty() {
        return new SharePointCrawlState(null, new ConcurrentHashMap<>());
    }

    /**
     * The state file of a library. Delta crawls and tree crawls of the whole library (from the root, with sub-folders) share
     * one state, so a periodic tree crawl only re-emits what delta could not see (permission-only changes), and delta then
     * skips what the tree crawl already emitted. A tree crawl of a narrower scope keeps its own state: compared with the
     * whole library's, every file outside its scope would look deleted.
     */
    static Path file(SharePointProperties settings, String driveId) {
        String root = settings.rootFolderPath() == null ? "" : settings.rootFolderPath().strip().replaceAll("^/+|/+$", "");
        boolean wholeLibrary = settings.crawlMode() == SharePointProperties.CrawlMode.DELTA
                || (root.isEmpty() && settings.includeSubfolders());
        String scope = wholeLibrary ? "library|" + driveId
                : "tree|" + root + "|" + settings.includeSubfolders() + "|" + driveId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            return Path.of(settings.stateDirectory()).resolve("drive-" + HexFormat.of().formatHex(digest, 0, 10) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static SharePointCrawlState load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return empty();
        }
        Data data = MAPPER.readValue(file.toFile(), Data.class);
        Map<String, Item> items = new ConcurrentHashMap<>();
        if (data != null && data.items() != null) {
            items.putAll(data.items());
        }
        return new SharePointCrawlState(data == null ? null : data.deltaLink(), items);
    }

    void save(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), new Data(deltaLink, Map.copyOf(items)));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    String deltaLink() {
        return deltaLink;
    }

    void deltaLink(String link) {
        deltaLink = link;
    }

    Item get(String itemId) {
        return items.get(itemId);
    }

    void put(String itemId, Item item) {
        items.put(itemId, item);
    }

    void remove(String itemId) {
        items.remove(itemId);
    }

    /** The ids of all known files. */
    List<String> fileIds() {
        List<String> ids = new ArrayList<>();
        items.forEach((id, item) -> {
            if (!item.folder()) {
                ids.add(id);
            }
        });
        return ids;
    }

    /** The ids of every known item below a folder (files and folders, any depth). */
    List<String> descendants(String folderId) {
        Map<String, List<String>> children = new java.util.HashMap<>();
        items.forEach((id, item) -> {
            if (item.parent() != null) {
                children.computeIfAbsent(item.parent(), k -> new ArrayList<>()).add(id);
            }
        });
        List<String> out = new ArrayList<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(children.getOrDefault(folderId, List.of()));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            out.add(id);
            queue.addAll(children.getOrDefault(id, List.of()));
        }
        return out;
    }

    /**
     * The path of an item's folder from the known folders ({@code /Projects/2026}), or null when a folder on the way is not
     * known (yet). The library root is the folder without a parent.
     */
    String folderPath(String parentId) {
        List<String> names = new ArrayList<>();
        String current = parentId;
        for (int guard = 0; current != null && guard < 512; guard++) {
            Item folder = items.get(current);
            if (folder == null) {
                return null;
            }
            if (folder.parent() == null) {
                java.util.Collections.reverse(names);
                return "/" + String.join("/", names);
            }
            names.add(folder.name());
            current = folder.parent();
        }
        return null;
    }
}
