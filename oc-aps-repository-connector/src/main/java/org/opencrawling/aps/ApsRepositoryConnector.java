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
package org.opencrawling.aps;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;

import org.opencrawling.aps.mcp.ApsMcpClient;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.observability.concurrency.ObservabilityTask;
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
import reactor.core.publisher.FluxSink;

@Component
public class ApsRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(ApsRepositoryConnector.class);

    // Alfresco Process Services (APS) REST Constants
    public static final String FIELD_ID = "id";
    public static final String FIELD_PROCESS_DEFINITION_ID = "processDefinitionId";
    public static final String FIELD_PROCESS_DEFINITION_KEY = "processDefinitionKey";
    public static final String FIELD_PROCESS_DEFINITION_NAME = "processDefinitionName";
    public static final String FIELD_BUSINESS_KEY = "businessKey";
    public static final String FIELD_START_USER_ID = "startUserId";
    public static final String FIELD_START_TIME = "startTime";
    public static final String FIELD_END_TIME = "endTime";
    public static final String FIELD_DURATION_IN_MILLIS = "durationInMillis";
    public static final String FIELD_TENANT_ID = "tenantId";
    public static final String FIELD_VARIABLES = "variables";
    public static final String FIELD_TASKS = "tasks";
    public static final String FIELD_ATTACHMENTS = "attachments";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_VALUE = "value";
    public static final String VAR_PREFIX = "aps_var_";
    public static final String ATTACHMENT_PREFIX = "aps_attachment_";
    public static final String URI_PREFIX = "aps://process-instances/";

    private final String url;
    private final String username;
    private final String password;
    private final int batchSize;
    private final String processDefinitionKey;
    private final boolean includeVariables;
    private final boolean includeTasks;
    private final boolean includeAttachments;
    private final String tenantId;
    private final String scope;
    private final boolean mcpEnabled;
    private final String mcpUrl;
    private final ObjectMapper objectMapper;

    private ApsMcpClient mcpClient;
    private HttpClient httpClient;
    private String authHeader;
    private volatile boolean connected = false;

    public ApsRepositoryConnector() {
        this("http://localhost:8080/activiti-app/api/enterprise", "admin@app.activiti.com", "admin", 100, "", true, true, true, "", "all", false, "");
    }

    @Autowired
    public ApsRepositoryConnector(
            @Value("${spring.opencrawling.connector.aps.url:http://localhost:8080/activiti-app/api/enterprise}") String url,
            @Value("${spring.opencrawling.connector.aps.username:admin@app.activiti.com}") String username,
            @Value("${spring.opencrawling.connector.aps.password:admin}") String password,
            @Value("${spring.opencrawling.connector.aps.batch-size:100}") int batchSize,
            @Value("${spring.opencrawling.connector.aps.process-definition-key:}") String processDefinitionKey,
            @Value("${spring.opencrawling.connector.aps.include-variables:true}") boolean includeVariables,
            @Value("${spring.opencrawling.connector.aps.include-tasks:true}") boolean includeTasks,
            @Value("${spring.opencrawling.connector.aps.include-attachments:true}") boolean includeAttachments,
            @Value("${spring.opencrawling.connector.aps.tenant-id:}") String tenantId,
            @Value("${spring.opencrawling.connector.aps.scope:all}") String scope,
            @Value("${spring.opencrawling.connector.aps.mcp.enabled:false}") boolean mcpEnabled,
            @Value("${spring.opencrawling.connector.aps.mcp.url:}") String mcpUrl) {
        this.url = url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.username = username;
        this.password = password;
        this.batchSize = batchSize;
        this.processDefinitionKey = processDefinitionKey;
        this.includeVariables = includeVariables;
        this.includeTasks = includeTasks;
        this.includeAttachments = includeAttachments;
        this.tenantId = tenantId;
        this.scope = scope;
        this.mcpEnabled = mcpEnabled;
        this.mcpUrl = (mcpUrl != null && !mcpUrl.isBlank()) ? mcpUrl : (this.url + "/mcp");
        this.objectMapper = new ObjectMapper();
        if (this.mcpEnabled) {
            this.mcpClient = new ApsMcpClient(this.mcpUrl, this.username, this.password);
        }
    }

    public ApsRepositoryConnector(
            String url,
            String username,
            String password,
            int batchSize,
            String processDefinitionKey,
            boolean includeVariables,
            boolean includeTasks,
            boolean includeAttachments,
            String tenantId,
            String scope) {
        this(url, username, password, batchSize, processDefinitionKey, includeVariables, includeTasks, includeAttachments, tenantId, scope, false, "");
    }

    public ApsRepositoryConnector(
            String url,
            String username,
            String password,
            int batchSize,
            String processDefinitionKey,
            boolean includeVariables,
            boolean includeTasks,
            String tenantId,
            String scope,
            boolean mcpEnabled,
            String mcpUrl) {
        this(url, username, password, batchSize, processDefinitionKey, includeVariables, includeTasks, true, tenantId, scope, mcpEnabled, mcpUrl);
    }

    public ApsRepositoryConnector(
            String url,
            String username,
            String password,
            int batchSize,
            String processDefinitionKey,
            boolean includeVariables,
            boolean includeTasks,
            String tenantId,
            String scope) {
        this(url, username, password, batchSize, processDefinitionKey, includeVariables, includeTasks, true, tenantId, scope, false, "");
    }

    public ApsRepositoryConnector(
            String url,
            String username,
            String password,
            int batchSize,
            String processDefinitionKey,
            boolean includeVariables,
            String scope) {
        this(url, username, password, batchSize, processDefinitionKey, includeVariables, true, true, "", scope, false, "");
    }

    public boolean isIncludeAttachments() {
        return this.includeAttachments;
    }

    @Override
    public String getName() {
        return "ApsConnector";
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting to Alfresco Process Services at URL: {}", url);
        if (this.httpClient == null) {
            this.httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        String credentials = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        // Test connectivity: first probe /profile
        String profileUrl = url + "/profile";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(profileUrl))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            log.info("Successfully connected to Alfresco Process Services via /profile.");
            this.connected = true;
            initMcpIfEnabled();
            return;
        }

        // Fallback test: historic-process-instances/query with size=1
        String queryUrl = url + "/historic-process-instances/query";
        HttpRequest queryRequest = HttpRequest.newBuilder()
                .uri(URI.create(queryUrl))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"size\":1}"))
                .build();

        HttpResponse<String> queryResponse = httpClient.send(queryRequest, HttpResponse.BodyHandlers.ofString());
        if (queryResponse.statusCode() == 200) {
            log.info("Successfully connected to Alfresco Process Services via /historic-process-instances/query.");
            this.connected = true;
            initMcpIfEnabled();
            return;
        }

        this.connected = false;
        throw new IOException("Failed to connect to Alfresco Process Services API. Profile status: "
                + response.statusCode() + ", Query status: " + queryResponse.statusCode() + ", Response: " + response.body());
    }

    private void initMcpIfEnabled() {
        if (mcpEnabled && mcpClient != null) {
            try {
                mcpClient.initialize();
                List<String> tools = mcpClient.listTools();
                log.info("APS MCP Server connected successfully at {}. Discovered {} tools: {}", mcpUrl, tools.size(), tools);
            } catch (Exception e) {
                log.warn("Could not initialize APS MCP Server at {}: {}. Continuing with REST API.", mcpUrl, e.getMessage());
            }
        }
    }

    public boolean isConnected() {
        return this.connected;
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting from Alfresco Process Services.");
        this.connected = false;
        this.httpClient = null;
        this.authHeader = null;
        if (this.mcpClient != null) {
            this.mcpClient.close();
        }
    }

    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
        String credentials = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    public ApsMcpClient getMcpClient() {
        return mcpClient;
    }

    public void setMcpClient(ApsMcpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            try {
                if (httpClient == null) {
                    connect();
                }

                String targetDefinitionKey = this.processDefinitionKey;
                if (basePath != null && !basePath.isBlank() && !basePath.equals("/")
                        && !"default".equalsIgnoreCase(basePath) && !"all".equalsIgnoreCase(basePath) && !"*".equals(basePath)) {
                    targetDefinitionKey = basePath.startsWith("/") ? basePath.substring(1) : basePath;
                }
                if ("default".equalsIgnoreCase(targetDefinitionKey) || "all".equalsIgnoreCase(targetDefinitionKey) || "*".equals(targetDefinitionKey)) {
                    targetDefinitionKey = "";
                }

                // If no specific workflow is configured and MCP is enabled, use MCP dynamic discovery
                if ((targetDefinitionKey == null || targetDefinitionKey.isBlank() || "*".equals(targetDefinitionKey))
                        && mcpEnabled && mcpClient != null) {
                    List<String> discoveredKeys = mcpClient.discoverProcessDefinitionKeys();
                    if (!discoveredKeys.isEmpty()) {
                        log.info("APS MCP Server discovered {} workflow definitions for ingestion: {}", discoveredKeys.size(), discoveredKeys);
                        for (String key : discoveredKeys) {
                            scanProcessDefinition(key, sink);
                        }
                        sink.complete();
                        return;
                    }
                }

                scanProcessDefinition(targetDefinitionKey, sink);
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    private void scanProcessDefinition(String targetDefinitionKey, FluxSink<RepositoryDocument> sink) throws Exception {
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
    }

    @SuppressWarnings("preview")
    private void processBatchWithVirtualThreads(List<JsonNode> instanceList, FluxSink<RepositoryDocument> sink) throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode instanceNode : instanceList) {
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        processWorkflowInstance(instanceNode, sink);
                    } catch (Exception e) {
                        log.error("Error creating document for APS process instance {}: {}", instanceNode.path(FIELD_ID).asText(), e.getMessage(), e);
                    }
                    return null;
                }));
            }
            scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            throw new RuntimeException("Batch processing failed for APS process instances", e.getCause());
        }
    }

    private JsonNode fetchHistoricProcessInstances(String defKey, int start, int size) throws IOException, InterruptedException {
        String queryUrl = url + "/historic-process-instances/query";

        ObjectNode queryBody = objectMapper.createObjectNode();
        queryBody.put("start", start);
        queryBody.put("size", size);
        queryBody.put("sort", "startTime");
        queryBody.put("order", "asc");

        if (includeVariables) {
            queryBody.put("includeProcessVariables", true);
        }

        if (defKey != null && !defKey.isBlank()) {
            queryBody.put("processDefinitionKey", defKey);
        }

        if (tenantId != null && !tenantId.isBlank()) {
            queryBody.put("tenantId", tenantId);
        }

        if ("completed".equalsIgnoreCase(scope)) {
            queryBody.put("finished", true);
        } else if ("active".equalsIgnoreCase(scope)) {
            queryBody.put("finished", false);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(queryUrl))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(queryBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch historic process instances from APS. Status code: " + response.statusCode() + ", Response: " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    private JsonNode fetchHistoricVariables(String processInstanceId) throws IOException, InterruptedException {
        String varUrl = url + "/process-instances/" + URLEncoder.encode(processInstanceId, StandardCharsets.UTF_8) + "/variables";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(varUrl))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.debug("No active variables found for APS instance {}. Status: {}", processInstanceId, response.statusCode());
            return objectMapper.createArrayNode();
        }

        JsonNode resNode = objectMapper.readTree(response.body());
        if (resNode.isArray()) {
            return resNode;
        } else if (resNode.has("data") && resNode.path("data").isArray()) {
            return resNode.path("data");
        }
        return objectMapper.createArrayNode();
    }

    private JsonNode fetchTasks(String processInstanceId) throws IOException, InterruptedException {
        String tasksUrl = url + "/tasks/query";

        ObjectNode queryBody = objectMapper.createObjectNode();
        queryBody.put("processInstanceId", processInstanceId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tasksUrl))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(queryBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.debug("No tasks found for APS instance {}. Status: {}", processInstanceId, response.statusCode());
            return objectMapper.createArrayNode();
        }

        JsonNode resNode = objectMapper.readTree(response.body());
        if (resNode.isArray()) {
            return resNode;
        } else if (resNode.has("data") && resNode.path("data").isArray()) {
            return resNode.path("data");
        }
        return objectMapper.createArrayNode();
    }

    public record AttachmentInfo(
            String id,
            String name,
            String mimeType,
            String simpleType,
            long size,
            String created,
            String createdBy,
            boolean relatedContent,
            String field,
            String taskId,
            String taskName
    ) {}

    private record ProcessInstanceContext(
            RepositoryDocument processDocument,
            List<AttachmentInfo> attachments,
            String docUri,
            Map<String, List<String>> metadata,
            List<PermissionRule> permissions,
            Instant lastModified,
            String tenantIdVal,
            String processDefinitionId,
            String processDefinitionKey,
            String processDefinitionName,
            String businessKey
    ) {}

    JsonNode fetchProcessAttachments(String processInstanceId) throws IOException, InterruptedException {
        String contentUrl = url + "/process-instances/" + URLEncoder.encode(processInstanceId, StandardCharsets.UTF_8) + "/content";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(contentUrl))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.debug("No attachments found for APS process instance {}. Status: {}", processInstanceId, response.statusCode());
            return objectMapper.createArrayNode();
        }

        JsonNode resNode = objectMapper.readTree(response.body());
        if (resNode.isArray()) {
            return resNode;
        } else if (resNode.has("data") && resNode.path("data").isArray()) {
            return resNode.path("data");
        }
        return objectMapper.createArrayNode();
    }

    JsonNode fetchTaskAttachments(String taskId) throws IOException, InterruptedException {
        String contentUrl = url + "/tasks/" + URLEncoder.encode(taskId, StandardCharsets.UTF_8) + "/content";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(contentUrl))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.debug("No attachments found for APS task {}. Status: {}", taskId, response.statusCode());
            return objectMapper.createArrayNode();
        }

        JsonNode resNode = objectMapper.readTree(response.body());
        if (resNode.isArray()) {
            return resNode;
        } else if (resNode.has("data") && resNode.path("data").isArray()) {
            return resNode.path("data");
        }
        return objectMapper.createArrayNode();
    }

    InputStream fetchAttachmentContentStream(String contentId) throws IOException, InterruptedException {
        String rawUrl = url + "/content/" + URLEncoder.encode(contentId, StandardCharsets.UTF_8) + "/raw";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rawUrl))
                .header("Authorization", authHeader)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            log.warn("Failed to download raw attachment content for APS content ID {}. Status code: {}", contentId, response.statusCode());
            return null;
        }

        return response.body();
    }

    private AttachmentInfo parseAttachmentNode(JsonNode item, String taskId, String taskName) {
        String contentId = item.path(FIELD_ID).asText();
        String name = item.path(FIELD_NAME).asText("attachment-" + contentId);
        String mimeType = item.path("mimeType").asText("application/octet-stream");
        String simpleType = item.path("simpleType").asText("");
        long size = item.path("size").asLong(0);
        String created = item.path("created").asText("");

        String createdBy = "";
        JsonNode createdByNode = item.path("createdBy");
        if (createdByNode.isObject()) {
            createdBy = createdByNode.path("email").asText(createdByNode.path(FIELD_ID).asText(""));
        } else if (!createdByNode.isMissingNode() && !createdByNode.isNull()) {
            createdBy = createdByNode.asText("");
        }

        boolean relatedContent = item.path("relatedContent").asBoolean(true);
        String field = item.path("field").asText("");

        return new AttachmentInfo(
                contentId,
                name,
                mimeType,
                simpleType,
                size,
                created,
                createdBy,
                relatedContent,
                field,
                taskId != null ? taskId : "",
                taskName != null ? taskName : ""
        );
    }

    private ProcessInstanceContext buildProcessContext(JsonNode instanceNode) throws IOException, InterruptedException {
        String processInstanceId = instanceNode.path(FIELD_ID).asText();
        String processDefinitionId = instanceNode.path(FIELD_PROCESS_DEFINITION_ID).asText("");
        String processDefinitionKey = instanceNode.path(FIELD_PROCESS_DEFINITION_KEY).asText("");
        String processDefinitionName = instanceNode.path(FIELD_PROCESS_DEFINITION_NAME).asText("");
        String businessKey = instanceNode.path(FIELD_BUSINESS_KEY).asText("");

        // startedBy can be string or object
        String startUserId = "";
        String startUserName = "";
        JsonNode startedByNode = instanceNode.path("startedBy");
        if (startedByNode.isObject()) {
            startUserId = startedByNode.path("email").asText(startedByNode.path("id").asText(""));
            String first = startedByNode.path("firstName").asText("");
            String last = startedByNode.path("lastName").asText("");
            startUserName = (first + " " + last).trim();
        } else if (!startedByNode.isMissingNode() && !startedByNode.isNull()) {
            startUserId = startedByNode.asText("");
        }
        if (startUserId.isBlank()) {
            startUserId = instanceNode.path(FIELD_START_USER_ID).asText("");
        }

        String startTimeStr = instanceNode.path("started").asText(instanceNode.path(FIELD_START_TIME).asText(""));
        String endTimeStr = instanceNode.path("ended").asText(instanceNode.path(FIELD_END_TIME).asText(""));
        long durationInMillis = instanceNode.path(FIELD_DURATION_IN_MILLIS).asLong(instanceNode.path("durationInMs").asLong(0));
        String tenantIdVal = instanceNode.path(FIELD_TENANT_ID).asText("");

        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("mimeType", List.of("application/json"));
        if (!processDefinitionId.isBlank()) metadata.put(FIELD_PROCESS_DEFINITION_ID, List.of(processDefinitionId));
        if (!processDefinitionKey.isBlank()) metadata.put(FIELD_PROCESS_DEFINITION_KEY, List.of(processDefinitionKey));
        if (!processDefinitionName.isBlank()) metadata.put(FIELD_PROCESS_DEFINITION_NAME, List.of(processDefinitionName));
        if (!businessKey.isBlank()) metadata.put(FIELD_BUSINESS_KEY, List.of(businessKey));
        if (!startUserId.isBlank()) metadata.put(FIELD_START_USER_ID, List.of(startUserId));
        if (!startTimeStr.isBlank()) metadata.put(FIELD_START_TIME, List.of(startTimeStr));
        if (!endTimeStr.isBlank()) metadata.put(FIELD_END_TIME, List.of(endTimeStr));
        if (durationInMillis > 0) metadata.put(FIELD_DURATION_IN_MILLIS, List.of(String.valueOf(durationInMillis)));
        if (!tenantIdVal.isBlank()) metadata.put(FIELD_TENANT_ID, List.of(tenantIdVal));

        ObjectNode contentJson = objectMapper.createObjectNode();
        contentJson.put(FIELD_ID, processInstanceId);
        contentJson.put(FIELD_PROCESS_DEFINITION_ID, processDefinitionId);
        contentJson.put(FIELD_PROCESS_DEFINITION_KEY, processDefinitionKey);
        if (!processDefinitionName.isBlank()) contentJson.put(FIELD_PROCESS_DEFINITION_NAME, processDefinitionName);
        if (!businessKey.isBlank()) contentJson.put(FIELD_BUSINESS_KEY, businessKey);
        if (!startUserId.isBlank()) contentJson.put(FIELD_START_USER_ID, startUserId);
        if (!startTimeStr.isBlank()) contentJson.put(FIELD_START_TIME, startTimeStr);
        if (!endTimeStr.isBlank()) contentJson.put(FIELD_END_TIME, endTimeStr);
        if (durationInMillis > 0) contentJson.put(FIELD_DURATION_IN_MILLIS, durationInMillis);
        if (!tenantIdVal.isBlank()) contentJson.put(FIELD_TENANT_ID, tenantIdVal);

        ObjectNode variablesJson = objectMapper.createObjectNode();
        if (includeVariables) {
            JsonNode variablesNode = instanceNode.path("variables");
            if (!variablesNode.isArray() || variablesNode.isEmpty()) {
                variablesNode = fetchHistoricVariables(processInstanceId);
            }
            if (variablesNode.isArray()) {
                for (JsonNode varNode : variablesNode) {
                    String name = "";
                    String valueStr = "";
                    if (varNode.has("variable")) {
                        JsonNode varItem = varNode.path("variable");
                        name = varItem.path(FIELD_NAME).asText();
                        JsonNode valNode = varItem.path(FIELD_VALUE);
                        valueStr = valNode.isNull() ? "" : valNode.asText();
                    } else {
                        name = varNode.path(FIELD_NAME).asText();
                        JsonNode valNode = varNode.path(FIELD_VALUE);
                        valueStr = valNode.isNull() ? "" : valNode.asText();
                    }

                    if (!name.isBlank()) {
                        metadata.put(VAR_PREFIX + name, List.of(valueStr));
                        variablesJson.put(name, valueStr);
                    }
                }
            }
        }
        contentJson.set(FIELD_VARIABLES, variablesJson);

        List<PermissionRule> permissions = new ArrayList<>();
        if (!startUserId.isBlank()) {
            permissions.add(new PermissionRule(startUserId, "user", startUserName.isBlank() ? startUserId : startUserName, "read"));
        }

        ArrayNode tasksJson = objectMapper.createArrayNode();
        if (includeTasks) {
            JsonNode tasksNode = fetchTasks(processInstanceId);
            if (tasksNode.isArray()) {
                for (JsonNode taskNode : tasksNode) {
                    tasksJson.add(taskNode);
                    String taskName = taskNode.path("name").asText("");

                    // Task assignee
                    JsonNode assigneeNode = taskNode.path("assignee");
                    String assigneeId = "";
                    String assigneeName = "";
                    if (assigneeNode.isObject()) {
                        assigneeId = assigneeNode.path("email").asText(assigneeNode.path("id").asText(""));
                        String first = assigneeNode.path("firstName").asText("");
                        String last = assigneeNode.path("lastName").asText("");
                        assigneeName = (first + " " + last).trim();
                    } else if (!assigneeNode.isMissingNode() && !assigneeNode.isNull()) {
                        assigneeId = assigneeNode.asText("");
                    }
                    if (!assigneeId.isBlank()) {
                        permissions.add(new PermissionRule(assigneeId, "user", assigneeName.isBlank() ? assigneeId : assigneeName, "read"));
                        if (!taskName.isBlank()) {
                            metadata.put("aps_task_" + taskName + "_assignee", List.of(assigneeId));
                        }
                    }

                    // Candidate groups
                    JsonNode candidateGroups = taskNode.path("candidateGroups");
                    if (candidateGroups.isArray()) {
                        for (JsonNode groupNode : candidateGroups) {
                            String groupName = groupNode.isObject() ? groupNode.path("name").asText(groupNode.path("id").asText("")) : groupNode.asText();
                            if (!groupName.isBlank()) {
                                permissions.add(new PermissionRule(groupName, "group", groupName, "read"));
                            }
                        }
                    }

                    // Candidate users
                    JsonNode candidateUsers = taskNode.path("candidateUsers");
                    if (candidateUsers.isArray()) {
                        for (JsonNode userNode : candidateUsers) {
                            String userIdent = userNode.isObject() ? userNode.path("email").asText(userNode.path("id").asText("")) : userNode.asText();
                            if (!userIdent.isBlank()) {
                                permissions.add(new PermissionRule(userIdent, "user", userIdent, "read"));
                            }
                        }
                    }
                }
            }
            contentJson.set(FIELD_TASKS, tasksJson);
        }

        // Attachments discovery
        List<AttachmentInfo> attachments = new ArrayList<>();
        if (includeAttachments) {
            Set<String> seenContentIds = new HashSet<>();
            try {
                JsonNode processContent = fetchProcessAttachments(processInstanceId);
                if (processContent != null && processContent.isArray()) {
                    for (JsonNode item : processContent) {
                        String cId = item.path(FIELD_ID).asText();
                        if (!cId.isBlank() && seenContentIds.add(cId)) {
                            attachments.add(parseAttachmentNode(item, null, null));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not fetch process-level attachments for APS instance {}: {}", processInstanceId, e.getMessage());
            }

            if (includeTasks && !tasksJson.isEmpty()) {
                for (JsonNode taskNode : tasksJson) {
                    String tId = taskNode.path(FIELD_ID).asText();
                    String tName = taskNode.path("name").asText("");
                    if (!tId.isBlank()) {
                        try {
                            JsonNode taskContent = fetchTaskAttachments(tId);
                            if (taskContent != null && taskContent.isArray()) {
                                for (JsonNode item : taskContent) {
                                    String cId = item.path(FIELD_ID).asText();
                                    if (!cId.isBlank() && seenContentIds.add(cId)) {
                                        attachments.add(parseAttachmentNode(item, tId, tName));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Could not fetch task attachments for task {} in APS instance {}: {}", tId, processInstanceId, e.getMessage());
                        }
                    }
                }
            }
        }

        ArrayNode attachmentsJson = objectMapper.createArrayNode();
        if (!attachments.isEmpty()) {
            List<String> attNames = new ArrayList<>();
            List<String> attIds = new ArrayList<>();
            for (AttachmentInfo att : attachments) {
                ObjectNode attNode = objectMapper.createObjectNode();
                attNode.put(FIELD_ID, att.id());
                attNode.put(FIELD_NAME, att.name());
                attNode.put("mimeType", att.mimeType());
                if (att.size() > 0) attNode.put("size", att.size());
                if (!att.created().isBlank()) attNode.put("created", att.created());
                if (!att.createdBy().isBlank()) attNode.put("createdBy", att.createdBy());
                attNode.put("relatedContent", att.relatedContent());
                if (!att.field().isBlank()) attNode.put("field", att.field());
                if (!att.taskId().isBlank()) {
                    attNode.put("taskId", att.taskId());
                    attNode.put("taskName", att.taskName());
                }
                attachmentsJson.add(attNode);

                attNames.add(att.name());
                attIds.add(att.id());
                metadata.put(ATTACHMENT_PREFIX + att.id() + "_name", List.of(att.name()));
                metadata.put(ATTACHMENT_PREFIX + att.id() + "_mimeType", List.of(att.mimeType()));
            }
            metadata.put("aps_attachment_count", List.of(String.valueOf(attNames.size())));
            metadata.put("aps_attachment_names", attNames);
            metadata.put("aps_attachment_ids", attIds);
        }
        contentJson.set(FIELD_ATTACHMENTS, attachmentsJson);

        SecurityConfig security = permissions.isEmpty() 
                ? SecurityConfig.createPublic() 
                : new SecurityConfig(false, permissions.stream().distinct().toList());

        String acl = permissions.stream()
                .map(PermissionRule::identity)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.joining(" "));
        if (acl.isBlank()) {
            acl = "public";
        }

        String docUri = URI_PREFIX + processInstanceId;

        Instant lastModified = Instant.now();
        if (!endTimeStr.isBlank()) {
            try {
                lastModified = Instant.parse(endTimeStr);
            } catch (Exception ignored) {
            }
        } else if (!startTimeStr.isBlank()) {
            try {
                lastModified = Instant.parse(startTimeStr);
            } catch (Exception ignored) {
            }
        }

        InputStream contentStream = new ByteArrayInputStream(objectMapper.writeValueAsBytes(contentJson));

        RepositoryDocument processDoc = new RepositoryDocument(
                processInstanceId,
                docUri,
                contentStream,
                metadata,
                acl,
                security,
                lastModified
        );

        return new ProcessInstanceContext(
                processDoc,
                attachments,
                docUri,
                metadata,
                permissions,
                lastModified,
                tenantIdVal,
                processDefinitionId,
                processDefinitionKey,
                processDefinitionName,
                businessKey
        );
    }

    public RepositoryDocument createDocument(JsonNode instanceNode) throws IOException, InterruptedException {
        return buildProcessContext(instanceNode).processDocument();
    }

    private void processWorkflowInstance(JsonNode instanceNode, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        ProcessInstanceContext context = buildProcessContext(instanceNode);
        synchronized (sink) {
            sink.next(context.processDocument());
        }

        if (includeAttachments && !context.attachments().isEmpty()) {
            String processInstanceId = instanceNode.path(FIELD_ID).asText();
            for (AttachmentInfo att : context.attachments()) {
                try {
                    InputStream attStream = fetchAttachmentContentStream(att.id());
                    if (attStream != null) {
                        String attDocId = processInstanceId + "-content-" + att.id();
                        String attDocUri = context.docUri() + "/content/" + att.id();

                        Map<String, List<String>> attMetadata = new HashMap<>();
                        attMetadata.put("mimeType", List.of(att.mimeType()));
                        attMetadata.put("name", List.of(att.name()));
                        if (att.size() > 0) {
                            attMetadata.put("size", List.of(String.valueOf(att.size())));
                        }
                        attMetadata.put("processInstanceId", List.of(processInstanceId));
                        if (!context.processDefinitionId().isBlank()) {
                            attMetadata.put(FIELD_PROCESS_DEFINITION_ID, List.of(context.processDefinitionId()));
                        }
                        if (!context.processDefinitionKey().isBlank()) {
                            attMetadata.put(FIELD_PROCESS_DEFINITION_KEY, List.of(context.processDefinitionKey()));
                        }
                        if (!context.processDefinitionName().isBlank()) {
                            attMetadata.put(FIELD_PROCESS_DEFINITION_NAME, List.of(context.processDefinitionName()));
                        }
                        if (!context.businessKey().isBlank()) {
                            attMetadata.put(FIELD_BUSINESS_KEY, List.of(context.businessKey()));
                        }
                        if (!context.tenantIdVal().isBlank()) {
                            attMetadata.put(FIELD_TENANT_ID, List.of(context.tenantIdVal()));
                        }
                        attMetadata.put("parentUri", List.of(context.docUri()));
                        attMetadata.put("contentId", List.of(att.id()));
                        attMetadata.put("isRelatedContent", List.of(String.valueOf(att.relatedContent())));
                        if (!att.field().isBlank()) {
                            attMetadata.put("formField", List.of(att.field()));
                        }
                        if (!att.taskId().isBlank()) {
                            attMetadata.put("taskId", List.of(att.taskId()));
                        }
                        if (!att.taskName().isBlank()) {
                            attMetadata.put("taskName", List.of(att.taskName()));
                        }
                        if (!att.createdBy().isBlank()) {
                            attMetadata.put("createdBy", List.of(att.createdBy()));
                        }
                        if (!att.created().isBlank()) {
                            attMetadata.put("created", List.of(att.created()));
                        }

                        // Inherit process variables
                        for (Map.Entry<String, List<String>> entry : context.metadata().entrySet()) {
                            if (entry.getKey().startsWith(VAR_PREFIX)) {
                                attMetadata.put(entry.getKey(), entry.getValue());
                            }
                        }

                        // Inherit security and ensure uploader has access
                        List<PermissionRule> attPermissions = new ArrayList<>(context.permissions());
                        if (!att.createdBy().isBlank() && attPermissions.stream().noneMatch(p -> att.createdBy().equals(p.identity()))) {
                            attPermissions.add(new PermissionRule(att.createdBy(), "user", att.createdBy(), "read"));
                        }
                        SecurityConfig attSecurity = attPermissions.isEmpty()
                                ? SecurityConfig.createPublic()
                                : new SecurityConfig(false, attPermissions.stream().distinct().toList());
                        String attAcl = attPermissions.stream()
                                .map(PermissionRule::identity)
                                .filter(id -> id != null && !id.isBlank())
                                .distinct()
                                .collect(Collectors.joining(" "));
                        if (attAcl.isBlank()) {
                            attAcl = "public";
                        }

                        Instant attModified = context.lastModified();
                        if (!att.created().isBlank()) {
                            try {
                                attModified = Instant.parse(att.created());
                            } catch (Exception ignored) {
                            }
                        }

                        RepositoryDocument attDoc = new RepositoryDocument(
                                attDocId,
                                attDocUri,
                                attStream,
                                attMetadata,
                                attAcl,
                                attSecurity,
                                attModified
                        );

                        synchronized (sink) {
                            sink.next(attDoc);
                        }
                        log.debug("Ingested attachment {} ({}) for APS process instance {}", att.name(), att.id(), processInstanceId);
                    }
                } catch (Exception attEx) {
                    log.warn("Failed to ingest attachment {} ({}) for APS process instance {}: {}",
                            att.name(), att.id(), processInstanceId, attEx.getMessage(), attEx);
                }
            }
        }
    }
}
