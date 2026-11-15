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
package org.opencrawling.documentum;

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
import java.util.Map;

/**
 * The incremental crawl state of one repository and crawl scope.
 *
 * <ul>
 *   <li>{@code watermark}: the next listing starts at this modification time (the previous scan's start minus the
 *       clock-skew margin); it only advances after a scan without failures;</li>
 *   <li>{@code watermarkOffset}: the time zone offset the server wrote dates in when the watermark was taken
 *       ({@code server-time-zone: auto}). A server in a zone with daylight saving reads a literal in the offset of that
 *       date, so the watermark is written in it, not in today's offset;</li>
 *   <li>{@code eventWatermark}: the time of the last {@code dm_audittrail} event read ({@code audit} deletion tracking);</li>
 *   <li>{@code objects}: per crawled object (the chronicle, or the version in {@code all} version mode), the version
 *       indexed, its {@code i_vstamp}, the fingerprint of its mapped permissions, its permission set and the OIS id, so
 *       unchanged documents are skipped and deleted ones get DELETE tombstones;</li>
 *   <li>{@code acls}: the {@code i_vstamp} of every permission set in use. Editing a permission set changes neither
 *       {@code r_modify_date} nor {@code i_vstamp} of the documents that use it, so each incremental run compares these
 *       stamps and re-checks the documents of a changed permission set.</li>
 * </ul>
 * Stored as JSON, written atomically, one file per repository and scope (crawl mode, types, conditions, folder, version
 * mode).
 */
final class DocumentumCrawlState {

    /** One object: what was last indexed. */
    record Entry(String objectId, int vstamp, String security, String aclDomain, String aclName, String id) {
    }

    /** A permission set's id and {@code i_vstamp} when last read. */
    record AclStamp(String id, int vstamp) {
    }

    record State(String watermark, String eventWatermark, Map<String, Entry> objects, Map<String, AclStamp> acls,
                 String watermarkOffset) {
        static State empty() {
            return new State(null, null, new LinkedHashMap<>(), new LinkedHashMap<>(), null);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private DocumentumCrawlState() {
    }

    static Path file(DocumentumProperties settings) {
        String scope = settings.crawlMode() + "|" + settings.objectTypes() + "|" + settings.where() + "|"
                + settings.dqlQuery() + "|" + settings.rootCabinetPath() + "|" + settings.includeSubfolders() + "|"
                + settings.versionMode() + "|" + settings.url();
        String name = (settings.repository() + "-" + hash(scope)).replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
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
        return new State(state.watermark(), state.eventWatermark(),
                state.objects() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(state.objects()),
                state.acls() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(state.acls()), state.watermarkOffset());
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
