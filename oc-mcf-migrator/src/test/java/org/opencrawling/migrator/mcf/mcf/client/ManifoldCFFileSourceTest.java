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
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reuses the exact same fixture files {@code MigrationEngineAcceptanceIT} uses against an HTTP
 * fake — proof that the file-based path and the live-API path parse identically, since they share
 * {@code McfEntityParser}.
 */
class ManifoldCFFileSourceTest {

    private final ManifoldCFFileSource source = new ManifoldCFFileSource(fixtureDir());

    @Test
    void listRepositoryConnections_matchesLiveApiAcceptanceData() {
        List<McfConnection> connections = source.listRepositoryConnections();
        assertThat(connections).hasSize(4);
        assertThat(connections).extracting(McfConnection::name)
            .containsExactly("Alfresco HR Data", "Alfresco Legal Documents", "Mfiles Source Repository", "SharepointDrive");
    }

    @Test
    void listOutputConnections_matchesLiveApiAcceptanceData() {
        assertThat(source.listOutputConnections()).hasSize(5);
    }

    @Test
    void listJobs_matchesLiveApiAcceptanceData() {
        List<McfJob> jobs = source.listJobs();
        assertThat(jobs).hasSize(5);
        assertThat(jobs).extracting(McfJob::description).contains("SharePoint drive to Vespa");
    }

    @Test
    void emptyAuthorityConnectionsFile_returnsEmptyList() {
        assertThat(source.listAuthorityConnections()).isEmpty();
    }

    @Test
    void missingFile_returnsEmptyRatherThanThrowing() throws IOException {
        Path emptyDir = Files.createTempDirectory("mcf-file-source-test");
        ManifoldCFFileSource emptySource = new ManifoldCFFileSource(emptyDir);
        assertThat(emptySource.listRepositoryConnections()).isEmpty();
        assertThat(emptySource.listJobs()).isEmpty();
    }

    @Test
    void fileWithErrorNode_throws() throws IOException {
        Path dir = Files.createTempDirectory("mcf-file-source-error-test");
        Files.writeString(dir.resolve("repositoryconnections.json"), "{\"error\": \"boom\"}");
        ManifoldCFFileSource errorSource = new ManifoldCFFileSource(dir);
        assertThatThrownBy(errorSource::listRepositoryConnections)
            .isInstanceOf(ManifoldCFApiException.class)
            .hasMessageContaining("boom");
    }

    private static Path fixtureDir() {
        try {
            return Path.of(ManifoldCFFileSourceTest.class.getClassLoader().getResource("fixtures/mcf").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
