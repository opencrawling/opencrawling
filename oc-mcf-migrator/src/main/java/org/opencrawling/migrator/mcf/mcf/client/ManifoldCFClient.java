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
package org.opencrawling.migrator.mcf.mcf.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.mcf.parse.McfEntityParser;
import org.opencrawling.migrator.mcf.mcf.parse.McfJsonNodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Thin read-only client for ManifoldCF's {@code /json/<command>} REST API. Deliberately reads via
 * this API rather than the {@code ExportConfiguration} CLI tool's proprietary binary zip format —
 * anyone with API access can run this tool, with no need for shell/docker access to the ManifoldCF
 * host itself.
 */
public class ManifoldCFClient implements ManifoldCFSource {

    private static final Logger log = LoggerFactory.getLogger(ManifoldCFClient.class);

    private static final String NODE_REPOSITORY_CONNECTION = "repositoryconnection";
    private static final String NODE_OUTPUT_CONNECTION = "outputconnection";
    private static final String NODE_TRANSFORMATION_CONNECTION = "transformationconnection";
    private static final String NODE_AUTHORITY_CONNECTION = "authorityconnection";
    private static final String NODE_JOB = "job";
    private static final String NODE_PIPELINE_STAGE = "pipelinestage";
    private static final String NODE_NOTIFICATION_STAGE = "notificationstage";
    private static final String NODE_ERROR = "error";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration timeout;

    public ManifoldCFClient(String baseUrl, String username, String password, int timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.password = password;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<McfConnection> listRepositoryConnections() {
        return listConnections("repositoryconnections", NODE_REPOSITORY_CONNECTION, McfConnectionKind.REPOSITORY);
    }

    @Override
    public List<McfConnection> listOutputConnections() {
        return listConnections("outputconnections", NODE_OUTPUT_CONNECTION, McfConnectionKind.OUTPUT);
    }

    @Override
    public List<McfConnection> listTransformationConnections() {
        return listConnections("transformationconnections", NODE_TRANSFORMATION_CONNECTION, McfConnectionKind.TRANSFORMATION);
    }

    @Override
    public List<McfConnection> listAuthorityConnections() {
        return listConnections("authorityconnections", NODE_AUTHORITY_CONNECTION, McfConnectionKind.AUTHORITY);
    }

    @Override
    public List<McfJob> listJobs() {
        JsonNode root = getJson("jobs");
        List<McfJob> jobs = new ArrayList<>();
        for (JsonNode jobNode : McfJsonNodes.childrenOfType(root, NODE_JOB)) {
            jobs.add(McfEntityParser.parseJob(jobNode));
        }
        log.info("Extracted {} job(s) from ManifoldCF at {}", jobs.size(), baseUrl);
        return jobs;
    }

    private List<McfConnection> listConnections(String path, String nodeType, McfConnectionKind kind) {
        JsonNode root = getJson(path);
        List<McfConnection> connections = new ArrayList<>();
        for (JsonNode connectionNode : McfJsonNodes.childrenOfType(root, nodeType)) {
            connections.add(McfEntityParser.parseConnection(connectionNode, kind));
        }
        log.info("Extracted {} {} connection(s) from ManifoldCF at {}", connections.size(), kind, baseUrl);
        return connections;
    }

    private JsonNode getJson(String path) {
        URI uri = URI.create(baseUrl + "/" + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json")
            .GET();
        basicAuthHeader().ifPresent(header -> builder.header("Authorization", header));

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new ManifoldCFApiException("Failed to reach ManifoldCF at " + uri, e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body();
            String detail = body == null || body.isBlank() ? "" : ": " + body.strip();
            throw new ManifoldCFApiException(
                "ManifoldCF API call to " + uri + " failed with HTTP " + response.statusCode() + detail);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new ManifoldCFApiException("ManifoldCF API returned malformed JSON from " + uri, e);
        }

        JsonNode error = root.get(NODE_ERROR);
        if (error != null && !error.isNull()) {
            throw new ManifoldCFApiException("ManifoldCF API error from " + uri + ": " + error.asText());
        }
        return root;
    }

    private Optional<String> basicAuthHeader() {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String credentials = username + ":" + (password == null ? "" : password);
        return Optional.of("Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }
}
