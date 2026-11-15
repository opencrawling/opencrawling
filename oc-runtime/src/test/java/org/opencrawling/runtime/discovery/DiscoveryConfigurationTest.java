/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.runtime.discovery;

import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Discovery exists only when enabled, its MCP tools only when the MCP server is on too, and its settings bind. */
class DiscoveryConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ConnectorFactory.class, () -> mock(ConnectorFactory.class))
            .withUserConfiguration(DiscoveryConfiguration.class, DiscoveryController.class, DiscoveryMcpTools.class);

    @Test
    void discoveryIsOffByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DiscoveryService.class);
            assertThat(context).doesNotHaveBean(DiscoveryController.class);
            assertThat(context).doesNotHaveBean(DiscoveryMcpTools.class);
        });
    }

    @Test
    void enablingItAddsTheServiceTheEndpointsAndTheTools() {
        runner.withPropertyValues("opencrawling.discovery.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DiscoveryService.class);
            assertThat(context).hasSingleBean(DiscoveryController.class);
            assertThat(context).hasSingleBean(DiscoveryMcpTools.class);
        });
    }

    @Test
    void theToolsFollowTheMcpServer() {
        runner.withPropertyValues("opencrawling.discovery.enabled=true", "opencrawling.mcp.server.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(DiscoveryController.class);
                    assertThat(context).doesNotHaveBean(DiscoveryMcpTools.class);
                });
    }

    @Test
    void settingsBindWithSafeDefaults() {
        runner.withPropertyValues("opencrawling.discovery.enabled=true").run(context -> {
            DiscoveryProperties defaults = context.getBean(DiscoveryProperties.class);
            assertThat(defaults.isAllowAssertedIdentity()).isFalse();
            assertThat(defaults.isRevealHiddenCounts()).isFalse();
        });
        runner.withPropertyValues("opencrawling.discovery.enabled=true",
                "opencrawling.discovery.allow-asserted-identity=true",
                "opencrawling.discovery.reveal-hidden-counts=true",
                "opencrawling.discovery.max-depth=2", "opencrawling.discovery.max-nodes=40",
                "opencrawling.discovery.sample-size=3", "opencrawling.discovery.max-scan=500",
                "opencrawling.discovery.timeout=5s").run(context -> {
            DiscoveryProperties properties = context.getBean(DiscoveryProperties.class);
            assertThat(properties.isAllowAssertedIdentity()).isTrue();
            assertThat(properties.isRevealHiddenCounts()).isTrue();
            assertThat(properties.depthLimit()).isEqualTo(2);
            assertThat(properties.nodeLimit()).isEqualTo(40);
            assertThat(properties.sampleLimit()).isEqualTo(3);
            assertThat(properties.scanLimit()).isEqualTo(500);
            assertThat(properties.timeoutLimit()).isEqualTo(Duration.ofSeconds(5));
        });
    }
}
