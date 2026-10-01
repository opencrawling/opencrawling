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
package org.opencrawling.aps;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.opencrawling.core.document.RepositoryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Live Integration Test for {@link ApsRepositoryConnector} targeting a real
 * Alfresco Process Services (APS) 26.2 container running on Docker.
 *
 * Enabled when the live APS REST endpoint is reachable or when -Daps.live.test=true or
 * -Dspring.opencrawling.connector.aps.url is provided.
 */
@EnabledIf("isApsReachable")
class ApsRepositoryConnectorIT {

    private static final Logger log = LoggerFactory.getLogger(ApsRepositoryConnectorIT.class);

    private String apsUrl;
    private String apsUsername;
    private String apsPassword;
    private ApsRepositoryConnector connector;

    static boolean isApsReachable() {
        if (Boolean.getBoolean("aps.live.test") || "true".equalsIgnoreCase(System.getenv("APS_LIVE_TEST"))) {
            return true;
        }
        String url = System.getProperty("spring.opencrawling.connector.aps.url",
                System.getenv().getOrDefault("APS_URL", "http://localhost:8088/activiti-app/api/enterprise"));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/profile"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<Void> resp = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200 || resp.statusCode() == 401;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        apsUrl = System.getProperty("spring.opencrawling.connector.aps.url",
                System.getenv().getOrDefault("APS_URL", "http://localhost:8088/activiti-app/api/enterprise"));
        apsUsername = System.getProperty("spring.opencrawling.connector.aps.username",
                System.getenv().getOrDefault("APS_USERNAME", "admin@app.activiti.com"));
        apsPassword = System.getProperty("spring.opencrawling.connector.aps.password",
                System.getenv().getOrDefault("APS_PASSWORD", "admin"));

        log.info("Initializing ApsRepositoryConnectorIT with APS URL: {}, User: {}", apsUrl, apsUsername);

