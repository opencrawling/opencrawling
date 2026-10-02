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
package org.opencrawling.alfresco;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;

import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.observability.concurrency.ObservabilityTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * High-performance Alfresco Content Services Repository Connector.
 * Supports dual-mode ingestion:
 * 1. Hierarchical folder tree traversal with subfolder filtering, exclusions, and site scoping.
 * 2. Targeted search ingestion using the Alfresco Search REST API (AFTS, CMISQL, and Lucene).
 * Integrates zero-trust ACL mapping, binary payload thresholds, and Java 25 Virtual Threads.
 */
@Component
public class AlfrescoRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoRepositoryConnector.class);

    private final String url;
    private final String username;
    private final String password;
    private final int batchSize;
    private final AlfrescoCrawlMode crawlMode;
    private final String rootFolderPath;
    private final String rootFolderId;
    private final String siteId;
    private final boolean includeSubfolders;
    private final Set<String> excludedFolders;
    private final String searchQuery;
    private final String queryLanguage;
    private final boolean includeAcls;
    private final boolean includeContentStream;
    private final long maxContentSizeBytes;
    private final Set<String> mimeTypeFilter;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;
    private String authHeader;

    public AlfrescoRepositoryConnector() {
        this("http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1",
             "admin", "admin", 100, AlfrescoCrawlMode.FOLDER,
             "/", "", "", true, Set.of("Data Dictionary"),
             "TYPE:'cm:content'", "afts", true, true, 52428800L, Set.of(), 30);
    }

    public AlfrescoRepositoryConnector(
            String url,
            String username,
            String password,
            int batchSize) {
        this(url, username, password, batchSize, AlfrescoCrawlMode.FOLDER,
             "/", "", "", true, Set.of("Data Dictionary"),
             "TYPE:'cm:content'", "afts", true, true, 52428800L, Set.of(), 30);
    }

    public AlfrescoRepositoryConnector(
            String url,
            String username,
            String password,
            int batchSize,
            String excludedFoldersConfig) {
        this(url, username, password, batchSize, AlfrescoCrawlMode.FOLDER,
             "/", "", "", true, parseDelimitedSet(excludedFoldersConfig, Set.of("Data Dictionary")),
             "TYPE:'cm:content'", "afts", true, true, 52428800L, Set.of(), 30);
    }

    @Autowired
    public AlfrescoRepositoryConnector(
            @Value("${spring.opencrawling.connector.alfresco.url:http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1}") String url,
            @Value("${spring.opencrawling.connector.alfresco.username:admin}") String username,
            @Value("${spring.opencrawling.connector.alfresco.password:admin}") String password,
            @Value("${spring.opencrawling.connector.alfresco.batch-size:100}") int batchSize,
            @Value("${spring.opencrawling.connector.alfresco.crawl-mode:folder}") String crawlModeStr,
            @Value("${spring.opencrawling.connector.alfresco.root-folder-path:/}") String rootFolderPath,
            @Value("${spring.opencrawling.connector.alfresco.root-folder-id:}") String rootFolderId,
            @Value("${spring.opencrawling.connector.alfresco.site-id:}") String siteId,
            @Value("${spring.opencrawling.connector.alfresco.include-subfolders:true}") boolean includeSubfolders,
            @Value("${spring.opencrawling.connector.alfresco.excluded-folders:Data Dictionary}") String excludedFoldersConfig,
            @Value("${spring.opencrawling.connector.alfresco.search-query:TYPE:'cm:content'}") String searchQuery,
            @Value("${spring.opencrawling.connector.alfresco.query-language:afts}") String queryLanguage,
            @Value("${spring.opencrawling.connector.alfresco.include-acls:true}") boolean includeAcls,
            @Value("${spring.opencrawling.connector.alfresco.include-content-stream:true}") boolean includeContentStream,
            @Value("${spring.opencrawling.connector.alfresco.max-content-size-bytes:52428800}") long maxContentSizeBytes,
            @Value("${spring.opencrawling.connector.alfresco.mimetype-filter:}") String mimeTypeFilterConfig,
            @Value("${spring.opencrawling.connector.alfresco.timeout-seconds:30}") int timeoutSeconds) {

        this(url, username, password, batchSize,
             AlfrescoCrawlMode.fromString(crawlModeStr),
             rootFolderPath, rootFolderId, siteId,
             includeSubfolders,
             parseDelimitedSet(excludedFoldersConfig, Set.of("Data Dictionary")),
             searchQuery, queryLanguage,
             includeAcls, includeContentStream,
             maxContentSizeBytes,
             parseDelimitedSet(mimeTypeFilterConfig, Set.of()),
             timeoutSeconds);
    }

    public AlfrescoRepositoryConnector(
            String url,
            String username,
            String password,
            int batchSize,
            AlfrescoCrawlMode crawlMode,
            String rootFolderPath,
            String rootFolderId,
            String siteId,
            boolean includeSubfolders,
            Set<String> excludedFolders,
            String searchQuery,
            String queryLanguage,
            boolean includeAcls,
            boolean includeContentStream,
            long maxContentSizeBytes,
            Set<String> mimeTypeFilter,
            int timeoutSeconds) {

        this.url = (url != null && !url.isBlank()) ? url.trim() : "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1";
        this.username = username != null ? username : "admin";
        this.password = password != null ? password : "admin";
        this.batchSize = batchSize > 0 ? batchSize : 100;
        this.crawlMode = crawlMode != null ? crawlMode : AlfrescoCrawlMode.FOLDER;
        this.rootFolderPath = (rootFolderPath != null && !rootFolderPath.isBlank()) ? rootFolderPath.trim() : "/";
        this.rootFolderId = rootFolderId != null ? rootFolderId.trim() : "";
        this.siteId = siteId != null ? siteId.trim() : "";
        this.includeSubfolders = includeSubfolders;
        this.excludedFolders = excludedFolders != null ? excludedFolders : Set.of("Data Dictionary");
        this.searchQuery = (searchQuery != null && !searchQuery.isBlank()) ? searchQuery.trim() : "TYPE:'cm:content'";
        this.queryLanguage = (queryLanguage != null && !queryLanguage.isBlank()) ? queryLanguage.trim() : "afts";
        this.includeAcls = includeAcls;
        this.includeContentStream = includeContentStream;
        this.maxContentSizeBytes = maxContentSizeBytes > 0 ? maxContentSizeBytes : 52428800L;
        this.mimeTypeFilter = mimeTypeFilter != null ? mimeTypeFilter : Set.of();
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
        this.objectMapper = new ObjectMapper();
    }

    private static Set<String> parseDelimitedSet(String input, Set<String> defaultSet) {
        if (input != null && !input.isBlank()) {
            return Arrays.stream(input.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }
        return defaultSet;
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
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        String credentials = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        // Simple connection check by fetching the root node
        String testUrl = url + "/nodes/-root-";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
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

                boolean isQuery = crawlMode == AlfrescoCrawlMode.QUERY || isQueryString(basePath);
                if (isQuery) {
                    QueryInfo queryInfo = resolveQueryInfo(basePath);
                    log.info("Executing Alfresco Search REST API query scan (language: '{}'): {}", queryInfo.language(), queryInfo.query());
                    scanQuery(queryInfo.query(), queryInfo.language(), sink);
                } else {
                    String startNodeId = "-root-";
                    String relativePath = null;

                    if (basePath != null && !basePath.isBlank() && !basePath.equals("-root-") && !basePath.equals("/")) {
                        if (basePath.startsWith("/")) {
                            relativePath = basePath.substring(1);
                            if (relativePath.isBlank()) {
                                relativePath = null;
                            }
                        } else {
                            startNodeId = basePath;
                        }
                    } else if (rootFolderId != null && !rootFolderId.isBlank()) {
                        startNodeId = rootFolderId;
                    } else if (siteId != null && !siteId.isBlank()) {
                        relativePath = "Sites/" + siteId + "/documentLibrary";
                    } else if (rootFolderPath != null && !rootFolderPath.isBlank() && !rootFolderPath.equals("-root-") && !rootFolderPath.equals("/")) {
                        if (rootFolderPath.startsWith("/")) {
                            relativePath = rootFolderPath.substring(1);
                            if (relativePath.isBlank()) {
                                relativePath = null;
                            }
                        } else {
                            startNodeId = rootFolderPath;
                        }
                    }

                    log.info("Executing Alfresco hierarchical folder scan starting at nodeId='{}', relativePath='{}' (recursive={})",
                            startNodeId, relativePath, includeSubfolders);
                    scanFolder(startNodeId, relativePath, sink);
                }

                synchronized (sink) {
                    sink.complete();
                }
            } catch (Exception e) {
                log.error("Alfresco scan failed: {}", e.getMessage(), e);
                synchronized (sink) {
                    sink.error(e);
                }
            }
        });
    }

    private boolean isQueryString(String path) {
        if (path == null || path.isBlank()) return false;
        String trimmed = path.trim();
        String lower = trimmed.toLowerCase();
        return lower.startsWith("select ") ||
               lower.startsWith("afts:") ||
               lower.startsWith("cmis:") ||
               lower.startsWith("lucene:") ||
               trimmed.startsWith("TYPE:") ||
               trimmed.startsWith("PATH:") ||
               trimmed.startsWith("ASPECT:") ||
               trimmed.startsWith("TAG:") ||
               trimmed.startsWith("TEXT:") ||
               trimmed.startsWith("@");
    }

    private record QueryInfo(String query, String language) {}

    private QueryInfo resolveQueryInfo(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank() || rawQuery.equals("-root-") || rawQuery.equals("/")) {
            return new QueryInfo(this.searchQuery, this.queryLanguage);
        }
        String trimmed = rawQuery.trim();
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("select ")) {
            return new QueryInfo(trimmed, "cmis");
        }
        if (lower.startsWith("afts:")) {
            return new QueryInfo(trimmed.substring(5).trim(), "afts");
        }
        if (lower.startsWith("cmis:")) {
            return new QueryInfo(trimmed.substring(5).trim(), "cmis");
        }
        if (lower.startsWith("lucene:")) {
            return new QueryInfo(trimmed.substring(7).trim(), "lucene");
        }
        return new QueryInfo(trimmed, this.queryLanguage != null ? this.queryLanguage : "afts");
    }

    private String resolveSearchUrl() {
        if (url.contains("/public/alfresco/versions/1")) {
            return url.replace("/public/alfresco/versions/1", "/public/search/versions/1/search");
        } else if (url.endsWith("/")) {
            return url + "search";
        } else {
            return url + "/search";
        }
    }

    private void scanQuery(String query, String queryLanguage, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        String searchUrl = resolveSearchUrl();
        int skipCount = 0;
        boolean hasMore = true;

        while (hasMore) {
            Map<String, Object> requestBody = new HashMap<>();
            Map<String, String> queryMap = new HashMap<>();
            queryMap.put("language", queryLanguage);
            queryMap.put("query", query);
            requestBody.put("query", queryMap);

            Map<String, Object> pagingMap = new HashMap<>();
            pagingMap.put("maxItems", batchSize);
            pagingMap.put("skipCount", skipCount);
            requestBody.put("paging", pagingMap);

            List<String> includes = new ArrayList<>(List.of("properties", "aspectNames"));
            if (includeAcls) {
                includes.add("permissions");
            }
            requestBody.put("include", includes);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Alfresco search query failed (status " + response.statusCode() + "): " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode listNode = root.path("list");
            JsonNode entriesNode = listNode.path("entries");

            if (!entriesNode.isArray() || entriesNode.isEmpty()) {
                break;
            }

            for (JsonNode entryWrapper : entriesNode) {
                JsonNode entry = entryWrapper.path("entry");
                boolean isFile = entry.path("isFile").asBoolean(true);
                if (isFile) {
                    JsonNode contentNode = entry.path("content");
                    String mimeType = (contentNode != null && !contentNode.isMissingNode())
                            ? contentNode.path("mimeType").asText("")
                            : "";
                    if (!matchesMimeType(mimeType)) {
                        continue;
                    }
                    try {
                        RepositoryDocument doc = createDocument(entry);
                        if (doc != null) {
                            synchronized (sink) {
                                sink.next(doc);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Error creating document from query result {}: {}", entry.path("id").asText(), e.getMessage());
                    }
                }
            }

            JsonNode paginationNode = listNode.path("pagination");
            hasMore = paginationNode.path("hasMoreItems").asBoolean(false);
            if (hasMore) {
                int count = paginationNode.path("count").asInt(batchSize);
                skipCount += count > 0 ? count : batchSize;
            }
        }
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
                        if (isFolderExcluded(childName, childId)) {
                            log.info("Skipping excluded folder '{}' (id: {})", childName, childId);
                            continue;
                        }
                        if (includeSubfolders) {
                            scope.fork(ObservabilityTask.observed(() -> {
                                scanFolder(childId, null, sink);
                                return null;
                            }));
                        } else {
                            log.debug("Skipping subfolder '{}' because includeSubfolders is false", childName);
                        }
                    } else if (isFile) {
                        try {
                            JsonNode contentNode = entry.path("content");
                            if (includeContentStream && (contentNode == null || contentNode.isMissingNode())) {
                                log.debug("Skipping file node without content: {} (name: {})", childId, childName);
                                continue;
                            }

                            String mimeType = (contentNode != null && !contentNode.isMissingNode())
                                    ? contentNode.path("mimeType").asText("")
                                    : "";
                            if (!matchesMimeType(mimeType)) {
                                log.debug("Skipping file {} with non-matching MIME type: {}", childName, mimeType);
                                continue;
                            }

                            RepositoryDocument doc = createDocument(entry);
                            if (doc != null) {
                                synchronized (sink) {
                                    sink.next(doc);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Error creating document for nodeId={} (name={}): {}", childId, childName, e.getMessage());
                        }
                    }
                }

                JsonNode paginationNode = listNode.path("pagination");
                hasMore = paginationNode.path("hasMoreItems").asBoolean(false);
                if (hasMore) {
                    int count = paginationNode.path("count").asInt(batchSize);
                    skipCount += count > 0 ? count : batchSize;
                }
            }

            scope.join();
        } catch (StructuredTaskScope.FailedException e) {
            throw new RuntimeException("Folder scan failed for nodeId=" + nodeId + (relativePath != null ? " (" + relativePath + ")" : ""), e.getCause());
        }
    }

    private boolean isFolderExcluded(String folderName, String folderId) {
        if (excludedFolders == null || excludedFolders.isEmpty()) return false;
        return excludedFolders.stream().anyMatch(ex ->
                ex.equalsIgnoreCase(folderName) ||
                ex.equalsIgnoreCase(folderId) ||
                folderName.toLowerCase().contains(ex.toLowerCase()));
    }

    private boolean matchesMimeType(String mimeType) {
        if (mimeTypeFilter == null || mimeTypeFilter.isEmpty()) {
            return true;
        }
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String target = mimeType.trim().toLowerCase();
        for (String filter : mimeTypeFilter) {
            String f = filter.trim().toLowerCase();
            if (f.equals(target)) {
                return true;
            }
            if (f.endsWith("/*")) {
                String prefix = f.substring(0, f.length() - 1);
                if (target.startsWith(prefix)) {
                    return true;
                }
            }
            if (f.startsWith("*") && f.length() > 1 && target.endsWith(f.substring(1))) {
                return true;
            }
        }
        return false;
    }

    private JsonNode fetchChildrenPage(String nodeId, String relativePath, int skipCount, int maxItems) throws IOException, InterruptedException {
        StringBuilder urlBuilder = new StringBuilder(url)
                .append("/nodes/")
                .append(nodeId)
                .append("/children")
                .append("?skipCount=").append(skipCount)
                .append("&maxItems=").append(maxItems)
                .append("&include=properties,aspectNames");

        if (includeAcls) {
            urlBuilder.append(",permissions");
        }

        if (relativePath != null && !relativePath.isEmpty()) {
            urlBuilder.append("&relativePath=").append(URLEncoder.encode(relativePath, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
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
                .timeout(Duration.ofSeconds(timeoutSeconds))
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
            } catch (Exception ignored) {}
        }

        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("name", List.of(name));
        metadata.put("nodeType", List.of(entry.path("nodeType").asText()));
        metadata.put("alfresco_node_id", List.of(childId));
        metadata.put("alfresco_node_type", List.of(entry.path("nodeType").asText()));

        if (entry.has("parentId")) {
            metadata.put("alfresco_parent_id", List.of(entry.path("parentId").asText()));
        }

        // Content details & size verification
        JsonNode contentNode = entry.path("content");
        long sizeInBytes = 0;
        if (contentNode != null && !contentNode.isMissingNode()) {
            String mimeType = contentNode.path("mimeType").asText();
            sizeInBytes = contentNode.path("sizeInBytes").asLong(0);
            metadata.put("mimeType", List.of(mimeType));
            metadata.put("sizeInBytes", List.of(String.valueOf(sizeInBytes)));
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

        // Aspects mapping
        JsonNode aspectNames = entry.path("aspectNames");
        if (aspectNames != null && aspectNames.isArray() && !aspectNames.isEmpty()) {
            List<String> aspects = new ArrayList<>();
            aspectNames.forEach(a -> aspects.add(a.asText()));
            metadata.put("alfresco_aspects", aspects);
        }

        // Security / Zero-Trust ACL mapping
        AlfrescoSecurityMapper.SecurityResult securityResult = includeAcls
                ? AlfrescoSecurityMapper.mapSecurity(entry.path("permissions"))
                : new AlfrescoSecurityMapper.SecurityResult(SecurityConfig.createPublic(), "public", List.of(), List.of(), true);

        metadata.put("alfresco_is_inherited", List.of(String.valueOf(securityResult.isInherited())));
        if (!securityResult.identityUsers().isEmpty()) {
            metadata.put("alfresco_identity_users", List.of(String.join(",", securityResult.identityUsers())));
        }
        if (!securityResult.identityGroups().isEmpty()) {
            metadata.put("alfresco_identity_groups", List.of(String.join(",", securityResult.identityGroups())));
        }

        // Binary Content Streaming
        String contentUri = url + "/nodes/" + childId + "/content";
        InputStream contentStream = null;
        if (includeContentStream && contentNode != null && !contentNode.isMissingNode()) {
            if (maxContentSizeBytes > 0 && sizeInBytes > maxContentSizeBytes) {
                log.warn("Skipping content stream for Alfresco node '{}' ({} bytes) exceeding maxContentSizeBytes ({} bytes)",
                        childId, sizeInBytes, maxContentSizeBytes);
            } else {
                try {
                    contentStream = getDocumentContentStream(childId);
                } catch (Exception e) {
                    log.warn("Failed to download content stream for node {}: {}", childId, e.getMessage());
                }
            }
        }

        return new RepositoryDocument(
            childId,
            contentUri,
            contentStream,
            metadata,
            securityResult.aclString(),
            securityResult.securityConfig(),
            modifiedAt
        );
    }
}
