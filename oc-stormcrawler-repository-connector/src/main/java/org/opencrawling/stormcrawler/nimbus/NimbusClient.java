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
package org.opencrawling.stormcrawler.nimbus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * REST Client for interacting with Apache Storm Nimbus and Storm UI REST APIs.
 */
public class NimbusClient {

    private static final Logger log = LoggerFactory.getLogger(NimbusClient.class);

    private final String restBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public NimbusClient(String restBaseUrl) {
        this(restBaseUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public NimbusClient(String restBaseUrl, HttpClient httpClient) {
        this.restBaseUrl = restBaseUrl.endsWith("/") ? restBaseUrl.substring(0, restBaseUrl.length() - 1) : restBaseUrl;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    public boolean ping() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(restBaseUrl + "/api/v1/cluster/summary"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Nimbus ping failed to {}: {}", restBaseUrl, e.getMessage());
            return false;
        }
    }

    public JsonNode getClusterSummary() throws IOException, InterruptedException {
        String url = restBaseUrl + "/api/v1/cluster/summary";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get cluster summary from " + url + ": HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    public JsonNode getTopologySummary() throws IOException, InterruptedException {
        String url = restBaseUrl + "/api/v1/topology/summary";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get topology summary from " + url + ": HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    public boolean activateTopology(String topologyId) throws IOException, InterruptedException {
        String url = restBaseUrl + "/api/v1/topology/" + topologyId + "/activate";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200;
    }

    public boolean deactivateTopology(String topologyId) throws IOException, InterruptedException {
        String url = restBaseUrl + "/api/v1/topology/" + topologyId + "/deactivate";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200;
    }

    public boolean killTopology(String topologyId, int waitSeconds) throws IOException, InterruptedException {
        String url = restBaseUrl + "/api/v1/topology/" + topologyId + "/kill/" + waitSeconds;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200;
    }

    public String getRestBaseUrl() {
        return restBaseUrl;
    }
}
