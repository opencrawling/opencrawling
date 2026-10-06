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
package org.opencrawling.core.text;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PipesForkTextExtractorTest {

    @Test
    void testEmbeddedTextExtraction() {
        TextExtractionProperties props = new TextExtractionProperties();
        props.setForkEnabled(false);

        try (PipesForkTextExtractor extractor = new PipesForkTextExtractor(props)) {
            String sample = "Hello OpenCrawling, this is a plain text extraction test.";
            byte[] bytes = sample.getBytes(StandardCharsets.UTF_8);

            TextExtractionResult result = extractor.extractText(bytes, Map.of("mimeType", List.of("text/plain")));

            assertTrue(result.success());
            assertTrue(result.text().contains("Hello OpenCrawling"));
            assertFalse(result.trimmed());
        }
    }

    @Test
    void testPipesForkTextExtraction() {
        TextExtractionProperties props = new TextExtractionProperties();
        props.setForkEnabled(true);
        props.setTimeoutMs(15000);

        try (PipesForkTextExtractor extractor = new PipesForkTextExtractor(props)) {
            String sample = "Apache Tika 4.1.0 process-isolated parsing via PipesForkParser!";
            byte[] bytes = sample.getBytes(StandardCharsets.UTF_8);

            TextExtractionResult result = extractor.extractText(
                    new ByteArrayInputStream(bytes),
                    Map.of("mimeType", List.of("text/plain"), "filename", List.of("test.txt"))
            );

            assertTrue(result.success());
            assertTrue(result.text().contains("PipesForkParser"));
        }
    }

    @Test
    void testNullByteSanitization() {
        TextExtractionProperties props = new TextExtractionProperties();
        props.setForkEnabled(false);

        try (PipesForkTextExtractor extractor = new PipesForkTextExtractor(props)) {
            String sampleWithNulls = "Corrupted\u0000text\u0000stream";
            byte[] bytes = sampleWithNulls.getBytes(StandardCharsets.UTF_8);

            TextExtractionResult result = extractor.extractText(bytes, Map.of("mimeType", List.of("text/plain")));

            assertTrue(result.success());
            assertFalse(result.text().contains("\u0000"));
            assertTrue(result.text().contains("Corrupted"));
        }
    }

    @Test
    void testEmptyContentHandling() {
        TextExtractionProperties props = new TextExtractionProperties();
        props.setForkEnabled(false);

        try (PipesForkTextExtractor extractor = new PipesForkTextExtractor(props)) {
            TextExtractionResult resultBytes = extractor.extractText(new byte[0], Map.of());
            assertTrue(resultBytes.success());
            assertEquals("", resultBytes.text());

            TextExtractionResult resultNullStream = extractor.extractText((ByteArrayInputStream) null, Map.of());
            assertTrue(resultNullStream.success());
            assertEquals("", resultNullStream.text());
        }
    }

    @Test
    void testExtractFatJarLibsZipSlipProtection(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("test-fatjar.jar");
        Path extractDir = tempDir.resolve("target-libs");
        Path escapedMaliciousFile = tempDir.resolve("escaped.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // Valid library
            JarEntry validEntry = new JarEntry("BOOT-INF/lib/valid-lib.jar");
            jos.putNextEntry(validEntry);
            jos.write("valid-jar-bytes".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // Malicious traversal library attempting to escape
            JarEntry evilEntry = new JarEntry("BOOT-INF/lib/../../escaped.jar");
            jos.putNextEntry(evilEntry);
            jos.write("malicious-bytes".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // Directory entry inside BOOT-INF/lib
            JarEntry dirEntry = new JarEntry("BOOT-INF/lib/sub/");
            jos.putNextEntry(dirEntry);
            jos.closeEntry();

            // Non-lib entry
            JarEntry otherEntry = new JarEntry("BOOT-INF/classes/App.class");
            jos.putNextEntry(otherEntry);
            jos.write("class-bytes".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        PipesForkTextExtractor.extractFatJarLibs(jarPath, extractDir);

        // Verify valid lib was extracted
        Path extractedValid = extractDir.resolve("valid-lib.jar");
        assertTrue(Files.exists(extractedValid), "Valid library should be extracted");
        assertEquals("valid-jar-bytes", Files.readString(extractedValid));

        // Verify malicious traversal was blocked and did NOT escape
        assertFalse(Files.exists(escapedMaliciousFile), "Malicious zip entry must not escape destination directory");
        assertFalse(Files.exists(extractDir.resolve("escaped.jar")), "Malicious zip entry with path traversal must be rejected");
        assertFalse(Files.exists(extractDir.resolve("App.class")), "Non-jar entry should not be extracted");
    }
}
