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
package org.opencrawling.migrator.mcf.mapping.vespa;

import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mapping.FieldNote;
import org.opencrawling.migrator.mcf.mapping.FieldNoteKind;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VespaOutputConnectorMapperTest {

    private static final String MCF_CLASS = "org.apache.manifoldcf.agents.output.vespa.VespaOutputConnector";
    private static final String SECRET_PASSWORD = "s3kr1t-actual-password";
    private static final String SECRET_BEARER = "bearer-actual-token-value";

    private final VespaOutputConnectorMapper mapper = new VespaOutputConnectorMapper();
    private final MigrationOptions options = new MigrationOptions(
        "http://mcf", null, null, "http://oc", null, false, "report.md", 384, List.of(), List.of(), false, 30, Map.of());

    @Test
    void supports_onlyManifoldCfVespaOutputConnector() {
        assertThat(mapper.supports(MCF_CLASS)).isTrue();
        assertThat(mapper.supports("org.apache.manifoldcf.agents.output.elasticsearch.ElasticSearchConnector")).isFalse();
    }

    @Test
    void map_fullFieldSet_producesExactlyTheExpectedTargetKeysAndNoOthers() {
        McfConnection source = realisticConnection();

        ConnectorMappingResult result = mapper.map(source, options);

        assertThat(result.supported()).isTrue();
        Map<String, String> target = result.target().configuration();
        // Exact key set JobController.startJob() actually reads for a "Vespa"-classed output connector.
        assertThat(target.keySet()).containsExactlyInAnyOrder(
            "vespaEndpoint", "vespaNamespace", "vespaDocumentType", "vespaTimeoutSeconds",
            "vespaDimensions", "vespaTlsEnabled");
    }

    @Test
    void map_directAndRenamedFields() {
        ConnectorMappingResult result = mapper.map(realisticConnection(), options);
        Map<String, String> target = result.target().configuration();

        assertThat(target).containsEntry("vespaEndpoint", "http://vespa:8080");
        assertThat(target).containsEntry("vespaNamespace", "default");
        assertThat(target).containsEntry("vespaDocumentType", "enterprise_content");
    }

    @Test
    void map_timeoutMs_convertedToSeconds() {
        McfConnection source = withConfig(Map.of("vespaTimeoutMs", "45000"));
        ConnectorMappingResult result = mapper.map(source, options);
        assertThat(result.target().configuration()).containsEntry("vespaTimeoutSeconds", "45");
        assertThat(result.notes()).anySatisfy(n -> {
            assertThat(n.field()).isEqualTo("vespaTimeoutMs");
            assertThat(n.kind()).isEqualTo(FieldNoteKind.CONVERTED);
        });
    }

    @Test
    void map_missingTimeoutMs_defaultsToThirtySeconds() {
        McfConnection source = withConfig(Map.of());
        ConnectorMappingResult result = mapper.map(source, options);
        assertThat(result.target().configuration()).containsEntry("vespaTimeoutSeconds", "30");
        assertThat(result.notes()).anySatisfy(n -> assertThat(n.kind()).isEqualTo(FieldNoteKind.DEFAULTED));
    }

    @Test
    void map_nonNumericTimeoutMs_fallsBackToDefault() {
        McfConnection source = withConfig(Map.of("vespaTimeoutMs", "not-a-number"));
        ConnectorMappingResult result = mapper.map(source, options);
        assertThat(result.target().configuration()).containsEntry("vespaTimeoutSeconds", "30");
    }

    @Test
    void map_dimensionsAlwaysDefaulted_usingOptionsOverride() {
        MigrationOptions with512 = new MigrationOptions(
            "http://mcf", null, null, "http://oc", null, false, "report.md", 512, List.of(), List.of(), false, 30, Map.of());
        ConnectorMappingResult result = mapper.map(withConfig(Map.of()), with512);
        assertThat(result.target().configuration()).containsEntry("vespaDimensions", "512");
        assertThat(result.notes()).anySatisfy(n -> {
            assertThat(n.field()).isEqualTo("vespaDimensions");
            assertThat(n.kind()).isEqualTo(FieldNoteKind.DEFAULTED);
        });
    }

    @Test
    void map_tlsAlwaysDefaultedToDisabled() {
        ConnectorMappingResult result = mapper.map(withConfig(Map.of()), options);
        assertThat(result.target().configuration()).containsEntry("vespaTlsEnabled", "false");
    }

    @Test
    void map_droppedAuthAndShadowFields_neverLeakRawSecretValues() {
        McfConnection source = realisticConnection();

        ConnectorMappingResult result = mapper.map(source, options);

        List<FieldNote> notes = result.notes();
        assertThat(notes).anySatisfy(n -> assertThat(n.field()).isEqualTo("vespaAuthMode"));
        assertThat(notes).anySatisfy(n -> assertThat(n.field()).isEqualTo("vespaUsername"));
        assertThat(notes).anySatisfy(n -> assertThat(n.field()).isEqualTo("vespaPassword"));
        assertThat(notes).anySatisfy(n -> assertThat(n.field()).isEqualTo("vespaBearerToken"));
        assertThat(notes).anySatisfy(n -> assertThat(n.field()).isEqualTo("sourceSystem"));
        assertThat(notes).anySatisfy(n -> assertThat(n.field()).isEqualTo("shadow*/fsShadow*"));
        assertThat(notes).anySatisfy(n -> assertThat(n.field()).isEqualTo("vespaEmbeddingEndpoint"));

        // The whole point of this test: nothing anywhere in the notes ever contains the raw secret text.
        assertThat(notes).noneSatisfy(n -> assertThat(n.message()).contains(SECRET_PASSWORD));
        assertThat(notes).noneSatisfy(n -> assertThat(n.message()).contains(SECRET_BEARER));
        // Also verify at the whole-result level, including the target itself (should never carry them).
        String everything = notes.stream().map(FieldNote::message).reduce("", String::concat)
            + result.target().configuration().values().stream().reduce("", String::concat);
        assertThat(everything).doesNotContain(SECRET_PASSWORD).doesNotContain(SECRET_BEARER);
    }

    @Test
    void map_shadowFieldsAbsent_noShadowNoteEmitted() {
        McfConnection source = withConfig(Map.of("vespaEndpoint", "http://vespa:8080"));
        ConnectorMappingResult result = mapper.map(source, options);
        assertThat(result.notes()).noneMatch(n -> n.field().contains("shadow"));
    }

    private McfConnection withConfig(Map<String, String> configuration) {
        return new McfConnection(McfConnectionKind.OUTPUT, "Vespa Federated Index", "Vespa Federated Index",
            MCF_CLASS, 10, configuration, null, List.of());
    }

    private McfConnection realisticConnection() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("vespaEndpoint", "http://vespa:8080");
        config.put("vespaHealthPath", "/state/v1/health");
        config.put("vespaNamespace", "default");
        config.put("vespaDocType", "enterprise_content");
        config.put("vespaAuthMode", "basic");
        config.put("vespaUsername", "vespa-user");
        config.put("vespaPassword", SECRET_PASSWORD);
        config.put("vespaBearerToken", SECRET_BEARER);
        config.put("vespaIdStrategy", "sha256");
        config.put("vespaTimeoutMs", "30000");
        config.put("vespaMaxBinaryBytes", "5242880");
        config.put("vespaTextFieldCandidates", "text,content,cm:content");
        config.put("vespaStoreBinary", "false");
        config.put("sourceSystem", "alfresco");
        config.put("sourceInstance", "repo2.localhost");
        config.put("repositoryId", "hr-repo");
        config.put("tenantId", "acme");
        config.put("sourceConnectionName", "Alfresco HR Data");
        config.put("shadowMode", "alfresco-rest");
        config.put("shadowAlfrescoUrl", "https://repo2.localhost/alfresco");
        config.put("shadowFallback", "true");
        config.put("vespaLanguageOverride", "en");
        config.put("vespaFixRtlText", "false");
        config.put("vespaEmbeddingEndpoint", "http://embedder:8000/embed");
        config.put("vespaEmbeddingTimeoutMs", "10000");
        config.put("vespaEmbeddingMaxChars", "2000");
        config.put("fsShadowEnabled", "false");
        return withConfig(config);
    }
}
