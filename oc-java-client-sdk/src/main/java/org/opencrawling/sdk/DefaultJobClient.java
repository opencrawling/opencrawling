/*
 * Copyright © 2026 Piergiorgio Lucidi (plucidi@ziaconsulting.com)
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

import com.fasterxml.jackson.core.type.TypeReference;
import org.opencrawling.sdk.exceptions.OpenCrawlingApiException;
import org.opencrawling.sdk.http.HttpTransport;
import org.opencrawling.sdk.models.JobRequest;
import org.opencrawling.sdk.models.JobResponse;

import java.util.List;
import java.util.Optional;

/**
 * Default implementation of JobClient using HttpTransport.
 */
public class DefaultJobClient implements JobClient {

    private final HttpTransport transport;

    public DefaultJobClient(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public List<JobResponse> list() {
        return transport.execute("GET", "/api/jobs", null, new TypeReference<List<JobResponse>>() {});
    }

    @Override
    public Optional<JobResponse> get(String id) {
        try {
            JobResponse response = transport.execute("GET", "/api/jobs/" + id, null, JobResponse.class);
            return Optional.ofNullable(response);
        } catch (OpenCrawlingApiException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public JobResponse create(JobRequest request) {
        transport.executeVoid("POST", "/api/jobs", request);
        // Fetch refreshed job list to find newly created or updated job
        List<JobResponse> jobs = list();
        if (request.id() != null && !request.id().isBlank() && !request.id().equals("new")) {
            return jobs.stream().filter(j -> j.id().equals(request.id())).findFirst().orElse(null);
        }
        return jobs.stream().filter(j -> j.name().equals(request.name())).findFirst().orElse(jobs.isEmpty() ? null : jobs.get(jobs.size() - 1));
    }

    @Override
    public JobResponse update(JobRequest request) {
        return create(request);
    }

    @Override
    public void delete(String id) {
        transport.executeVoid("DELETE", "/api/jobs/" + id, null);
    }

    @Override
    public void start(String id) {
        transport.executeVoid("POST", "/api/jobs/" + id + "/start", null);
    }

    @Override
    public void pause(String id) {
        transport.executeVoid("POST", "/api/jobs/" + id + "/pause", null);
    }

    @Override
    public void stop(String id) {
        transport.executeVoid("POST", "/api/jobs/" + id + "/stop", null);
    }
}
