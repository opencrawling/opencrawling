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
package org.opencrawling.migrator.mcf.mapping;

import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.mapping.filesystem.FileConnectorMapper;
import org.opencrawling.migrator.mcf.mapping.vespa.VespaOutputConnectorMapper;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorMapperRegistryTest {

    @Test
    void explicitList_findResolvesBySupports_unknownClassIsEmpty() {
        ConnectorMapperRegistry registry = new ConnectorMapperRegistry(List.of(new FileConnectorMapper(), new VespaOutputConnectorMapper()));

        assertThat(registry.find("org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector"))
            .get().isInstanceOf(FileConnectorMapper.class);
        assertThat(registry.find("org.apache.manifoldcf.agents.output.vespa.VespaOutputConnector"))
            .get().isInstanceOf(VespaOutputConnectorMapper.class);
        assertThat(registry.find("com.speedysearch.manifoldcf.mfiles.MFilesRepositoryConnector")).isEmpty();
    }

    /**
     * This is the real proof of the extensibility mechanism, not just an assertion of intent:
     * {@code src/test/resources/META-INF/services/....ConnectorMapper} lists this test's own
     * {@link SyntheticThirdMapper} alongside the two shipped mappers (whose SPI entries live in
     * {@code src/main/resources}), and both resource sets land on the same test classpath — so a
     * registry built from the real no-arg constructor (real {@link java.util.ServiceLoader}, no
     * mocking) must discover all three, exactly as a genuine third-party mapper jar would.
     */
    @Test
    void realServiceLoaderDiscovery_findsBuiltInMappersPlusThirdPartyExtension() {
        ConnectorMapperRegistry registry = new ConnectorMapperRegistry();

        assertThat(registry.all()).anyMatch(m -> m instanceof FileConnectorMapper);
        assertThat(registry.all()).anyMatch(m -> m instanceof VespaOutputConnectorMapper);
        assertThat(registry.all()).anyMatch(m -> m instanceof SyntheticThirdMapper);

        assertThat(registry.find(SyntheticThirdMapper.SUPPORTED_CLASS)).get().isInstanceOf(SyntheticThirdMapper.class);
    }

    /** A trivial third-party-shaped mapper, registered only via the test-scope SPI file. */
    public static class SyntheticThirdMapper implements ConnectorMapper {
        static final String SUPPORTED_CLASS = "org.apache.manifoldcf.agents.output.solr.SolrConnector";

        @Override
        public boolean supports(String manifoldClassName) {
            return SUPPORTED_CLASS.equals(manifoldClassName);
        }

        @Override
        public String manifoldClassName() {
            return SUPPORTED_CLASS;
        }

        @Override
        public String targetType() {
            return "output";
        }

        @Override
        public ConnectorMappingResult map(McfConnection source, MigrationOptions options) {
            throw new UnsupportedOperationException("not needed for this test");
        }
    }
}
