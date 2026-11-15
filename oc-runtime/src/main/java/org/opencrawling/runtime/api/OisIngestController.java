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

import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Accepts OIS documents from producers outside the runtime, such as the StormCrawler bolt, and publishes
 * them into the ingestion pipeline of an existing job. The job supplies the embedding model; the store is
 * the one this process writes to. Documents always go through the RAG pipeline.
 *
 * <p>The endpoint is disabled unless {@code opencrawling.ingest.ois.token} is set, and every request must
 * carry {@code Authorization: Bearer <token>}. Whoever holds the token decides the document id and its
 * permissions.
 */
@Tag(name = "OIS Direct Ingestion", description = "Direct ingestion of Open Ingestion Standard (OIS) documents into active RAG jobs")
@RestController
@RequestMapping("/api/v1/ingest/ois")
public class OisIngestController {

    private static final org.opencrawling.core.reporting.ExecutionProvenance OIS_PROVENANCE =
            org.opencrawling.core.reporting.ExecutionProvenance.anonymous(
                    org.opencrawling.core.reporting.ExecutionProvenance.SOURCE_OIS, null);

    private static final Logger log = LoggerFactory.getLogger(OisIngestController.class);

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final JobController jobController;
    private final JobOrchestrator jobOrchestrator;
    private final String token;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.opencrawling.runtime.reporting.JobLedgerAccumulator ledgerAccumulator;

    public OisIngestController(JobController jobController, JobOrchestrator jobOrchestrator,
            @Value("${opencrawling.ingest.ois.token:}") String token) {
        this.jobController = jobController;
        this.jobOrchestrator = jobOrchestrator;
        this.token = token;
    }

