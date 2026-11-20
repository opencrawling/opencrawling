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

import org.opencrawling.runtime.reporting.JobLedgerAccumulator;
import org.opencrawling.runtime.reporting.JobReportProperties;
import org.opencrawling.runtime.scheduler.ScheduleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Deletes what finished runs leave once it is older than {@code opencrawling.control-plane.run-retention} (90 days by
 * default; 0 keeps everything), #315:
 * <ul>
 *   <li>the runs and their history in the {@link RunStore} (in a shared store, any runtime may do it: deleting twice
 *   deletes nothing more);</li>
 *   <li>this runtime's report files (summary, manifest, Parquet and Avro ledgers) in the local report directory
 *   ({@code spring.opencrawling.reporting.storage.local-dir}), by when they were written; reports in S3 storage are
 *   left to the bucket's own lifecycle rules;</li>
 *   <li>the completed executions this runtime keeps in memory.</li>
 * </ul>
 */
@Component
public class RunRetention {

    private static final Logger log = LoggerFactory.getLogger(RunRetention.class);

    private final RunStore store;
    private final JobReportProperties reports;
    private final JobLedgerAccumulator ledger;
    private final ScheduleStore scheduleStore;
    private final Duration retention;
    private final Duration dispatchRetention;
    private final Clock clock;

    @Autowired
    public RunRetention(ObjectProvider<RunStore> store, ObjectProvider<JobReportProperties> reports,
                        ObjectProvider<JobLedgerAccumulator> ledger,
                        ObjectProvider<ScheduleStore> scheduleStore,
                        @Value("${opencrawling.control-plane.run-retention:90d}") String retention,
                        @Value("${opencrawling.scheduler.dispatch-retention:90d}") String dispatchRetention) {
        this(store.getIfAvailable(InMemoryRunStore::new), reports.getIfAvailable(), ledger.getIfAvailable(),
                scheduleStore.getIfAvailable(), DurationStyle.detectAndParse(retention),
                DurationStyle.detectAndParse(dispatchRetention), Clock.systemUTC());
    }

    public RunRetention(RunStore store, JobReportProperties reports, JobLedgerAccumulator ledger, Duration retention,
                        Clock clock) {
        this(store, reports, ledger, null, retention, retention, clock);
    }

    public RunRetention(RunStore store, JobReportProperties reports, JobLedgerAccumulator ledger,
                        ScheduleStore scheduleStore, Duration retention, Duration dispatchRetention, Clock clock) {
        this.store = store;
        this.reports = reports;
        this.ledger = ledger;
        this.scheduleStore = scheduleStore;
        this.retention = retention;
        this.dispatchRetention = dispatchRetention != null ? dispatchRetention : retention;
        this.clock = clock;
    }

    /** What one sweep deleted. */
    public record Swept(int runs, int reportFiles, int executions, int scheduleDispatches) {
        public Swept(int runs, int reportFiles, int executions) {
            this(runs, reportFiles, executions, 0);
        }
    }

    @Scheduled(initialDelayString = "${opencrawling.control-plane.run-retention-initial-delay:1m}",
            fixedDelayString = "${opencrawling.control-plane.run-retention-interval:6h}")
    public void scheduledSweep() {
        try {
            Swept swept = sweep();
            if (swept.runs() + swept.reportFiles() + swept.executions() + swept.scheduleDispatches() > 0) {
                log.info("Run retention ({}): deleted {} runs, {} report files, {} executions kept in memory, {} schedule dispatches.",
                        retention, swept.runs(), swept.reportFiles(), swept.executions(), swept.scheduleDispatches());
            }
        } catch (RuntimeException e) {
            log.warn("Run retention sweep failed; it is tried again later: {}", e.getMessage(), e);
        }
    }

    /** Deletes what is older than the retention, now. */
    public Swept sweep() {
        int runs = 0;
        int files = 0;
        int executions = 0;
        if (!retention.isZero() && !retention.isNegative()) {
            Instant cutoff = clock.instant().minus(retention);
            runs = store.deleteEndedBefore(cutoff);
            files = deleteReportFiles(cutoff);
            executions = ledger == null ? 0 : ledger.forgetCompletedBefore(cutoff);
        }
        int dispatches = 0;
        if (scheduleStore != null && dispatchRetention != null && !dispatchRetention.isZero() && !dispatchRetention.isNegative()) {
            dispatches = scheduleStore.deleteDispatchesBefore(clock.instant().minus(dispatchRetention));
        }
        return new Swept(runs, files, executions, dispatches);
    }

    private int deleteReportFiles(Instant cutoff) {
        if (reports == null || !"LOCAL".equalsIgnoreCase(reports.getStorage().getType())) {
            return 0;
        }
        Path dir = reports.getStorage().resolveLocalDir().toPath();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int deleted = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).filter(RunRetention::isReportFile).toList();
        } catch (IOException e) {
            log.warn("Run retention could not list {}: {}", dir, e.getMessage());
            return 0;
        }
        for (Path file : files) {
            try {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff) && Files.deleteIfExists(file)) {
                    deleted++;
                }
            } catch (IOException e) {
                log.warn("Run retention could not delete {}: {}", file, e.getMessage());
            }
        }
        if (deleted > 0) {
            deleteEmptyDirectories(dir);
        }
        return deleted;
    }

    /** A file the reports write: what else is in the directory is left alone. */
    static boolean isReportFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return (name.startsWith("summary_") && name.endsWith(".json"))
                || (name.startsWith("manifest_") && name.endsWith(".csv"))
                || name.endsWith(".parquet") || name.endsWith(".avro");
    }

    /** The empty partition directories (mode=/year=/month=/day=) under {@code root}, deepest first; never root. */
    private static void deleteEmptyDirectories(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path dir : walk.filter(Files::isDirectory).filter(d -> !d.equals(root))
                    .filter(d -> d.getFileName().toString().contains("="))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                try (Stream<Path> entries = Files.list(dir)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(dir);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Run retention left empty directories under {}: {}", root, e.getMessage());
        }
    }
}
