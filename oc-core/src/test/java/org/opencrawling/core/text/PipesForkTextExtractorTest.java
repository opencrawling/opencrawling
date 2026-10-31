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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
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
            assertFalse(extractor.isForkActive());
            assertTrue(extractor.isHealthy());
            assertEquals("In-Process Embedded Tika", extractor.getEngineName());
        }
    }

    @Test
    void testPipesForkTextExtraction() {
        TextExtractionProperties props = new TextExtractionProperties();
        props.setForkEnabled(true);
        props.setTimeoutMs(15000);

        try (PipesForkTextExtractor extractor = new PipesForkTextExtractor(props)) {
            assertTrue(extractor.isForkActive(), "Forked process parser must be active");
            assertTrue(extractor.isHealthy());
            assertEquals("PipesForkParser (Process-Isolated)", extractor.getEngineName());

            String sample = "Apache Tika 4.2.0 process-isolated parsing via PipesForkParser!";
            byte[] bytes = sample.getBytes(StandardCharsets.UTF_8);

            TextExtractionResult result = extractor.extractText(
                    new ByteArrayInputStream(bytes),
                    Map.of("mimeType", List.of("text/plain"), "filename", List.of("test.txt"))
            );

            assertTrue(result.success());
            assertTrue(result.text().contains("PipesForkParser"));
            assertEquals("4.2.0", extractor.getVersion());
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
    void testEmbeddedDocumentExtraction() throws IOException {
        byte[] zipBytes = createSampleZipBytes();

        // 1. With extractEmbedded = true (default)
        TextExtractionProperties propsEnabled = new TextExtractionProperties();
        propsEnabled.setForkEnabled(false);
        propsEnabled.setExtractEmbedded(true);

        try (PipesForkTextExtractor extractor = new PipesForkTextExtractor(propsEnabled)) {
            TextExtractionResult result = extractor.extractText(zipBytes, Map.of("mimeType", List.of("application/zip")));
            assertTrue(result.success());
            assertTrue(result.text().contains("First embedded file inside zip"), "Nested zip entries should be extracted");
            assertTrue(result.text().contains("Second embedded file inside zip"), "Nested zip entries should be extracted");
        }

        // 2. With extractEmbedded = false
        TextExtractionProperties propsDisabled = new TextExtractionProperties();
        propsDisabled.setForkEnabled(false);
        propsDisabled.setExtractEmbedded(false);

        try (PipesForkTextExtractor extractor = new PipesForkTextExtractor(propsDisabled)) {
            TextExtractionResult result = extractor.extractText(zipBytes, Map.of("mimeType", List.of("application/zip")));
            assertTrue(result.success());
            assertFalse(result.text().contains("First embedded file inside zip"), "Nested zip entries should not be extracted when extractEmbedded=false");
        }
    }

    @Test
    void testWriteLimitTruncation() {
        TextExtractionProperties props = new TextExtractionProperties();
        props.setForkEnabled(false);
        props.setWriteLimit(20);

        try (PipesForkTextExtractor extractor = new PipesForkTextExtractor(props)) {
            String longContent = "This is a very long text stream that definitely exceeds the configured write limit of 20 characters.";
            byte[] bytes = longContent.getBytes(StandardCharsets.UTF_8);

            TextExtractionResult result = extractor.extractText(bytes, Map.of("mimeType", List.of("text/plain")));
            assertTrue(result.success());
            assertTrue(result.trimmed(), "Result must be marked trimmed when write limit is reached");
            assertTrue(result.text().length() <= 30, "Text length should be constrained by writeLimit");
        }
    }

    @Test
    void testNoOpTextExtractionService() {
        TextExtractionService noop = TextExtractionService.noop();
        assertFalse(noop.isForkActive());
        assertTrue(noop.isHealthy());
        assertEquals("No-Op / Plain Text Fallback", noop.getEngineName());
        assertEquals("N/A", noop.getVersion());

        // Plain text should succeed
        byte[] textBytes = "Plain text for noop".getBytes(StandardCharsets.UTF_8);
        TextExtractionResult textRes = noop.extractText(textBytes, Map.of("mimeType", List.of("text/plain")));
        assertTrue(textRes.success());
        assertEquals("Plain text for noop", textRes.text());

        // Binary content without plain text mime should return empty text
        byte[] binBytes = new byte[]{0x1F, (byte) 0x8B, 0x08, 0x00};
        TextExtractionResult binRes = noop.extractText(binBytes, Map.of("mimeType", List.of("application/octet-stream")));
        assertTrue(binRes.success());
        assertEquals("", binRes.text());
    }

    @Test
    void testCleanProcessTerminationOnClose() {
        TextExtractionProperties props = new TextExtractionProperties();
        props.setForkEnabled(true);
        props.setTimeoutMs(15000);

        PipesForkTextExtractor extractor = new PipesForkTextExtractor(props);
        assertTrue(extractor.isForkActive());
        extractor.close();
        assertFalse(extractor.isForkActive(), "Child fork parser should be null/inactive after close()");
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

    @Test
    void testPipesForkLifecycleFailureFallback() {
        TextExtractionProperties props = new TextExtractionProperties();
        props.setForkEnabled(true);
        props.setTimeoutMs(15000);
        props.setJvmArgs(List.of("-XX:+NonExistentJvmFlagFailsStartup"));

        try (PipesForkTextExtractor extractor = new PipesForkTextExtractor(props)) {
            // When native pipesForkParser.start() fails fast due to bad JVM args, fallback kicks in
            assertFalse(extractor.isForkActive());
            assertFalse(extractor.isHealthy(), "Service is degraded when requested fork fails to start");
            assertEquals("In-Process Embedded Tika", extractor.getEngineName());

            // Extraction should still succeed via embedded fallback
            String sample = "Testing graceful fallback after fork initialization failure.";
            TextExtractionResult result = extractor.extractText(
                    sample.getBytes(StandardCharsets.UTF_8),
                    Map.of("mimeType", List.of("text/plain"))
            );
            assertTrue(result.success());
            assertTrue(result.text().contains("Testing graceful fallback"));
        }
    }

    @Test
    void testUtcDateNormalizationAndMetadataExtraction() {
        // Tika 4.2.0 parses zone-less dates as UTC (TIKA-4917)
        Metadata md = new Metadata();
        md.set("custom_date", "2026-10-25T14:30:00");
        Date parsedDate = md.getDate(Property.externalDate("custom_date"));
        assertNotNull(parsedDate);
        Instant instant = parsedDate.toInstant();
        assertEquals(Instant.parse("2026-10-25T14:30:00Z"), instant);

        // Verify metadata extraction via extractor
        TextExtractionProperties props = new TextExtractionProperties();
        props.setForkEnabled(false);
        props.setExtractMetadata(true);

        try (PipesForkTextExtractor extractor = new PipesForkTextExtractor(props)) {
            Map<String, List<String>> initialMeta = new HashMap<>();
            initialMeta.put("mimeType", List.of("text/plain"));
            TextExtractionResult result = extractor.extractText(
                    "Sample text content for metadata test.".getBytes(StandardCharsets.UTF_8),
                    initialMeta
            );
            assertTrue(result.success());
            assertNotNull(result.metadata());
            assertTrue(result.metadata().containsKey("tk:Content-Type"));
        }
    }

    private byte[] createSampleZipBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("inner1.txt"));
            zos.write("First embedded file inside zip".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("inner2.txt"));
            zos.write("Second embedded file inside zip".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
