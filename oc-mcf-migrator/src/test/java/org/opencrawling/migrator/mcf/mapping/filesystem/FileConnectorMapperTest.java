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
package org.opencrawling.migrator.mcf.mapping.filesystem;

import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileConnectorMapperTest {

    private final FileConnectorMapper mapper = new FileConnectorMapper();
    private final MigrationOptions options = new MigrationOptions(
        "http://mcf", null, null, "http://oc", null, false, "report.md", 384, List.of(), List.of(), false, 30, Map.of());

    @Test
    void supports_onlyTheStockFileConnectorClass() {
        assertThat(mapper.supports("org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector")).isTrue();
        assertThat(mapper.supports("org.apache.manifoldcf.crawler.connectors.alfrescowebscript.AlfrescoConnector")).isFalse();
        assertThat(mapper.supports("com.speedysearch.manifoldcf.mfiles.MFilesRepositoryConnector")).isFalse();
    }

    @Test
    void map_emptyConnection_migratesCleanlyWithNoNotes() {
        McfConnection source = new McfConnection(
            McfConnectionKind.REPOSITORY, "SharepointDrive", "Company File Share",
            "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector", 10, Map.of(), null, List.of());

        ConnectorMappingResult result = mapper.map(source, options);

        assertThat(result.supported()).isTrue();
        assertThat(result.target().name()).isEqualTo("SharepointDrive");
        assertThat(result.target().type()).isEqualTo("repository");
        assertThat(result.target().className()).isEqualTo("org.opencrawling.filesystem.FileSystemRepositoryConnector");
        assertThat(result.target().configuration()).isEmpty();
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void map_aclAuthorityAndThrottles_notedAsDropped() {
        McfConnection source = new McfConnection(
            McfConnectionKind.REPOSITORY, "SharepointDrive", null,
            "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector", 10,
            Map.of(), "AD Authority", List.of("*", "internal-only"));

        ConnectorMappingResult result = mapper.map(source, options);

        assertThat(result.supported()).isTrue();
        assertThat(result.notes()).anySatisfy(note -> assertThat(note.field()).isEqualTo("aclAuthority"));
        assertThat(result.notes()).anySatisfy(note -> assertThat(note.field()).isEqualTo("throttles"));
    }

    @Test
    void map_nullDescription_fallsBackToName() {
        McfConnection source = new McfConnection(
            McfConnectionKind.REPOSITORY, "SharepointDrive", null,
            "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector", 10, Map.of(), null, List.of());

        ConnectorMappingResult result = mapper.map(source, options);

        assertThat(result.target().description()).isEqualTo("SharepointDrive");
    }
}
