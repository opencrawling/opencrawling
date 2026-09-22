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
package org.opencrawling.migrator.mcf.ois;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.engine.JobPlanEntry;
import org.opencrawling.migrator.mcf.job.JobMappingResult;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mapping.filesystem.FileConnectorMapper;
import org.opencrawling.migrator.mcf.mapping.opensearch.ElasticSearchToOpenSearch2Mapper;
import org.opencrawling.migrator.mcf.mapping.vespa.VespaOutputConnectorMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.mcf.model.McfPipelineStage;
import org.opencrawling.migrator.mcf.mcf.model.McfScheduleRecord;
import org.opencrawling.sdk.models.JobRequest;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OisJobRendererTest {

    private static final String REAL_SECRET = "sekrit-password-abc123";

    private final OisJobRenderer renderer = new OisJobRenderer();
    private final MigrationOptions options = new MigrationOptions(
        "http://mcf", null, null, "http://oc", null, false, "report.md", 384, List.of(), List.of(), false, 30, Map.of());

    @Test
    @SuppressWarnings("unchecked")
    void convert_supportedJob_producesExpectedOisStructure() {
        Map<String, ConnectorMappingResult> mappingsByName = buildMappings(false);
        JobPlanEntry entry = jobEntry("SharePoint drive to Vespa", "SharepointDrive", "Vespa Federated Index");

        OisConversionResult result = renderer.convert(entry, mappingsByName);
        Map<String, Object> doc = result.document();

        assertThat(doc.get("version")).isEqualTo("ois/v1alpha1");
        Map<String, Object> metadata = (Map<String, Object>) doc.get("metadata");
        assertThat(metadata.get("name")).isEqualTo("sharepoint-drive-to-vespa");

        Map<String, Object> spec = (Map<String, Object>) doc.get("spec");
        assertThat(spec.get("schedule")).isEqualTo("0 0 * * *");
        assertThat(spec).doesNotContainKey("pipeline");

        Map<String, Object> connector = (Map<String, Object>) spec.get("connector");
        assertThat(connector.get("type")).isEqualTo("filesystem-source");

        Map<String, Object> output = (Map<String, Object>) spec.get("output");
        assertThat(output.get("type")).isEqualTo("vespa");
        assertThat(result.fileBaseName()).isEqualTo("sharepoint-drive-to-vespa");
    }

    @Test
    void convert_notesExplainScheduleAndPipelineOmission() {
        Map<String, ConnectorMappingResult> mappingsByName = buildMappings(false);
        JobPlanEntry entry = jobEntry("SharePoint drive to Vespa", "SharepointDrive", "Vespa Federated Index");

        OisConversionResult result = renderer.convert(entry, mappingsByName);

        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("spec.schedule defaulted"));
        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("spec.pipeline omitted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_simpleScheduleRecord_translatesToRealCronInstead() {
        Map<String, ConnectorMappingResult> mappingsByName = buildMappings(false);
        JobRequest target = JobRequest.builder()
            .name("SharePoint drive to Vespa").repositoryConnector("SharepointDrive").outputConnector("Vespa Federated Index").build();
        McfScheduleRecord schedule = new McfScheduleRecord(List.of(1, 3), List.of(2), List.of(30), List.of(), List.of(), null);
        McfPipelineStage outputStage = new McfPipelineStage(0, -1, true, "Vespa Federated Index", null, new ObjectMapper().createObjectNode());
        McfJob job = new McfJob("1", "SharePoint drive to Vespa", "SharepointDrive", null, List.of(outputStage), 0,
            null, null, null, null, null, null, null, null, List.of(schedule));
        JobPlanEntry entry = new JobPlanEntry(job, JobMappingResult.supported(target, List.of()));

        OisConversionResult result = renderer.convert(entry, mappingsByName);
        Map<String, Object> spec = (Map<String, Object>) result.document().get("spec");

        assertThat(spec.get("schedule")).isEqualTo("30 2 * * 1,3");
        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("spec.schedule translated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void convert_redactsSecretsInOutputConfig() {
        Map<String, ConnectorMappingResult> mappingsByName = buildMappings(true);
        JobPlanEntry entry = jobEntry("es job", "SharepointDrive", "es-1");

        OisConversionResult result = renderer.convert(entry, mappingsByName);
        Map<String, Object> spec = (Map<String, Object>) result.document().get("spec");
        Map<String, Object> output = (Map<String, Object>) spec.get("output");
        Map<String, Object> config = (Map<String, Object>) output.get("config");

        assertThat(config.get("opensearch2Password")).isEqualTo("***REDACTED***");
        assertThat(result.document().toString()).doesNotContain(REAL_SECRET);
    }

    @Test
    void convert_unresolvedTypeName_fallsBackToClassNameWithNote() {
        JobRequest target = JobRequest.builder()
            .name("mystery job").repositoryConnector("SharepointDrive").outputConnector("Mystery Output").build();
        JobPlanEntry entry = new JobPlanEntry(dummyJob("mystery job", "SharepointDrive", "Mystery Output"),
            JobMappingResult.supported(target, List.of()));

        Map<String, ConnectorMappingResult> mappingsByName = new LinkedHashMap<>(buildMappings(false));
        mappingsByName.put(McfConnection.key(McfConnectionKind.OUTPUT, "Mystery Output"), ConnectorMappingResult.supported(
            org.opencrawling.sdk.models.ConnectorRequest.builder()
                .name("Mystery Output").type("output").className("org.opencrawling.mystery.MysteryOutputConnector")
                .configuration(Map.of()).build(),
            List.of()));

        OisConversionResult result = renderer.convert(entry, mappingsByName);
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) result.document().get("spec");
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) spec.get("output");

        assertThat(output.get("type")).isEqualTo("org.opencrawling.mystery.MysteryOutputConnector");
        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("no short OIS-style identifier"));
    }

    @Test
    void renderYaml_producesParsableYamlMatchingDocument() {
        Map<String, ConnectorMappingResult> mappingsByName = buildMappings(false);
        JobPlanEntry entry = jobEntry("SharePoint drive to Vespa", "SharepointDrive", "Vespa Federated Index");
        OisConversionResult result = renderer.convert(entry, mappingsByName);

        String yamlText = renderer.renderYaml(result.document());
        Object parsed = new Yaml().load(yamlText);

        assertThat(parsed).isEqualTo(result.document());
    }

    @Test
    void renderJson_producesParsableJsonMatchingDocument() throws Exception {
        Map<String, ConnectorMappingResult> mappingsByName = buildMappings(false);
        JobPlanEntry entry = jobEntry("SharePoint drive to Vespa", "SharepointDrive", "Vespa Federated Index");
        OisConversionResult result = renderer.convert(entry, mappingsByName);

        String jsonText = renderer.renderJson(result.document());
        JsonNode parsed = new ObjectMapper().readTree(jsonText);

        assertThat(parsed.get("version").asText()).isEqualTo("ois/v1alpha1");
        assertThat(parsed.get("spec").get("connector").get("type").asText()).isEqualTo("filesystem-source");
    }

    private Map<String, ConnectorMappingResult> buildMappings(boolean withElasticsearch) {
        Map<String, ConnectorMappingResult> byName = new LinkedHashMap<>();

        FileConnectorMapper fileMapper = new FileConnectorMapper();
        McfConnection sharepoint = new McfConnection(McfConnectionKind.REPOSITORY, "SharepointDrive", "Company File Share",
            "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector", 10, Map.of(), null, List.of());
        byName.put(sharepoint.lookupKey(), fileMapper.map(sharepoint, options));

        VespaOutputConnectorMapper vespaMapper = new VespaOutputConnectorMapper();
        Map<String, String> vespaConfig = new LinkedHashMap<>();
        vespaConfig.put("vespaEndpoint", "http://vespa:8080");
        McfConnection vespa = new McfConnection(McfConnectionKind.OUTPUT, "Vespa Federated Index", "Vespa Federated Index",
            "org.apache.manifoldcf.agents.output.vespa.VespaOutputConnector", 10, vespaConfig, null, List.of());
        byName.put(vespa.lookupKey(), vespaMapper.map(vespa, options));

        if (withElasticsearch) {
            ElasticSearchToOpenSearch2Mapper esMapper = new ElasticSearchToOpenSearch2Mapper();
            Map<String, String> esConfig = new LinkedHashMap<>();
            esConfig.put("SERVERLOCATION", "http://es:9200");
            esConfig.put("USERNAME", "elastic");
            esConfig.put("PASSWORD", REAL_SECRET);
            McfConnection es = new McfConnection(McfConnectionKind.OUTPUT, "es-1", "es-1",
                "org.apache.manifoldcf.agents.output.elasticsearch.ElasticSearchConnector", 10, esConfig, null, List.of());
            byName.put(es.lookupKey(), esMapper.map(es, options));
        }

        return byName;
    }

    private JobPlanEntry jobEntry(String name, String repositoryConnector, String outputConnector) {
        JobRequest target = JobRequest.builder()
            .name(name).repositoryConnector(repositoryConnector).outputConnector(outputConnector).path("/mnt/drive-a").build();
        return new JobPlanEntry(dummyJob(name, repositoryConnector, outputConnector), JobMappingResult.supported(target, List.of()));
    }

    /** A minimal but structurally real McfJob — OisJobRenderer reads the output stage from here, not from the JobRequest. */
    private McfJob dummyJob(String description, String repositoryConnector, String outputConnector) {
        ObjectNode rawSpec = new ObjectMapper().createObjectNode();
        McfPipelineStage outputStage = new McfPipelineStage(0, -1, true, outputConnector, null, rawSpec);
        return new McfJob(String.valueOf(description.hashCode()), description, repositoryConnector, null, List.of(outputStage), 0,
            null, null, null, null, null, null, null, null, List.of());
    }
}
