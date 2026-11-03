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

/**
 * Camunda Repository Connector supporting both Camunda 8 (Zeebe &amp; REST API v2)
 * and legacy Camunda Platform 7.
 */
@Component
public class CamundaRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(CamundaRepositoryConnector.class);

    // Common BPMN & Camunda Constants
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

    // Camunda 8 Specific Constants
    public static final String URI_PREFIX_CAMUNDA8 = "camunda8://process-instances/";
    public static final String URI_PREFIX_USER_TASKS = "camunda8://user-tasks/";
    public static final String FIELD_CAMUNDA8_PROCESS_INSTANCE_KEY = "camunda8_process_instance_key";
    public static final String FIELD_CAMUNDA8_PROCESS_DEFINITION_KEY = "camunda8_process_definition_key";
    public static final String FIELD_CAMUNDA8_BPMN_PROCESS_ID = "camunda8_bpmn_process_id";
    public static final String FIELD_CAMUNDA8_VERSION = "camunda8_version";
    public static final String FIELD_CAMUNDA8_STATE = "camunda8_state";
    public static final String FIELD_CAMUNDA8_START_DATE = "camunda8_start_date";
    public static final String FIELD_CAMUNDA8_END_DATE = "camunda8_end_date";
    public static final String FIELD_CAMUNDA8_TENANT_ID = "camunda8_tenant_id";
    public static final String FIELD_CAMUNDA8_INCIDENT_COUNT = "camunda8_incident_count";
    public static final String FIELD_CAMUNDA8_USER_TASKS = "camunda8_user_tasks";
    public static final String VAR_PREFIX_CAMUNDA8 = "camunda8_var_";

    // Collocated Metadata Constants
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
    private final String authType;
    private final String username;
    private final String password;
    private final String oauthTokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String audience;
    private final int batchSize;
    private final String processDefinitionKey;
    private final boolean includeVariables;
    private final boolean includeUserTasks;
    private final boolean includeIncidents;
    private final boolean includeAcls;
    private final String scope;
    private final String tenantId;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;
    private String cachedAccessToken;
    private Instant tokenExpiration;

    public CamundaRepositoryConnector() {
        this("http://localhost:8080/v2", "basic", "demo", "demo", "", "", "", "zeebe-api", 100, "", true, true, true, true, "all", "");
    }

    @Autowired
    public CamundaRepositoryConnector(
            @Value("${spring.opencrawling.connector.camunda.url:${opencrawling.connector.camunda.url:http://localhost:8080/v2}}") String url,
            @Value("${spring.opencrawling.connector.camunda.auth-type:${opencrawling.connector.camunda.auth-type:basic}}") String authType,
            @Value("${spring.opencrawling.connector.camunda.username:${opencrawling.connector.camunda.username:demo}}") String username,
            @Value("${spring.opencrawling.connector.camunda.password:${opencrawling.connector.camunda.password:demo}}") String password,
            @Value("${spring.opencrawling.connector.camunda.oauth-token-url:${opencrawling.connector.camunda.oauth-token-url:}}") String oauthTokenUrl,
            @Value("${spring.opencrawling.connector.camunda.client-id:${opencrawling.connector.camunda.client-id:}}") String clientId,
            @Value("${spring.opencrawling.connector.camunda.client-secret:${opencrawling.connector.camunda.client-secret:}}") String clientSecret,
            @Value("${spring.opencrawling.connector.camunda.audience:${opencrawling.connector.camunda.audience:zeebe-api}}") String audience,
            @Value("${spring.opencrawling.connector.camunda.batch-size:${opencrawling.connector.camunda.batch-size:100}}") int batchSize,
            @Value("${spring.opencrawling.connector.camunda.process-definition-key:${opencrawling.connector.camunda.process-definition-key:}}") String processDefinitionKey,
            @Value("${spring.opencrawling.connector.camunda.include-variables:${opencrawling.connector.camunda.include-variables:true}}") boolean includeVariables,
            @Value("${spring.opencrawling.connector.camunda.include-user-tasks:${opencrawling.connector.camunda.include-user-tasks:true}}") boolean includeUserTasks,
            @Value("${spring.opencrawling.connector.camunda.include-incidents:${opencrawling.connector.camunda.include-incidents:true}}") boolean includeIncidents,
            @Value("${spring.opencrawling.connector.camunda.include-acls:${opencrawling.connector.camunda.include-acls:true}}") boolean includeAcls,
            @Value("${spring.opencrawling.connector.camunda.scope:${opencrawling.connector.camunda.scope:all}}") String scope,
            @Value("${spring.opencrawling.connector.camunda.tenant-id:${opencrawling.connector.camunda.tenant-id:}}") String tenantId) {
        this.url = normalizeUrl(url);
        this.authType = (authType != null && !authType.isBlank()) ? authType : "basic";
        this.username = username;
        this.password = password;
        this.oauthTokenUrl = oauthTokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.audience = (audience != null && !audience.isBlank()) ? audience : "zeebe-api";
        this.batchSize = batchSize > 0 ? batchSize : 100;
        this.processDefinitionKey = processDefinitionKey != null ? processDefinitionKey : "";
        this.includeVariables = includeVariables;
        this.includeUserTasks = includeUserTasks;
        this.includeIncidents = includeIncidents;
        this.includeAcls = includeAcls;
        this.scope = (scope != null && !scope.isBlank()) ? scope : "all";
        this.tenantId = tenantId != null ? tenantId : "";
        this.objectMapper = new ObjectMapper();
    }

    public CamundaRepositoryConnector(
            String url,
            String username,
            String password,
            int batchSize,
            String processDefinitionKey,
            boolean includeVariables,
            String scope,
            boolean includeAcls) {
        this(url, "basic", username, password, "", "", "", "zeebe-api", batchSize, processDefinitionKey, includeVariables, true, true, includeAcls, scope, "");
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

    private static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "http://localhost:8080/v2";
        }
        String clean = rawUrl.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    boolean isCamunda7() {
        return url != null && url.contains("/engine-rest");
    }

    String getCamunda8BaseUrl() {
        if (url.endsWith("/v2")) {
            return url;
        }
        return url + "/v2";
    }

    @Override
    public String getName() {
        return "CamundaConnector";
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting to Camunda at URL: {}", url);
        if (this.httpClient == null) {
            this.httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        String auth = resolveAuthHeader();

        if (isCamunda7()) {
            String testUrl = url + "/history/process-instance?maxResults=1";
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl))
                    .header("Accept", "application/json")
                    .GET();
            if (auth != null) {
                reqBuilder.header("Authorization", auth);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("Successfully connected to Camunda 7 REST API.");
            } else {
                throw new IOException("Failed to connect to Camunda 7 REST API. Status code: " + response.statusCode() + ", Response: " + response.body());
            }
        } else {
            // Camunda 8 REST API v2: check /topology first, fallback to /process-instances/search
            String c8Base = getCamunda8BaseUrl();
            String topologyUrl = c8Base + "/topology";
            HttpRequest.Builder topologyBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(topologyUrl))
                    .header("Accept", "application/json")
                    .GET();
            if (auth != null) {
                topologyBuilder.header("Authorization", auth);
            }
            HttpResponse<String> topologyResp = httpClient.send(topologyBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (topologyResp.statusCode() == 200 || topologyResp.statusCode() == 204) {
                log.info("Successfully connected to Camunda 8 REST API v2 at {}", topologyUrl);
                return;
            }

            // Fallback check via /process-instances/search
            String searchUrl = c8Base + "/process-instances/search";
            ObjectNode pingBody = objectMapper.createObjectNode();
            pingBody.putObject("page").put("limit", 1);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(pingBody.toString()));
            if (auth != null) {
                reqBuilder.header("Authorization", auth);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 204) {
                log.info("Successfully connected to Camunda 8 REST API v2 at {}", searchUrl);
                return;
            }

            throw new IOException("Failed to connect to Camunda 8 REST API. Status code: " + topologyResp.statusCode() + ", Response: " + topologyResp.body());
        }
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting from Camunda API.");
        this.httpClient = null;
        this.cachedAccessToken = null;
        this.tokenExpiration = null;
    }

    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    private synchronized String getOAuth2AccessToken() throws IOException, InterruptedException {
        Instant now = Instant.now();
        if (cachedAccessToken != null && tokenExpiration != null && now.isBefore(tokenExpiration.minusSeconds(30))) {
            return cachedAccessToken;
        }

        if (oauthTokenUrl == null || oauthTokenUrl.isBlank()) {
            throw new IllegalArgumentException("oauthTokenUrl is required for oauth2 authentication");
        }

        StringBuilder formBody = new StringBuilder();
        formBody.append("grant_type=client_credentials");
        if (clientId != null && !clientId.isBlank()) {
            formBody.append("&client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        }
        if (clientSecret != null && !clientSecret.isBlank()) {
            formBody.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
        }
        if (audience != null && !audience.isBlank()) {
            formBody.append("&audience=").append(URLEncoder.encode(audience, StandardCharsets.UTF_8));
        }

        HttpRequest tokenRequest = HttpRequest.newBuilder()
                .uri(URI.create(oauthTokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to obtain OAuth2 token from " + oauthTokenUrl + ". HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode tokenJson = objectMapper.readTree(response.body());
        String token = tokenJson.path("access_token").asText();
        if (token == null || token.isBlank()) {
            throw new IOException("OAuth2 response missing access_token: " + response.body());
        }

        long expiresIn = tokenJson.path("expires_in").asLong(3600);
        this.cachedAccessToken = token;
        this.tokenExpiration = now.plusSeconds(expiresIn);
        return token;
    }

    private String resolveAuthHeader() throws IOException, InterruptedException {
        if ("oauth2".equalsIgnoreCase(authType) || (oauthTokenUrl != null && !oauthTokenUrl.isBlank())) {
            return "Bearer " + getOAuth2AccessToken();
        } else if (username != null && !username.isBlank()) {
            String credentials = username + ":" + password;
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return null;
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
                    JsonNode instanceArray = isCamunda7()
                            ? fetchHistoricProcessInstancesCamunda7(targetDefinitionKey, start, batchSize)
                            : fetchProcessInstancesCamunda8(targetDefinitionKey, start, batchSize);

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
                        String id = extractFirstNonBlank(instanceNode, "processInstanceKey", "key", FIELD_ID);
                        log.error("Error creating document for process instance {}: {}", id, e.getMessage(), e);
                    }
                    return null;
                }));
            }
            scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            throw new RuntimeException("Batch processing failed for process instances", e.getCause());
        }
    }

    private JsonNode fetchProcessInstancesCamunda8(String defKey, int start, int size) throws IOException, InterruptedException {
        String searchUrl = getCamunda8BaseUrl() + "/process-instances/search";
        ObjectNode requestJson = objectMapper.createObjectNode();

        ObjectNode pageNode = requestJson.putObject("page");
        pageNode.put("from", start);
        pageNode.put("limit", size);

        ObjectNode filterNode = requestJson.putObject("filter");
        if (defKey != null && !defKey.isBlank()) {
            try {
                long numericDefKey = Long.parseLong(defKey);
                filterNode.put("processDefinitionKey", numericDefKey);
            } catch (NumberFormatException e) {
                filterNode.put("bpmnProcessId", defKey);
            }
        }

        if ("completed".equalsIgnoreCase(scope)) {
            filterNode.put("state", "COMPLETED");
        } else if ("active".equalsIgnoreCase(scope)) {
            filterNode.put("state", "ACTIVE");
        } else if ("canceled".equalsIgnoreCase(scope)) {
            filterNode.put("state", "CANCELED");
        }

        if (tenantId != null && !tenantId.isBlank()) {
            filterNode.put("tenantId", tenantId);
        }

        ArrayNode sortArray = requestJson.putArray("sort");
        ObjectNode sortNode = sortArray.addObject();
        sortNode.put("field", "startDate");
        sortNode.put("order", "ASC");

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()));

        String auth = resolveAuthHeader();
        if (auth != null) {
            reqBuilder.header("Authorization", auth);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 && response.body() != null && response.body().contains("Failed to execute Process Instance Search Query")) {
            log.warn("Camunda 8 search query endpoint returned 400 (secondary search store not configured on standalone broker); returning empty result set.");
            return objectMapper.createArrayNode();
        }
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch Camunda 8 process instances. Status code: " + response.statusCode() + ", Response: " + response.body());
        }

        JsonNode respJson = objectMapper.readTree(response.body());
        return respJson.has("items") ? respJson.get("items") : respJson;
    }

    private JsonNode fetchHistoricProcessInstancesCamunda7(String defKey, int start, int size) throws IOException, InterruptedException {
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

        String auth = resolveAuthHeader();
        if (auth != null) {
            reqBuilder.header("Authorization", auth);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch historic process instances. Status code: " + response.statusCode() + ", Response: " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    private JsonNode fetchUserTasksOrIdentityLinks(String processInstanceKey) throws IOException, InterruptedException {
        if (isCamunda7()) {
            return fetchHistoricIdentityLinksCamunda7(processInstanceKey);
        }
        return fetchUserTasksCamunda8(processInstanceKey);
    }

    private JsonNode fetchUserTasksCamunda8(String processInstanceKey) throws IOException, InterruptedException {
        String tasksUrl = getCamunda8BaseUrl() + "/user-tasks/search";
        ObjectNode requestJson = objectMapper.createObjectNode();
        ObjectNode filterNode = requestJson.putObject("filter");
        try {
            filterNode.put("processInstanceKey", Long.parseLong(processInstanceKey));
        } catch (NumberFormatException e) {
            filterNode.put("processInstanceKey", processInstanceKey);
        }
        requestJson.putObject("page").put("limit", 100);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(tasksUrl))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()));

        String auth = resolveAuthHeader();
        if (auth != null) {
            reqBuilder.header("Authorization", auth);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Failed to fetch user tasks for instance {}. Status: {}", processInstanceKey, response.statusCode());
            return objectMapper.createArrayNode();
        }

        JsonNode respJson = objectMapper.readTree(response.body());
        return respJson.has("items") ? respJson.get("items") : respJson;
    }

    private JsonNode fetchHistoricIdentityLinksCamunda7(String processInstanceId) throws IOException, InterruptedException {
        String linksUrl = url + "/history/identity-link-log?processInstanceId=" + URLEncoder.encode(processInstanceId, StandardCharsets.UTF_8);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(linksUrl))
                .header("Accept", "application/json")
                .GET();

        String auth = resolveAuthHeader();
        if (auth != null) {
            reqBuilder.header("Authorization", auth);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Failed to fetch historic identity links for instance {}. Status: {}", processInstanceId, response.statusCode());
            return objectMapper.createArrayNode();
        }

        return objectMapper.readTree(response.body());
    }

    private JsonNode fetchVariables(String processInstanceKey) throws IOException, InterruptedException {
        if (isCamunda7()) {
            return fetchHistoricVariablesCamunda7(processInstanceKey);
        }
        return fetchVariablesCamunda8(processInstanceKey);
    }

    private JsonNode fetchVariablesCamunda8(String processInstanceKey) throws IOException, InterruptedException {
        String varUrl = getCamunda8BaseUrl() + "/variables/search";
        ObjectNode requestJson = objectMapper.createObjectNode();
        ObjectNode filterNode = requestJson.putObject("filter");
        try {
            filterNode.put("processInstanceKey", Long.parseLong(processInstanceKey));
        } catch (NumberFormatException e) {
            filterNode.put("processInstanceKey", processInstanceKey);
        }
        requestJson.putObject("page").put("limit", 100);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(varUrl))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()));

        String auth = resolveAuthHeader();
        if (auth != null) {
            reqBuilder.header("Authorization", auth);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Failed to fetch variables for instance {}. Status: {}", processInstanceKey, response.statusCode());
            return objectMapper.createArrayNode();
        }

        JsonNode respJson = objectMapper.readTree(response.body());
        return respJson.has("items") ? respJson.get("items") : respJson;
    }

    private JsonNode fetchHistoricVariablesCamunda7(String processInstanceId) throws IOException, InterruptedException {
        String varUrl = url + "/history/variable-instance?processInstanceId=" + URLEncoder.encode(processInstanceId, StandardCharsets.UTF_8);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(varUrl))
                .header("Accept", "application/json")
                .GET();

        String auth = resolveAuthHeader();
        if (auth != null) {
            reqBuilder.header("Authorization", auth);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Failed to fetch historic variables for instance {}. Status: {}", processInstanceId, response.statusCode());
            return objectMapper.createArrayNode();
        }

        return objectMapper.readTree(response.body());
    }

    private int fetchIncidentCountCamunda8(String processInstanceKey) {
        try {
            String incidentsUrl = getCamunda8BaseUrl() + "/incidents/search";
            ObjectNode requestJson = objectMapper.createObjectNode();
            ObjectNode filterNode = requestJson.putObject("filter");
            try {
                filterNode.put("processInstanceKey", Long.parseLong(processInstanceKey));
            } catch (NumberFormatException e) {
                filterNode.put("processInstanceKey", processInstanceKey);
            }
            filterNode.put("state", "ACTIVE");
            requestJson.putObject("page").put("limit", 100);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(incidentsUrl))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson.toString()));

            String auth = resolveAuthHeader();
            if (auth != null) {
                reqBuilder.header("Authorization", auth);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode respJson = objectMapper.readTree(response.body());
                JsonNode items = respJson.has("items") ? respJson.get("items") : respJson;
                return items.isArray() ? items.size() : 0;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch incidents for instance {}: {}", processInstanceKey, e.getMessage());
        }
        return 0;
    }

    private RepositoryDocument createDocument(JsonNode instanceNode) throws IOException, InterruptedException {
        String processInstanceKey = extractFirstNonBlank(instanceNode, "processInstanceKey", "key", FIELD_ID);
        String processDefinitionKey = extractFirstNonBlank(instanceNode, "processDefinitionKey");
        String bpmnProcessId = extractFirstNonBlank(instanceNode, "bpmnProcessId", FIELD_PROCESS_DEFINITION_KEY, FIELD_PROCESS_DEFINITION_ID);
        int version = instanceNode.path("processDefinitionVersion").asInt(instanceNode.path("version").asInt(1));
        String state = extractFirstNonBlank(instanceNode, "state");
        String startDate = extractFirstNonBlank(instanceNode, "startDate", FIELD_START_TIME);
        String endDate = extractFirstNonBlank(instanceNode, "endDate", FIELD_END_TIME);
        String businessKey = extractFirstNonBlank(instanceNode, FIELD_BUSINESS_KEY);
        String startUserId = extractFirstNonBlank(instanceNode, FIELD_START_USER_ID, "initiator");
        String tenant = extractFirstNonBlank(instanceNode, "tenantId");
        long durationInMillis = instanceNode.path(FIELD_DURATION_IN_MILLIS).asLong(0);

        if (state.isBlank()) {
            state = !endDate.isBlank() ? "COMPLETED" : "ACTIVE";
        }

        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("mimeType", List.of("application/json"));
        metadata.put(FIELD_ID, List.of(processInstanceKey));

        // Camunda 8 Metadata Attributes
        metadata.put(FIELD_CAMUNDA8_PROCESS_INSTANCE_KEY, List.of(processInstanceKey));
        if (!processDefinitionKey.isBlank()) {
            metadata.put(FIELD_CAMUNDA8_PROCESS_DEFINITION_KEY, List.of(processDefinitionKey));
            metadata.put(FIELD_PROCESS_DEFINITION_ID, List.of(processDefinitionKey));
            metadata.put(FIELD_CAMUNDA_PROCESS_DEFINITION_ID, List.of(processDefinitionKey));
        }
        if (!bpmnProcessId.isBlank()) {
            metadata.put(FIELD_CAMUNDA8_BPMN_PROCESS_ID, List.of(bpmnProcessId));
            metadata.put(FIELD_PROCESS_DEFINITION_KEY, List.of(bpmnProcessId));
            metadata.put(FIELD_CAMUNDA_PROCESS_DEFINITION_KEY, List.of(bpmnProcessId));
        }
        metadata.put(FIELD_CAMUNDA8_VERSION, List.of(String.valueOf(version)));
        metadata.put(FIELD_CAMUNDA8_STATE, List.of(state));
        if (!startDate.isBlank()) {
            metadata.put(FIELD_CAMUNDA8_START_DATE, List.of(startDate));
            metadata.put(FIELD_START_TIME, List.of(startDate));
        }
        if (!endDate.isBlank()) {
            metadata.put(FIELD_CAMUNDA8_END_DATE, List.of(endDate));
            metadata.put(FIELD_END_TIME, List.of(endDate));
        }
        if (durationInMillis > 0) {
            metadata.put(FIELD_DURATION_IN_MILLIS, List.of(String.valueOf(durationInMillis)));
        }
        if (!tenant.isBlank()) {
            metadata.put(FIELD_CAMUNDA8_TENANT_ID, List.of(tenant));
            metadata.put("tenantId", List.of(tenant));
            metadata.put(FIELD_CAMUNDA_TENANT_ID, List.of(tenant));
        }
        if (!businessKey.isBlank()) {
            metadata.put(FIELD_BUSINESS_KEY, List.of(businessKey));
            metadata.put(FIELD_CAMUNDA_BUSINESS_KEY, List.of(businessKey));
        }
        if (!startUserId.isBlank()) {
            metadata.put(FIELD_START_USER_ID, List.of(startUserId));
            metadata.put(FIELD_CAMUNDA_START_USER_ID, List.of(startUserId));
        }

        // Collocated DB & Entity References
        metadata.put(FIELD_CAMUNDA_PROC_INST_ID, List.of(processInstanceKey));
        metadata.put(FIELD_CAMUNDA_PROCESS_INSTANCE_ID, List.of(processInstanceKey));
        metadata.put(FIELD_CAMUNDA_SCOPE_ID, List.of(processInstanceKey));
        metadata.put(FIELD_CAMUNDA_SCOPE_TYPE, List.of("processInstance"));

        // Content JSON Representation
        ObjectNode contentJson = objectMapper.createObjectNode();
        contentJson.put(FIELD_ID, processInstanceKey);
        contentJson.put("processInstanceKey", processInstanceKey);
        contentJson.put("processDefinitionKey", processDefinitionKey);
        contentJson.put("bpmnProcessId", bpmnProcessId);
        contentJson.put("version", version);
        contentJson.put("state", state);
        contentJson.put(FIELD_BUSINESS_KEY, businessKey);
        contentJson.put(FIELD_START_USER_ID, startUserId);
        contentJson.put(FIELD_START_TIME, startDate);
        contentJson.put(FIELD_END_TIME, endDate);
        contentJson.put(FIELD_DURATION_IN_MILLIS, durationInMillis);
        contentJson.put(FIELD_CAMUNDA_PROC_INST_ID, processInstanceKey);
        contentJson.put(FIELD_CAMUNDA_SCOPE_ID, processInstanceKey);
        contentJson.put(FIELD_CAMUNDA_SCOPE_TYPE, "processInstance");
        if (!tenant.isBlank()) {
            contentJson.put("tenantId", tenant);
            contentJson.put(FIELD_CAMUNDA_TENANT_ID, tenant);
        }

        // Zero-Trust Security Mapping
        SecurityConfig securityConfig = SecurityConfig.createPublic();
        String aclString = "public";

        if (includeAcls) {
            JsonNode tasksOrLinksNode = fetchUserTasksOrIdentityLinks(processInstanceKey);
            securityConfig = CamundaSecurityMapper.mapSecurity(tasksOrLinksNode, startUserId);

            List<String> allowedUsers = CamundaSecurityMapper.extractAllowedUsers(tasksOrLinksNode, startUserId);
            if (!allowedUsers.isEmpty()) {
                metadata.put(FIELD_CAMUNDA_IDENTITY_USERS, allowedUsers);
            }
            List<String> allowedGroups = CamundaSecurityMapper.extractAllowedGroups(tasksOrLinksNode);
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

        // Variables
        ObjectNode variablesJson = objectMapper.createObjectNode();
        if (includeVariables) {
            JsonNode variablesNode = fetchVariables(processInstanceKey);
            JsonNode varArray = variablesNode.has("items") ? variablesNode.get("items") : variablesNode;
            if (varArray.isArray()) {
                for (JsonNode varNode : varArray) {
                    String name = varNode.path(FIELD_NAME).asText();
                    JsonNode valNode = varNode.path(FIELD_VALUE);
                    String valueStr = valNode.isNull() ? "" : valNode.asText();

                    if (!name.isBlank()) {
                        metadata.put(VAR_PREFIX + name, List.of(valueStr));
                        metadata.put(VAR_PREFIX_CAMUNDA8 + name, List.of(valueStr));
                        variablesJson.put(name, valueStr);
                    }
                }
            }
        }
        contentJson.set(FIELD_VARIABLES, variablesJson);

        // Incidents
        if (includeIncidents && !isCamunda7()) {
            int incidentCount = fetchIncidentCountCamunda8(processInstanceKey);
            metadata.put(FIELD_CAMUNDA8_INCIDENT_COUNT, List.of(String.valueOf(incidentCount)));
            contentJson.put("incidentCount", incidentCount);
        }

        String docUri = isCamunda7()
                ? URI_PREFIX + processInstanceKey
                : URI_PREFIX_CAMUNDA8 + processInstanceKey;

        Instant lastModified = Instant.now();
        String dateToParse = !endDate.isBlank() ? endDate : startDate;
        if (!dateToParse.isBlank()) {
            try {
                lastModified = Instant.parse(dateToParse);
            } catch (Exception e) {
                // Ignore fallback
            }
        }

        InputStream contentStream = new ByteArrayInputStream(objectMapper.writeValueAsBytes(contentJson));

        return new RepositoryDocument(
                processInstanceKey,
                docUri,
                contentStream,
                metadata,
                aclString,
                securityConfig,
                lastModified
        );
    }

    private static String extractFirstNonBlank(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode field = node.path(fieldName);
            if (!field.isMissingNode() && !field.isNull() && !field.asText("").isBlank()) {
                return field.asText().trim();
            }
        }
        return "";
    }
}
