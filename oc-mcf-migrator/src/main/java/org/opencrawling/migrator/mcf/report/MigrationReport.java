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
package org.opencrawling.migrator.mcf.report;

import org.opencrawling.migrator.mcf.engine.ApplyOutcome;
import org.opencrawling.migrator.mcf.engine.MigrationPlan;

/**
 * Everything a {@link ReportRenderer} needs. {@code applyOutcome} is null for a dry run — only
 * present when this report describes a run made with {@code --apply}.
 */
public record MigrationReport(
    String generatedAt,
    boolean applyMode,
    String manifoldCfUrl,
    String openCrawlingUrl,
    MigrationPlan plan,
    ApplyOutcome applyOutcome
) {
}
