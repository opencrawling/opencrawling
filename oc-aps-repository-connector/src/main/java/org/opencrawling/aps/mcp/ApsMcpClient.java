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
package org.opencrawling.aps.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Model Context Protocol (MCP) Client for Alfresco Process Services (APS).
 * Connects to the APS MCP Server over Streamable HTTP/JSON-RPC 2.0 to dynamically
 * discover process definitions, workflow schemas, variable types, and candidate security roles.
 */
public class ApsMcpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ApsMcpClient.class);

    private final String mcpEndpoint;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIdCounter = new AtomicLong(1);

    private HttpClient httpClient;
    private String authHeader;
    private boolean initialized = false;

    public ApsMcpClient(String mcpEndpoint, String username, String password) {
        this.mcpEndpoint = mcpEndpoint != null && mcpEndpoint.endsWith("/") 
                ? mcpEndpoint.substring(0, mcpEndpoint.length() - 1) 
                : mcpEndpoint;
        this.username = username;
        this.password = password;
        this.objectMapper = new ObjectMapper();
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
        if (username != null && password != null && !username.isBlank()) {
            String credentials = username + ":" + password;
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Initializes the connection to the APS MCP server using the MCP handshake.
     */
    public synchronized void initialize() throws IOException, InterruptedException {
        if (httpClient == null) {
            this.httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        if (username != null && password != null && !username.isBlank()) {
            String credentials = username + ":" + password;
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }

        log.info("Initializing connection to APS MCP Server at: {}", mcpEndpoint);

        // Standard MCP initialize request
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("protocolVersion", "2024-11-05");

        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "OpenCrawling-APS-Connector");
        clientInfo.put("version", "1.0.0");
        initParams.set("clientInfo", clientInfo);

        ObjectNode capabilities = objectMapper.createObjectNode();
        initParams.set("capabilities", capabilities);

        JsonNode response = sendJsonRpcRequest("initialize", initParams);
        if (response.has("error")) {
            throw new IOException("APS MCP initialization failed: " + response.path("error").path("message").asText());
        }

        this.initialized = true;
        log.info("Successfully initialized APS MCP connection. Server info: {}", response.path("result").path("serverInfo"));
    }

    /**
     * Lists all tools advertised by the APS MCP server.
     */
    public List<String> listTools() throws IOException, InterruptedException {
        ensureInitialized();
        JsonNode response = sendJsonRpcRequest("tools/list", objectMapper.createObjectNode());
        List<String> tools = new ArrayList<>();
        JsonNode toolsNode = response.path("result").path("tools");
        if (toolsNode.isArray()) {
            for (JsonNode toolNode : toolsNode) {
                tools.add(toolNode.path("name").asText());
            }
        }
        return tools;
    }

    /**
     * Invokes a specific tool on the APS MCP server.
     */
    public JsonNode callTool(String toolName, Map<String, Object> arguments) throws IOException, InterruptedException {
        ensureInitialized();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        ObjectNode argsNode = objectMapper.valueToTree(arguments);
        params.set("arguments", argsNode);

        JsonNode response = sendJsonRpcRequest("tools/call", params);
        if (response.has("error")) {
            throw new IOException("APS MCP tool call '" + toolName + "' failed: " + response.path("error").path("message").asText());
        }
        return response.path("result");
    }

    /**
     * Dynamically discovers all deployed process definition keys from the APS MCP server.
     */
    public List<String> discoverProcessDefinitionKeys() {
        List<String> definitionKeys = new ArrayList<>();
        try {
            ensureInitialized();
            // Try standard tool names for listing workflow definitions
            String[] candidateTools = {"list_process_definitions", "get_process_definitions", "list_workflows"};
            for (String toolName : candidateTools) {
                try {
                    JsonNode result = callTool(toolName, Map.of());
                    JsonNode contentNode = result.path("content");
                    if (contentNode.isArray() && !contentNode.isEmpty()) {
                        String text = contentNode.get(0).path("text").asText("");
                        if (!text.isBlank()) {
                            JsonNode parsed = objectMapper.readTree(text);
                            if (parsed.isArray()) {
                                for (JsonNode item : parsed) {
                                    String key = item.path("key").asText(item.path("processDefinitionKey").asText(""));
                                    if (!key.isBlank() && !definitionKeys.contains(key)) {
                                        definitionKeys.add(key);
                                    }
                                }
                            }
                        }
                    }
                    if (!definitionKeys.isEmpty()) {
                        break;
                    }
                } catch (Exception ignored) {
                    // Try next candidate tool name
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover process definition keys via APS MCP server: {}", e.getMessage());
        }
        return definitionKeys;
    }

    /**
     * Discovers process variable schemas and expected types for a given process definition key.
     */
    public Map<String, String> discoverVariableSchema(String processDefinitionKey) {
        Map<String, String> varSchemas = new HashMap<>();
        try {
            ensureInitialized();
            String[] candidateTools = {"get_process_definition_schema", "get_process_definition_model", "get_process_schema"};
            for (String toolName : candidateTools) {
                try {
                    JsonNode result = callTool(toolName, Map.of("processDefinitionKey", processDefinitionKey));
                    JsonNode contentNode = result.path("content");
                    if (contentNode.isArray() && !contentNode.isEmpty()) {
                        String text = contentNode.get(0).path("text").asText("");
                        if (!text.isBlank()) {
                            JsonNode parsed = objectMapper.readTree(text);
                            JsonNode varsNode = parsed.path("variables");
                            if (varsNode.isArray()) {
                                for (JsonNode varNode : varsNode) {
                                    String name = varNode.path("name").asText();
                                    String type = varNode.path("type").asText("string");
                                    if (!name.isBlank()) {
                                        varSchemas.put(name, type);
                                    }
                                }
                            }
                        }
                    }
                    if (!varSchemas.isEmpty()) {
                        break;
                    }
                } catch (Exception ignored) {
                    // Try next candidate tool
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover variable schema for '{}' via APS MCP server: {}", processDefinitionKey, e.getMessage());
        }
        return varSchemas;
    }

    /**
     * Discovers organizational candidate groups for zero-trust security mapping.
     */
    public List<String> discoverCandidateGroups() {
        List<String> groups = new ArrayList<>();
        try {
            ensureInitialized();
            String[] candidateTools = {"list_candidate_groups", "get_security_groups", "list_groups"};
            for (String toolName : candidateTools) {
                try {
                    JsonNode result = callTool(toolName, Map.of());
                    JsonNode contentNode = result.path("content");
                    if (contentNode.isArray() && !contentNode.isEmpty()) {
                        String text = contentNode.get(0).path("text").asText("");
                        if (!text.isBlank()) {
                            JsonNode parsed = objectMapper.readTree(text);
                            if (parsed.isArray()) {
                                for (JsonNode g : parsed) {
                                    String groupName = g.path("name").asText(g.asText());
                                    if (!groupName.isBlank() && !groups.contains(groupName)) {
                                        groups.add(groupName);
                                    }
                                }
                            }
                        }
                    }
                    if (!groups.isEmpty()) {
                        break;
                    }
                } catch (Exception ignored) {
                    // Try next candidate tool
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover candidate groups via APS MCP server: {}", e.getMessage());
        }
        return groups;
    }

    public boolean isInitialized() {
        return initialized;
    }

    private void ensureInitialized() throws IOException, InterruptedException {
        if (!initialized) {
            initialize();
        }
    }

    private JsonNode sendJsonRpcRequest(String method, JsonNode params) throws IOException, InterruptedException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("id", requestIdCounter.getAndIncrement());
        requestBody.put("method", method);
        requestBody.set("params", params);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(mcpEndpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)));

        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("MCP server HTTP error: " + response.statusCode() + " - " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    @Override
    public void close() {
        this.initialized = false;
        this.httpClient = null;
        this.authHeader = null;
    }
}