        connector = new ApsRepositoryConnector(
                apsUrl,
                apsUsername,
                apsPassword,
                50,
                "",
                true,
                true,
                "",
                "all"
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connector != null && connector.isConnected()) {
            connector.disconnect();
        }
    }

    @Test
    @DisplayName("Verify authentication and live connection to APS 26.2 container")
    void testLiveConnectAndDisconnect() throws Exception {
        connector.connect();
        assertTrue(connector.isConnected(), "Connector should be marked connected after successful auth");
        assertEquals("ApsConnector", connector.getName(), "Connector name must match standard convention");

        connector.disconnect();
        assertFalse(connector.isConnected(), "Connector should be disconnected after disconnect() call");
    }

    @Test
    @DisplayName("Verify scanning workflow instances stream from live APS instance")
    void testLiveScanWorkflowInstances() throws Exception {
        connector.connect();
        assertTrue(connector.isConnected());

        Flux<RepositoryDocument> documentFlux = connector.scan("");
        assertNotNull(documentFlux, "Scan flux must not be null");

        // Verify the stream completes without error and check document structure
        List<RepositoryDocument> docs = documentFlux.collectList().block(Duration.ofSeconds(30));
        assertNotNull(docs, "Document list must not be null");
        log.info("Discovered {} workflow instances from live APS instance.", docs.size());

        for (RepositoryDocument doc : docs) {
            assertNotNull(doc.id(), "Document ID must not be null");
            assertNotNull(doc.uri(), "Document URI must not be null");
            assertTrue(doc.uri().startsWith("aps://process-instances/"),
                    "URI must follow Open Ingestion Standard aps scheme: " + doc.uri());
            assertNotNull(doc.metadata(), "Document metadata must not be null");
            assertNotNull(doc.security(), "SecurityConfig must not be null");

            // Verify content stream is readable
            if (doc.contentStream() != null) {
                try (InputStream is = doc.contentStream()) {
                    byte[] bytes = is.readAllBytes();
                    assertTrue(bytes.length > 0, "Content stream must contain JSON payload bytes");
                }
            }
        }
    }

    @Test
    @DisplayName("Verify live connector rejects invalid credentials")
    void testLiveInvalidCredentials() {
        ApsRepositoryConnector badConnector = new ApsRepositoryConnector(
                apsUrl,
                apsUsername,
                "wrong_password_12345",
                10,
                "",
                false,
                false,
                "",
                "all"
        );

        assertThrows(Exception.class, badConnector::connect,
                "Connecting with invalid credentials against live APS must throw Exception");
        assertFalse(badConnector.isConnected());
    }

    @Test
    @DisplayName("Verify scanning workflow attachments and content streams from live APS instance")
    void testLiveScanWorkflowAttachments() throws Exception {
        connector.connect();
        assertTrue(connector.isConnected());

        Flux<RepositoryDocument> documentFlux = connector.scan("");
        assertNotNull(documentFlux, "Scan flux must not be null");

        List<RepositoryDocument> docs = documentFlux.collectList().block(Duration.ofSeconds(30));
        assertNotNull(docs, "Document list must not be null");

        List<RepositoryDocument> attachmentDocs = docs.stream()
                .filter(d -> d.uri() != null && d.uri().contains("/content/"))
                .toList();

        if (!attachmentDocs.isEmpty()) {
            log.info("Discovered {} attachment documents from live APS instance.", attachmentDocs.size());
            for (RepositoryDocument attDoc : attachmentDocs) {
                assertNotNull(attDoc.id(), "Attachment doc ID must not be null");
                assertTrue(attDoc.id().contains("-content-"), "Attachment doc ID format must be {procId}-content-{contentId}");
                assertTrue(attDoc.uri().startsWith("aps://process-instances/"), "Attachment URI must follow standard convention");
                assertTrue(attDoc.uri().contains("/content/"), "Attachment URI must contain /content/");

                // Check attachment metadata
                assertNotNull(attDoc.metadata().get("name"), "Attachment name metadata must be present");
                assertNotNull(attDoc.metadata().get("mimeType"), "Attachment mimeType metadata must be present");
                assertNotNull(attDoc.metadata().get("parentUri"), "Attachment parentUri metadata must be present");
                assertNotNull(attDoc.metadata().get("processInstanceId"), "Attachment processInstanceId metadata must be present");
                assertNotNull(attDoc.metadata().get("contentId"), "Attachment contentId metadata must be present");

                // Verify content stream is readable and non-empty
                assertNotNull(attDoc.contentStream(), "Attachment contentStream must not be null");
                try (InputStream is = attDoc.contentStream()) {
                    byte[] bytes = is.readAllBytes();
                    assertTrue(bytes.length > 0, "Attachment content bytes must not be empty");
                }
            }

            // Also verify that parent process instance doc has aps_attachment_count
            List<RepositoryDocument> procDocsWithAtt = docs.stream()
                    .filter(d -> !d.uri().contains("/content/") && d.metadata().containsKey("aps_attachment_count"))
                    .toList();
            assertFalse(procDocsWithAtt.isEmpty(), "At least one parent process instance must report aps_attachment_count");
        } else {
            log.info("No attachments found (empty or unlicensed repository state). Test passed gracefully.");
        }
    }

    @Test
    @DisplayName("Verify filtered scanning by status filter against live APS")
    void testLiveScanWithCompletedScope() throws Exception {
        ApsRepositoryConnector completedConnector = new ApsRepositoryConnector(
                apsUrl,
                apsUsername,
                apsPassword,
                10,
                "",
                true,
                true,
                "",
                "completed"
        );

        completedConnector.connect();
        assertTrue(completedConnector.isConnected());

        StepVerifier.create(completedConnector.scan("").take(5))
                .thenConsumeWhile(doc -> {
                    assertNotNull(doc.id());
                    return true;
                })
                .verifyComplete();

        completedConnector.disconnect();
    }
}
