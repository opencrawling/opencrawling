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
package org.opencrawling.runtime.scheduler;

/** Outcome recorded when the native scheduler evaluates a scheduled tick or post-run state (#197). */
public enum DispatchOutcome {
    /** Scheduled run reserved and launched cleanly. */
    STARTED,
    /** Skipped (or coalesced) because the job already had an active run. */
    SKIPPED_OVERLAP,
    /** Skipped because the runtime/cluster cap on concurrent runs was reached. */
    SKIPPED_CAPACITY,
    /** Missed during downtime beyond {@code misfire-grace-period} and skipped per {@code MisfirePolicy.SKIP}. */
    MISSED_SKIPPED,
    /** Job failed pre-flight validation or connector resolution when starting on a scheduled tick. */
    REJECTED,
    /** Schedule automatically disabled after reaching {@code maxConsecutiveFailures}. */
    AUTO_PAUSED
}
