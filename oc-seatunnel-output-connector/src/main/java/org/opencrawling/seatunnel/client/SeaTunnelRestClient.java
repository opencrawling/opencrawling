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
package org.opencrawling.seatunnel.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class SeaTunnelRestClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SeaTunnelRestClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Duration timeout;

    public SeaTunnelRestClient(String baseUrl, int timeoutSeconds) {
        String cleanUrl = baseUrl.trim();
        if (cleanUrl.endsWith("/")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
        }
        this.baseUrl = cleanUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    public String submitJob(String jobName, String jobConfig) throws IOException, InterruptedException {
        String encodedName = URLEncoder.encode(jobName, StandardCharsets.UTF_8);
        String submitUrl = baseUrl + "/submit-job?jobName=" + encodedName;

        log.info("Submitting SeaTunnel job '{}' to {}", jobName, submitUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(submitUrl))
                .header("Content-Type", "text/plain")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jobConfig, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("SeaTunnel job '{}' successfully submitted. Response: {}", jobName, response.body());
            return response.body();
        } else {
            String errorMsg = "SeaTunnel job submission failed with HTTP " + response.statusCode() + ": " + response.body();
            log.error(errorMsg);
            throw new IOException(errorMsg);
        }
    }

    public String getJobs() throws IOException, InterruptedException {
        String jobsUrl = baseUrl + "/jobs";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(jobsUrl))
                .timeout(timeout)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        } else {
            throw new IOException("Failed to fetch SeaTunnel jobs with HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    public String getJobInfo(String jobId) throws IOException, InterruptedException {
        String jobInfoUrl = baseUrl + "/job-info/" + jobId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(jobInfoUrl))
                .timeout(timeout)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        } else {
            throw new IOException("Failed to fetch SeaTunnel job info for " + jobId + " (HTTP " + response.statusCode() + "): " + response.body());
        }
    }

    public boolean stopJob(String jobId) throws IOException, InterruptedException {
        String stopUrl = baseUrl + "/stop-job";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stopUrl))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString("{\"jobId\":\"" + jobId + "\"}", StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    public String getOverview() throws IOException, InterruptedException {
        String overviewUrl = baseUrl + "/overview";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(overviewUrl))
                .timeout(timeout)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        } else {
            // Fallback to /jobs if /overview is unavailable on older Zeta versions
            return getJobs();
        }
    }

    public boolean isReachable() {
        try {
            getOverview();
            return true;
        } catch (Exception e) {
            log.debug("SeaTunnel cluster unreachable at {}: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public void close() {
        // HttpClient manages its own resources; nothing to explicitly close.
    }
}
