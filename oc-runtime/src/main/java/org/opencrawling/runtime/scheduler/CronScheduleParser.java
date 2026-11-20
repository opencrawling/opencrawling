/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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

import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Parses, normalizes, validates and evaluates 5-field POSIX crontabs (OIS {@code spec.schedule} default),
 * 6-field Spring cron expressions and standard Spring macros ({@code @hourly}, {@code @daily}, etc.) (#197).
 */
public final class CronScheduleParser {

    private static final int MAX_PREVIEW_COUNT = 20;
    private static final Map<String, String> MACRO_DESCRIPTIONS = Map.of(
            "@hourly", "Every hour",
            "@daily", "Every day at 00:00",
            "@midnight", "Every day at 00:00",
            "@weekly", "Every Sunday at 00:00",
            "@monthly", "On day 1 of the month at 00:00",
            "@yearly", "Once a year on January 1 at 00:00",
            "@annually", "Once a year on January 1 at 00:00");

    private CronScheduleParser() {}

    /** A validated cron expression and its resolved timezone. */
    public record ParsedSchedule(String normalizedCron, CronExpression expression, ZoneId zone) {}

    /** Normalizes a 5-field POSIX cron to 6-field Spring cron (or passes through 6-field / macros). */
    public static String normalize(String rawCron) {
        if (rawCron == null || rawCron.isBlank()) {
            throw new ScheduleValidationException("cron", "must not be blank");
        }
        String collapsed = rawCron.trim().replaceAll("\\s+", " ");
        if (collapsed.startsWith("@")) {
            return collapsed.toLowerCase(Locale.ROOT);
        }
        String[] parts = collapsed.split(" ");
        if (parts.length == 5) {
            return "0 " + collapsed;
        }
        if (parts.length == 6) {
            return collapsed;
        }
        throw new ScheduleValidationException("cron",
                "must have 5 fields (POSIX crontab) or 6 fields (Spring cron with seconds), got " + parts.length);
    }

    /** Parses the IANA timezone ID (falling back to {@code defaultTimezone} if blank). */
    public static ZoneId parseZone(String timezone, String defaultTimezone) {
        String candidate = timezone != null && !timezone.isBlank() ? timezone.strip()
                : (defaultTimezone != null && !defaultTimezone.isBlank() ? defaultTimezone.strip() : "UTC");
        try {
            return ZoneId.of(candidate);
        } catch (DateTimeException e) {
            throw new ScheduleValidationException("timezone", "unknown IANA timezone '" + candidate + "'");
        }
    }

    /** Validates cron syntax, timezone, minimum interval floor, and optional window bounds. */
    public static ParsedSchedule validate(JobScheduleConfig config, SchedulerProperties properties, Instant now) {
        if (config == null) {
            throw new ScheduleValidationException("schedule", "must not be null");
        }
        String normalized = normalize(config.cron());
        CronExpression expression;
        try {
            expression = CronExpression.parse(normalized);
        } catch (IllegalArgumentException e) {
            throw new ScheduleValidationException("cron", "invalid cron expression: " + e.getMessage());
        }
        ZoneId zone = parseZone(config.timezone(), properties != null ? properties.getDefaultTimezone() : "UTC");

        if (config.jitterSeconds() != null && (config.jitterSeconds() < 0 || config.jitterSeconds() > 3600)) {
            throw new ScheduleValidationException("jitterSeconds", "must be between 0 and 3600");
        }
        if (config.maxConsecutiveFailures() != null
                && (config.maxConsecutiveFailures() < 0 || config.maxConsecutiveFailures() > 100)) {
            throw new ScheduleValidationException("maxConsecutiveFailures", "must be between 0 and 100");
        }
        if (config.startAt() != null && config.endAt() != null && !config.endAt().isAfter(config.startAt())) {
            throw new ScheduleValidationException("endAt", "must be after startAt");
        }

        Duration minInterval = properties != null ? properties.getMinInterval() : Duration.ofMinutes(1);
        ZonedDateTime probeStart = ZonedDateTime.ofInstant(now != null ? now : Instant.now(), zone);
        ZonedDateTime first = expression.next(probeStart);
        if (first == null) {
            throw new ScheduleValidationException("cron", "cron expression never matches an upcoming calendar time");
        }
        if (minInterval != null && !minInterval.isZero() && !minInterval.isNegative()) {
            ZonedDateTime prev = first;
            for (int i = 0; i < 2; i++) {
                ZonedDateTime next = expression.next(prev);
                if (next == null) {
                    break;
                }
                Duration gap = Duration.between(prev, next);
                if (gap.compareTo(minInterval) < 0) {
                    throw new ScheduleValidationException("cron",
                            "schedule fires every " + gap.toSeconds() + "s which is below the minimum allowed interval of "
                                    + minInterval);
                }
                prev = next;
            }
        }
        return new ParsedSchedule(normalized, expression, zone);
    }

    /** Computes up to {@code count} future trigger timestamps strictly obeying {@code startAt} and {@code endAt}. */
    public static List<Instant> nextFireTimes(JobScheduleConfig config, String defaultTimezone, Instant after, int count) {
        if (config == null || config.cron() == null || config.cron().isBlank() || count <= 0) {
            return List.of();
        }
        int capped = Math.min(count, MAX_PREVIEW_COUNT);
        String normalized = normalize(config.cron());
        CronExpression expression = CronExpression.parse(normalized);
        ZoneId zone = parseZone(config.timezone(), defaultTimezone);

        Instant anchor = after != null ? after : Instant.now();
        if (config.startAt() != null && config.startAt().minusNanos(1).isAfter(anchor)) {
            anchor = config.startAt().minusNanos(1);
        }

        List<Instant> result = new ArrayList<>(capped);
        ZonedDateTime cursor = ZonedDateTime.ofInstant(anchor, zone);
        for (int i = 0; i < capped; i++) {
            ZonedDateTime next = expression.next(cursor);
            if (next == null) {
                break;
            }
            Instant instant = next.toInstant();
            if (config.endAt() != null && instant.isAfter(config.endAt())) {
                break;
            }
            result.add(instant);
            cursor = next;
        }
        return result;
    }

    /**
     * Computes the next fire timestamp after {@code after} when enabled, including optional bounded random jitter.
     * Returns empty when disabled or when no future tick falls before {@code endAt}.
     */
    public static Optional<Instant> computeNextFireAt(JobScheduleConfig config, String defaultTimezone, Instant after) {
        if (config == null || !config.enabled()) {
            return Optional.empty();
        }
        List<Instant> next = nextFireTimes(config, defaultTimezone, after, 1);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        Instant fireAt = next.getFirst();
        int jitter = config.effectiveJitterSeconds();
        if (jitter > 0) {
            int offset = ThreadLocalRandom.current().nextInt(jitter + 1);
            Instant jittered = fireAt.plusSeconds(offset);
            if (config.endAt() != null && jittered.isAfter(config.endAt())) {
                jittered = config.endAt();
            }
            fireAt = jittered;
        }
        return Optional.of(fireAt);
    }

    /** Produces a concise human-readable English description of a normalized cron and timezone. */
    public static String describe(String normalizedCron, ZoneId zone) {
        String macro = MACRO_DESCRIPTIONS.get(normalizedCron);
        if (macro != null) {
            return macro + " (" + zone.getId() + ")";
        }
        String[] parts = normalizedCron.split(" ");
        if (parts.length == 6) {
            String sec = parts[0];
            String min = parts[1];
            String hour = parts[2];
            String dom = parts[3];
            String mon = parts[4];
            String dow = parts[5];
            boolean wholeMinute = "0".equals(sec);
            boolean anyDay = ("*".equals(dom) || "?".equals(dom)) && ("*".equals(dow) || "?".equals(dow));
            if (wholeMinute && "*".equals(mon)) {
                if ("*".equals(min) && "*".equals(hour) && anyDay) {
                    return "Every minute (" + zone.getId() + ")";
                }
                if (min.startsWith("*/") && isDigits(min.substring(2)) && "*".equals(hour) && anyDay) {
                    return "Every " + min.substring(2) + " minutes (" + zone.getId() + ")";
                }
                if (isDigits(min) && "*".equals(hour) && anyDay) {
                    return "Every hour at minute " + min + " (" + zone.getId() + ")";
                }
                if (isDigits(min) && isDigits(hour) && "*".equals(dom) && ("*".equals(dow) || "?".equals(dow))) {
                    return "Every day at " + pad2(hour) + ":" + pad2(min) + " (" + zone.getId() + ")";
                }
                if (isDigits(min) && isDigits(hour) && isDigits(dom) && ("*".equals(dow) || "?".equals(dow))) {
                    return "At " + pad2(hour) + ":" + pad2(min) + ", on day " + dom + " of the month (" + zone.getId() + ")";
                }
                if (isDigits(min) && isDigits(hour) && ("*".equals(dom) || "?".equals(dom))) {
                    return "At " + pad2(hour) + ":" + pad2(min) + " on days " + dow + " (" + zone.getId() + ")";
                }
            }
        }
        return "Cron '" + normalizedCron + "' (" + zone.getId() + ")";
    }

    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String pad2(String num) {
        return num.length() == 1 ? "0" + num : num;
    }
}
