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
package org.opencrawling.runtime.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.event.JobExecutionCompletedEvent;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stopped run ends on its own thread, which the stop interrupted (#428). Its reports must still be written, and the
 * interrupt must still be there for the run's code afterwards.
 */
class StoppedRunReportsTest {

    @TempDir
    Path reports;

    JobLedgerAccumulator accumulator;

    @BeforeEach
    void setUp() {
        JobReportProperties properties = new JobReportProperties();
        properties.getStorage().setLocalDir(reports.toString());
        properties.getFormats().getParquet().setEnabled(true);
        properties.getFormats().setJson(true);
        properties.getFormats().setCsv(true);
        ParquetJobReportGenerator parquet = new ParquetJobReportGenerator(properties);
        OperationalReportGenerator operational = new OperationalReportGenerator(properties);
        // The listeners as Spring calls them: synchronously, on the thread that completes the execution.
        accumulator = new JobLedgerAccumulator(event -> {
            if (event instanceof JobExecutionCompletedEvent completed) {
                parquet.onJobExecutionCompleted(completed);
                operational.onJobExecutionCompleted(completed);
            }
        });
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void aRunCompletedOnAnInterruptedThreadKeepsItsReportsAndTheInterrupt() throws IOException {
        // As in a runtime that has already written reports: the writers' one-time setup (Hadoop's file system) is
        // done, so the interrupt reaches the file writes themselves.
        accumulator.startExecution("41", "exec-earlier", "Earlier job", PipelineMode.RAG, "FileSystem", "PGVector");
        accumulator.completeExecution("exec-earlier", true, Map.of());

        accumulator.startExecution("42", "exec-stopped", "Stopped job", PipelineMode.RAG, "FileSystem", "PGVector");
        accumulator.recordDocument(DocumentLedgerEntry.ragSuccess("42", "exec-stopped", "doc-1", "file:///docs/a.txt",
                "FileSystem", "PGVector", 12L, 2, 100, "nomic-embed-text", 768, "vector_store_768"));

        Thread.currentThread().interrupt(); // what JobRunRegistry.stop does to the run's thread
        accumulator.completeExecution("exec-stopped", false, Map.of("error", "stopped"));

        boolean stillInterrupted = Thread.interrupted();
        assertThat(files().stream().filter(name -> name.contains("exec-stopped")))
                .as("the stopped run's reports").anySatisfy(name -> assertThat(name).endsWith(".parquet"))
                .anySatisfy(name -> assertThat(name).endsWith(".json"))
                .anySatisfy(name -> assertThat(name).endsWith(".csv"));
        assertThat(stillInterrupted).as("the interrupt is restored for the run's code").isTrue();
    }

    @Test
    void aRunCompletedOnAThreadThatIsNotInterruptedStaysSo() {
        accumulator.startExecution("42", "exec-done", "Done job", PipelineMode.RAG, "FileSystem", "PGVector");
        accumulator.completeExecution("exec-done", true, Map.of());

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private List<String> files() throws IOException {
        try (Stream<Path> walk = Files.walk(reports)) {
            return walk.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(name -> !name.startsWith(".")).toList();
        }
    }
}
