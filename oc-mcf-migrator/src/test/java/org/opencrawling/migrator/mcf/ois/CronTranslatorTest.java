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

import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.mcf.model.McfScheduleRecord;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CronTranslatorTest {

    @Test
    void tryTranslate_singleRecordWithOneHourAndMinute_producesDailyCron() {
        McfScheduleRecord record = new McfScheduleRecord(List.of(), List.of(2), List.of(30), List.of(), List.of(), null);
        Optional<String> result = CronTranslator.tryTranslate(List.of(record));
        assertThat(result).contains("30 2 * * *");
    }

    @Test
    void tryTranslate_singleRecordWithDaysOfWeek_producesWeekdayCron() {
        McfScheduleRecord record = new McfScheduleRecord(List.of(1, 3, 5), List.of(9), List.of(0), List.of(), List.of(), null);
        Optional<String> result = CronTranslator.tryTranslate(List.of(record));
        assertThat(result).contains("0 9 * * 1,3,5");
    }

    @Test
    void tryTranslate_noRecords_returnsEmpty() {
        assertThat(CronTranslator.tryTranslate(List.of())).isEmpty();
    }

    @Test
    void tryTranslate_multipleRecords_returnsEmpty() {
        McfScheduleRecord record = new McfScheduleRecord(List.of(), List.of(9), List.of(0), List.of(), List.of(), null);
        assertThat(CronTranslator.tryTranslate(List.of(record, record))).isEmpty();
    }

    @Test
    void tryTranslate_monthOfYearRestriction_returnsEmpty() {
        McfScheduleRecord record = new McfScheduleRecord(List.of(), List.of(9), List.of(0), List.of(6), List.of(), null);
        assertThat(CronTranslator.tryTranslate(List.of(record))).isEmpty();
    }

    @Test
    void tryTranslate_dayOfMonthRestriction_returnsEmpty() {
        McfScheduleRecord record = new McfScheduleRecord(List.of(), List.of(9), List.of(0), List.of(), List.of(15), null);
        assertThat(CronTranslator.tryTranslate(List.of(record))).isEmpty();
    }

    @Test
    void tryTranslate_multipleHoursInOneRecord_returnsEmpty() {
        McfScheduleRecord record = new McfScheduleRecord(List.of(), List.of(9, 15), List.of(0), List.of(), List.of(), null);
        assertThat(CronTranslator.tryTranslate(List.of(record))).isEmpty();
    }

    @Test
    void tryTranslate_multipleMinutesInOneRecord_returnsEmpty() {
        McfScheduleRecord record = new McfScheduleRecord(List.of(), List.of(9), List.of(0, 30), List.of(), List.of(), null);
        assertThat(CronTranslator.tryTranslate(List.of(record))).isEmpty();
    }
}
