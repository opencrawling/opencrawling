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
package org.opencrawling.migrator.mcf.job;

import org.opencrawling.migrator.mcf.mapping.FieldNote;
import org.opencrawling.sdk.models.JobRequest;

import java.util.List;

/**
 * The outcome of resolving one {@code McfJob}. A job is only ever unsupported for one of two
 * reasons: it references at least one connector with no registered {@link
 * org.opencrawling.migrator.mcf.mapping.ConnectorMapper} ({@code blockingConnectors}
 * names exactly which), or its pipeline shape has no OpenCrawling equivalent (e.g. more than one
 * output stage — {@code JobRequest} has exactly one output slot).
 */
public record JobMappingResult(
    boolean supported,
    JobRequest target,
    List<String> blockingConnectors,
    List<FieldNote> notes,
    String unsupportedReason
) {
    public JobMappingResult {
        blockingConnectors = blockingConnectors == null ? List.of() : List.copyOf(blockingConnectors);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static JobMappingResult unsupported(List<String> blockingConnectors, String reason) {
        return new JobMappingResult(false, null, blockingConnectors, List.of(), reason);
    }

    public static JobMappingResult supported(JobRequest target, List<FieldNote> notes) {
        return new JobMappingResult(true, target, List.of(), notes, null);
    }
}
