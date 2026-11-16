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

import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFApiException;
import org.opencrawling.migrator.mcf.oc.OpenCrawlingWriter;
import org.opencrawling.sdk.models.ConnectorRequest;
import org.opencrawling.sdk.models.JobRequest;
import org.springframework.http.ResponseEntity;

/**
 * Writes a ManifoldCF migration's connectors and jobs straight into this runtime's controllers, on the request
 * thread: the calling admin's identity and the usual validation and authorization apply. Unlike a loopback HTTP call
 * it needs no credentials, so it also works with {@code protect-api} on, behind any port or context path.
 */
class InProcessMigrationWriter extends OpenCrawlingWriter {

    private final ConnectorController connectors;
    private final JobController jobs;

    InProcessMigrationWriter(ConnectorController connectors, JobController jobs) {
        this.connectors = connectors;
        this.jobs = jobs;
    }

    @Override
    public void upsertConnector(ConnectorRequest request) {
        ResponseEntity<Void> result = connectors.save(new ConnectorController.ConnectorDTO(request.name(),
                request.description(), request.type(), request.className(), request.maxConnections(), request.configuration()));
        if (result.getStatusCode().isError()) {
            throw new ManifoldCFApiException("Saving connector '" + request.name() + "' failed: HTTP " + result.getStatusCode().value());
        }
    }

    /** Updates the job with the same name if there is one, so a re-run does not create duplicates. */
    @Override
    public void upsertJob(JobRequest request) {
        String id = jobs.getAllJobs().stream()
                .filter(job -> job.name() != null && job.name().equals(request.name()))
                .map(JobController.JobDTO::id)
                .findFirst().orElse(null);
        JobController.JobDTO job = new JobController.JobDTO(id, request.name(), request.repositoryConnector(),
                request.outputConnector(), request.authorityConnector() == null ? "" : request.authorityConnector(), request.path(),
                null, null, 0, null, request.transformationConnector(),
                request.narrativization() == null ? null : new JobController.NarrativizationConfig(
                        request.narrativization().enabled(), request.narrativization().template(),
                        request.narrativization().connectorType()),
                request.pipelineMode(), null, null);
        JobController.SaveOutcome outcome = jobs.save(job);
        if (!(outcome instanceof JobController.SaveOutcome.Saved)) {
            throw new ManifoldCFApiException("Saving job '" + request.name() + "' failed: " + outcome);
        }
    }
}
