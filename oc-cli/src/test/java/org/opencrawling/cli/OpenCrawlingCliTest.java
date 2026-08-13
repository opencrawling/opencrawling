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
}
