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
package org.opencrawling.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class OpenCrawlingCliTest {

    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void testRootCommandHelp() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("--help");
        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("OpenCrawling CLI"));
    }

    @Test
    void testConfigSetAndGet() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCodeSet = cmd.execute("config", "set", "--server-url", "http://localhost:8080", "--context", "test-ctx");
        assertEquals(0, exitCodeSet);

        int exitCodeContext = cmd.execute("config", "context");
        assertEquals(0, exitCodeContext);
        assertTrue(outContent.toString().contains("test-ctx"));
    }

    @Test
    void testArchetypeInit() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("archetype", "init", "--type", "repository", "--name", "TestCustomConnector", "--output-dir", "target/test-archetype");
        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("Successfully generated connector project"));
    }

    @Test
    void testSchemaValidate() throws Exception {
        java.io.File tempFile = java.io.File.createTempFile("test-ois-schema", ".json");
        tempFile.deleteOnExit();
        java.nio.file.Files.writeString(tempFile.toPath(), "{\"name\": \"TestJob\", \"repositoryConnector\": \"FileSystem\"}");

        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("schema", "validate", "--file", tempFile.getAbsolutePath());
        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("valid"));
    }

    private int validate(String json) throws Exception {
        java.io.File tempFile = java.io.File.createTempFile("test-ois-migration", ".json");
        tempFile.deleteOnExit();
        java.nio.file.Files.writeString(tempFile.toPath(), json);
        return new CommandLine(new OpenCrawlingCliCommand()).execute("schema", "validate", "--file", tempFile.getAbsolutePath());
    }

    @Test
    void testSchemaValidateAcceptsValidMigrationEnvelope() throws Exception {
        String json = """
            {"$schema":"https://opencrawling.org/schemas/v1/ois-document.json","id":"DOC-1","action":"UPSERT",
             "contentRef":{"key":"legal/report.pdf","claimCheckUri":"s3://claims/report.pdf","filename":"report.pdf",
               "mimeType":"application/pdf","contentLength":1048576,
               "checksumSha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
             "security":{"inheritanceEnabled":true,"permissions":[
               {"identity":"ROLE_LEGAL","identityType":"ROLE","access":"READ"},
               {"principalId":"jdoe","principalType":"USER","accessType":"READ_WRITE"}]}}
            """;
        assertEquals(0, validate(json));
        assertTrue(outContent.toString().contains("OIS Migration Mode document payload detected"));
    }

    @Test
    void testSchemaValidateRejectsBrokenMigrationEnvelope() throws Exception {
        String json = """
            {"id":"DOC-2","action":"UPSERT",
             "contentRef":{"key":"x","mimeType":"","contentLength":-1,"checksumSha256":"abc"},
             "security":{"inheritanceEnabled":"yes","permissions":[{"identity":"","access":"READ"}]}}
            """;
        assertEquals(1, validate(json));
        String out = outContent.toString();
        assertTrue(out.contains("checksumSha256"));
        assertTrue(out.contains("contentLength"));
        assertTrue(out.contains("mimeType"));
        assertTrue(out.contains("inheritanceEnabled"));
        assertTrue(out.contains("permissions[0]"));
    }

    @Test
    void testSchemaValidateRejectsTombstoneWithContentRef() throws Exception {
        String json = """
            {"id":"DOC-3","action":"DELETE","contentRef":{"key":"x"}}
            """;
        assertEquals(1, validate(json));
        assertTrue(outContent.toString().contains("DELETE tombstones must not carry"));
    }

    @Test
    void testSchemaValidateRejectsUnknownJobPipelineMode() throws Exception {
        assertEquals(1, validate("{\"name\":\"J\",\"repositoryConnector\":\"FileSystem\",\"pipelineMode\":\"migraton\"}"));
        assertEquals(0, validate("{\"name\":\"J\",\"repositoryConnector\":\"FileSystem\",\"pipelineMode\":\"migration\"}"));
    }

    @Test
    void testJobStartRejectsInvalidModeBeforeContactingServer() {
        java.io.PrintStream originalErr = System.err;
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errContent));
        try {
            int exitCode = new CommandLine(new OpenCrawlingCliCommand())
                    .execute("job", "start", "--name", "x", "--mode", "migraton", "--url", "http://127.0.0.1:1");
            assertEquals(2, exitCode);
            assertTrue(errContent.toString().contains("Invalid --mode"));
        } finally {
            System.setErr(originalErr);
        }
    }
}
