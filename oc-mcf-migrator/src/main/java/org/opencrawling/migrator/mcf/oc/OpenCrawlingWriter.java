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
package org.opencrawling.migrator.mcf.oc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.sdk.DefaultOpenCrawlingClient;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.models.ConnectorRequest;
import org.opencrawling.sdk.models.JobRequest;
import org.opencrawling.sdk.v1.ApiProblemException;
import org.opencrawling.sdk.v1.PlatformClient;
import org.opencrawling.sdk.v1.RequestOptions;
import org.opencrawling.sdk.v1.model.ConnectorInput;
import org.opencrawling.sdk.v1.model.Job;
import org.opencrawling.sdk.v1.model.JobInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * The only class in this tool that writes to OpenCrawling — everything else only builds plans.
 * Built on {@code oc-java-client-sdk} rather than a bespoke REST client, for wire-format fidelity
 * with the live {@code ConnectorController}/{@code JobController} and as an act of upstream
 * citizenship (reusing the platform's own published client).
 *
 * <p>Writes through the Platform API ({@code /api/v1}). Re-running a migration updates what an
 * earlier run created instead of duplicating it: a connector is created, or replaced when one with
 * that name exists ({@code 409 connector-exists}); a job is replaced when a job with the same name
 * exists, since the server does not deduplicate jobs by name, and created otherwise. A replace names
 * the version it replaces ({@code If-Match}), so re-runs also work on a runtime that sets
 * {@code opencrawling.api.require-if-match=true}.
 */
public class OpenCrawlingWriter {

    private static final Logger log = LoggerFactory.getLogger(OpenCrawlingWriter.class);

    private final PlatformClient api;

    public OpenCrawlingWriter(String baseUrl, String apiKey, String bearerToken, int timeoutSeconds) {
        if ((apiKey == null || apiKey.isBlank()) && (bearerToken == null || bearerToken.isBlank())) {
            // Not an error — the wizard's self-loopback /apply call intentionally has no credentials
            // to send today (OpenCrawling doesn't gate its own /api/connectors and /api/jobs behind
            // auth for localhost callers). Logged so an operator who later adds that auth gate sees
            // why every upsert from here started failing, rather than debugging it as a mapping bug.
            log.debug("Constructing OpenCrawlingWriter for '{}' with no API key or bearer token; requests will be unauthenticated.", baseUrl);
        }
        OpenCrawlingClient client = new DefaultOpenCrawlingClient(
            HttpClient.newHttpClient(), baseUrl, apiKey, bearerToken, Duration.ofSeconds(timeoutSeconds), new ObjectMapper());
        this.api = client.platform();
    }

    /**
     * For writers that save another way, such as in-process inside the runtime: they override
     * {@link #upsertConnector} and {@link #upsertJob} and have no HTTP client.
     */
    protected OpenCrawlingWriter() {
        this.api = null;
    }

    public void upsertConnector(ConnectorRequest request) {
        log.info("Upserting connector '{}' (type={}, class={})", request.name(), request.type(), request.className());
        ConnectorInput input = new ConnectorInput(request.name(), request.description(), request.type(),
            request.className(), request.maxConnections() > 0 ? request.maxConnections() : null, request.configuration());
        try {
            api.connectors().create(input, RequestOptions.none());
        } catch (ApiProblemException e) {
            if (!"connector-exists".equals(e.code())) {
                throw e;
            }
            log.info("Connector '{}' already exists; replacing it", request.name());
            // Names the version it replaces, so it also works on a runtime with opencrawling.api.require-if-match.
            api.connectors().replace(request.name(), input, versionOf(api.connectors().get(request.name()).etag()));
        }
    }

    public void upsertJob(JobRequest request) {
        JobInput input = new JobInput(request.name(), request.repositoryConnector(), request.outputConnector(),
            blankToNull(request.authorityConnector()), blankToNull(request.transformationConnector()), request.path(),
            blankToNull(request.pipelineMode()), null, request.narrativization(), null, request.schedule());
        Optional<Job> existing = api.jobs().stream(PlatformClient.JobFilter.all().withText(request.name()))
            .filter(job -> job.name().equals(request.name()))
            .findFirst();
        if (existing.isPresent()) {
            log.info("Job '{}' already exists (id={}); updating in place instead of creating a duplicate",
                request.name(), existing.get().id());
            api.jobs().replace(existing.get().id(), input, versionOf(api.jobs().get(existing.get().id()).etag()));
        } else {
            log.info("Creating job '{}'", request.name());
            api.jobs().create(input, RequestOptions.none());
        }
    }

    /** If-Match on the version just read; none when the runtime sent no ETag. */
    private static RequestOptions versionOf(String etag) {
        return etag == null ? RequestOptions.none() : RequestOptions.ifMatch(etag);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
