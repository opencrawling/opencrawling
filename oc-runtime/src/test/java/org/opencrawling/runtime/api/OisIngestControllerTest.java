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
package org.opencrawling.runtime.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OisIngestControllerTest {

    private static final String TOKEN = "s3cret";
    private static final String BEARER = "Bearer " + TOKEN;
    private static final String URL = "http://example.com/page.html";
    private static final String UPSERT = """
            {"id": "http://example.com/page.html", "action": "UPSERT",
             "source": {"type": "stormcrawler", "instance": "test", "connectorVersion": "1.0.0"},
             "content": {"mimeType": "text/html", "text": "page text"},
             "metadata": {"title": "Page", "contentHash": "abc"},
             "security": {"inheritanceEnabled": false,
                          "permissions": [{"identity": "ROLE_USER", "identityType": "role", "access": "read"}]}}
            """;
    private static final String DELETE = """
            {"id": "http://example.com/page.html", "action": "DELETE", "metadata": {"http.status": "404"}}
            """;

    private JobController jobController;
    private JobOrchestrator jobOrchestrator;
    private JobDTO job;

    @BeforeEach
    void setUp() {
        jobController = mock(JobController.class);
        jobOrchestrator = mock(JobOrchestrator.class);
        job = new JobDTO("7", "Web", "StormCrawler_Web_Engine", "PGVector_Output", "", "", "Ready", "Idle", 0, "N/A",
                "Ollama_Embedding_Default", null);
        when(jobController.getJob("7")).thenReturn(ResponseEntity.ok(job));
        when(jobController.getJob("missing")).thenReturn(ResponseEntity.notFound().build());
        when(jobController.resolvePipelineMode(job)).thenReturn(PipelineMode.RAG);
    }

    @Test
    void upsertIsPublishedForTheJobAndAccepted() throws Exception {
        ResponseEntity<String> response = controller(TOKEN).ingest("7", BEARER, UPSERT);

        assertEquals(202, response.getStatusCode().value());
        ArgumentCaptor<RepositoryDocument> published = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(jobOrchestrator).publishDocument(published.capture(), eq("Ollama_Embedding_Default"));
        RepositoryDocument doc = published.getValue();
        assertEquals(URL, doc.id());
        assertEquals(URL, doc.uri());
        assertEquals(DocumentAction.UPSERT, doc.action());
        assertEquals("page text", new String(doc.contentStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(List.of("Page"), doc.metadata().get("title"));
        assertEquals("ROLE_USER", doc.acl());
        assertEquals("ROLE_USER", doc.security().permissions().getFirst().identity());
    }

    @Test
    void deleteIsPublishedWithoutContent() throws Exception {
        ResponseEntity<String> response = controller(TOKEN).ingest("7", BEARER, DELETE);

        assertEquals(202, response.getStatusCode().value());
        ArgumentCaptor<RepositoryDocument> published = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(jobOrchestrator).publishDocument(published.capture(), eq("Ollama_Embedding_Default"));
        assertEquals(DocumentAction.DELETE, published.getValue().action());
        assertNull(published.getValue().contentStream());
    }

    @Test
    void endpointIsDisabledWithoutAConfiguredToken() {
        assertEquals(404, controller("").ingest("7", BEARER, UPSERT).getStatusCode().value());
        verifyNoInteractions(jobOrchestrator);
    }

    @Test
    void missingOrWrongCredentialsAreRejected() {
        assertEquals(401, controller(TOKEN).ingest("7", null, UPSERT).getStatusCode().value());
        assertEquals(401, controller(TOKEN).ingest("7", "Bearer wrong", UPSERT).getStatusCode().value());
        assertEquals(401, controller(TOKEN).ingest("7", TOKEN, UPSERT).getStatusCode().value());
        assertEquals(401, controller(TOKEN).ingest("7", null, "not json").getStatusCode().value());
        verifyNoInteractions(jobOrchestrator);
    }

    @Test
    void unknownJobIsNotFound() {
        assertEquals(404, controller(TOKEN).ingest("missing", BEARER, UPSERT).getStatusCode().value());
        verifyNoInteractions(jobOrchestrator);
    }

    @Test
    void jobInMigrationModeIsRefused() {
        when(jobController.resolvePipelineMode(job)).thenReturn(PipelineMode.MIGRATION);

        assertEquals(409, controller(TOKEN).ingest("7", BEARER, UPSERT).getStatusCode().value());
        verifyNoInteractions(jobOrchestrator);
    }

    @Test
    void invalidDocumentsAreBadRequests() {
        OisIngestController controller = controller(TOKEN);

        assertEquals(400, controller.ingest("7", BEARER, "not json").getStatusCode().value());
        assertEquals(400, controller.ingest("7", BEARER, "null").getStatusCode().value());
        assertEquals(400, controller.ingest("7", BEARER, null).getStatusCode().value());
        assertEquals(400, controller.ingest("7", BEARER, "{\"action\": \"UPSERT\", \"content\": {\"text\": \"x\"}}").getStatusCode().value());
        assertEquals(400, controller.ingest("7", BEARER, "{\"id\": \"x\", \"action\": \"UPSERT\"}").getStatusCode().value());
        assertEquals(400, controller.ingest("7", BEARER, "{\"id\": \"x\", \"action\": \"MERGE\"}").getStatusCode().value());
        assertEquals(400, controller.ingest("7", BEARER, "{\"id\": \"x\", \"content\": {\"text\": \"x\"}}").getStatusCode().value());
        assertEquals(400, controller.ingest("7", BEARER, upsertWithSecurity(null)).getStatusCode().value());
        assertEquals(400, controller.ingest("7", BEARER, upsertWithSecurity("{\"permissions\": [null]}")).getStatusCode().value());
        assertEquals(400, controller.ingest("7", BEARER,
                upsertWithSecurity("{\"permissions\": [{\"identityType\": \"role\", \"access\": \"read\"}]}")).getStatusCode().value());
        verifyNoInteractions(jobOrchestrator);
    }

    @Test
    void writeAccessCountsAsReadForTheAcl() throws Exception {
        String editorsOnly = upsertWithSecurity(
                "{\"permissions\": [{\"identity\": \"editors\", \"identityType\": \"group\", \"access\": \"write\"}]}");

        assertEquals(202, controller(TOKEN).ingest("7", BEARER, editorsOnly).getStatusCode().value());

        ArgumentCaptor<RepositoryDocument> published = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(jobOrchestrator).publishDocument(published.capture(), any());
        assertEquals("editors", published.getValue().acl());
    }

    @Test
    void publishFailureIsServiceUnavailable() throws Exception {
        doThrow(new IllegalStateException("broker down")).when(jobOrchestrator).publishDocument(any(), any());

        assertEquals(503, controller(TOKEN).ingest("7", BEARER, UPSERT).getStatusCode().value());
    }

    private static String upsertWithSecurity(String security) {
        return "{\"id\": \"x\", \"action\": \"UPSERT\", \"content\": {\"text\": \"x\"}"
                + (security != null ? ", \"security\": " + security : "") + "}";
    }

    private OisIngestController controller(String token) {
        return new OisIngestController(jobController, jobOrchestrator, token);
    }
}
