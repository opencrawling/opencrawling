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
package org.opencrawling.runtime.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring builds the registry (it has a second constructor for tests), with and without the run cap configured.
 */
class JobRunRegistryContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JobRunRegistry.class);

    @Test
    void springBuildsTheRegistryWithoutACap() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JobRunRegistry.class).maxConcurrentRuns()).isZero();
        });
    }

    @Test
    void springBuildsTheRegistryWithTheConfiguredCap() {
        runner.withPropertyValues("opencrawling.jobs.max-concurrent-runs=3").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JobRunRegistry.class).maxConcurrentRuns()).isEqualTo(3);
        });
    }
}
