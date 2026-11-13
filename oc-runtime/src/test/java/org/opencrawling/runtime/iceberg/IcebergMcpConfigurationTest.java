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
package org.opencrawling.runtime.iceberg;

import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.mcp.backend.IcebergMcpSearchBackend;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies conditional Spring activation and property clamping for {@link IcebergMcpConfiguration},
 * {@link IcebergMcpTools}, and {@link IcebergMcpSearchBackend}.
 */
class IcebergMcpConfigurationTest {

    private static ApplicationContextRunner runner() {
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.initialize("cfg_test", Map.of("warehouse", "inmemory://cfg-test"));
        return new ApplicationContextRunner()
                .withUserConfiguration(IcebergMcpConfiguration.class, IcebergMcpTools.class, IcebergMcpSearchBackend.class)
                .withBean(Catalog.class, () -> catalog);
    }

    @Test
    void disabledByDefaultUnderVectorBackends() {
        runner().withPropertyValues("spring.opencrawling.output.type=pgvector")
                .run(context -> {
                    assertThat(context).hasSingleBean(IcebergMcpProperties.class);
                    assertThat(context).doesNotHaveBean(IcebergMcpTools.class);
                    assertThat(context).doesNotHaveBean(IcebergMcpSearchBackend.class);
                });
    }

    @Test
    void enablesToolsViaPrimaryPropertyAndAliasProperty() {
        runner().withPropertyValues(
                        "spring.opencrawling.output.type=pgvector",
                        "opencrawling.mcp.iceberg.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(IcebergMcpTools.class);
                    assertThat(context).doesNotHaveBean(IcebergMcpSearchBackend.class);
                });

        runner().withPropertyValues(
                        "spring.opencrawling.output.type=pgvector",
                        "opencrawling.iceberg.mcp.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(IcebergMcpTools.class);
                    assertThat(context).doesNotHaveBean(IcebergMcpSearchBackend.class);
                });
    }

    @Test
    void enablesToolsAndSearchBackendAutomaticallyWhenOutputTypeIsIceberg() {
        runner().withPropertyValues("spring.opencrawling.output.type=iceberg")
                .run(context -> {
                    assertThat(context).hasSingleBean(IcebergMcpTools.class);
                    assertThat(context).hasSingleBean(IcebergMcpSearchBackend.class);
                    assertThat(context.getBean(IcebergMcpSearchBackend.class).backendType()).isEqualTo("iceberg");
                });
    }

    @Test
    void disablesBothToolsAndBackendWhenMcpServerDisabled() {
        runner().withPropertyValues(
                        "spring.opencrawling.output.type=iceberg",
                        "opencrawling.mcp.iceberg.enabled=true",
                        "opencrawling.mcp.server.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IcebergMcpTools.class);
                    assertThat(context).doesNotHaveBean(IcebergMcpSearchBackend.class);
                });
    }

    @Test
    void propertiesClampRequestedAndConfiguredBoundsToHardCeilings() {
        IcebergMcpProperties props = new IcebergMcpProperties();
        props.setDefaultMaxResults(10);
        props.setMaxResults(99999);
        props.setMaxScan(9_999_999);
        props.setMaxSnapshots(50_000);

        assertThat(props.effectiveLimit(null)).isEqualTo(10);
        assertThat(props.effectiveLimit(0)).isEqualTo(1);
        assertThat(props.effectiveLimit(10_000)).isEqualTo(IcebergMcpProperties.HARD_MAX_RESULTS);
        assertThat(props.effectiveMaxScan()).isEqualTo(IcebergMcpProperties.HARD_MAX_SCAN);
        assertThat(props.effectiveMaxSnapshots()).isEqualTo(IcebergMcpProperties.HARD_MAX_SNAPSHOTS);
    }
}
