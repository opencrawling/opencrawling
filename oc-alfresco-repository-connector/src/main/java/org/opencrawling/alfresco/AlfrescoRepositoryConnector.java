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
package org.opencrawling.alfresco;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

@Component
public class AlfrescoRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoRepositoryConnector.class);
    
    private final String url;
    private final String username;
    private final String password;
    private final int batchSize;
    private final ObjectMapper objectMapper;
    
    private HttpClient httpClient;
    private String authHeader;

    public AlfrescoRepositoryConnector(
            @Value("${spring.opencrawling.connector.alfresco.url:http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1}") String url,
            @Value("${spring.opencrawling.connector.alfresco.username:admin}") String username,
            @Value("${spring.opencrawling.connector.alfresco.password:admin}") String password,
            @Value("${spring.opencrawling.connector.alfresco.batch-size:100}") int batchSize) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.batchSize = batchSize;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "AlfrescoConnector";
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting to Alfresco Content Services at URL: {}", url);
        if (this.httpClient == null) {
            this.httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        
        String credentials = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        
        // Simple connection check by fetching the root node
        String testUrl = url + "/nodes/-root-";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testUrl))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            log.info("Successfully connected to Alfresco Content Services. Session initialized.");
        } else {
            throw new IOException("Failed to connect to Alfresco Content Services. Status code: " + response.statusCode() + ", Response: " + response.body());
        }
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting from Alfresco Content Services.");
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
                
                String startNodeId = "-root-";
                String relativePath = null;
                
                if (basePath != null && !basePath.isBlank() && !basePath.equals("-root-")) {
                    if (basePath.startsWith("/")) {
                        // It is a path; we use -root- and relativePath
                        relativePath = basePath.substring(1); // strip leading slash
                    } else {
                        // It is a specific Node ID (e.g. UUID)
                        startNodeId = basePath;
                    }
                }
                
                scanFolder(startNodeId, relativePath, sink);
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    @SuppressWarnings("preview")
    private void scanFolder(String nodeId, String relativePath, FluxSink<RepositoryDocument> sink) throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            int skipCount = 0;
            boolean hasMore = true;
            
            while (hasMore) {
                JsonNode responseNode;
                try {
                    responseNode = fetchChildrenPage(nodeId, relativePath, skipCount, batchSize);
                } catch (Exception e) {
                    log.error("Error fetching children page for nodeId={}, relativePath={}, skipCount={}", nodeId, relativePath, skipCount, e);
                    break;
                }
                
                JsonNode listNode = responseNode.path("list");
                JsonNode entriesNode = listNode.path("entries");
                
                if (!entriesNode.isArray() || entriesNode.isEmpty()) {
                    break;
                }
                
                for (JsonNode entryWrapper : entriesNode) {
                    JsonNode entry = entryWrapper.path("entry");
                    String childId = entry.path("id").asText();
                    String childName = entry.path("name").asText();
                    boolean isFolder = entry.path("isFolder").asBoolean(false);
                    boolean isFile = entry.path("isFile").asBoolean(false);
                    
                    if (isFolder) {
                        scope.fork(() -> {
                            // After resolving the path down, we use the absolute child Node ID, so relativePath is null
                            scanFolder(childId, null, sink);
                            return null;
                        });
                    } else if (isFile) {
                        try {
                            RepositoryDocument doc = createDocument(entry);
                            sink.next(doc);
                        } catch (Exception e) {
                            log.error("Error creating document for nodeId={} (name={}): {}", childId, childName, e.getMessage());
                        }
                    }
                }
                
                JsonNode paginationNode = listNode.path("pagination");
                hasMore = paginationNode.path("hasMoreItems").asBoolean(false);
                if (hasMore) {
                    skipCount += paginationNode.path("count").asInt(batchSize);
                }
            }
            
            scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            throw new RuntimeException("Folder scan failed for nodeId=" + nodeId + (relativePath != null ? " (" + relativePath + ")" : ""), e.getCause());
        }
    }

    private JsonNode fetchChildrenPage(String nodeId, String relativePath, int skipCount, int maxItems) throws IOException, InterruptedException {
        StringBuilder urlBuilder = new StringBuilder(url)
                .append("/nodes/")
                .append(nodeId)
                .append("/children")
                .append("?skipCount=").append(skipCount)
                .append("&maxItems=").append(maxItems)
                .append("&include=properties");
        
        if (relativePath != null && !relativePath.isEmpty()) {
            urlBuilder.append("&relativePath=").append(URLEncoder.encode(relativePath, StandardCharsets.UTF_8));
        }
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch children for node: " + nodeId + ", relativePath: " + relativePath + ". Status code: " + response.statusCode());
        }
        
        return objectMapper.readTree(response.body());
    }

    private InputStream getDocumentContentStream(String nodeId) throws IOException, InterruptedException {
        String contentUrl = url + "/nodes/" + nodeId + "/content";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(contentUrl))
                .header("Authorization", authHeader)
                .GET()
                .build();
        
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download document content for node: " + nodeId + ", status code: " + response.statusCode());
        }
        return response.body();
    }

    private RepositoryDocument createDocument(JsonNode entry) throws IOException, InterruptedException {
        String childId = entry.path("id").asText();
        String name = entry.path("name").asText();
        String modifiedAtStr = entry.path("modifiedAt").asText();
        Instant modifiedAt = Instant.now();
        if (modifiedAtStr != null && !modifiedAtStr.isEmpty()) {
            try {
                modifiedAt = Instant.parse(modifiedAtStr);
            } catch (Exception e) {
                // fallback
            }
        }
        
        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("name", List.of(name));
        metadata.put("nodeType", List.of(entry.path("nodeType").asText()));
        
        // Content details
        JsonNode contentNode = entry.path("content");
        if (contentNode != null && !contentNode.isMissingNode()) {
            metadata.put("mimeType", List.of(contentNode.path("mimeType").asText()));
            metadata.put("sizeInBytes", List.of(String.valueOf(contentNode.path("sizeInBytes").asLong())));
        }
        
        // Properties mapping
        JsonNode propertiesNode = entry.path("properties");
        if (propertiesNode != null && !propertiesNode.isMissingNode()) {
            propertiesNode.properties().iterator().forEachRemaining(prop -> {
                String propKey = prop.getKey();
                String propValue = prop.getValue().asText();
                if (propValue != null) {
                    metadata.put(propKey, List.of(propValue));
                }
            });
        }
        
        String contentUri = url + "/nodes/" + childId + "/content";
        InputStream contentStream = getDocumentContentStream(childId);
        
        return new RepositoryDocument(
            childId,
            contentUri,
            contentStream,
            metadata,
            "public",
            modifiedAt
        );
    }
}
