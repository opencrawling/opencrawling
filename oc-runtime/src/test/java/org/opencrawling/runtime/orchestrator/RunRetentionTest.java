/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
package org.opencrawling.runtime.orchestrator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.runtime.orchestrator.RunStore.RunState;
import org.opencrawling.runtime.reporting.JobLedgerAccumulator;
import org.opencrawling.runtime.reporting.JobReportProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The run retention (#315, part 3): what finished runs leave is deleted once it is older than the retention. */
class RunRetentionTest {

    static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    static final Duration RETENTION = Duration.ofDays(90);

    @TempDir
    Path reports;

    private JobReportProperties properties() {
        JobReportProperties properties = new JobReportProperties();
        properties.getStorage().setLocalDir(reports.toString());
        return properties;
    }

    private Path file(String relative, Instant written) throws IOException {
        Path file = reports.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Files.setLastModifiedTime(file, FileTime.from(written));
        return file;
    }

    @Test
    void endedRunsAndReportFilesOlderThanTheRetentionAreDeleted() throws IOException {
        Instant old = NOW.minus(RETENTION).minusSeconds(60);
        Instant recent = NOW.minus(RETENTION).plusSeconds(60);
        InMemoryRunStore store = new InMemoryRunStore();
        store.claim("job-1", "exec-old", null, "rt", old.minusSeconds(10), 0, 0);
        store.finish("exec-old", RunState.COMPLETED, "rt", old, null);
        store.claim("job-1", "exec-recent", null, "rt", recent.minusSeconds(10), 0, 0);
        store.finish("exec-recent", RunState.COMPLETED, "rt", recent, null);
        store.claim("job-2", "exec-active", null, "rt", old.minusSeconds(100), 0, 0);

        Path oldSummary = file("summary_exec-old.json", old);
        Path oldManifest = file("manifest_exec-old.csv", old);
        Path oldParquet = file("mode=rag/year=2026/month=08/day=18/job_1_exec_exec-old.parquet", old);
        Path oldAvro = file("mode=rag/year=2026/month=08/day=18/job_1_exec_exec-old.avro", old);
        Path recentSummary = file("summary_exec-recent.json", recent);
        Path recentParquet = file("mode=rag/year=2026/month=08/day=20/job_1_exec_exec-recent.parquet", recent);
        Path notAReport = file("notes.txt", old);
        Path emptyByChoice = Files.createDirectories(reports.resolve("keep"));

        JobLedgerAccumulator ledger = new JobLedgerAccumulator(event -> { });
        RunRetention retention = new RunRetention(store, properties(), ledger, RETENTION, Clock.fixed(NOW, ZoneOffset.UTC));

        RunRetention.Swept swept = retention.sweep();

        assertThat(swept.runs()).isEqualTo(1);
        assertThat(swept.reportFiles()).isEqualTo(4);
        assertThat(store.get("exec-old")).isEmpty();
        assertThat(store.get("exec-recent")).isPresent();
        assertThat(store.get("exec-active")).as("an active run is never deleted").isPresent();
        assertThat(oldSummary).doesNotExist();
        assertThat(oldManifest).doesNotExist();
        assertThat(oldParquet).doesNotExist();
        assertThat(oldAvro).doesNotExist();
        assertThat(reports.resolve("mode=rag/year=2026/month=08/day=18")).as("its emptied partition").doesNotExist();
        assertThat(recentSummary).exists();
        assertThat(recentParquet).exists();
        assertThat(notAReport).as("not a report file").exists();
        assertThat(emptyByChoice).as("not a report partition").exists();
    }

    @Test
    void completedExecutionsKeptInMemoryAreForgottenAfterTheRetention() {
        JobLedgerAccumulator ledger = new JobLedgerAccumulator(event -> { });
        ledger.startExecution("job-1", "exec-1", "Job", PipelineMode.RAG, "Repo", "Out");
        ledger.completeExecution("exec-1", true, Map.of());
        RunRetention retention = new RunRetention(new InMemoryRunStore(), null, ledger, RETENTION,
                Clock.fixed(Instant.now().plus(RETENTION).plusSeconds(60), ZoneOffset.UTC));

        assertThat(retention.sweep().executions()).isEqualTo(1);
        assertThat(ledger.getCompletedEvent("exec-1")).isNull();
    }

    @Test
    void aRetentionOfZeroKeepsEverything() throws IOException {
        InMemoryRunStore store = new InMemoryRunStore();
        store.claim("job-1", "exec-old", null, "rt", Instant.EPOCH, 0, 0);
        store.finish("exec-old", RunState.COMPLETED, "rt", Instant.EPOCH, null);
        Path summary = file("summary_exec-old.json", Instant.EPOCH);

        RunRetention.Swept swept = new RunRetention(store, properties(), null, Duration.ZERO,
                Clock.fixed(NOW, ZoneOffset.UTC)).sweep();

        assertThat(swept).isEqualTo(new RunRetention.Swept(0, 0, 0));
        assertThat(store.get("exec-old")).isPresent();
        assertThat(summary).exists();
    }

    @Test
    void oldScheduleDispatchesAreSweptWhileRecentOnesAreKept() {
        Instant old = NOW.minus(RETENTION).minusSeconds(60);
        Instant recent = NOW.minus(RETENTION).plusSeconds(60);
        var scheduleStore = new org.opencrawling.runtime.scheduler.InMemoryScheduleStore();
        scheduleStore.recordDispatch(new org.opencrawling.runtime.scheduler.ScheduleDispatchRecord(
                "d-old", "job-1", old, old, "rt", "e-1",
                org.opencrawling.runtime.scheduler.DispatchOutcome.STARTED, null));
        scheduleStore.recordDispatch(new org.opencrawling.runtime.scheduler.ScheduleDispatchRecord(
                "d-recent", "job-1", recent, recent, "rt", "e-2",
                org.opencrawling.runtime.scheduler.DispatchOutcome.STARTED, null));

        RunRetention retention = new RunRetention(new InMemoryRunStore(), null, null,
                scheduleStore, RETENTION, RETENTION, Clock.fixed(NOW, ZoneOffset.UTC));
        RunRetention.Swept swept = retention.sweep();

        assertThat(swept.scheduleDispatches()).isEqualTo(1);
        assertThat(scheduleStore.listDispatches("job-1"))
                .extracting(org.opencrawling.runtime.scheduler.ScheduleDispatchRecord::id)
                .containsExactly("d-recent");
    }
}
