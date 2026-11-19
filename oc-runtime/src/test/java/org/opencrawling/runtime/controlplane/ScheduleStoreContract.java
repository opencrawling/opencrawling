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
package org.opencrawling.runtime.controlplane;

import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.scheduler.DispatchOutcome;
import org.opencrawling.runtime.scheduler.JobScheduleState;
import org.opencrawling.runtime.scheduler.ScheduleDispatchRecord;
import org.opencrawling.runtime.scheduler.ScheduleStore;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract tests shared across every {@link ScheduleStore} implementation (#197). */
abstract class ScheduleStoreContract {

    abstract ScheduleStore freshStore();

    ScheduleStore otherRuntime() {
        return null;
    }

    ScheduleStore restarted() {
        return null;
    }

    @Test
    void savesQueriesDueAndDeletesStates() {
        ScheduleStore store = freshStore();
        Instant now = Instant.parse("2026-11-19T12:00:00Z");

        store.saveState(new JobScheduleState("j-later", true, now.plusSeconds(300),
                null, null, null, null, false, 0, now));
        store.saveState(new JobScheduleState("j-paused", false, now.minusSeconds(60),
                null, null, null, null, false, 0, now));
        store.saveState(new JobScheduleState("j-due-2", true, now,
                null, null, DispatchOutcome.STARTED, null, false, 0, now));
        store.saveState(new JobScheduleState("j-due-1", true, now.minusSeconds(120),
                null, "exec-1", DispatchOutcome.STARTED, null, true, 1, now));

        assertThat(store.findDue(now, 10)).extracting(JobScheduleState::jobId)
                .containsExactly("j-due-1", "j-due-2");
        assertThat(store.findDue(now, 1)).extracting(JobScheduleState::jobId)
                .containsExactly("j-due-1");

        // Update existing state
        store.saveState(new JobScheduleState("j-due-1", true, now.plusSeconds(600),
                now, "exec-2", DispatchOutcome.STARTED, null, false, 0, now));
        assertThat(store.getState("j-due-1")).hasValueSatisfying(s -> {
            assertThat(s.nextFireAt()).isEqualTo(now.plusSeconds(600));
            assertThat(s.consecutiveFailures()).isZero();
            assertThat(s.coalescePending()).isFalse();
        });
        assertThat(store.findDue(now, 10)).extracting(JobScheduleState::jobId)
                .containsExactly("j-due-2");

        ScheduleStore restarted = restarted();
        if (restarted != null) {
            assertThat(restarted.getAllStates()).hasSize(4);
            assertThat(restarted.getState("j-due-1")).hasValueSatisfying(s ->
                    assertThat(s.lastExecutionId()).isEqualTo("exec-2"));
        }

        ScheduleStore peer = otherRuntime();
        if (peer != null) {
            assertThat(peer.getAllStates()).hasSize(4);
            peer.deleteState("j-paused");
            assertThat(store.getState("j-paused")).isEmpty();
        } else {
            store.deleteState("j-paused");
            assertThat(store.getState("j-paused")).isEmpty();
        }
    }

    @Test
    void recordsListsCountsAndPrunesDispatches() {
        ScheduleStore store = freshStore();
        Instant t0 = Instant.parse("2026-11-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-11-18T12:00:00Z");
        Instant t2 = Instant.parse("2026-11-19T12:00:00Z");

        store.recordDispatch(new ScheduleDispatchRecord("d-old", "job-1", t0, t0, "rt-a", "e-0", DispatchOutcome.STARTED, null));
        store.recordDispatch(new ScheduleDispatchRecord("d-skip", "job-1", t1, t1, "rt-a", null, DispatchOutcome.SKIPPED_OVERLAP, "busy"));
        store.recordDispatch(new ScheduleDispatchRecord("d-new", "job-1", t2, t2, "rt-b", "e-2", DispatchOutcome.STARTED, null));
        store.recordDispatch(new ScheduleDispatchRecord("d-other", "job-2", t2, t2, "rt-a", null, DispatchOutcome.REJECTED, "bad"));

        assertThat(store.listDispatches("job-1")).extracting(ScheduleDispatchRecord::id)
                .containsExactly("d-new", "d-skip", "d-old");
        assertThat(store.countDispatchesSince(t1.minusSeconds(1), null)).isEqualTo(3);
        assertThat(store.countDispatchesSince(t1.minusSeconds(1),
                List.of(DispatchOutcome.SKIPPED_OVERLAP, DispatchOutcome.REJECTED))).isEqualTo(2);

        int deleted = store.deleteDispatchesBefore(t0.plusSeconds(60));
        assertThat(deleted).isEqualTo(1);
        assertThat(store.listDispatches("job-1")).extracting(ScheduleDispatchRecord::id)
                .containsExactly("d-new", "d-skip");

        ScheduleStore restarted = restarted();
        if (restarted != null) {
            assertThat(restarted.listDispatches("job-1")).extracting(ScheduleDispatchRecord::id)
                    .containsExactly("d-new", "d-skip");
        }
    }
}
