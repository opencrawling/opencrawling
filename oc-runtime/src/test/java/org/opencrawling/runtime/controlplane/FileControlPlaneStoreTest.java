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
package org.opencrawling.runtime.controlplane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.PersistenceHelper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file store as a runtime uses it: a job's thread saves the list while request threads read it without the lock.
 * A reader must see a whole list, the old one or the new one, never a file being written.
 */
class FileControlPlaneStoreTest {

    private static final List<String> DEFAULTS = List.of("defaults");

    private final String name = "file-store-test-" + UUID.randomUUID() + ".json";
    private final ControlPlaneStore store = FileControlPlaneStore.INSTANCE;

    @AfterEach
    void removeTheFiles() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of(PersistenceHelper.DATA_DIR), "*" + name + "*")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void aReaderNeverSeesAFileBeingWritten() throws Exception {
        List<String> small = items("a", 10);
        List<String> large = items("b", 5_000);
        store.save(name, small);

        AtomicBoolean writing = new AtomicBoolean(true);
        ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            Future<?> writer = threads.submit(() -> {
                try {
                    for (int i = 0; i < 300; i++) {
                        store.save(name, i % 2 == 0 ? large : small);
                    }
                } finally {
                    writing.set(false);
                }
            });
            // One reader through the store and its cache, one straight from the file.
            List<Future<?>> readers = List.of(
                    threads.submit(() -> read(writing, seen, () -> store.loadList(name, String.class, DEFAULTS), small, large)),
                    threads.submit(() -> read(writing, seen,
                            () -> PersistenceHelper.loadList(name, String.class, DEFAULTS), small, large)));
            writer.get(2, TimeUnit.MINUTES);
            for (Future<?> reader : readers) {
                reader.get(1, TimeUnit.MINUTES);
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(seen).as("lists read while the file was being written").isEmpty();
        assertThat(store.loadList(name, String.class, DEFAULTS)).as("the last write").isEqualTo(small);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of(PersistenceHelper.DATA_DIR), "*" + name + "*")) {
            List<Path> left = new ArrayList<>();
            files.forEach(left::add);
            assertThat(left).as("no temporary file is left").containsExactly(Path.of(PersistenceHelper.DATA_DIR, name));
        }
    }

    @Test
    void theCacheFollowsAFileReplacedBetweenReads() {
        store.save(name, items("first", 3));
        assertThat(store.loadList(name, String.class, DEFAULTS)).isEqualTo(items("first", 3));
        store.save(name, items("again", 3));
        assertThat(store.loadList(name, String.class, DEFAULTS)).isEqualTo(items("again", 3));
    }

    @Test
    void aFileReplacedDuringAReadIsNotCachedAsTheOldContent() {
        List<String> before = items("before", 3);
        List<String> after = items("after", 30);
        store.save(name, before);
        FileControlPlaneStore files = FileControlPlaneStore.INSTANCE;
        Object read = files.cached(name, String.class, () -> {
            List<String> old = PersistenceHelper.loadList(name, String.class, DEFAULTS);
            store.save(name, after); // another thread saves while this read is under way
            return old;
        });
        assertThat(read).isEqualTo(before);
        assertThat(store.loadList(name, String.class, DEFAULTS)).as("the next read").isEqualTo(after);
    }

    private static void read(AtomicBoolean writing, ConcurrentLinkedQueue<String> seen,
                             java.util.function.Supplier<List<String>> load, List<String> small, List<String> large) {
        while (writing.get()) {
            List<String> list = load.get();
            if (!list.equals(small) && !list.equals(large)) {
                seen.add(list.size() + " items, starting " + list.stream().limit(1).toList());
            }
        }
    }

    private static List<String> items(String prefix, int count) {
        List<String> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(prefix + "-" + i);
        }
        return Collections.unmodifiableList(items);
    }
}
