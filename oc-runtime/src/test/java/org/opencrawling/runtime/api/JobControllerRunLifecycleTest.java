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
package org.opencrawling.runtime.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.connector.ConnectorResolutionException;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Job runs through the job API: one run per job, the cap on runs, a real stop, and statuses that say how a run
 * ended.
 */
class JobControllerRunLifecycleTest {

    private final List<String> createdJobs = new ArrayList<>();
    private ConnectorFactory factory;
    private CrawlStub crawl;
    private JobController controller;
    private JobRunRegistry registry;

    /** Stands in for the crawl: blocks until released or stopped, then returns the configured outcome. */
    private static final class CrawlStub extends JobOrchestrator {
        volatile CountDownLatch started = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        volatile boolean outcome = true;

        CrawlStub() {
            super(null, null, null);
        }

        @Override
        public boolean runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path,
                              String transformationConnector, String jobId, String jobName,
                              JobController.NarrativizationConfig narrativization,
                              org.opencrawling.core.pipeline.PipelineMode pipelineMode, String executionId,
                              BooleanSupplier stopRequested, org.opencrawling.core.reporting.ExecutionProvenance provenance) {
            started.countDown();
            try {
                while (!release.await(10, TimeUnit.MILLISECONDS)) {
                    if (stopRequested.getAsBoolean()) {
                        return false;
                    }
                }
            } catch (InterruptedException e) {
                return false;
            }
            return outcome;
        }
    }

    @BeforeEach
    void setUp() {
        factory = mock(ConnectorFactory.class);
        when(factory.repository(any())).thenReturn(mock(RepositoryConnector.class));
        when(factory.output(any(), any())).thenReturn(mock(OutputConnector.class));
        crawl = new CrawlStub();
        registry = new JobRunRegistry(2);
        controller = new JobController(crawl, factory, registry, mock(JdbcTemplate.class));
    }

    @AfterEach
    void cleanUp() {
        createdJobs.forEach(id -> {
            registry.stop(id);
            controller.deleteJob(id);
        });
    }

    private String newJob() {
        String name = "lifecycle-test-" + UUID.randomUUID();
        controller.saveJob(new JobController.JobDTO("new", name, "No_Such_Connector", "No_Such_Output", "", "/tmp",
                null, null, 0, null, null, null, "rag"));
        String id = controller.getAllJobs().stream().filter(job -> job.name().equals(name)).findFirst().orElseThrow().id();
        createdJobs.add(id);
        return id;
    }

    private JobController.JobDTO job(String id) {
        return controller.getJob(id).getBody();
    }

    private static int status(org.springframework.http.ResponseEntity<?> response) {
        return response.getStatusCode().value();
    }

    @Test
    void jobsSavedInTheSameMillisecondGetDistinctIds() {
        List<String> ids = List.of(newJob(), newJob(), newJob(), newJob());

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void anUnknownJobIsNotFound() {
        assertThat(status(controller.startJob("no-such-job"))).isEqualTo(404);
        assertThat(status(controller.stopJob("no-such-job"))).isEqualTo(404);
        assertThat(status(controller.getRun("no-such-job"))).isEqualTo(404);
    }

    @Test
    void aRunningJobCannotBeStartedAgain() throws Exception {
        String id = newJob();
        assertThat(status(controller.startJob(id))).isEqualTo(200);
        assertThat(crawl.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(status(controller.startJob(id))).isEqualTo(409);

        assertThat(job(id).status()).isEqualTo("Running");
        assertThat(controller.getRun(id).getBody().jobId()).isEqualTo(id);
        assertThat(controller.getRuns()).extracting(JobRunRegistry.RunInfo::jobId).contains(id);
        crawl.release.countDown();
        await().atMost(5, TimeUnit.SECONDS).until(() -> "Finished".equals(job(id).status()));
        assertThat(job(id).currentStage()).isEqualTo("Completed");
        assertThat(status(controller.getRun(id))).isEqualTo(404);
    }

    @Test
    void theCapOnRunsRejectsFurtherStarts() throws Exception {
        String a = newJob();
        String b = newJob();
        String c = newJob();
        assertThat(status(controller.startJob(a))).isEqualTo(200);
        assertThat(status(controller.startJob(b))).isEqualTo(200);

        assertThat(status(controller.startJob(c))).isEqualTo(429);

        assertThat(job(c).status()).isEqualTo("Ready");
        crawl.release.countDown();
    }

    @Test
    void stopEndsTheRunAndMarksTheJobStopped() throws Exception {
        String id = newJob();
        controller.startJob(id);
        assertThat(crawl.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(status(controller.stopJob(id))).isEqualTo(202);

        await().atMost(5, TimeUnit.SECONDS).until(() -> "Finished".equals(job(id).status()));
        assertThat(job(id).currentStage()).isEqualTo("Stopped");
        assertThat(registry.run(id)).isEmpty();
    }

    @Test
    void stoppingAJobWithoutARunMarksItFinishedAsBefore() {
        String id = newJob();

        assertThat(status(controller.stopJob(id))).isEqualTo(200);

        assertThat(job(id).status()).isEqualTo("Finished");
    }

    @Test
    void aFailedRunIsMarkedAsAnErrorNotFinished() {
        String id = newJob();
        crawl.outcome = false;
        crawl.release.countDown();

        controller.startJob(id);

        await().atMost(5, TimeUnit.SECONDS).until(() -> "Error".equals(job(id).status()));
        assertThat(job(id).currentStage()).isEqualTo("Failed");
    }

    @Test
    void aRejectedConnectorFreesTheJobsSlot() {
        String id = newJob();
        when(factory.repository(any())).thenThrow(new ConnectorResolutionException("unknown class"));

        assertThat(status(controller.startJob(id))).isEqualTo(409);
        assertThat(registry.run(id)).isEmpty();

        doReturn(mock(RepositoryConnector.class)).when(factory).repository(any());
        crawl.release.countDown();
        assertThat(status(controller.startJob(id))).isEqualTo(200);
    }
}
