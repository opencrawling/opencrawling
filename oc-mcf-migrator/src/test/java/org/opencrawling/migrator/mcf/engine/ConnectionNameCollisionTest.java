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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.job.JobMappingResult;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapperRegistry;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.mcf.model.McfPipelineStage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ManifoldCF only guarantees connection-name uniqueness within one kind's own registry — a
 * repository connection and an output connection can legally share a name. Before {@code
 * McfConnection.lookupKey()} existed, {@code MigrationEngine.plan()} keyed its internal maps by
 * name alone, so the second connection extracted with a shared name silently overwrote the
 * first's entry, corrupting job resolution for whichever connector lost the collision.
 */
class ConnectionNameCollisionTest {

    private static final String FILE_CLASS = "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector";
    private static final String VESPA_CLASS = "org.apache.manifoldcf.agents.output.vespa.VespaOutputConnector";
    private static final String SHARED_NAME = "Shared";

    private final MigrationEngine engine = new MigrationEngine(
        null, new ConnectorMapperRegistry(), null,
        new MigrationOptions("http://mcf", null, null, "http://oc", null, false, "report.md", 384,
            List.of(), List.of(), false, 30, Map.of()));

    @Test
    void bothConnectionsKeepTheirOwnMapping_despiteSharingAName() {
        McfConnection repo = new McfConnection(McfConnectionKind.REPOSITORY, SHARED_NAME, null, FILE_CLASS, 10, Map.of(), null, List.of());
        McfConnection output = new McfConnection(McfConnectionKind.OUTPUT, SHARED_NAME, null, VESPA_CLASS, 10, Map.of(), null, List.of());

        MigrationPlan plan = engine.plan(new MigrationSnapshot(List.of(repo, output), List.of()));

        assertThat(plan.connections()).hasSize(2);
        ConnectionPlanEntry repoEntry = plan.connections().stream().filter(e -> e.source().kind() == McfConnectionKind.REPOSITORY).findFirst().orElseThrow();
        ConnectionPlanEntry outputEntry = plan.connections().stream().filter(e -> e.source().kind() == McfConnectionKind.OUTPUT).findFirst().orElseThrow();

        assertThat(repoEntry.mapping().supported()).isTrue();
        assertThat(repoEntry.mapping().target().className()).isEqualTo("org.opencrawling.filesystem.FileSystemRepositoryConnector");
        assertThat(outputEntry.mapping().supported()).isTrue();
        assertThat(outputEntry.mapping().target().className()).isEqualTo("org.opencrawling.vespa.VespaOutputConnector");
    }

    @Test
    void jobResolvesTheRepositorySideOfACollidingName_notTheOutputSide() {
        McfConnection repo = new McfConnection(McfConnectionKind.REPOSITORY, SHARED_NAME, null, FILE_CLASS, 10, Map.of(), null, List.of());
        McfConnection output = new McfConnection(McfConnectionKind.OUTPUT, SHARED_NAME, null, VESPA_CLASS, 10, Map.of(), null, List.of());
        McfPipelineStage outputStage = new McfPipelineStage(0, -1, true, SHARED_NAME, null, emptyNode());
        McfJob job = new McfJob("1", "Job over shared name", SHARED_NAME, null, List.of(outputStage), 0,
            null, null, null, null, null, null, null, null, List.of());

        MigrationPlan plan = engine.plan(new MigrationSnapshot(List.of(repo, output), List.of(job)));

        assertThat(plan.jobs()).hasSize(1);
        JobMappingResult mapping = plan.jobs().get(0).mapping();
        assertThat(mapping.supported()).isTrue();
        assertThat(mapping.target().repositoryConnector()).isEqualTo(SHARED_NAME);
        assertThat(mapping.target().outputConnector()).isEqualTo(SHARED_NAME);
    }

    private ObjectNode emptyNode() {
        return new ObjectMapper().createObjectNode();
    }
}
