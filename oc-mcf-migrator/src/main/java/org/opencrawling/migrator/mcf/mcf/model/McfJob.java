/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
package org.opencrawling.migrator.mcf.mcf.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A ManifoldCF job, as extracted from {@code /json/jobs}. {@code documentSpecification} is the
 * raw ConfigurationNode-shaped tree ({@code DocumentSpecificationParser} only understands the
 * stock FileConnector's shape within it); hopcount/recrawl/expiration/reseed fields have no direct
 * OpenCrawling equivalent and are kept purely so the audit report can name them explicitly, while
 * {@code scheduleRecords} are best-effort translated into paused OpenCrawling cron schedules (#197)
 * and {@code convert}'s OIS {@code spec.schedule}.
 */
public record McfJob(
    String id,
    String description,
    String repositoryConnectionName,
    JsonNode documentSpecification,
    List<McfPipelineStage> pipelineStages,
    int notificationStageCount,
    String startMode,
    String runMode,
    String hopcountMode,
    String priority,
    String recrawlInterval,
    String maxRecrawlInterval,
    String expirationInterval,
    String reseedInterval,
    List<McfScheduleRecord> scheduleRecords
) {
    public McfJob {
        pipelineStages = pipelineStages == null ? List.of() : List.copyOf(pipelineStages);
        scheduleRecords = scheduleRecords == null ? List.of() : List.copyOf(scheduleRecords);
    }

    public List<McfPipelineStage> outputStages() {
        return pipelineStages.stream().filter(McfPipelineStage::isOutput).toList();
    }

    public List<McfPipelineStage> transformationStages() {
        return pipelineStages.stream().filter(stage -> !stage.isOutput()).toList();
    }

    /** True if this job has anything ManifoldCF supports but OpenCrawling has no target for at all. */
    public boolean hasSchedulingConcerns() {
        return !scheduleRecords.isEmpty()
            || (hopcountMode != null && !hopcountMode.isBlank())
            || (recrawlInterval != null && !recrawlInterval.isBlank())
            || notificationStageCount > 0;
    }
}