    @Operation(
        summary = "Ingest OIS Document",
        description = "Publishes one Open Ingestion Standard (OIS) document into the ingestion pipeline of an active RAG job. "
                + "Accepts UPSERT actions (requiring text content and security ACL permissions) or DELETE tombstones to remove documents.",
        security = @SecurityRequirement(name = "OisTokenAuth")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Document accepted and published to Kafka ingestion pipeline"),
        @ApiResponse(responseCode = "400", description = "Malformed JSON or invalid OIS document structure"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid bearer authorization token"),
        @ApiResponse(responseCode = "404", description = "OIS endpoint is disabled or target job ID does not exist"),
        @ApiResponse(responseCode = "409", description = "Target job runs in migration mode (OIS ingestion supports RAG jobs only)"),
        @ApiResponse(responseCode = "503", description = "Document could not be published to Kafka")
    })
    @PostMapping("/{jobId}")
    public ResponseEntity<String> ingest(
            @Parameter(description = "Target job unique identifier", example = "1") @PathVariable String jobId,
            @Parameter(description = "Bearer authorization token: 'Bearer <token>'", example = "Bearer s3cret")
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            // Bound as a String and parsed only after the credentials check, so that an unauthenticated
            // caller gets 401 for a malformed or empty body too.
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "OIS document JSON payload")
            @RequestBody(required = false) String body) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JobDTO job = jobController.findJobById(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        // Answers carry only the fixed messages from here on: no request input and no exception text.
        if (jobController.resolvePipelineMode(job).isMigration()) {
            return plainText(HttpStatus.CONFLICT, "the job runs in migration mode; OIS ingestion supports RAG jobs only");
        }

        OisDocument ois;
        try {
            ois = body != null ? MAPPER.readValue(body, OisDocument.class) : null;
        } catch (JacksonException e) {
            return plainText(HttpStatus.BAD_REQUEST, "the body is not valid OIS JSON");
        }
        String problem = problemWith(ois);
        if (problem != null) {
            return plainText(HttpStatus.BAD_REQUEST, problem);
        }
        RepositoryDocument doc = toRepositoryDocument(ois);

        long startTime = System.currentTimeMillis();
        String execId = "exec-ois-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String repoName = (job.repositoryConnector() != null && !job.repositoryConnector().isBlank()
                && !job.repositoryConnector().equalsIgnoreCase("FileSystem_Local"))
                ? job.repositoryConnector()
                : "OisDirectIngestion";
        String outName = (job.outputConnector() != null && !job.outputConnector().isBlank())
                ? job.outputConnector()
                : "PGVector_Output";
        String jobName = (job.name() != null && !job.name().isBlank()) ? job.name() : ("Job #" + job.id());
        String model = (job.transformationConnector() != null && !job.transformationConnector().isBlank())
                ? job.transformationConnector()
                : "mxbai-embed-large";
        if (ledgerAccumulator != null) {
            ledgerAccumulator.startExecution(job.id(), execId, jobName, org.opencrawling.core.pipeline.PipelineMode.RAG, repoName, outName,
                    OIS_PROVENANCE);
        }

        try {
            jobOrchestrator.publishDocument(doc, job.transformationConnector(), job.id(), OIS_PROVENANCE,
                    ois.source() != null ? ois.source().type() : null,
                    ois.source() != null ? ois.source().instance() : null);
            long durationMs = Math.max(1L, System.currentTimeMillis() - startTime);
            if (ledgerAccumulator != null) {
                int tokenCount = (doc.action() == DocumentAction.DELETE || ois.content() == null || ois.content().text() == null)
                        ? 0
                        : Math.max(1, ois.content().text().length() / 4);
                int chunkCount = doc.action() == DocumentAction.DELETE ? 0 : Math.max(1, (int) Math.ceil(tokenCount / 500.0));
                ledgerAccumulator.recordDocument(org.opencrawling.core.reporting.DocumentLedgerEntry.ragSuccess(
                        job.id(), execId, doc.id(), doc.uri(), repoName, outName,
                        durationMs, chunkCount, tokenCount, model, 1024, "vector_store"
                ));
                ledgerAccumulator.completeExecution(execId, true, Map.of("durationMs", durationMs, "embeddingModel", model));
            }
            jobController.recordPushExecution(job.id(), 1L, true);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long durationMs = Math.max(1L, System.currentTimeMillis() - startTime);
            if (ledgerAccumulator != null) {
                ledgerAccumulator.recordDocument(org.opencrawling.core.reporting.DocumentLedgerEntry.failure(
                        job.id(), execId, org.opencrawling.core.pipeline.PipelineMode.RAG, doc.id(), doc.uri(),
                        repoName, outName, durationMs, "KAFKA_PUBLISH_ERROR", e.getMessage()
                ));
                ledgerAccumulator.completeExecution(execId, false, Map.of("durationMs", durationMs, "embeddingModel", model));
            }
            jobController.recordPushExecution(job.id(), 0L, false);
            log.error("Failed to publish OIS document {} for job {}", doc.id(), jobId, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.accepted().build();
    }

    private static ResponseEntity<String> plainText(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(message);
    }

    /** What makes {@code ois} unusable, or null when it can be published. */
    private static String problemWith(OisDocument ois) {
        if (ois == null) {
            return "the body is not an OIS document";
        }
        if (ois.id() == null || ois.id().isBlank()) {
            return "'id' is required";
        }
        if (!"UPSERT".equals(ois.action()) && !"DELETE".equals(ois.action())) {
            return "'action' must be UPSERT or DELETE";
        }
        if ("DELETE".equals(ois.action())) {
            return null;
        }
        if (ois.content() == null || ois.content().text() == null) {
            return "'content.text' is required for UPSERT";
        }
        if (ois.security() == null) {
            return "'security' is required for UPSERT";
        }
        if (ois.security().permissions() != null && ois.security().permissions().stream()
                .anyMatch(rule -> rule == null || rule.identity() == null || rule.access() == null)) {
            return "each 'security.permissions' entry needs an identity and an access";
        }
        return null;
    }

    /** Maps a document that {@link #problemWith} accepted. */
    private static RepositoryDocument toRepositoryDocument(OisDocument ois) {
        DocumentAction action = DocumentAction.valueOf(ois.action());
        ByteArrayInputStream content = null;
        String acl = "";
        if (action != DocumentAction.DELETE) {
            content = new ByteArrayInputStream(ois.content().text().getBytes(StandardCharsets.UTF_8));
            acl = readAcl(ois.security());
        }
        Map<String, List<String>> metadata = new HashMap<>();
        if (ois.metadata() != null) {
            ois.metadata().forEach((key, value) -> {
                if (value != null) {
                    metadata.put(key, List.of(String.valueOf(value)));
                }
            });
        }
        return new RepositoryDocument(ois.id(), ois.id(), content, metadata, acl, ois.security(), Instant.now(), action);
    }

    /**
     * The flat {@code acl} value derived from {@code security}: all identities with read, write, or admin access
     * joined by comma (or {@code ""} when none).
     */
    private static String readAcl(SecurityConfig security) {
        List<PermissionRule> rules = (security != null && security.permissions() != null) ? security.permissions() : List.of();
        List<String> readers = rules.stream()
                .filter(rule -> "read".equalsIgnoreCase(rule.access()) || "write".equalsIgnoreCase(rule.access()) || "admin".equalsIgnoreCase(rule.access()))
                .map(PermissionRule::identity)
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return String.join(",", readers);
    }

    /** The fields of an OIS document this endpoint reads; any other field is ignored. */
    @Schema(description = "Open Ingestion Standard (OIS) document payload")
    record OisDocument(
            @Schema(description = "Canonical document unique identifier or URI", example = "http://example.com/page.html")
            String id,
            @Schema(description = "Document action: 'UPSERT' or 'DELETE'", example = "UPSERT")
            String action,
            @Schema(description = "Document content payload")
            Content content,
            @Schema(description = "Arbitrary metadata key-value mappings")
            Map<String, Object> metadata,
            @Schema(description = "Access Control List and security configuration")
            SecurityConfig security,
            @Schema(description = "The repository the document comes from")
            Source source) {

        @Schema(description = "The repository a pushed document comes from")
        record Source(
                @Schema(description = "Repository type", example = "sharepoint")
                String type,
                @Schema(description = "Base URI of the repository instance", example = "https://contoso.sharepoint.com")
                String instance) {
        }

        @Schema(description = "Textual content payload of the document")
        record Content(
                @Schema(description = "MIME type of the content", example = "text/html")
                String mimeType,
                @Schema(description = "Raw text content to be chunked and embedded", example = "Document content...")
                String text) {
        }
    }
}
