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
package org.opencrawling.migrator.mcf.ois;

import org.opencrawling.migrator.mcf.mcf.model.McfScheduleRecord;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Attempts to express a ManifoldCF job's schedule as a single standard 5-field crontab expression,
 * for {@code convert}'s OIS output. Only handles the simple, common case on purpose: exactly one
 * schedule record, a specific hour and minute, no month-of-year/day-of-month restriction (those
 * don't combine cleanly with a day-of-week list in 5-field cron). Anything else — multiple
 * records, a duration window, more than one hour/minute value, a month or day-of-month restriction
 * — returns empty rather than guess at a translation, consistent with this tool's "never guess"
 * rule; the caller falls back to a defaulted schedule with an explanatory note in that case.
 *
 * <p>Because {@link McfScheduleRecord}'s own field-name mapping is unverified against a real
 * ManifoldCF export (see its javadoc), a wrong field name there simply means the record parses as
 * empty and this always returns empty — it can never produce an incorrect cron expression from
 * misread data, only fail to produce one at all.
 */
final class CronTranslator {

    private CronTranslator() {
    }

    static Optional<String> tryTranslate(List<McfScheduleRecord> records) {
        if (records.size() != 1) {
            return Optional.empty();
        }
        McfScheduleRecord record = records.get(0);
        if (!record.monthsOfYear().isEmpty() || !record.daysOfMonth().isEmpty()) {
            return Optional.empty();
        }
        if (record.hoursOfDay().size() != 1 || record.minutesOfHour().size() != 1) {
            return Optional.empty();
        }

        int hour = record.hoursOfDay().get(0);
        int minute = record.minutesOfHour().get(0);
        String dayOfWeek = record.daysOfWeek().isEmpty()
            ? "*"
            : record.daysOfWeek().stream().map(String::valueOf).collect(Collectors.joining(","));

        return Optional.of(minute + " " + hour + " * * " + dayOfWeek);
    }
}
