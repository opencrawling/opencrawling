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
package org.opencrawling.migrator.mcf.engine;

import java.util.List;

/**
 * The fully-resolved migration plan — every source connection/job paired with its mapping
 * decision. Pure data, computed with no network writes; {@code MigrationEngine.apply} is the only
 * thing that turns this into actual {@code POST} calls, and only when {@code --apply} is set.
 */
public record MigrationPlan(List<ConnectionPlanEntry> connections, List<JobPlanEntry> jobs) {
    public MigrationPlan {
        connections = connections == null ? List.of() : List.copyOf(connections);
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}
