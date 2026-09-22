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
package org.opencrawling.migrator.mcf.mcf.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.mcf.model.McfScheduleRecord;

import static org.assertj.core.api.Assertions.assertThat;

class McfEntityParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseJob_noScheduleChildren_producesEmptyScheduleRecords() throws Exception {
        JsonNode node = objectMapper.readTree("""
            {"_children_": [
                {"_type_": "id", "_value_": "1"},
                {"_type_": "description", "_value_": "No schedule"},
                {"_type_": "repository_connection", "_value_": "SomeRepo"}
            ]}
            """);

        McfJob job = McfEntityParser.parseJob(node);

        assertThat(job.scheduleRecords()).isEmpty();
        assertThat(job.hasSchedulingConcerns()).isFalse();
    }

    @Test
    void parseJob_singleScheduleRecord_extractsFields() throws Exception {
        JsonNode node = objectMapper.readTree("""
            {"_children_": [
                {"_type_": "id", "_value_": "1"},
                {"_type_": "description", "_value_": "Nightly crawl"},
                {"_type_": "repository_connection", "_value_": "SomeRepo"},
                {"_type_": "schedule", "_children_": [
                    {"_type_": "hourofday", "_value_": "2"},
                    {"_type_": "minutesofhour", "_value_": "30"},
                    {"_type_": "dayofweek", "_value_": "1"},
                    {"_type_": "dayofweek", "_value_": "3"}
                ]}
            ]}
            """);

        McfJob job = McfEntityParser.parseJob(node);

        assertThat(job.scheduleRecords()).hasSize(1);
        McfScheduleRecord record = job.scheduleRecords().get(0);
        assertThat(record.hoursOfDay()).containsExactly(2);
        assertThat(record.minutesOfHour()).containsExactly(30);
        assertThat(record.daysOfWeek()).containsExactly(1, 3);
        assertThat(record.monthsOfYear()).isEmpty();
        assertThat(record.daysOfMonth()).isEmpty();
        assertThat(job.hasSchedulingConcerns()).isTrue();
    }

    @Test
    void parseJob_multipleScheduleRecords_extractsEachSeparately() throws Exception {
        JsonNode node = objectMapper.readTree("""
            {"_children_": [
                {"_type_": "id", "_value_": "1"},
                {"_type_": "description", "_value_": "Twice-daily crawl"},
                {"_type_": "repository_connection", "_value_": "SomeRepo"},
                {"_type_": "schedule", "_children_": [
                    {"_type_": "hourofday", "_value_": "2"},
                    {"_type_": "minutesofhour", "_value_": "0"}
                ]},
                {"_type_": "schedule", "_children_": [
                    {"_type_": "hourofday", "_value_": "14"},
                    {"_type_": "minutesofhour", "_value_": "0"}
                ]}
            ]}
            """);

        McfJob job = McfEntityParser.parseJob(node);

        assertThat(job.scheduleRecords()).hasSize(2);
        assertThat(job.scheduleRecords().get(0).hoursOfDay()).containsExactly(2);
        assertThat(job.scheduleRecords().get(1).hoursOfDay()).containsExactly(14);
    }
}
