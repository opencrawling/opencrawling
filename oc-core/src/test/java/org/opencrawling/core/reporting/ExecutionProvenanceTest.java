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
package org.opencrawling.core.reporting;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionProvenanceTest {

    @Test
    void aVerifiedCallerIsRecordedAsTheTrigger() {
        ExecutionProvenance provenance = ExecutionProvenance.verified("alice", null, List.of("finance"), "acme",
                ExecutionProvenance.SOURCE_API, "req-42");

        assertThat(provenance.triggeredBy()).isEqualTo("alice");
        assertThat(provenance.onBehalfOf()).isNull();
        assertThat(provenance.callerGroups()).containsExactly("finance");
        assertThat(provenance.callerTenant()).isEqualTo("acme");
        assertThat(provenance.callerVerified()).isTrue();
        assertThat(provenance.triggerSource()).isEqualTo("api");
        assertThat(provenance.correlationId()).isEqualTo("req-42");
    }

    @Test
    void anAgentActingForAUserIsTheTriggerAndTheUserIsOnBehalfOf() {
        ExecutionProvenance provenance = ExecutionProvenance.verified("alice", "ingestion-agent", List.of(), null,
                ExecutionProvenance.SOURCE_API, null);

        assertThat(provenance.triggeredBy()).isEqualTo("ingestion-agent");
        assertThat(provenance.onBehalfOf()).isEqualTo("alice");
    }

    @Test
    void anAnonymousExecutionRecordsOnlyHowItStarted() {
        ExecutionProvenance provenance = ExecutionProvenance.anonymous(ExecutionProvenance.SOURCE_OIS, "batch-7");

        assertThat(provenance.triggerSource()).isEqualTo("ois");
        assertThat(provenance.correlationId()).isEqualTo("batch-7");
        assertThat(provenance.triggeredBy()).isNull();
        assertThat(provenance.callerGroups()).isEmpty();
        assertThat(provenance.callerVerified()).isFalse();
    }

    @Test
    void anUnverifiedIdentityCannotBeRecorded() {
        assertThatThrownBy(() -> new ExecutionProvenance("alice", null, List.of(), null, false, "api", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionProvenance(null, null, List.of("finance"), null, false, "api", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionProvenance(null, null, List.of(), "acme", false, "api", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void correlationIdsThatAreNotPlainIdsAreDropped() {
        assertThat(ExecutionProvenance.safeCorrelationId(" 0af7651916cd43dd8448eb211c80319c ")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(ExecutionProvenance.safeCorrelationId("urn:case/2026-11@acme")).isEqualTo("urn:case/2026-11@acme");
        assertThat(ExecutionProvenance.safeCorrelationId("line\nbreak")).isNull();
        assertThat(ExecutionProvenance.safeCorrelationId("has space")).isNull();
        assertThat(ExecutionProvenance.safeCorrelationId("")).isNull();
        assertThat(ExecutionProvenance.safeCorrelationId("x".repeat(129))).isNull();
        assertThat(ExecutionProvenance.anonymous("api", "<script>").correlationId()).isNull();
    }

    @Test
    void blankAndMissingGroupsAreDropped() {
        ExecutionProvenance provenance = ExecutionProvenance.verified("alice", null, Arrays.asList("finance", null, " "),
                null, "api", null);

        assertThat(provenance.callerGroups()).containsExactly("finance");
    }

    @Test
    void unknownProvenanceHasNothing() {
        ExecutionProvenance unknown = ExecutionProvenance.unknown();

        assertThat(unknown.triggerSource()).isNull();
        assertThat(unknown.triggeredBy()).isNull();
        assertThat(unknown.callerVerified()).isFalse();
    }
}
