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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryCommandTest {

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();

    private static HttpServer server;
    private static String serverUrl;

    @BeforeAll
    static void setUpAll() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        serverUrl = "http://127.0.0.1:" + port;

        server.createContext("/api/discovery/connectors", exchange -> {
            String response = """
                [
                  {
                    "name": "cmis-live",
                    "description": "Production CMIS Archive"
                  },
                  {
                    "name": "alfresco-live",
                    "description": "Alfresco Content Repository"
                  }
                ]
                """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/api/discovery/cmis-live/tree", exchange -> {
            String response = """
                {
                  "connector": "cmis-live",
                  "root": "/Company Home",
                  "nodes": [
                    {
                      "id": "cmis://root",
                      "sourceId": "1001",
                      "parentId": null,
                      "name": "Company Home",
                      "path": "/Company Home",
                      "type": "FOLDER",
                      "depth": 0,
                      "mimeType": null,
                      "sizeBytes": null,
                      "lastModified": "2026-11-06T08:00:00Z"
                    },
                    {
                      "id": "cmis://doc1",
                      "sourceId": "1002",
                      "parentId": "1001",
                      "name": "Q3_Report.pdf",
                      "path": "/Company Home/Q3_Report.pdf",
                      "type": "DOCUMENT",
                      "depth": 1,
                      "mimeType": "application/pdf",
                      "sizeBytes": 1048576,
                      "lastModified": "2026-11-06T09:00:00Z"
                    }
                  ],
                  "hiddenCount": 2,
                  "truncated": false,
                  "truncationReason": null
                }
                """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/api/discovery/cmis-live/dry-run", exchange -> {
            String response = """
                {
                  "connector": "cmis-live",
                  "matchCount": 128,
                  "countAccuracy": "EXACT",
                  "samples": [
                    {
                      "id": "cmis://doc1",
                      "sourceId": "1002",
                      "parentId": "1001",
                      "name": "Q3_Report.pdf",
                      "path": "/Company Home/Q3_Report.pdf",
                      "type": "DOCUMENT",
                      "depth": 1,
                      "mimeType": "application/pdf",
                      "sizeBytes": 1048576,
                      "lastModified": "2026-11-06T09:00:00Z"
                    }
                  ],
                  "hiddenCount": 5,
                  "truncated": true,
                  "truncationReason": "MAX_SCAN",
                  "note": "Lower bound estimation reached"
                }
                """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();
    }

    @AfterAll
    static void tearDownAll() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void testDiscoveryHelp() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("discovery", "--help");
        assertEquals(0, exitCode);
        String out = outContent.toString();
        assertTrue(out.contains("connectors"));
        assertTrue(out.contains("tree"));
        assertTrue(out.contains("dry-run"));
    }

    @Test
    void testDiscoveryNoSubcommandShowsUsage() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("discovery");
        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("Please specify a subcommand"));
    }

    @Test
    void testDiscoveryConnectorsTable() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("discovery", "connectors", "--url", serverUrl);
        assertEquals(0, exitCode);
        String out = outContent.toString();
        assertTrue(out.contains("cmis-live"));
        assertTrue(out.contains("Production CMIS Archive"));
        assertTrue(out.contains("alfresco-live"));
    }

    @Test
    void testDiscoveryConnectorsJson() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("discovery", "connectors", "--url", serverUrl, "--format", "json");
        assertEquals(0, exitCode);
        String out = outContent.toString();
        assertTrue(out.contains("\"name\" : \"cmis-live\""));
        assertTrue(out.contains("\"description\" : \"Production CMIS Archive\""));
    }

    @Test
    void testDiscoveryTreeTreeFormat() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("discovery", "tree", "cmis-live", "--url", serverUrl, "--start", "/Company Home");
        assertEquals(0, exitCode);
        String out = outContent.toString();
        assertTrue(out.contains("Connector:") && out.contains("cmis-live"));
        assertTrue(out.contains("Root:") && out.contains("/Company Home"));
        assertTrue(out.contains("Company Home"));
        assertTrue(out.contains("Q3_Report.pdf"));
        assertTrue(out.contains("application/pdf"));
    }

    @Test
    void testDiscoveryTreeTableFormat() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("discovery", "tree", "cmis-live", "--url", serverUrl, "--format", "table");
        assertEquals(0, exitCode);
        String out = outContent.toString();
        assertTrue(out.contains("DEPTH"));
        assertTrue(out.contains("MIME TYPE"));
        assertTrue(out.contains("Q3_Report.pdf"));
    }

    @Test
    void testDiscoveryTreeJsonFormat() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("discovery", "tree", "cmis-live", "--url", serverUrl, "--format", "json");
        assertEquals(0, exitCode);
        String out = outContent.toString();
        assertTrue(out.contains("\"connector\" : \"cmis-live\""));
        assertTrue(out.contains("\"root\" : \"/Company Home\""));
    }

    @Test
    void testDiscoveryDryRunTableFormat() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("discovery", "dry-run", "cmis-live", "--url", serverUrl, "--scope", "/Company Home", "--sample-size", "5");
        assertEquals(0, exitCode);
        String out = outContent.toString();
        assertTrue(out.contains("Connector:") && out.contains("cmis-live"));
        assertTrue(out.contains("Match Count:") && out.contains("128 [EXACT]"));
        assertTrue(out.contains("Hidden Count:") && out.contains("5 items hidden by caller ACLs"));
        assertTrue(out.contains("Truncated: preview stopped early due to MAX_SCAN"));
        assertTrue(out.contains("Q3_Report.pdf"));
    }

    @Test
    void testDiscoveryDryRunJsonFormat() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("discovery", "dry-run", "cmis-live", "--url", serverUrl, "--format", "json");
        assertEquals(0, exitCode);
        String out = outContent.toString();
        assertTrue(out.contains("\"matchCount\" : 128"));
        assertTrue(out.contains("\"countAccuracy\" : \"EXACT\""));
    }

    @Test
    void testDiscoveryMissingConnectorFails() {
        CommandLine cmd = new CommandLine(new OpenCrawlingCliCommand());
        int exitCode = cmd.execute("discovery", "tree");
        assertEquals(1, exitCode);
        assertTrue(errContent.toString().contains("Connector name is required"));
    }
}
