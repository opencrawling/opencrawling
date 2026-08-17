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
package org.opencrawling.migrator.mcf.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mapping.FieldNoteKind;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.mcf.model.McfPipelineStage;
import org.opencrawling.sdk.models.ConnectorRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobMapperTest {

    private static final String FILE_CONNECTOR_CLASS = "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector";
    private static final String ALFRESCO_CLASS = "org.apache.manifoldcf.crawler.connectors.alfrescowebscript.AlfrescoConnector";
    private static final String VESPA_CLASS = "org.apache.manifoldcf.agents.output.vespa.VespaOutputConnector";

    private final JobMapper jobMapper = new JobMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MigrationOptions options = new MigrationOptions(
        "http://mcf", null, null, "http://oc", null, false, "report.md", 384, List.of(), List.of(), false, 30, Map.of());

    @Test
    void unsupportedRepositoryConnection_jobSkippedNamingIt() throws Exception {
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "Alfresco HR Data", ALFRESCO_CLASS);
        McfConnection output = connection(McfConnectionKind.OUTPUT, "Vespa Federated Index", VESPA_CLASS);
        McfJob job = job("HR Documents to Vespa", "Alfresco HR Data",
            List.of(stage(0, -1, true, "Vespa Federated Index")), specWithPath("/irrelevant"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "Alfresco HR Data"), repo, key(McfConnectionKind.OUTPUT, "Vespa Federated Index"), output),
            Map.of(key(McfConnectionKind.REPOSITORY, "Alfresco HR Data"), ConnectorMappingResult.unsupported("no mapper"),
                key(McfConnectionKind.OUTPUT, "Vespa Federated Index"), supported("Vespa Federated Index")),
            options);

        assertThat(result.supported()).isFalse();
        assertThat(result.blockingConnectors()).containsExactly("Alfresco HR Data (repository)");
    }

    @Test
    void unsupportedOutputConnection_jobSkippedNamingIt() throws Exception {
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "SharepointDrive", FILE_CONNECTOR_CLASS);
        McfJob job = job("Migration From SharePoint to Alfresco", "SharepointDrive",
            List.of(stage(0, -1, true, "Alfresco")), specWithPath("/mnt/drive-a/files"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), repo),
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), supported("SharepointDrive"),
                key(McfConnectionKind.OUTPUT, "Alfresco"), ConnectorMappingResult.unsupported("no mapper")),
            options);

        assertThat(result.supported()).isFalse();
        assertThat(result.blockingConnectors()).containsExactly("Alfresco (output)");
    }

    @Test
    void bothSidesSupported_withFilters_migratesWithScopeChangeNote() throws Exception {
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "SharepointDrive", FILE_CONNECTOR_CLASS);
        McfJob job = job("SharePoint drive to Vespa", "SharepointDrive",
            List.of(stage(0, -1, true, "Vespa Federated Index")),
            specWithPathAndFilters("/mnt/drive-a/files"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), repo),
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), supported("SharepointDrive"),
                key(McfConnectionKind.OUTPUT, "Vespa Federated Index"), supported("Vespa Federated Index")),
            options);

        assertThat(result.supported()).isTrue();
        assertThat(result.target().repositoryConnector()).isEqualTo("SharepointDrive");
        assertThat(result.target().outputConnector()).isEqualTo("Vespa Federated Index");
        assertThat(result.target().path()).isEqualTo("/mnt/drive-a/files");
        assertThat(result.notes()).anySatisfy(n -> assertThat(n.kind()).isEqualTo(FieldNoteKind.SCOPE_CHANGE));
    }

    @Test
    void bothSidesSupported_noFilters_noScopeChangeNote() throws Exception {
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "SharepointDrive", FILE_CONNECTOR_CLASS);
        McfJob job = job("SharePoint drive to Vespa", "SharepointDrive",
            List.of(stage(0, -1, true, "Vespa Federated Index")), specWithPath("/mnt/drive-a/files"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), repo),
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), supported("SharepointDrive"),
                key(McfConnectionKind.OUTPUT, "Vespa Federated Index"), supported("Vespa Federated Index")),
            options);

        assertThat(result.supported()).isTrue();
        assertThat(result.notes()).noneMatch(n -> n.kind() == FieldNoteKind.SCOPE_CHANGE);
    }

    @Test
    void multipleOutputStages_unsupported() throws Exception {
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "SharepointDrive", FILE_CONNECTOR_CLASS);
        McfJob job = job("Two outputs", "SharepointDrive",
            List.of(stage(0, -1, true, "Vespa Federated Index"), stage(1, -1, true, "Alfresco")),
            specWithPath("/data"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), repo),
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), supported("SharepointDrive"),
                key(McfConnectionKind.OUTPUT, "Vespa Federated Index"), supported("Vespa Federated Index"),
                key(McfConnectionKind.OUTPUT, "Alfresco"), supported("Alfresco")),
            options);

        assertThat(result.supported()).isFalse();
        assertThat(result.unsupportedReason()).contains("2 output stage(s)");
    }

    @Test
    void multipleTransformationStages_migratesFirstOnly_dropsRest() throws Exception {
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "SharepointDrive", FILE_CONNECTOR_CLASS);
        McfJob job = job("With transforms", "SharepointDrive",
            List.of(stage(0, -1, false, "Content Limiter"), stage(1, 0, false, "Second Transform"),
                stage(2, 1, true, "Vespa Federated Index")),
            specWithPath("/data"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), repo),
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), supported("SharepointDrive"),
                key(McfConnectionKind.OUTPUT, "Vespa Federated Index"), supported("Vespa Federated Index"),
                key(McfConnectionKind.TRANSFORMATION, "Content Limiter"), supported("Content Limiter"),
                key(McfConnectionKind.TRANSFORMATION, "Second Transform"), supported("Second Transform")),
            options);

        assertThat(result.supported()).isTrue();
        assertThat(result.target().transformationConnector()).isEqualTo("Content Limiter");
        assertThat(result.notes()).anySatisfy(n -> assertThat(n.message()).contains("only the first transformation stage"));
    }

    @Test
    void noTransformationStage_leavesUnsetWithDefaultedNote() throws Exception {
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "SharepointDrive", FILE_CONNECTOR_CLASS);
        McfJob job = job("No transform", "SharepointDrive",
            List.of(stage(0, -1, true, "Vespa Federated Index")), specWithPath("/data"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), repo),
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), supported("SharepointDrive"),
                key(McfConnectionKind.OUTPUT, "Vespa Federated Index"), supported("Vespa Federated Index")),
            options);

        assertThat(result.supported()).isTrue();
        assertThat(result.target().transformationConnector()).isNull();
        assertThat(result.notes()).anySatisfy(n -> assertThat(n.field()).isEqualTo("transformationConnector"));
    }

    @Test
    void nonFileSystemRepoClass_pathDefaultsWithDefaultedNote() throws Exception {
        // Hypothetical: even if some future mapper supported this repo class, DocumentSpecificationParser
        // only understands FileConnector's shape, so path can't be derived — job still migrates.
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "Alfresco HR Data", ALFRESCO_CLASS);
        McfJob job = job("Hypothetical", "Alfresco HR Data",
            List.of(stage(0, -1, true, "Vespa Federated Index")), specWithPath("/mnt/drive-a/files"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "Alfresco HR Data"), repo),
            Map.of(key(McfConnectionKind.REPOSITORY, "Alfresco HR Data"), supported("Alfresco HR Data"),
                key(McfConnectionKind.OUTPUT, "Vespa Federated Index"), supported("Vespa Federated Index")),
            options);

        assertThat(result.supported()).isTrue();
        assertThat(result.target().path()).isEqualTo("/data");
        assertThat(result.notes()).anySatisfy(n -> {
            assertThat(n.field()).isEqualTo("path");
            assertThat(n.kind()).isEqualTo(FieldNoteKind.DEFAULTED);
        });
    }

    @Test
    void outputOutsideDynamicResolution_getsRuntimeRiskNote() throws Exception {
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "SharepointDrive", FILE_CONNECTOR_CLASS);
        McfJob job = job("ES job", "SharepointDrive", List.of(stage(0, -1, true, "ES Output")), specWithPath("/data"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), repo),
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), supportedWithTargetClass("SharepointDrive", "org.opencrawling.filesystem.FileSystemRepositoryConnector"),
                key(McfConnectionKind.OUTPUT, "ES Output"), supportedWithTargetClass("ES Output", "org.opencrawling.opensearch2.OpenSearch2OutputConnector")),
            options);

        assertThat(result.supported()).isTrue();
        assertThat(result.notes()).anySatisfy(n -> {
            assertThat(n.kind()).isEqualTo(FieldNoteKind.RUNTIME_RISK);
            assertThat(n.field()).isEqualTo("outputConnector");
        });
    }

    @Test
    void outputWithinDynamicResolution_noRuntimeRiskNote() throws Exception {
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "SharepointDrive", FILE_CONNECTOR_CLASS);
        McfJob job = job("Vespa job", "SharepointDrive", List.of(stage(0, -1, true, "Vespa Federated Index")), specWithPath("/data"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), repo),
            Map.of(key(McfConnectionKind.REPOSITORY, "SharepointDrive"), supportedWithTargetClass("SharepointDrive", "org.opencrawling.filesystem.FileSystemRepositoryConnector"),
                key(McfConnectionKind.OUTPUT, "Vespa Federated Index"), supportedWithTargetClass("Vespa Federated Index", "org.opencrawling.vespa.VespaOutputConnector")),
            options);

        assertThat(result.supported()).isTrue();
        assertThat(result.notes()).noneMatch(n -> n.kind() == FieldNoteKind.RUNTIME_RISK);
    }

    @Test
    void repositoryOutsideDynamicResolution_getsRuntimeRiskNote() throws Exception {
        McfConnection repo = connection(McfConnectionKind.REPOSITORY, "CMIS Repo", "org.apache.manifoldcf.crawler.connectors.cmis.CmisRepositoryConnector");
        McfJob job = job("CMIS job", "CMIS Repo", List.of(stage(0, -1, true, "Vespa Federated Index")), specWithPath("/data"));

        JobMappingResult result = jobMapper.map(job,
            Map.of(key(McfConnectionKind.REPOSITORY, "CMIS Repo"), repo),
            Map.of(key(McfConnectionKind.REPOSITORY, "CMIS Repo"), supportedWithTargetClass("CMIS Repo", "org.opencrawling.cmis.CmisRepositoryConnector"),
                key(McfConnectionKind.OUTPUT, "Vespa Federated Index"), supportedWithTargetClass("Vespa Federated Index", "org.opencrawling.vespa.VespaOutputConnector")),
            options);

        assertThat(result.supported()).isTrue();
        assertThat(result.notes()).anySatisfy(n -> {
            assertThat(n.kind()).isEqualTo(FieldNoteKind.RUNTIME_RISK);
            assertThat(n.field()).isEqualTo("repositoryConnector");
        });
    }

    private static String key(McfConnectionKind kind, String name) {
        return McfConnection.key(kind, name);
    }

    private ConnectorMappingResult supported(String name) {
        return ConnectorMappingResult.supported(
            ConnectorRequest.builder().name(name).type("output").className("does-not-matter").build(), List.of());
    }

    private ConnectorMappingResult supportedWithTargetClass(String name, String targetClassName) {
        return ConnectorMappingResult.supported(
            ConnectorRequest.builder().name(name).type("output").className(targetClassName).build(), List.of());
    }

    private McfConnection connection(McfConnectionKind kind, String name, String className) {
        return new McfConnection(kind, name, name, className, 10, Map.of(), null, List.of());
    }

    private McfPipelineStage stage(int id, int prerequisite, boolean isOutput, String connectionName) {
        return new McfPipelineStage(id, prerequisite, isOutput, connectionName, null, objectMapper.createObjectNode());
    }

    private McfJob job(String description, String repositoryConnectionName, List<McfPipelineStage> stages, JsonNode documentSpec) {
        return new McfJob(String.valueOf(description.hashCode()), description, repositoryConnectionName, documentSpec,
            stages, 0, "windowbegin", "scan once", "accurate", "5", "-1", "-1", "-1", "-1", List.of());
    }

    private JsonNode specWithPath(String path) throws Exception {
        return objectMapper.readTree("{\"startpoint\": {\"_attribute_path\": \"" + path + "\"}}");
    }

    private JsonNode specWithPathAndFilters(String path) throws Exception {
        return objectMapper.readTree("""
            {"startpoint": {
                "_attribute_path": "%s",
                "_children_": [
                    {"_type_": "include", "_attribute_match": ".*\\\\.pdf", "_attribute_type": "file"},
                    {"_type_": "exclude", "_attribute_match": ".*\\\\.metadata\\\\..*", "_attribute_type": "file"}
                ]
            }}
            """.formatted(path));
    }
}
