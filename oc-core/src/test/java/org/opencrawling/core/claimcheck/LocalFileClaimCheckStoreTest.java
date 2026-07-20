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
package org.opencrawling.core.claimcheck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileClaimCheckStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void testPutGetAndDelete() throws Exception {
        LocalFileClaimCheckStore store = new LocalFileClaimCheckStore(tempDir);

        String sampleText = "Hello, OpenCrawling Claim Check Pattern!";
        byte[] bytes = sampleText.getBytes(StandardCharsets.UTF_8);

        URI claimUri;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            claimUri = store.put("doc-101.txt", in, bytes.length, "text/plain");
        }

        assertThat(claimUri).isNotNull();
        assertThat(store.supports(claimUri)).isTrue();
        assertThat(claimUri.getScheme()).isEqualTo("file");

        // Verify reading content stream
        try (InputStream readStream = store.get(claimUri)) {
            String readText = new String(readStream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(readText).isEqualTo(sampleText);
        }

        // Verify deletion
        store.delete(claimUri);
        Path filePath = Path.of(claimUri);
        assertThat(Files.exists(filePath)).isFalse();
    }

    @Test
    void testExternalFileDeletionIsProtected() throws Exception {
        LocalFileClaimCheckStore store = new LocalFileClaimCheckStore(tempDir.resolve("claims"));

        Path externalFile = tempDir.resolve("important_source_doc.txt");
        Files.writeString(externalFile, "External Content");

        URI externalUri = externalFile.toUri();
        assertThat(store.supports(externalUri)).isTrue();

        // Delete should NOT remove external file outside managed claims dir
        store.delete(externalUri);
        assertThat(Files.exists(externalFile)).isTrue();
    }
}
