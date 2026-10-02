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
package org.opencrawling.camunda;

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
import org.opencrawling.core.security.SecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Flux;

@Component
public class CamundaRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(CamundaRepositoryConnector.class);

    // BPMN Standard & Camunda REST Constants
    public static final String FIELD_ID = "id";
    public static final String FIELD_PROCESS_DEFINITION_ID = "processDefinitionId";
    public static final String FIELD_PROCESS_DEFINITION_KEY = "processDefinitionKey";
    public static final String FIELD_BUSINESS_KEY = "businessKey";
    public static final String FIELD_START_USER_ID = "startUserId";
    public static final String FIELD_START_TIME = "startTime";
    public static final String FIELD_END_TIME = "endTime";
    public static final String FIELD_DURATION_IN_MILLIS = "durationInMillis";
    public static final String FIELD_VARIABLES = "variables";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_VALUE = "value";
    public static final String VAR_PREFIX = "camunda_var_";
    public static final String URI_PREFIX = "camunda://process-instances/";

    public static final String FIELD_INCLUDE_ACLS = "includeAcls";
    public static final String FIELD_CAMUNDA_PROC_INST_ID = "camunda_proc_inst_id";
    public static final String FIELD_CAMUNDA_PROCESS_INSTANCE_ID = "camunda_process_instance_id";
    public static final String FIELD_CAMUNDA_SCOPE_ID = "camunda_scope_id";
    public static final String FIELD_CAMUNDA_SCOPE_TYPE = "camunda_scope_type";
    public static final String FIELD_CAMUNDA_PROCESS_DEFINITION_ID = "camunda_process_definition_id";
    public static final String FIELD_CAMUNDA_PROCESS_DEFINITION_KEY = "camunda_process_definition_key";
    public static final String FIELD_CAMUNDA_BUSINESS_KEY = "camunda_business_key";
    public static final String FIELD_CAMUNDA_START_USER_ID = "camunda_start_user_id";
    public static final String FIELD_CAMUNDA_TENANT_ID = "camunda_tenant_id";
    public static final String FIELD_CAMUNDA_IDENTITY_USERS = "camunda_identity_users";
    public static final String FIELD_CAMUNDA_IDENTITY_GROUPS = "camunda_identity_groups";

    private final String url;
    private final String username;
    private final String password;
    private final int batchSize;
    private final String processDefinitionKey;
    private final boolean includeVariables;
    private final String scope;
    private final boolean includeAcls;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;
    private String authHeader;

    public CamundaRepositoryConnector() {
        this("http://localhost:8080/engine-rest", "demo", "demo", 100, "", true, "all", true);
    }

    @Autowired
    public CamundaRepositoryConnector(
            @Value("${spring.opencrawling.connector.camunda.url:http://localhost:8080/engine-rest}") String url,
            @Value("${spring.opencrawling.connector.camunda.username:demo}") String username,
            @Value("${spring.opencrawling.connector.camunda.password:demo}") String password,
            @Value("${spring.opencrawling.connector.camunda.batch-size:100}") int batchSize,
            @Value("${spring.opencrawling.connector.camunda.process-definition-key:}") String processDefinitionKey,
            @Value("${spring.opencrawling.connector.camunda.include-variables:true}") boolean includeVariables,
            @Value("${spring.opencrawling.connector.camunda.scope:all}") String scope,
            @Value("${spring.opencrawling.connector.camunda.include-acls:true}") boolean includeAcls) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.username = username;
        this.password = password;
        this.batchSize = batchSize;
        this.processDefinitionKey = processDefinitionKey;
        this.includeVariables = includeVariables;
        this.scope = scope;
        this.includeAcls = includeAcls;
        this.objectMapper = new ObjectMapper();
    }

    public CamundaRepositoryConnector(
            String url,
            String username,
            String password,
            int batchSize,
            String processDefinitionKey,
            boolean includeVariables,
            String scope) {
        this(url, username, password, batchSize, processDefinitionKey, includeVariables, scope, true);
    }

    @Override
    public String getName() {
        return "CamundaConnector";
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting to Camunda REST API at URL: {}", url);
        if (this.httpClient == null) {
            this.httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        if (username != null && !username.isBlank()) {
            String credentials = username + ":" + password;
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }

        String testUrl = url + "/history/process-instance?maxResults=1";
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(testUrl))
                .header("Accept", "application/json")
                .GET();

        if (authHeader != null) {
            reqBuilder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            log.info("Successfully connected to Camunda REST API.");
        } else {
            throw new IOException("Failed to connect to Camunda REST API. Status code: " + response.statusCode() + ", Response: " + response.body());
        }
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting from Camunda REST API.");
        this.httpClient = null;
        this.authHeader = null;
    }

    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
        if (username != null && !username.isBlank()) {
            String credentials = username + ":" + password;
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
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
                    JsonNode instanceArray = fetchHistoricProcessInstances(targetDefinitionKey, start, batchSize);

                    if (!instanceArray.isArray() || instanceArray.isEmpty()) {
                        break;
                    }

                    List<JsonNode> instanceList = new ArrayList<>();
                    for (JsonNode instanceNode : instanceArray) {
                        instanceList.add(instanceNode);
                    }

                    processBatchWithVirtualThreads(instanceList, sink);

                    start += instanceList.size();
                    hasMore = instanceList.size() == batchSize;
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
                .append("/history/process-instance")
                .append("?firstResult=").append(start)
                .append("&maxResults=").append(size)
                .append("&sortBy=").append(FIELD_START_TIME).append("&sortOrder=asc");

        if (defKey != null && !defKey.isBlank()) {
            urlBuilder.append("&").append(FIELD_PROCESS_DEFINITION_KEY).append("=").append(URLEncoder.encode(defKey, StandardCharsets.UTF_8));
        }

        if ("completed".equalsIgnoreCase(scope)) {
            urlBuilder.append("&finished=true");
        } else if ("active".equalsIgnoreCase(scope)) {
            urlBuilder.append("&unfinished=true");
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .header("Accept", "application/json")
                .GET();

        if (authHeader != null) {
            reqBuilder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch historic process instances. Status code: " + response.statusCode() + ", Response: " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    private JsonNode fetchHistoricVariables(String processInstanceId) throws IOException, InterruptedException {
        String varUrl = url + "/history/variable-instance?processInstanceId=" + URLEncoder.encode(processInstanceId, StandardCharsets.UTF_8);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(varUrl))
                .header("Accept", "application/json")
                .GET();

        if (authHeader != null) {
            reqBuilder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Failed to fetch historic variables for instance {}. Status: {}", processInstanceId, response.statusCode());
            return objectMapper.createArrayNode();
        }

        return objectMapper.readTree(response.body());
    }

    private JsonNode fetchHistoricIdentityLinks(String processInstanceId) throws IOException, InterruptedException {
        String linksUrl = url + "/history/identity-link-log?processInstanceId=" + URLEncoder.encode(processInstanceId, StandardCharsets.UTF_8);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(linksUrl))
                .header("Accept", "application/json")
                .GET();

        if (authHeader != null) {
            reqBuilder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Failed to fetch historic identity links for instance {}. Status: {}", processInstanceId, response.statusCode());
            return objectMapper.createArrayNode();
        }

        return objectMapper.readTree(response.body());
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
        String tenantId = instanceNode.path("tenantId").asText("");

        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("mimeType", List.of("application/json"));
        if (!processDefinitionId.isBlank()) metadata.put(FIELD_PROCESS_DEFINITION_ID, List.of(processDefinitionId));
        if (!processDefinitionKey.isBlank()) metadata.put(FIELD_PROCESS_DEFINITION_KEY, List.of(processDefinitionKey));
        if (!businessKey.isBlank()) metadata.put(FIELD_BUSINESS_KEY, List.of(businessKey));
        if (!startUserId.isBlank()) metadata.put(FIELD_START_USER_ID, List.of(startUserId));
        if (!startTimeStr.isBlank()) metadata.put(FIELD_START_TIME, List.of(startTimeStr));
        if (!endTimeStr.isBlank()) metadata.put(FIELD_END_TIME, List.of(endTimeStr));
        metadata.put(FIELD_DURATION_IN_MILLIS, List.of(String.valueOf(durationInMillis)));

        // Collocated DB and Entity References
        metadata.put(FIELD_CAMUNDA_PROC_INST_ID, List.of(processInstanceId));
        metadata.put(FIELD_CAMUNDA_PROCESS_INSTANCE_ID, List.of(processInstanceId));
        metadata.put(FIELD_CAMUNDA_SCOPE_ID, List.of(processInstanceId));
        metadata.put(FIELD_CAMUNDA_SCOPE_TYPE, List.of("processInstance"));
        if (!processDefinitionId.isBlank()) metadata.put(FIELD_CAMUNDA_PROCESS_DEFINITION_ID, List.of(processDefinitionId));
        if (!processDefinitionKey.isBlank()) metadata.put(FIELD_CAMUNDA_PROCESS_DEFINITION_KEY, List.of(processDefinitionKey));
        if (!businessKey.isBlank()) metadata.put(FIELD_CAMUNDA_BUSINESS_KEY, List.of(businessKey));
        if (!startUserId.isBlank()) metadata.put(FIELD_CAMUNDA_START_USER_ID, List.of(startUserId));
        if (!tenantId.isBlank()) {
            metadata.put("tenantId", List.of(tenantId));
            metadata.put(FIELD_CAMUNDA_TENANT_ID, List.of(tenantId));
        }

        ObjectNode contentJson = objectMapper.createObjectNode();
        contentJson.put(FIELD_ID, processInstanceId);
        contentJson.put(FIELD_PROCESS_DEFINITION_ID, processDefinitionId);
        contentJson.put(FIELD_PROCESS_DEFINITION_KEY, processDefinitionKey);
        contentJson.put(FIELD_BUSINESS_KEY, businessKey);
        contentJson.put(FIELD_START_USER_ID, startUserId);
        contentJson.put(FIELD_START_TIME, startTimeStr);
        contentJson.put(FIELD_END_TIME, endTimeStr);
        contentJson.put(FIELD_DURATION_IN_MILLIS, durationInMillis);
        contentJson.put(FIELD_CAMUNDA_PROC_INST_ID, processInstanceId);
        contentJson.put(FIELD_CAMUNDA_SCOPE_ID, processInstanceId);
        contentJson.put(FIELD_CAMUNDA_SCOPE_TYPE, "processInstance");
        if (!tenantId.isBlank()) {
            contentJson.put("tenantId", tenantId);
            contentJson.put(FIELD_CAMUNDA_TENANT_ID, tenantId);
        }

        SecurityConfig securityConfig = SecurityConfig.createPublic();
        String aclString = "public";

        if (includeAcls) {
            JsonNode identityLinksNode = fetchHistoricIdentityLinks(processInstanceId);
            securityConfig = CamundaSecurityMapper.mapSecurity(identityLinksNode, startUserId);

            List<String> allowedUsers = CamundaSecurityMapper.extractAllowedUsers(identityLinksNode, startUserId);
            if (!allowedUsers.isEmpty()) {
                metadata.put(FIELD_CAMUNDA_IDENTITY_USERS, allowedUsers);
            }
            List<String> allowedGroups = CamundaSecurityMapper.extractAllowedGroups(identityLinksNode);
            if (!allowedGroups.isEmpty()) {
                metadata.put(FIELD_CAMUNDA_IDENTITY_GROUPS, allowedGroups);
            }

            ArrayNode usersArray = objectMapper.createArrayNode();
            allowedUsers.forEach(usersArray::add);
            contentJson.set("allowedUsers", usersArray);

            ArrayNode groupsArray = objectMapper.createArrayNode();
            allowedGroups.forEach(groupsArray::add);
            contentJson.set("allowedGroups", groupsArray);

            List<String> allIdentities = new ArrayList<>(allowedUsers);
            allIdentities.addAll(allowedGroups);
            if (!allIdentities.isEmpty()) {
                aclString = String.join(",", allIdentities);
            }
        }

        ObjectNode variablesJson = objectMapper.createObjectNode();

        if (includeVariables) {
            JsonNode variablesNode = fetchHistoricVariables(processInstanceId);
            if (variablesNode.isArray()) {
                for (JsonNode varNode : variablesNode) {
                    String name = varNode.path(FIELD_NAME).asText();
                    JsonNode valNode = varNode.path(FIELD_VALUE);
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
                aclString,
                securityConfig,
                lastModified
        );
    }
}
