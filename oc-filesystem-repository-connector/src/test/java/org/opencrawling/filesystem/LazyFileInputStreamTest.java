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
package org.opencrawling.filesystem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.RepositoryDocument;

class LazyFileInputStreamTest {

    @TempDir
    Path tmp;

    @Test
    void testFileIsOpenedOnlyOnFirstRead() throws IOException {
        Path file = Files.writeString(tmp.resolve("a.txt"), "hello");
        LazyFileInputStream in = new LazyFileInputStream(file);
        assertFalse(in.isOpened());

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        assertTrue(in.isOpened());
        in.close();
        assertThrows(IOException.class, in::read);
    }

    @Test
    void testCloseWithoutReadNeverOpensTheFile() throws IOException {
        // Deleted before close: an eager stream would already hold the handle, a lazy one never touches the file.
        Path file = Files.writeString(tmp.resolve("b.txt"), "x");
        LazyFileInputStream in = new LazyFileInputStream(file);
        Files.delete(file);
        in.close();
        assertFalse(in.isOpened());
    }

    @Test
    void testScanDoesNotOpenFiles() throws IOException {
        assertTrue(new FileSystemRepositoryConnector(false).supportsConcurrentProcessing(),
                "lazy streams make the filesystem connector safe for parallel crawler lanes");
        for (int i = 0; i < 50; i++) {
            Files.writeString(tmp.resolve("f" + i + ".txt"), "content-" + i);
        }
        List<RepositoryDocument> docs = new FileSystemRepositoryConnector(false).scan(tmp.toString()).collectList().block();

        assertEquals(50, docs.size());
        for (RepositoryDocument doc : docs) {
            LazyFileInputStream in = (LazyFileInputStream) doc.contentStream();
            assertFalse(in.isOpened(), "scan must not hold a file handle per document");
        }
        try (InputStream in = docs.get(0).contentStream()) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).startsWith("content-"));
        }
    }
}
