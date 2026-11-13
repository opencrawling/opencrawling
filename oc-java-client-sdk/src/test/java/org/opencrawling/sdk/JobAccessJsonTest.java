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
package org.opencrawling.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.models.JobAccess;
import org.opencrawling.sdk.models.JobRequest;
import org.opencrawling.sdk.models.JobResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobAccessJsonTest {

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void aJobRequestSendsItsAccessOnlyWhenSet() throws Exception {
        JobRequest withAccess = JobRequest.builder().name("hr").access(JobAccess.forGroups(List.of("hr-team"), List.of("auditors"))).build();
        String sent = json.writeValueAsString(withAccess);
        assertThat(sent).contains("\"access\":{\"allowedGroups\":[\"hr-team\"],\"viewerGroups\":[\"auditors\"]}");

        // Without access the runtime keeps the job's current access on update.
        assertThat(json.writeValueAsString(JobRequest.builder().name("hr").build())).doesNotContain("access");
    }

    @Test
    void aJobResponseCarriesTheAccessTheRuntimeStored() throws Exception {
        JobResponse job = json.readValue("""
                {"id":"7","name":"hr","access":{"owner":"hr.lead","tenantId":"acme","allowedUsers":["clerk"],
                 "allowedGroups":["hr-team"],"viewerUsers":[],"viewerGroups":["auditors"]}}""", JobResponse.class);
        assertThat(job.access()).isEqualTo(new JobAccess("hr.lead", "acme", List.of("clerk"), List.of("hr-team"),
                List.of(), List.of("auditors")));
        assertThat(json.readValue("{\"id\":\"1\"}", JobResponse.class).access()).isNull();
    }
}
