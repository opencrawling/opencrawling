/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.flowable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;

import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Flux;

@Component
public class FlowableRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(FlowableRepositoryConnector.class);

    // BPMN Standard & Flowable REST Constants
    public static final String FIELD_ID = "id";
    public static final String FIELD_PROCESS_DEFINITION_ID = "processDefinitionId";
    public static final String FIELD_PROCESS_DEFINITION_KEY = "processDefinitionKey";
    public static final String FIELD_BUSINESS_KEY = "businessKey";
    public static final String FIELD_START_USER_ID = "startUserId";
    public static final String FIELD_START_TIME = "startTime";
    public static final String FIELD_END_TIME = "endTime";
    public static final String FIELD_DURATION_IN_MILLIS = "durationInMillis";
    public static final String FIELD_VARIABLES = "variables";
    public static final String FIELD_VARIABLE = "variable";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_VALUE = "value";
    public static final String VAR_PREFIX = "flowable_var_";
    public static final String URI_PREFIX = "flowable://process-instances/";

    private final String url;
    private final String username;
    private final String password;
    private final int batchSize;
    private final String processDefinitionKey;
    private final boolean includeVariables;
    private final String scope;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;
    private String authHeader;

    public FlowableRepositoryConnector(
            @Value("${spring.opencrawling.connector.flowable.url:${spring.opencrawling.connector.flowable.endpoint:http://localhost:8080/flowable-rest/service}}") String url,
            @Value("${spring.opencrawling.connector.flowable.username:admin}") String username,
            @Value("${spring.opencrawling.connector.flowable.password:test}") String password,
            @Value("${spring.opencrawling.connector.flowable.batch-size:100}") int batchSize,
            @Value("${spring.opencrawling.connector.flowable.process-definition-key:}") String processDefinitionKey,
            @Value("${spring.opencrawling.connector.flowable.include-variables:true}") boolean includeVariables,
            @Value("${spring.opencrawling.connector.flowable.scope:all}") String scope) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.username = username;
        this.password = password;
        this.batchSize = batchSize;
        this.processDefinitionKey = processDefinitionKey;
        this.includeVariables = includeVariables;
        this.scope = scope;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "FlowableConnector";
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting to Flowable REST API at URL: {}", url);
        if (this.httpClient == null) {
            this.httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        String credentials = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        String testUrl = url + "/history/historic-process-instances?size=1";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testUrl))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            log.info("Successfully connected to Flowable REST API.");
        } else {
            throw new IOException("Failed to connect to Flowable REST API. Status code: " + response.statusCode() + ", Response: " + response.body());
        }
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting from Flowable REST API.");
        this.httpClient = null;
        this.authHeader = null;
    }

    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
        String credentials = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            try {
                if (httpClient == null) {
                    connect();
                }

                String targetDefinitionKey = this.processDefinitionKey;
                if (basePath != null && !basePath.isBlank() && !basePath.equals("/")) {
                    targetDefinitionKey = basePath.startsWith("/") ? basePath.substring(1) : basePath;
                }

                int start = 0;
                boolean hasMore = true;

                while (hasMore) {
                    JsonNode page = fetchHistoricProcessInstances(targetDefinitionKey, start, batchSize);
                    JsonNode dataNode = page.path("data");

                    if (!dataNode.isArray() || dataNode.isEmpty()) {
                        break;
                    }

                    List<JsonNode> instanceList = new ArrayList<>();
                    for (JsonNode instanceNode : dataNode) {
                        instanceList.add(instanceNode);
                    }

                    processBatchWithVirtualThreads(instanceList, sink);

                    int total = page.path("total").asInt(0);
                    start += instanceList.size();
                    hasMore = start < total && !instanceList.isEmpty();
                }

                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    @SuppressWarnings("preview")
    private void processBatchWithVirtualThreads(List<JsonNode> instanceList, reactor.core.publisher.FluxSink<RepositoryDocument> sink) throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode instanceNode : instanceList) {
                scope.fork(org.opencrawling.observability.concurrency.ObservabilityTask.observed(() -> {
                    try {
                        RepositoryDocument doc = createDocument(instanceNode);
                        sink.next(doc);
                    } catch (Exception e) {
                        log.error("Error creating document for process instance {}: {}", instanceNode.path(FIELD_ID).asText(), e.getMessage(), e);
                    }
                    return null;
                }));
            }
            scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            throw new RuntimeException("Batch processing failed for process instances", e.getCause());
        }
    }

    private JsonNode fetchHistoricProcessInstances(String defKey, int start, int size) throws IOException, InterruptedException {
        StringBuilder urlBuilder = new StringBuilder(url)
                .append("/history/historic-process-instances")
                .append("?start=").append(start)
                .append("&size=").append(size)
                .append("&sort=").append(FIELD_START_TIME).append("&order=asc");

        if (defKey != null && !defKey.isBlank()) {
            urlBuilder.append("&").append(FIELD_PROCESS_DEFINITION_KEY).append("=").append(URLEncoder.encode(defKey, StandardCharsets.UTF_8));
        }

        if ("completed".equalsIgnoreCase(scope)) {
            urlBuilder.append("&finished=true");
        } else if ("active".equalsIgnoreCase(scope)) {
            urlBuilder.append("&finished=false");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch historic process instances. Status code: " + response.statusCode() + ", Response: " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    private JsonNode fetchHistoricVariables(String processInstanceId) throws IOException, InterruptedException {
        String varUrl = url + "/history/historic-variable-instances?processInstanceId=" + URLEncoder.encode(processInstanceId, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(varUrl))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Failed to fetch historic variables for instance {}. Status: {}", processInstanceId, response.statusCode());
            return objectMapper.createArrayNode();
        }

        JsonNode resNode = objectMapper.readTree(response.body());
        return resNode.path("data");
    }

    private RepositoryDocument createDocument(JsonNode instanceNode) throws IOException, InterruptedException {
        String processInstanceId = instanceNode.path(FIELD_ID).asText();
        String processDefinitionId = instanceNode.path(FIELD_PROCESS_DEFINITION_ID).asText("");
        String processDefinitionKey = instanceNode.path(FIELD_PROCESS_DEFINITION_KEY).asText("");
        String businessKey = instanceNode.path(FIELD_BUSINESS_KEY).asText("");
        String startUserId = instanceNode.path(FIELD_START_USER_ID).asText("");
        String startTimeStr = instanceNode.path(FIELD_START_TIME).asText("");
        String endTimeStr = instanceNode.path(FIELD_END_TIME).asText("");
        long durationInMillis = instanceNode.path(FIELD_DURATION_IN_MILLIS).asLong(0);

        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("mimeType", List.of("application/json"));
        if (!processDefinitionId.isBlank()) metadata.put(FIELD_PROCESS_DEFINITION_ID, List.of(processDefinitionId));
        if (!processDefinitionKey.isBlank()) metadata.put(FIELD_PROCESS_DEFINITION_KEY, List.of(processDefinitionKey));
        if (!businessKey.isBlank()) metadata.put(FIELD_BUSINESS_KEY, List.of(businessKey));
        if (!startUserId.isBlank()) metadata.put(FIELD_START_USER_ID, List.of(startUserId));
        if (!startTimeStr.isBlank()) metadata.put(FIELD_START_TIME, List.of(startTimeStr));
        if (!endTimeStr.isBlank()) metadata.put(FIELD_END_TIME, List.of(endTimeStr));
        metadata.put(FIELD_DURATION_IN_MILLIS, List.of(String.valueOf(durationInMillis)));

        ObjectNode contentJson = objectMapper.createObjectNode();
        contentJson.put(FIELD_ID, processInstanceId);
        contentJson.put(FIELD_PROCESS_DEFINITION_ID, processDefinitionId);
        contentJson.put(FIELD_PROCESS_DEFINITION_KEY, processDefinitionKey);
        contentJson.put(FIELD_BUSINESS_KEY, businessKey);
        contentJson.put(FIELD_START_USER_ID, startUserId);
        contentJson.put(FIELD_START_TIME, startTimeStr);
        contentJson.put(FIELD_END_TIME, endTimeStr);
        contentJson.put(FIELD_DURATION_IN_MILLIS, durationInMillis);

        ObjectNode variablesJson = objectMapper.createObjectNode();

        if (includeVariables) {
            JsonNode variablesNode = fetchHistoricVariables(processInstanceId);
            if (variablesNode.isArray()) {
                for (JsonNode varNode : variablesNode) {
                    JsonNode varItem = varNode.path(FIELD_VARIABLE);
                    String name = varItem.path(FIELD_NAME).asText();
                    JsonNode valNode = varItem.path(FIELD_VALUE);
                    String valueStr = valNode.isNull() ? "" : valNode.asText();

                    if (!name.isBlank()) {
                        metadata.put(VAR_PREFIX + name, List.of(valueStr));
                        variablesJson.put(name, valueStr);
                    }
                }
            }
        }

        contentJson.set(FIELD_VARIABLES, variablesJson);

        String docUri = URI_PREFIX + processInstanceId;

        Instant lastModified = Instant.now();
        if (!endTimeStr.isBlank()) {
            try {
                lastModified = Instant.parse(endTimeStr);
            } catch (Exception e) {
                // fallback
            }
        } else if (!startTimeStr.isBlank()) {
            try {
                lastModified = Instant.parse(startTimeStr);
            } catch (Exception e) {
                // fallback
            }
        }

        InputStream contentStream = new ByteArrayInputStream(objectMapper.writeValueAsBytes(contentJson));

        return new RepositoryDocument(
                processInstanceId,
                docUri,
                contentStream,
                metadata,
                "public",
                lastModified
        );
    }
}
