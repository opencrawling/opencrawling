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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapperRegistry;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFSource;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.mcf.model.McfPipelineStage;
import org.opencrawling.migrator.mcf.oc.OpenCrawlingWriter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of {@code --map-connector}: a connection with no registered mapper (e.g. a
 * ManifoldCF Solr output, which has no OpenCrawling target) can still be migrated by manually
 * redirecting it to an OpenCrawling connector the user already created by hand.
 */
class ConnectorOverrideTest {

    private static final String SOLR_CLASS = "org.apache.manifoldcf.agents.output.solr.SolrConnector";

    @Test
    void plan_overriddenConnection_isSupportedWithNoTarget() {
        MigrationEngine engine = engineWithOverride(Map.of("Solr_Output", "Qdrant_Vector_Store"));
        MigrationSnapshot snapshot = new MigrationSnapshot(
            List.of(solrConnection()), List.of());

        MigrationPlan plan = engine.plan(snapshot);

        assertThat(plan.connections()).hasSize(1);
        var mapping = plan.connections().get(0).mapping();
        assertThat(mapping.supported()).isTrue();
        assertThat(mapping.target()).isNull();
        assertThat(mapping.overrideTargetName()).isEqualTo("Qdrant_Vector_Store");
    }

    @Test
    void plan_jobReferencingOverriddenOutput_isSupportedWithOverrideNameSubstituted() {
        MigrationEngine engine = engineWithOverride(Map.of("Solr_Output", "Qdrant_Vector_Store"));
        McfPipelineStage outputStage = new McfPipelineStage(0, -1, true, "Solr_Output", null, emptyNode());
        McfJob job = new McfJob("1", "Crawl to Solr", "SomeFileRepo", null, List.of(outputStage), 0,
            null, null, null, null, null, null, null, null, List.of());
        McfConnection repo = new McfConnection(McfConnectionKind.REPOSITORY, "SomeFileRepo", null,
            "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector", 10, Map.of(), null, List.of());

        MigrationSnapshot snapshot = new MigrationSnapshot(List.of(repo, solrConnection()), List.of(job));
        MigrationPlan plan = engine.plan(snapshot);

        assertThat(plan.jobs()).hasSize(1);
        var jobMapping = plan.jobs().get(0).mapping();
        assertThat(jobMapping.supported()).isTrue();
        assertThat(jobMapping.target().outputConnector()).isEqualTo("Qdrant_Vector_Store");
    }

    @Test
    void apply_overriddenConnection_isNeverUpserted() {
        MigrationEngine engine = engineWithOverride(Map.of("Solr_Output", "Qdrant_Vector_Store"));
        MigrationSnapshot snapshot = new MigrationSnapshot(List.of(solrConnection()), List.of());
        MigrationPlan plan = engine.plan(snapshot);

        ApplyOutcome outcome = engine.apply(plan);

        assertThat(outcome.connectionResults()).isEmpty();
    }

    private MigrationEngine engineWithOverride(Map<String, String> overrides) {
        MigrationOptions options = new MigrationOptions(
            "http://mcf", null, null, "http://oc", null, true, "report.md", 384,
            List.of(), List.of(), false, 30, overrides);
        ManifoldCFSource noopSource = new ManifoldCFSource() {
            public List<McfConnection> listRepositoryConnections() { return List.of(); }
            public List<McfConnection> listOutputConnections() { return List.of(); }
            public List<McfConnection> listTransformationConnections() { return List.of(); }
            public List<McfConnection> listAuthorityConnections() { return List.of(); }
            public List<McfJob> listJobs() { return List.of(); }
        };
        OpenCrawlingWriter writer = new OpenCrawlingWriter("http://localhost:1", null, null, 5);
        return new MigrationEngine(noopSource, new ConnectorMapperRegistry(), writer, options);
    }

    private McfConnection solrConnection() {
        return new McfConnection(McfConnectionKind.OUTPUT, "Solr_Output", null, SOLR_CLASS, 10, Map.of(), null, List.of());
    }

    private ObjectNode emptyNode() {
        return new ObjectMapper().createObjectNode();
    }
}
