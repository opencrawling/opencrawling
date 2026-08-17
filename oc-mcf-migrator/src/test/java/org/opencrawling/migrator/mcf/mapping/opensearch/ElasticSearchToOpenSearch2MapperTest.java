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
package org.opencrawling.migrator.mcf.mapping.opensearch;

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

class ElasticSearchToOpenSearch2MapperTest {

    private static final String MCF_CLASS = "org.apache.manifoldcf.agents.output.elasticsearch.ElasticSearchConnector";
    private static final String SECRET_PASSWORD = "es-actual-secret-password";

    private final ElasticSearchToOpenSearch2Mapper mapper = new ElasticSearchToOpenSearch2Mapper();
    private final MigrationOptions options = new MigrationOptions(
        "http://mcf", null, null, "http://oc", null, false, "report.md", 384, List.of(), List.of(), false, 30, Map.of());

    @Test
    void supports_onlyManifoldCfElasticSearchConnector() {
        assertThat(mapper.supports(MCF_CLASS)).isTrue();
        assertThat(mapper.supports("org.apache.manifoldcf.agents.output.solr.SolrConnector")).isFalse();
    }

    @Test
    void map_directAndRenamedFields() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("SERVERLOCATION", "http://es01:9200/");
        config.put("INDEXNAME", "index-1");
        config.put("USERNAME", "es-user");
        config.put("PASSWORD", SECRET_PASSWORD);

        ConnectorMappingResult result = mapper.map(connection(config), options);

        assertThat(result.supported()).isTrue();
        Map<String, String> target = result.target().configuration();
        assertThat(target).containsEntry("opensearch2Uris", "http://es01:9200/");
        assertThat(target).containsEntry("opensearch2IndexName", "index-1");
        assertThat(target).containsEntry("opensearch2Username", "es-user");
        assertThat(target).containsEntry("opensearch2Password", SECRET_PASSWORD);
    }

    @Test
    void map_dimensionsAlwaysDefaulted() {
        ConnectorMappingResult result = mapper.map(connection(Map.of()), options);
        assertThat(result.target().configuration()).containsEntry("opensearch2Dimensions", "384");
        assertThat(result.notes()).anySatisfy(n -> {
            assertThat(n.field()).isEqualTo("opensearch2Dimensions");
            assertThat(n.kind()).isEqualTo(FieldNoteKind.DEFAULTED);
        });
    }

    @Test
    void map_alwaysCarriesRuntimeRiskNoteAboutPropertyKeyMismatch() {
        ConnectorMappingResult result = mapper.map(connection(Map.of()), options);
        assertThat(result.notes()).anySatisfy(n -> assertThat(n.kind()).isEqualTo(FieldNoteKind.RUNTIME_RISK));
    }

    @Test
    void map_fullFieldSet_producesOnlyExpectedTargetKeys() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("SERVERLOCATION", "http://es01:9200/");
        config.put("INDEXNAME", "index-1");
        config.put("USERNAME", "es-user");
        config.put("PASSWORD", SECRET_PASSWORD);
        config.put("SERVERKEYSTORE", "keystore-bytes");
        config.put("INDEXTYPE", "_doc");
        config.put("USEINGESTATTACHMENT", "true");
        config.put("USEMAPPERATTACHMENTS", "false");
        config.put("PIPELINENAME", "attachment");
        config.put("CONTENTATTRIBUTENAME", "content");
        config.put("URIATTRIBUTENAME", "url");
        config.put("CREATEDDATEATTRIBUTENAME", "created");
        config.put("MODIFIEDDATEATTRIBUTENAME", "last-modified");
        config.put("INDEXINGDATEATTRIBUTENAME", "indexed");
        config.put("MIMETYPEATTRIBUTENAME", "mime-type");
        config.put("FIELDLIST", "");
        config.put("ELASTICSEARCH_SOCKET_TIMEOUT", "900000");
        config.put("ELASTICSEARCH_CONNECTION_TIMEOUT", "60000");

        ConnectorMappingResult result = mapper.map(connection(config), options);

        assertThat(result.target().configuration().keySet()).containsExactlyInAnyOrder(
            "opensearch2Uris", "opensearch2IndexName", "opensearch2Username", "opensearch2Password", "opensearch2Dimensions");
        // one DROPPED/CONVERTED/DEFAULTED note per source field consumed or explicitly dropped, plus the runtime-risk note
        assertThat(result.notes()).hasSizeGreaterThanOrEqualTo(14);
    }

    @Test
    void map_neverLeaksRawSecretValueInAnyNoteMessage() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("PASSWORD", SECRET_PASSWORD);
        ConnectorMappingResult result = mapper.map(connection(config), options);

        assertThat(result.notes()).noneSatisfy(n -> assertThat(n.message()).contains(SECRET_PASSWORD));
    }

    private McfConnection connection(Map<String, String> configuration) {
        return new McfConnection(McfConnectionKind.OUTPUT, "es-1", "es-1", MCF_CLASS, 10, configuration, null, List.of());
    }
}
