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
package org.opencrawling.runtime.ois;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.ois.OisMetadata;
import org.opencrawling.core.ois.OisSidecar;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.ozone.OzoneOutputConnector;
import org.opencrawling.ozone.client.OzoneNativeStorageClient;
import org.opencrawling.ozone.config.OzoneOutputProperties;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.s3.S3OutputConnector;
import org.opencrawling.s3.config.S3OutputProperties;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sidecars the migration writers really store validate against the Open Ingestion Standard document schema.
 *
 * <p>Documents go through the same steps as in a crawl ({@link JobOrchestrator#withOisBlocks}) and then through the
 * Ozone and S3 output connectors; what they write is validated with format assertions on.
 *
 * <p>The schema is the copy in {@code src/test/resources/ois/document.schema.json}. The {@code ois-conformance} CI job
 * runs this test against the OIS repository's current schema instead, by passing its path in the
 * {@code ois.schema.file} system property, so that the spec and OpenCrawling cannot drift apart unnoticed.
 */
class OisSidecarConformanceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        String file = System.getProperty("ois.schema.file");
        String text;
        if (file != null && !file.isBlank()) {
            text = Files.readString(Path.of(file));
        } else {
            try (InputStream in = OisSidecarConformanceTest.class.getResourceAsStream("/ois/document.schema.json")) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(text);
    }

    private static List<String> errors(String json) {
        JsonNode node = JSON.readTree(json);
        List<String> messages = new ArrayList<>();
        for (Error error : schema.validate(node, context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))) {
            messages.add(error.toString());
        }
        return messages;
    }

    /** A connector the way a real one reports itself, with credentials in its URL that must not leak. */
    private static final RepositoryConnector ALFRESCO = new RepositoryConnector() {
        @Override
        public Flux<RepositoryDocument> scan(String basePath) {
            return Flux.empty();
        }

        @Override
        public String getName() {
            return "AlfrescoConnector";
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public String sourceInstance() {
            return "https://admin:s3cret@ecm.example.com:8443/alfresco?alf_ticket=TICKET_1#x";
        }

        @Override
        public String connectorVersion() {
            return "1.0.5";
        }
    };

    private static final ExecutionProvenance AGENT_RUN = ExecutionProvenance.verified("alice", "copilot-agent",
            List.of("legal"), "acme", ExecutionProvenance.SOURCE_MCP, "corr-1");

    /** A document as a CMIS-like source emits it, after the pipeline stamped it. */
    private static RepositoryDocument crawled(String id, DocumentAction action) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("filename", List.of(id + ".pdf"));
        metadata.put("mimeType", List.of("application/pdf"));
        metadata.put("relativePath", List.of("legal/" + id + ".pdf"));
        metadata.put("title", List.of("Master services agreement"));
        metadata.put("author", List.of("Alice Martin", "Marco Bianchi"));
        metadata.put("lastModified", List.of("2026-10-05T20:00:00+02:00"));
        metadata.put("cmis:isLatestVersion", List.of("true"));
        metadata.put("cmis:creationDate", List.of("13/05/2024"));
        metadata.put("cmis.secondaryObjectTypeIds", List.of("P:cm:titled", "P:cm:author"));
        // A source must not be able to forge these.
        metadata.put("ois:provenance.onBehalfOf", List.of("mallory"));
        metadata.put("ois:governance.decision", List.of("ALLOW"));
        SecurityConfig security = new SecurityConfig(true, List.of(
                new PermissionRule("GROUP_LEGAL", "cmis-group", null, "read"),
                new PermissionRule("bob", "cmis-user", "Bob", "deny")));
        RepositoryDocument doc = new RepositoryDocument(id, "s3://claims/" + id,
                action == DocumentAction.DELETE ? null
                        : new ByteArrayInputStream(("%PDF " + id).getBytes(StandardCharsets.UTF_8)),
                metadata, "GROUP_LEGAL", security, Instant.parse("2026-10-05T18:00:00Z"), action);
        return JobOrchestrator.withOisBlocks(doc, ALFRESCO, "job-42", AGENT_RUN);
    }

    private static OzoneNativeStorageClient ozoneClient;

    private static OzoneOutputConnector ozone() throws Exception {
        OzoneOutputProperties properties = new OzoneOutputProperties();
        properties.setVolume("lake");
        properties.setBucket("legal");
        properties.setClientType("NATIVE");
        properties.setSidecarSuffix(".ois.json");
        properties.setOmPort(1); // nothing listens: in-memory store
        properties.setAllowInMemoryFallback(true);
        properties.setTombstoneAction("ARCHIVE_TOMBSTONE");
        ozoneClient = new OzoneNativeStorageClient(properties);
        ozoneClient.connect();
        PipelineProperties pipeline = new PipelineProperties();
        pipeline.setMode(PipelineMode.MIGRATION);
        return new OzoneOutputConnector(properties, ozoneClient, pipeline, new ObjectMapper());
    }

    private static String stored(String suffix) {
        return ozoneClient.getStorage().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(suffix))
                .map(entry -> new String(entry.getValue(), StandardCharsets.UTF_8))
                .findFirst().orElseThrow(() -> new AssertionError("nothing stored ending in " + suffix));
    }

    @Test
    void ozoneUpsertSidecarConformsAndCarriesSourceAndProvenance() throws Exception {
        OzoneOutputConnector connector = ozone();
        connector.send(crawled("msa-7", DocumentAction.UPSERT)).block();

        String sidecar = stored(".ois.json");
        assertThat(errors(sidecar)).isEmpty();

        JsonNode node = JSON.readTree(sidecar);
        assertThat(node.path("content").path("sizeBytes").asLong()).isEqualTo("%PDF msa-7".length());
        assertThat(node.path("content").path("sha256").asString()).hasSize(64);
        assertThat(node.has("contentRef")).isFalse();
        assertThat(node.path("source").path("type").asString()).isEqualTo("alfresco");
        assertThat(node.path("source").path("instance").asString())
                .as("no user info, query or fragment").isEqualTo("https://ecm.example.com:8443/alfresco");
        assertThat(node.path("source").path("connectorVersion").asString()).isEqualTo("1.0.5");
        assertThat(sidecar).doesNotContain("s3cret").doesNotContain("TICKET_1");

        JsonNode metadata = node.path("metadata");
        assertThat(metadata.path("author").isArray()).isTrue();
        assertThat(metadata.path("title").asString()).isEqualTo("Master services agreement");
        assertThat(metadata.path("lastModified").asString()).isEqualTo("2026-10-05T18:00:00Z");
        assertThat(metadata.path("cmis:isLatestVersion").isBoolean()).isTrue();
        assertThat(metadata.has("cmis:creationDate")).isFalse();
        assertThat(metadata.path("cmis:creationDate.raw").get(0).asString()).isEqualTo("13/05/2024");

        JsonNode provenance = metadata.path(OisMetadata.PROVENANCE);
        assertThat(provenance.path("jobId").asString()).isEqualTo("job-42");
        assertThat(provenance.path("triggeredBy").asString()).isEqualTo("mcp-agent");
        assertThat(provenance.path("onBehalfOf").asString()).as("the source's value is replaced").isEqualTo("alice");
        assertThat(provenance.path("actorAgent").asString()).isEqualTo("copilot-agent");
        assertThat(metadata.has(OisMetadata.GOVERNANCE)).as("migrations are not governed; the source's claim is dropped")
                .isFalse();
        assertThat(metadata.has("ois:source.type")).isFalse();

        JsonNode permissions = node.path("security").path("permissions");
        assertThat(node.path("security").path("evaluationPolicy").asString()).isEqualTo("FAIL_CLOSED");
        assertThat(permissions.get(0).has("displayName")).as("absent, not null").isFalse();
        assertThat(permissions.get(0).path("identityType").asString()).as("raw type kept").isEqualTo("cmis-group");
    }

    @Test
    void ozoneTombstoneConformsAndGrantsNothing() throws Exception {
        OzoneOutputConnector connector = ozone();
        connector.send(crawled("msa-8", DocumentAction.UPSERT)).block();
        connector.send(crawled("msa-8", DocumentAction.DELETE)).block();

        String tombstone = ozoneClient.getStorage().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(".tombstones/"))
                .map(entry -> new String(entry.getValue(), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
        assertThat(errors(tombstone)).isEmpty();
        JsonNode node = JSON.readTree(tombstone);
        assertThat(node.path("action").asString()).isEqualTo("DELETE");
        assertThat(node.path("source").path("instance").asString()).isEqualTo("https://ecm.example.com:8443/alfresco");
        assertThat(node.path("security").path("permissions").isEmpty()).isTrue();
        assertThat(tombstone).doesNotContain("public");
    }

    @Test
    void s3SidecarAndTombstoneConform() throws Exception {
        S3OutputProperties properties = new S3OutputProperties();
        properties.setBucket("lake");
        properties.setRegion("eu-west-1");
        properties.setSidecarSuffix(".ois.json");
        properties.setStorageClass("STANDARD");
        properties.setTombstoneAction("ARCHIVE_TOMBSTONE");
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"etag-1\"").build());
        PipelineProperties pipeline = new PipelineProperties();
        pipeline.setMode(PipelineMode.MIGRATION);
        S3OutputConnector connector = new S3OutputConnector(properties, s3, pipeline, new ObjectMapper());

        connector.send(crawled("msa-9", DocumentAction.UPSERT)).block();
        connector.send(crawled("msa-9", DocumentAction.DELETE)).block();

        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodies = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3, times(4)).putObject(requests.capture(), bodies.capture());
        int checked = 0;
        for (int i = 0; i < requests.getAllValues().size(); i++) {
            if (requests.getAllValues().get(i).key().endsWith(".ois.json")) {
                String json = new String(bodies.getAllValues().get(i).contentStreamProvider().newStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                assertThat(errors(json)).as(requests.getAllValues().get(i).key()).isEmpty();
                checked++;
            }
        }
        assertThat(checked).as("the sidecar and the tombstone").isEqualTo(2);
    }

    @Test
    void governedMetadataConforms() throws Exception {
        Map<String, List<String>> metadata = OisMetadata.withGovernance(crawled("msa-10", DocumentAction.UPSERT).metadata(),
                OisMetadata.Decision.REDACT, List.of("EMAIL", "IBAN"), "pii-masking", Instant.now());
        OisSidecar sidecar = OisSidecar.upsert("msa-10", "s3://lake/msa-10", "2026-10-05T18:00:00Z",
                OisSidecar.source(metadata, "alfresco", "urn:x", null, null),
                new OisSidecar.Content("application/pdf", null, null, null, 9L, null, null, null, null, null, null, null),
                metadata, SecurityConfig.none(), null);
        String json = new ObjectMapper().writeValueAsString(sidecar);

        assertThat(errors(json)).isEmpty();
        JsonNode governance = JSON.readTree(json).path("metadata").path(OisMetadata.GOVERNANCE);
        assertThat(governance.path("decision").asString()).isEqualTo("REDACT");
        assertThat(governance.path("redacted").asBoolean()).isTrue();
        assertThat(governance.path("redactionCategories")).hasSize(2);
    }

    @Test
    void theValidatorRejectsTheOldFormatAndBadDates() {
        String old = """
                {"id":"d","action":"UPSERT","source":{"type":"repository","originalUri":"s3://c/d"},
                 "contentRef":{"key":"d","mimeType":"application/pdf","contentLength":1,"checksumSha256":"ab"},
                 "metadata":{"title":["x"]},"security":{"inheritanceEnabled":false,"permissions":[
                 {"identity":"g","identityType":"group","displayName":null,"access":"read"}]}}""";
        assertThat(errors(old)).as("no content, no source.instance, null displayName").hasSizeGreaterThanOrEqualTo(3);

        String badDate = """
                {"id":"d","source":{"type":"t","instance":"urn:x"},"content":{"mimeType":"text/plain"},
                 "metadata":{"lastModified":"yesterday"},"security":{"inheritanceEnabled":false,"permissions":[]}}""";
        assertThat(errors(badDate)).as("format assertions are on").isNotEmpty();
    }
}
