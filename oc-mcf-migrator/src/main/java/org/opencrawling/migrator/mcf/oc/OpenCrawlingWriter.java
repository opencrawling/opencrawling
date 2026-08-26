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
package org.opencrawling.migrator.mcf.oc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.sdk.DefaultOpenCrawlingClient;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.models.ConnectorRequest;
import org.opencrawling.sdk.models.JobRequest;
import org.opencrawling.sdk.models.JobResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The only class in this tool that writes to OpenCrawling — everything else only builds plans.
 * Built on {@code oc-java-client-sdk} rather than a bespoke REST client, for wire-format fidelity
 * with the live {@code ConnectorController}/{@code JobController} and as an act of upstream
 * citizenship (reusing the platform's own published client).
 *
 * <p>Connector upserts are naturally idempotent — {@code ConnectorController.createConnector}
 * removes any existing connector with the same name before adding, confirmed in source — but job
 * creation is not: {@code POST /api/jobs} with a blank id always mints a new job, with no
 * server-side dedup by name. {@link #upsertJob} does the dedup itself: look up an existing job by
 * name first, and if found, resend the request with that job's id so the server updates in place
 * instead of creating a duplicate on every re-run.
 */
public class OpenCrawlingWriter {

    private static final Logger log = LoggerFactory.getLogger(OpenCrawlingWriter.class);

    private final OpenCrawlingClient client;

    public OpenCrawlingWriter(String baseUrl, String apiKey, String bearerToken, int timeoutSeconds) {
        if ((apiKey == null || apiKey.isBlank()) && (bearerToken == null || bearerToken.isBlank())) {
            // Not an error — the wizard's self-loopback /apply call intentionally has no credentials
            // to send today (OpenCrawling doesn't gate its own /api/connectors and /api/jobs behind
            // auth for localhost callers). Logged so an operator who later adds that auth gate sees
            // why every upsert from here started failing, rather than debugging it as a mapping bug.
            log.debug("Constructing OpenCrawlingWriter for '{}' with no API key or bearer token; requests will be unauthenticated.", baseUrl);
        }
        this.client = new DefaultOpenCrawlingClient(
            HttpClient.newHttpClient(), baseUrl, apiKey, bearerToken, Duration.ofSeconds(timeoutSeconds), new ObjectMapper());
    }

    public void upsertConnector(ConnectorRequest request) {
        log.info("Upserting connector '{}' (type={}, class={})", request.name(), request.type(), request.className());
        client.connectors().create(request);
    }

    public void upsertJob(JobRequest request) {
        Optional<JobResponse> existing = client.jobs().list().stream()
            .filter(job -> job.name().equals(request.name()))
            .findFirst();

        if (existing.isPresent()) {
            log.info("Job '{}' already exists (id={}); updating in place instead of creating a duplicate",
                request.name(), existing.get().id());
            client.jobs().create(withId(request, existing.get().id()));
        } else {
            log.info("Creating job '{}'", request.name());
            client.jobs().create(request);
        }
    }

    private static JobRequest withId(JobRequest request, String id) {
        return JobRequest.builder()
            .id(id)
            .name(request.name())
            .repositoryConnector(request.repositoryConnector())
            .outputConnector(request.outputConnector())
            .authorityConnector(request.authorityConnector())
            .path(request.path())
            .status(request.status())
            .currentStage(request.currentStage())
            .documents(request.documents())
            .lastRun(request.lastRun())
            .transformationConnector(request.transformationConnector())
            .narrativization(request.narrativization())
            .build();
    }
}
