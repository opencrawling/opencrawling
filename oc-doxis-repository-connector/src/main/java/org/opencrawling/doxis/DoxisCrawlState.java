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
package org.opencrawling.doxis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The incremental crawl state of one repository: for every document crawled, the {@code modificationDate} its search hit had
 * and the OIS ids emitted for it (so an ALL_VERSIONS crawl can tombstone every version of a document that disappeared).
 *
 * <p>Doxis offers no usable change feed for this: on the tested CSB 14.4 the audit trail was off and the {@code DXE_MODDATE}
 * attribute matched nothing, while every search hit carries the document's {@code modificationDate}. Stored as JSON, written
 * atomically, one file per customer and repository.
 */
final class DoxisCrawlState {

    /** One document: the modification date its search hit had, and the OIS ids emitted for it. */
    record Entry(String modified, List<String> ids) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private DoxisCrawlState() {
    }

    static Path file(DoxisProperties settings, String repositoryShortName) {
        String name = (settings.customerName() + "-" + repositoryShortName).replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
        return Path.of(settings.stateDirectory()).resolve(name);
    }

    /** The saved state, or an empty map before the first incremental crawl. */
    static Map<String, Entry> load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        Map<String, Entry> state = MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Entry>>() {
        });
        return state == null ? new LinkedHashMap<>() : state;
    }

    static void save(Path file, Map<String, Entry> state) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), new LinkedHashMap<>(state));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
