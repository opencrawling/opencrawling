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
package org.opencrawling.migrator.mcf.mcf.model;

import java.util.List;

/**
 * One ManifoldCF job {@code schedule} record. An empty list on any field means "any"/unrestricted
 * for that unit, matching ManifoldCF's own semantics (an absent {@code dayofweek} means every day,
 * not "never"). Field-name mapping ({@code dayofweek}/{@code hourofday}/{@code minutesofhour}/
 * {@code monthofyear}/{@code dayofmonth}/{@code duration}) follows the same node-type vocabulary
 * verified elsewhere in this project's real ManifoldCF JSON (see {@code McfJsonNodes}), but this
 * specific set of field names has <b>not</b> been independently confirmed against a real populated
 * schedule — none of this project's own fixtures happen to carry one. See {@code
 * org.opencrawling.migrator.mcf.ois.CronTranslator} for what this is actually used for and why a
 * wrong guess here fails safe (falls back to a defaulted schedule, never a wrong one).
 */
public record McfScheduleRecord(
    List<Integer> daysOfWeek,
    List<Integer> hoursOfDay,
    List<Integer> minutesOfHour,
    List<Integer> monthsOfYear,
    List<Integer> daysOfMonth,
    Integer durationMinutes
) {
    public McfScheduleRecord {
        daysOfWeek = daysOfWeek == null ? List.of() : List.copyOf(daysOfWeek);
        hoursOfDay = hoursOfDay == null ? List.of() : List.copyOf(hoursOfDay);
        minutesOfHour = minutesOfHour == null ? List.of() : List.copyOf(minutesOfHour);
        monthsOfYear = monthsOfYear == null ? List.of() : List.copyOf(monthsOfYear);
        daysOfMonth = daysOfMonth == null ? List.of() : List.copyOf(daysOfMonth);
    }
}
