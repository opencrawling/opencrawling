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
package org.opencrawling.runtime.transport;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.push.grpc.GrpcPushConnectorAutoConfiguration;
import org.opencrawling.push.grpc.PushDocumentDispatcher;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GrpcPushDisabledContextTest {

    @Configuration
    static class MockDepsConfig {
        @Bean
        JobController jobController() {
            return mock(JobController.class);
        }

        @Bean
        JobOrchestrator jobOrchestrator() {
            return mock(JobOrchestrator.class);
        }

        @Bean
        ClaimCheckStore claimCheckStore() {
            return mock(ClaimCheckStore.class);
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcPushConnectorAutoConfiguration.class))
            .withUserConfiguration(MockDepsConfig.class, RuntimePushDocumentDispatcher.class);

    @Test
    void whenGrpcPushDisabled_thenRuntimePushDocumentDispatcherNotCreatedAndContextStarts() {
        contextRunner
                .withPropertyValues("opencrawling.connector.grpc-push.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RuntimePushDocumentDispatcher.class);
                    assertThat(context).doesNotHaveBean(PushDocumentDispatcher.class);
                });
    }

    @Test
    void whenGrpcPushEnabled_thenDispatcherAndConnectorCreated() {
        contextRunner
                .withPropertyValues("opencrawling.connector.grpc-push.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RuntimePushDocumentDispatcher.class);
                    assertThat(context).hasSingleBean(PushDocumentDispatcher.class);
                });
    }
}
