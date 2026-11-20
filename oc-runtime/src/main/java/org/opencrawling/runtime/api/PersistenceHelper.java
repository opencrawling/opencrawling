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
package org.opencrawling.runtime.api;

import tools.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ArrayList;

public class PersistenceHelper {

    private static final ObjectMapper mapper = new ObjectMapper();
    /** The directory the file store keeps its JSON files in, relative to the working directory. */
    public static final String DATA_DIR = "data";

    static {
        try {
            new File(DATA_DIR).mkdirs();
        } catch (Exception e) {
            System.err.println("Could not create data directory: " + e.getMessage());
        }
    }

    public static <T> List<T> loadList(String filename, Class<T> clazz, List<T> defaults) {
        File file = new File(DATA_DIR + "/" + filename);
        if (!file.exists()) {
            save(filename, defaults);
            return new ArrayList<>(defaults);
        }
        try {
            return mapper.readValue(file, mapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (Exception e) {
            System.err.println("Error loading persistence list from " + filename + ": " + e.getMessage());
            return new ArrayList<>(defaults);
        }
    }

    public static <T> T loadObject(String filename, Class<T> clazz, T defaultValue) {
        File file = new File(DATA_DIR + "/" + filename);
        if (!file.exists()) {
            save(filename, defaultValue);
            return defaultValue;
        }
        try {
            return mapper.readValue(file, clazz);
        } catch (Exception e) {
            System.err.println("Error loading persistence object from " + filename + ": " + e.getMessage());
            return defaultValue;
        }
    }

    /**
     * Writes the file whole or not at all: the content goes to a temporary file in the same directory, which then
     * replaces the file in one step. A reader, in this thread or another, sees the old content or the new one, never a
     * file being written (which it would read as empty and replace with the defaults).
     */
    public static synchronized void save(String filename, Object data) {
        try {
            Path file = Path.of(DATA_DIR, filename).toAbsolutePath();
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
            try {
                mapper.writeValue(tmp.toFile(), data);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            System.err.println("Error saving persistence to " + filename + ": " + e.getMessage());
        }
    }
}
