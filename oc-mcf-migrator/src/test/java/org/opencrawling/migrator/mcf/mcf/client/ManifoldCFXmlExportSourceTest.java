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
package org.opencrawling.migrator.mcf.mcf.client;

import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.engine.MigrationEngine;
import org.opencrawling.migrator.mcf.engine.MigrationPlan;
import org.opencrawling.migrator.mcf.engine.MigrationSnapshot;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapperRegistry;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ManifoldCFXmlExportSource} against a hand-built XML fixture mirroring this
 * project's existing JSON fixtures' content (same connections/job) — see
 * {@link org.opencrawling.migrator.mcf.mcf.parse.McfXmlToJsonAdapter}'s javadoc for the caveat that
 * the underlying node-type vocabulary this assumes is not independently confirmed against a real
 * ManifoldCF {@code ExportConfiguration} output.
 */
class ManifoldCFXmlExportSourceTest {

    private final ManifoldCFXmlExportSource source = new ManifoldCFXmlExportSource(fixturePath());

    @Test
    void listRepositoryConnections_parsesBothConnections() {
        List<McfConnection> connections = source.listRepositoryConnections();

        assertThat(connections).extracting(McfConnection::name).containsExactly("SharepointDrive", "Alfresco HR Data");
        McfConnection sharepoint = connections.get(0);
        assertThat(sharepoint.className()).isEqualTo("org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector");
        assertThat(sharepoint.description()).isEqualTo("Company File Share");

        McfConnection alfresco = connections.get(1);
        assertThat(alfresco.configuration()).containsEntry("hostname", "repo2.localhost");
    }

    @Test
    void listOutputConnections_parsesConfigurationParameters() {
        List<McfConnection> connections = source.listOutputConnections();

        assertThat(connections).hasSize(1);
        McfConnection vespa = connections.get(0);
        assertThat(vespa.name()).isEqualTo("Vespa Federated Index");
        assertThat(vespa.className()).isEqualTo("org.apache.manifoldcf.agents.output.vespa.VespaOutputConnector");
        assertThat(vespa.configuration()).containsEntry("vespaEndpoint", "http://vespa:8080");
    }

    @Test
    void listJobs_parsesPipelineStageAndDocumentSpecification() {
        List<McfJob> jobs = source.listJobs();

        assertThat(jobs).hasSize(1);
        McfJob job = jobs.get(0);
        assertThat(job.description()).isEqualTo("SharePoint drive to Vespa");
        assertThat(job.repositoryConnectionName()).isEqualTo("SharepointDrive");
        assertThat(job.outputStages()).hasSize(1);
        assertThat(job.outputStages().get(0).connectionName()).isEqualTo("Vespa Federated Index");
    }

    @Test
    void listAuthorityConnections_andTransformationConnections_areEmptyWhenAbsentFromExport() {
        assertThat(source.listAuthorityConnections()).isEmpty();
        assertThat(source.listTransformationConnections()).isEmpty();
    }

    @Test
    void fullEngine_plansTheXmlExportSuccessfully() {
        MigrationOptions options = new MigrationOptions(
            "file://export.xml", null, null, "http://oc", null, false, "report.md", 384,
            List.of(), List.of(), false, 30, java.util.Map.of());
        MigrationEngine engine = new MigrationEngine(source, new ConnectorMapperRegistry(), null, options);

        MigrationSnapshot snapshot = engine.extract();
        MigrationPlan plan = engine.plan(snapshot);

        assertThat(plan.connections()).hasSize(3);
        assertThat(plan.connections()).filteredOn(e -> e.mapping().supported()).hasSize(2);
        assertThat(plan.jobs()).hasSize(1);
        assertThat(plan.jobs().get(0).mapping().supported()).isTrue();
    }

    private static Path fixturePath() {
        try {
            return Paths.get(ManifoldCFXmlExportSourceTest.class.getClassLoader()
                .getResource("fixtures/mcf-xml/export.xml").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
