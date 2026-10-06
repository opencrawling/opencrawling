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
package org.opencrawling.runtime.api;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.pipeline.PipelineAutoConfiguration;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies per-job pipeline mode overrides and the global {@code opencrawling.pipeline.mode} fallback.
 */
class JobControllerPipelineModeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PipelineAutoConfiguration.class));

    @Test
    void globalPipelineModeIsBoundFromConfiguration() {
        contextRunner.withPropertyValues("opencrawling.pipeline.mode=migration").run(ctx -> {
            assertThat(ctx).hasSingleBean(PipelineProperties.class);
            assertEquals(PipelineMode.MIGRATION, ctx.getBean(PipelineProperties.class).getMode());
        });
        contextRunner.run(ctx -> assertEquals(PipelineMode.RAG, ctx.getBean(PipelineProperties.class).getMode()));
    }

    @Test
    void jobOverrideWinsOverGlobalDefault() {
        PipelineProperties global = new PipelineProperties();
        global.setMode(PipelineMode.MIGRATION);

        assertEquals(PipelineMode.RAG, JobController.resolvePipelineMode("1", "rag", global));
        assertEquals(PipelineMode.MIGRATION, JobController.resolvePipelineMode("1", null, global));
        assertEquals(PipelineMode.MIGRATION, JobController.resolvePipelineMode("1", "", global));
    }

    @Test
    void missingGlobalDefaultsToRagAndInvalidJobModeFallsBack() {
        assertEquals(PipelineMode.RAG, JobController.resolvePipelineMode("1", null, null));
        assertEquals(PipelineMode.MIGRATION, JobController.resolvePipelineMode("1", "MIGRATION", null));

        PipelineProperties global = new PipelineProperties();
        global.setMode(PipelineMode.MIGRATION);
        assertEquals(PipelineMode.MIGRATION, JobController.resolvePipelineMode("1", "bogus", global));
    }
}
