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
package org.opencrawling.migrator.mcf.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapperRegistry;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFClient;
import org.opencrawling.migrator.mcf.oc.OpenCrawlingWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the full extract → plan → apply pipeline offline, against two {@code HttpServer} fakes
 * shaped like real ManifoldCF and OpenCrawling responses, over the fixtures in {@code
 * src/test/resources/fixtures/mcf/} — a faithful (if config-trimmed) copy of this project's own
 * real live ManifoldCF configuration: 4 repository connections, 5 output connections, 1
 * transformation, 0 authorities, 5 jobs. Per the connector/job-level matching rule, four
 * connectors have registered mappers today (stock filesystem, this project's own Vespa output
 * connector, and the two stock-Elasticsearch outputs via the ES→OpenSearch2 mapper), and exactly
 * one of the five jobs references only supported connectors — this test's expected numbers are not
 * arbitrary, they're what actually happens when this real dataset is run through this real tool.
 *
 * <p><b>Naming note:</b> deliberately named {@code ...Test}, not {@code ...IT} — this project has
 * no {@code maven-failsafe-plugin} configured, so a {@code *IT.java} file is silently never run by
 * plain {@code mvn test} (Surefire's default include pattern excludes it). This test needs no
 * separate integration-test lifecycle (no Docker, no external services — just two in-process
 * {@code HttpServer} fakes), so there's no reason to use the {@code IT} suffix and every reason
 * not to, given the silent-skip risk.
 */
class MigrationEngineAcceptanceTest {

    private static HttpServer mcfServer;
    private static HttpServer ocServer;
    private static List<String> capturedConnectorPosts;
    private static List<String> capturedJobPosts;
    private static MigrationEngine engine;

    @BeforeAll
    static void setUp() throws IOException {
        mcfServer = HttpServer.create(new InetSocketAddress(0), 0);
        int mcfPort = mcfServer.getAddress().getPort();
        serveFixture(mcfServer, "/repositoryconnections", "repositoryconnections.json");
        serveFixture(mcfServer, "/outputconnections", "outputconnections.json");
        serveFixture(mcfServer, "/transformationconnections", "transformationconnections.json");
        serveFixture(mcfServer, "/authorityconnections", "authorityconnections.json");
        serveFixture(mcfServer, "/jobs", "jobs.json");
        mcfServer.start();

        capturedConnectorPosts = new CopyOnWriteArrayList<>();
        capturedJobPosts = new CopyOnWriteArrayList<>();
        ocServer = HttpServer.create(new InetSocketAddress(0), 0);
        int ocPort = ocServer.getAddress().getPort();
        ocServer.createContext("/api/connectors", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                capturedConnectorPosts.add(readBody(exchange));
                respond(exchange, 201, "");
            } else {
                respond(exchange, 200, "[]");
            }
        });
        ocServer.createContext("/api/jobs", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                capturedJobPosts.add(readBody(exchange));
                respond(exchange, 201, "");
            } else {
                // OpenCrawlingWriter.upsertJob always lists first to dedup by name; empty means "always create".
                respond(exchange, 200, "[]");
            }
        });
        ocServer.start();

        ManifoldCFClient mcfClient = new ManifoldCFClient("http://localhost:" + mcfPort, null, null, 5);
        ConnectorMapperRegistry registry = new ConnectorMapperRegistry();
        OpenCrawlingWriter writer = new OpenCrawlingWriter("http://localhost:" + ocPort, null, null, 5);
        MigrationOptions options = new MigrationOptions(
            "http://localhost:" + mcfPort, null, null, "http://localhost:" + ocPort, null,
            true, "report.md", 384, List.of(), List.of(), false, 5, Map.of());
        engine = new MigrationEngine(mcfClient, registry, writer, options);
    }

    @AfterAll
    static void tearDown() {
        if (mcfServer != null) {
            mcfServer.stop(0);
        }
        if (ocServer != null) {
            ocServer.stop(0);
        }
    }

    @Test
    void plan_matchesExactAcceptanceNumbers() {
        MigrationSnapshot snapshot = engine.extract();
        assertThat(snapshot.connections()).hasSize(10); // 4 repo + 5 output + 1 transformation
        assertThat(snapshot.jobs()).hasSize(5);

        MigrationPlan plan = engine.plan(snapshot);

        long connectionsMigrated = plan.connections().stream().filter(e -> e.mapping().supported()).count();
        assertThat(connectionsMigrated).isEqualTo(4);
        assertThat(plan.connections()).filteredOn(e -> e.mapping().supported())
            .extracting(e -> e.source().name())
            .containsExactlyInAnyOrder("SharepointDrive", "Vespa Federated Index", "es-1", "es-2");

        long jobsMigrated = plan.jobs().stream().filter(e -> e.mapping().supported()).count();
        assertThat(jobsMigrated).isEqualTo(1);
        assertThat(plan.jobs()).filteredOn(e -> e.mapping().supported())
            .extracting(e -> e.source().description())
            .containsExactly("SharePoint drive to Vespa");
    }

    @Test
    void plan_skippedJobs_nameTheirSpecificBlockingConnector() {
        MigrationPlan plan = engine.plan(engine.extract());

        assertThat(blockingReasonFor(plan, "HR Documents to Vespa")).contains("Alfresco HR Data (repository)");
        assertThat(blockingReasonFor(plan, "Legal Documents to Vespa")).contains("Alfresco Legal Documents (repository)");
        assertThat(blockingReasonFor(plan, "Mfiles to Vespa")).contains("Mfiles Source Repository (repository)");
        assertThat(blockingReasonFor(plan, "Migration From SharePoint to Alfresco")).contains("Alfresco (output)");
    }

    @Test
    void plan_theOneMigratableJob_carriesAScopeChangeNoteForItsDroppedFilters() {
        MigrationPlan plan = engine.plan(engine.extract());
        JobPlanEntry migratable = plan.jobs().stream()
            .filter(e -> e.source().description().equals("SharePoint drive to Vespa"))
            .findFirst().orElseThrow();

        assertThat(migratable.mapping().supported()).isTrue();
        assertThat(migratable.mapping().target().path()).isEqualTo("/mnt/drive-a/files");
        assertThat(migratable.mapping().notes())
            .anyMatch(n -> n.kind() == org.opencrawling.migrator.mcf.mapping.FieldNoteKind.SCOPE_CHANGE);
    }

    @Test
    void plan_esConnections_migrateWithRuntimeRiskNote() {
        MigrationPlan plan = engine.plan(engine.extract());
        ConnectionPlanEntry es1 = plan.connections().stream()
            .filter(e -> e.source().name().equals("es-1")).findFirst().orElseThrow();

        assertThat(es1.mapping().supported()).isTrue();
        assertThat(es1.mapping().target().className()).isEqualTo("org.opencrawling.opensearch2.OpenSearch2OutputConnector");
        assertThat(es1.mapping().notes())
            .anyMatch(n -> n.kind() == org.opencrawling.migrator.mcf.mapping.FieldNoteKind.RUNTIME_RISK);
    }

    @Test
    void apply_sendsExactlyTheSupportedConnectorsAndJob() {
        MigrationPlan plan = engine.plan(engine.extract());
        capturedConnectorPosts.clear();
        capturedJobPosts.clear();

        ApplyOutcome outcome = engine.apply(plan);

        assertThat(capturedConnectorPosts).hasSize(4);
        assertThat(capturedJobPosts).hasSize(1);
        assertThat(outcome.connectionResults()).hasSize(4);
        assertThat(outcome.connectionResults().values()).allMatch(ApplyOutcome.ApplyResult::success);
        assertThat(outcome.jobResults()).hasSize(1);
        assertThat(outcome.jobResults().values()).allMatch(ApplyOutcome.ApplyResult::success);

        String vespaConnectorBody = capturedConnectorPosts.stream()
            .filter(body -> body.contains("Vespa Federated Index")).findFirst().orElseThrow();
        assertThat(vespaConnectorBody).contains("\"vespaDimensions\":\"384\"");

        String jobBody = capturedJobPosts.get(0);
        assertThat(jobBody).contains("SharePoint drive to Vespa").contains("SharepointDrive").contains("Vespa Federated Index");
    }

    private static List<String> blockingReasonFor(MigrationPlan plan, String jobDescription) {
        return plan.jobs().stream()
            .filter(e -> e.source().description().equals(jobDescription))
            .findFirst().orElseThrow()
            .mapping().blockingConnectors();
    }

    private static void serveFixture(HttpServer server, String path, String fixtureFile) {
        String body = readFixture(fixtureFile);
        server.createContext(path, exchange -> respond(exchange, 200, body));
    }

    private static String readFixture(String fixtureFile) {
        try (InputStream in = MigrationEngineAcceptanceTest.class.getClassLoader()
                .getResourceAsStream("fixtures/mcf/" + fixtureFile)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + fixtureFile);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixture: " + fixtureFile, e);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
