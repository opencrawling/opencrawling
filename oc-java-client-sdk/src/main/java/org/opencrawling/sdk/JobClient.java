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

import org.opencrawling.sdk.models.JobRequest;
import org.opencrawling.sdk.models.JobResponse;

import java.util.List;
import java.util.Optional;

/**
 * Client for managing OpenCrawling ingestion jobs.
 */
public interface JobClient {

    /**
     * Lists all registered jobs.
     */
    List<JobResponse> list();

    /**
     * Retrieves job details by unique job ID.
     */
    Optional<JobResponse> get(String id);

    /**
     * Creates and registers a new job.
     */
    JobResponse create(JobRequest request);

    /**
     * Updates an existing job configuration.
     */
    JobResponse update(JobRequest request);

    /**
     * Deletes a job by ID.
     */
    void delete(String id);

    /**
     * Starts execution of a job by ID.
     */
    void start(String id);

    /**
     * Pauses execution of a job by ID.
     */
    void pause(String id);

    /**
     * Stops execution of a job by ID.
     */
    void stop(String id);
}
