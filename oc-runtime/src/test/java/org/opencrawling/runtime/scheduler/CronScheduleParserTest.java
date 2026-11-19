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

import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.scheduler.JobScheduleConfig.MisfirePolicy;
import org.opencrawling.runtime.scheduler.JobScheduleConfig.OverlapPolicy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link CronScheduleParser} (#197). */
class CronScheduleParserTest {

    private static final SchedulerProperties DEFAULTS = new SchedulerProperties();

    private static JobScheduleConfig config(String cron, String tz, boolean enabled) {
        return new JobScheduleConfig(cron, tz, enabled, OverlapPolicy.SKIP, MisfirePolicy.SKIP, null, null, null, null);
    }

    @Test
    void normalizesFiveFieldSixFieldAndMacros() {
        assertThat(CronScheduleParser.normalize("0  2  *  *  *")).isEqualTo("0 0 2 * * *");
        assertThat(CronScheduleParser.normalize("30 15 10 * * MON-FRI")).isEqualTo("30 15 10 * * MON-FRI");
        assertThat(CronScheduleParser.normalize(" @HOURLY ")).isEqualTo("@hourly");
    }

    @Test
    void rejectsInvalidFieldCountsUnknownZonesIntervalsAndBounds() {
        Instant now = Instant.parse("2026-11-19T12:00:00Z");

        assertThatThrownBy(() -> CronScheduleParser.validate(config("* * * *", "UTC", true), DEFAULTS, now))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessageContaining("5 fields");

        assertThat(CronScheduleParser.validate(config("0 * * * * *", "UTC", true), DEFAULTS, now).normalizedCron())
                .isEqualTo("0 * * * * *");

        assertThatThrownBy(() -> CronScheduleParser.validate(config("*/30 * * * * *", "UTC", true), DEFAULTS, now))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessageContaining("below the minimum allowed interval");

        SchedulerProperties subMinuteAllowed = new SchedulerProperties();
        subMinuteAllowed.setMinInterval(Duration.ZERO);
        assertThat(CronScheduleParser.validate(config("*/10 * * * * *", "UTC", true), subMinuteAllowed, now)
                .normalizedCron()).isEqualTo("*/10 * * * * *");

        assertThatThrownBy(() -> CronScheduleParser.validate(config("0 2 * * *", "Mars/Olympus", true), DEFAULTS, now))
                .isInstanceOf(ScheduleValidationException.class)
                .hasMessageContaining("timezone");

        JobScheduleConfig badJitter = new JobScheduleConfig("0 2 * * *", "UTC", true, null, null, 3601, null, null, null);
        assertThatThrownBy(() -> CronScheduleParser.validate(badJitter, DEFAULTS, now))
                .isInstanceOf(ScheduleValidationException.class)
                .extracting(e -> ((ScheduleValidationException) e).getField())
                .isEqualTo("jitterSeconds");

        JobScheduleConfig badWindow = new JobScheduleConfig(
                "0 2 * * *", "UTC", true, null, null, null,
                now.plusSeconds(60), now.plusSeconds(60), null);
        assertThatThrownBy(() -> CronScheduleParser.validate(badWindow, DEFAULTS, now))
                .isInstanceOf(ScheduleValidationException.class)
                .extracting(e -> ((ScheduleValidationException) e).getField())
                .isEqualTo("endAt");
    }

    @Test
    void handlesDstSpringForwardAndFallBackInEuropeRomeAndAmericaNewYork() {
        // 2026 Europe/Rome spring-forward is 2026-03-29 02:00 CET -> 03:00 CEST (UTC+1 -> UTC+2).
        // 02:30 local time does not exist on 2026-03-29 in Europe/Rome, so Spring skips directly to 2026-03-30!
        JobScheduleConfig rome = config("30 2 * * *", "Europe/Rome", true);
        List<Instant> romeSpring = CronScheduleParser.nextFireTimes(
                rome, "UTC", Instant.parse("2026-03-28T00:00:00Z"), 3);
        assertThat(romeSpring).containsExactly(
                Instant.parse("2026-03-28T01:30:00Z"),
                Instant.parse("2026-03-30T00:30:00Z"),
                Instant.parse("2026-03-31T00:30:00Z"));

        // 2026 America/New_York fall-back is 2026-11-01 02:00 EDT -> 01:00 EST (UTC-4 -> UTC-5).
        // Daily 09:00 switches from 13:00Z (EDT) on Oct 31 to 14:00Z (EST) on Nov 1.
        JobScheduleConfig ny = config("0 9 * * *", "America/New_York", true);
        List<Instant> nyFall = CronScheduleParser.nextFireTimes(
                ny, "UTC", Instant.parse("2026-10-31T00:00:00Z"), 3);
        assertThat(nyFall).containsExactly(
                Instant.parse("2026-10-31T13:00:00Z"),
                Instant.parse("2026-11-01T14:00:00Z"),
                Instant.parse("2026-11-02T14:00:00Z"));
    }

    @Test
    void respectsStartAtEndAtAndBoundedJitter() {
        Instant startAt = Instant.parse("2026-11-19T02:00:00Z");
        Instant endAt = Instant.parse("2026-11-19T03:00:10Z");
        JobScheduleConfig windowed = new JobScheduleConfig(
                "@hourly", "UTC", true, null, null, 45, startAt, endAt, null);

        List<Instant> preview = CronScheduleParser.nextFireTimes(
                windowed, "UTC", Instant.parse("2026-11-19T00:15:00Z"), 5);
        assertThat(preview).containsExactly(
                Instant.parse("2026-11-19T02:00:00Z"),
                Instant.parse("2026-11-19T03:00:00Z"));

        // At 02:50Z the only upcoming unjittered tick is 03:00:00Z, and jitter=45s must clamp at endAt (03:00:10Z).
        for (int i = 0; i < 20; i++) {
            Instant next = CronScheduleParser.computeNextFireAt(
                    windowed, "UTC", Instant.parse("2026-11-19T02:50:00Z")).orElseThrow();
            assertThat(next)
                    .isAfterOrEqualTo(Instant.parse("2026-11-19T03:00:00Z"))
                    .isBeforeOrEqualTo(endAt);
        }

        assertThat(CronScheduleParser.computeNextFireAt(config("@hourly", "UTC", false), "UTC", startAt))
                .isEmpty();
    }

    @Test
    void producesHumanReadableDescriptions() {
        ZoneId utc = ZoneId.of("UTC");
        assertThat(CronScheduleParser.describe("0 * * * * *", utc)).isEqualTo("Every minute (UTC)");
        assertThat(CronScheduleParser.describe("0 */15 * * * *", utc)).isEqualTo("Every 15 minutes (UTC)");
        assertThat(CronScheduleParser.describe("0 0 2 * * *", ZoneId.of("Europe/Rome")))
                .isEqualTo("Every day at 02:00 (Europe/Rome)");
        assertThat(CronScheduleParser.describe("@weekly", utc)).isEqualTo("Every Sunday at 00:00 (UTC)");
    }
}
