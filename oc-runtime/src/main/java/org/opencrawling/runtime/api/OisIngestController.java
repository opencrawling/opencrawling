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

/**
 * Accepts OIS documents from producers outside the runtime, such as the StormCrawler bolt, and publishes
 * them into the ingestion pipeline of an existing job. The job supplies the embedding model; the store is
 * the one this process writes to. Documents always go through the RAG pipeline.
 *
 * <p>The endpoint is disabled unless {@code opencrawling.ingest.ois.token} is set, and every request must
 * carry {@code Authorization: Bearer <token>}. Whoever holds the token decides the document id and its
 * permissions.
 */
@RestController
@RequestMapping("/api/v1/ingest/ois")
public class OisIngestController {

    private static final Logger log = LoggerFactory.getLogger(OisIngestController.class);

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final JobController jobController;
    private final JobOrchestrator jobOrchestrator;
    private final String token;

    public OisIngestController(JobController jobController, JobOrchestrator jobOrchestrator,
            @Value("${opencrawling.ingest.ois.token:}") String token) {
        this.jobController = jobController;
        this.jobOrchestrator = jobOrchestrator;
        this.token = token;
    }

    /**
     * Publishes one OIS document for job {@code jobId}. An UPSERT needs {@code content.text}; a DELETE
     * removes the document with the same id.
     *
     * @return 202 once the document is on Kafka; 400 for a malformed document; 401 for missing or wrong
     *         credentials; 404 when the endpoint is disabled or the job does not exist; 409 when the job
     *         runs in migration mode; 503 when the document could not be published
     */
    @PostMapping("/{jobId}")
    public ResponseEntity<String> ingest(@PathVariable String jobId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            // Bound as a String and parsed only after the credentials check, so that an unauthenticated
            // caller gets 401 for a malformed or empty body too.
            @RequestBody(required = false) String body) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JobDTO job = jobController.getJob(jobId).getBody();
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (jobController.resolvePipelineMode(job).isMigration()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Job " + jobId + " runs in migration mode; OIS ingestion supports RAG jobs only");
        }

        RepositoryDocument doc;
        try {
            if (body == null) {
                throw new IllegalArgumentException("the request has no body");
            }
            doc = toRepositoryDocument(MAPPER.readValue(body, OisDocument.class));
        } catch (JacksonException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

        try {
            jobOrchestrator.publishDocument(doc, job.transformationConnector());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to publish OIS document {} for job {}", doc.id(), jobId, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.accepted().build();
    }

    private static RepositoryDocument toRepositoryDocument(OisDocument ois) {
        if (ois == null) {
            throw new IllegalArgumentException("the body is not an OIS document");
        }
        if (ois.id() == null || ois.id().isBlank()) {
            throw new IllegalArgumentException("'id' is required");
        }
        if (ois.action() == null) {
            throw new IllegalArgumentException("'action' is required");
        }
        DocumentAction action = DocumentAction.valueOf(ois.action());
        ByteArrayInputStream content = null;
        String acl = "";
        if (action != DocumentAction.DELETE) {
            if (ois.content() == null || ois.content().text() == null) {
                throw new IllegalArgumentException("'content.text' is required for " + action);
            }
            if (ois.security() == null) {
                throw new IllegalArgumentException("'security' is required for " + action);
            }
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
     * The flat {@code acl} value the MCP pre-filter matches: the first identity with read or write access.
     * A document readable by several identities is pre-filtered on the first one only; the MCP post-check
     * reads the full {@code security} permissions.
     */
    private static String readAcl(SecurityConfig security) {
        List<PermissionRule> rules = security.permissions() != null ? security.permissions() : List.of();
        for (PermissionRule rule : rules) {
            if (rule == null || rule.identity() == null || rule.access() == null) {
                throw new IllegalArgumentException("each 'security.permissions' entry needs an identity and an access");
            }
        }
        return rules.stream()
                .filter(rule -> "read".equalsIgnoreCase(rule.access()) || "write".equalsIgnoreCase(rule.access()))
                .map(PermissionRule::identity)
                .findFirst()
                .orElse("");
    }

    /** The fields of an OIS document this endpoint reads; any other field is ignored. */
    record OisDocument(String id, String action, Content content, Map<String, Object> metadata,
            SecurityConfig security) {

        record Content(String mimeType, String text) {
        }
    }
}
