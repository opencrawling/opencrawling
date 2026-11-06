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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.StructuredTaskScope;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.opencrawling.core.connector.discovery.CountAccuracy;
import org.opencrawling.core.connector.discovery.DiscoverableRepositoryConnector;
import org.opencrawling.core.connector.discovery.DiscoveryException;
import org.opencrawling.core.connector.discovery.DiscoveryNode;
import org.opencrawling.core.connector.discovery.DiscoveryResult;
import org.opencrawling.core.connector.discovery.DryRunRequest;
import org.opencrawling.core.connector.discovery.DryRunResult;
import org.opencrawling.core.connector.discovery.ExploreRequest;
import org.opencrawling.core.connector.discovery.TruncationReason;
import org.opencrawling.core.connector.discovery.VisibilityFilter;
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
public class AlfrescoRepositoryConnector implements DiscoverableRepositoryConnector {

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

    record QueryInfo(String query, String language) {}

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

    // ---- Discovery and dry run (#203) ----

    /** Longest query a dry run accepts. */
    static final int MAX_QUERY_LENGTH = 4000;
    /** A node reference that is not a path: a node id or an alias such as {@code -root-}. */
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");
    private static final Set<String> QUERY_LANGUAGES = Set.of("afts", "cmis", "lucene");
    private static final Set<String> PUBLIC_AUTHORITIES = Set.of("group_everyone", "guest", "role_anonymous", "everyone");

    private void ensureConnected() {
        if (httpClient == null) {
            try {
                connect();
            } catch (Exception e) {
                throw new DiscoveryException("Cannot connect to Alfresco: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Lists the tree below a folder, breadth-first. The folder must be the configured root (folder, site or path)
     * or below it, and neither it nor a folder between it and the root may be excluded; excluded folders are left
     * out of the listing, as the crawler leaves them out. Permissions are always requested, whatever
     * {@code include-acls} says, since the filter decides on them.
     */
    @Override
    public DiscoveryResult explore(ExploreRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        ensureConnected();
        try {
            String startRef = request.start() != null ? request.start() : configuredRootRef();
            DiscoveryNode startNode = visibleFolder(folderInScope(startRef), startRef, filter);
            Deque<DiscoveryNode> folders = new ArrayDeque<>();
            folders.add(startNode);
            List<DiscoveryNode> nodes = new ArrayList<>();
            long hidden = 0;
            TruncationReason truncation = null;
            while (!folders.isEmpty()) {
                DiscoveryNode folder = folders.poll();
                int skipCount = 0;
                boolean hasMore = true;
                while (hasMore) {
                    if (System.nanoTime() - deadline > 0) {
                        return new DiscoveryResult(nodes, hidden, TruncationReason.TIMEOUT);
                    }
                    JsonNode list = discoveryChildren(folder.sourceId(), skipCount).path("list");
                    List<DiscoveryNode> candidates = new ArrayList<>();
                    int entries = 0;
                    for (JsonNode wrapper : list.path("entries")) {
                        entries++;
                        DiscoveryNode node = childNode(wrapper.path("entry"), folder.sourceId(), folder.depth() + 1);
                        if (node != null && (node.type() == DiscoveryNode.NodeType.FOLDER || request.includeDocuments())) {
                            candidates.add(node);
                        }
                    }
                    boolean[] visible = VisibilityFilter.decide(filter, candidates);
                    for (int i = 0; i < visible.length; i++) {
                        if (!visible[i]) {
                            hidden++;
                            continue;
                        }
                        DiscoveryNode node = candidates.get(i);
                        if (nodes.size() >= request.maxNodes()) {
                            return new DiscoveryResult(nodes, hidden, TruncationReason.MAX_NODES);
                        }
                        nodes.add(node);
                        if (node.type() == DiscoveryNode.NodeType.FOLDER) {
                            if (node.depth() < request.maxDepth()) {
                                folders.add(node);
                            } else {
                                truncation = TruncationReason.MAX_DEPTH;
                            }
                        }
                    }
                    hasMore = entries > 0 && list.path("pagination").path("hasMoreItems").asBoolean(false);
                    skipCount += entries;
                }
            }
            return new DiscoveryResult(nodes, hidden, truncation);
        } catch (IOException e) {
            throw new DiscoveryException("Alfresco exploration failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Alfresco exploration was interrupted.", e);
        }
    }

    /**
     * Counts what a crawl of the scope would pick up that the filter shows, with the crawler's rules: for a folder,
     * its files (and, with {@code include-subfolders}, its subfolders' files) that pass the MIME type filter,
     * skipping excluded folders; for a query (AFTS, CMIS or Lucene, as the crawler takes it), every file it
     * returns. Without a scope, the connector's configured query or root.
     *
     * <p>Alfresco returns permissions with search results too, so a query's count is checked item by item, like a
     * folder's. Subfolders are walked whether or not the filter shows them, since a crawl takes their files too;
     * samples name no folder.
     */
    @Override
    public DryRunResult dryRun(DryRunRequest request, VisibilityFilter filter) {
        long deadline = System.nanoTime() + request.timeout().toNanos();
        ensureConnected();
        String scope = request.scope();
        try {
            if ((scope == null && crawlMode == AlfrescoCrawlMode.QUERY) || isQueryString(scope)) {
                QueryInfo query = readOnlyQuery(scope == null ? new QueryInfo(searchQuery, queryLanguage) : resolveQueryInfo(scope));
                return dryRunQuery(query, request, filter, deadline);
            }
            String folderRef = scope != null ? scope : configuredRootRef();
            return dryRunFolder(visibleFolder(folderInScope(folderRef), folderRef, filter), request, filter, deadline);
        } catch (IOException e) {
            throw new DiscoveryException("Alfresco dry run failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Alfresco dry run was interrupted.", e);
        }
    }

    private DryRunResult dryRunFolder(DiscoveryNode start, DryRunRequest request, VisibilityFilter filter, long deadline)
            throws IOException, InterruptedException {
        Deque<String> folders = new ArrayDeque<>();
        folders.add(start.sourceId());
        Set<String> visited = new HashSet<>();
        Tally tally = new Tally(request);
        while (!folders.isEmpty()) {
            String folderId = folders.poll();
            if (!visited.add(folderId)) {
                continue;
            }
            int skipCount = 0;
            boolean hasMore = true;
            while (hasMore) {
                if (System.nanoTime() - deadline > 0) {
                    return tally.result(TruncationReason.TIMEOUT);
                }
                JsonNode list = discoveryChildren(folderId, skipCount).path("list");
                List<DiscoveryNode> documents = new ArrayList<>();
                int entries = 0;
                for (JsonNode wrapper : list.path("entries")) {
                    entries++;
                    DiscoveryNode node = childNode(wrapper.path("entry"), null, 1);
                    if (node == null) {
                        continue;
                    }
                    if (node.type() == DiscoveryNode.NodeType.FOLDER) {
                        if (includeSubfolders) {
                            folders.add(node.sourceId());
                        }
                    } else {
                        documents.add(node);
                    }
                }
                if (!tally.add(documents, filter)) {
                    return tally.result(TruncationReason.MAX_SCAN);
                }
                hasMore = entries > 0 && list.path("pagination").path("hasMoreItems").asBoolean(false);
                skipCount += entries;
            }
        }
        return tally.result(null);
    }

    private DryRunResult dryRunQuery(QueryInfo query, DryRunRequest request, VisibilityFilter filter, long deadline)
            throws IOException, InterruptedException {
        Tally tally = new Tally(request);
        int skipCount = 0;
        boolean hasMore = true;
        while (hasMore) {
            if (System.nanoTime() - deadline > 0) {
                return tally.result(TruncationReason.TIMEOUT);
            }
            JsonNode list = discoverySearch(query, skipCount).path("list");
            List<DiscoveryNode> documents = new ArrayList<>();
            int entries = 0;
            for (JsonNode wrapper : list.path("entries")) {
                entries++;
                JsonNode entry = wrapper.path("entry");
                // A query crawl takes the files it returns, filtered only by MIME type.
                if (entry.path("isFile").asBoolean(true) && matchesMimeType(entry.path("content").path("mimeType").asText(""))) {
                    DiscoveryNode node = documentNode(entry, null, 1);
                    if (node != null) {
                        documents.add(node);
                    }
                }
            }
            if (!tally.add(documents, filter)) {
                return tally.result(TruncationReason.MAX_SCAN);
            }
            hasMore = entries > 0 && list.path("pagination").path("hasMoreItems").asBoolean(false);
            skipCount += entries;
        }
        return tally.result(null);
    }

    /** Counts a dry run's documents page by page against the filter and the scan limit. */
    private static final class Tally {
        private final DryRunRequest request;
        private final List<DiscoveryNode> samples = new ArrayList<>();
        private long scanned;
        private long count;
        private long hidden;

        Tally(DryRunRequest request) {
            this.request = request;
        }

        /** @return false when the scan limit was reached */
        boolean add(List<DiscoveryNode> documents, VisibilityFilter filter) {
            boolean[] visible = VisibilityFilter.decide(filter, documents);
            for (int i = 0; i < visible.length; i++) {
                if (scanned >= request.maxScan()) {
                    return false;
                }
                scanned++;
                if (!visible[i]) {
                    hidden++;
                    continue;
                }
                count++;
                if (samples.size() < request.sampleSize()) {
                    samples.add(documents.get(i));
                }
            }
            return true;
        }

        DryRunResult result(TruncationReason truncation) {
            return new DryRunResult(count, truncation == null ? CountAccuracy.EXACT : CountAccuracy.LOWER_BOUND,
                    samples, hidden, truncation);
        }
    }

    /** A query scope in one of the languages the Search API takes, and of bounded length. */
    static QueryInfo readOnlyQuery(QueryInfo query) {
        String language = query.language() == null ? "" : query.language().toLowerCase(Locale.ROOT);
        if (!QUERY_LANGUAGES.contains(language)) {
            throw new IllegalArgumentException("A query scope must be AFTS, CMIS or Lucene.");
        }
        if (query.query() == null || query.query().isBlank()) {
            throw new IllegalArgumentException("A query scope must not be empty.");
        }
        if (query.query().length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("A query scope may be at most " + MAX_QUERY_LENGTH + " characters.");
        }
        return new QueryInfo(query.query(), language);
    }

    /** The configured root as a reference: the root folder id, the site's document library, or the root path. */
    private String configuredRootRef() {
        if (rootFolderId != null && !rootFolderId.isBlank()) {
            return rootFolderId;
        }
        if (siteId != null && !siteId.isBlank()) {
            return "/Sites/" + siteId + "/documentLibrary";
        }
        return rootFolderPath == null || rootFolderPath.isBlank() ? "/" : rootFolderPath;
    }

    private static String notFound(String reference) {
        return "No Alfresco folder '" + reference + "' in this connector's scope.";
    }

    /**
     * Reads a folder and checks that the connector may look into it: it must be the configured root or below it
     * (by the ids of its ancestors), and neither it nor a folder between it and the root may be excluded. Every
     * refusal reads the same, so that a caller cannot learn what exists outside its view.
     */
    private JsonNode folderInScope(String reference) throws IOException, InterruptedException {
        JsonNode folder = discoveryNode(reference);
        JsonNode root = discoveryNode(configuredRootRef());
        if (folder == null || root == null || !folder.path("isFolder").asBoolean(false)) {
            throw new IllegalArgumentException(notFound(reference));
        }
        String rootId = root.path("id").asText();
        if (!rootId.equals(folder.path("id").asText())) {
            boolean belowRoot = false;
            for (JsonNode ancestor : folder.path("path").path("elements")) {
                if (belowRoot && isFolderExcluded(ancestor.path("name").asText(""), ancestor.path("id").asText(""))) {
                    throw new IllegalArgumentException(notFound(reference));
                }
                if (rootId.equals(ancestor.path("id").asText())) {
                    belowRoot = true;
                }
            }
            if (!belowRoot || isFolderExcluded(folder.path("name").asText(""), folder.path("id").asText(""))) {
                throw new IllegalArgumentException(notFound(reference));
            }
        }
        return folder;
    }

    /**
     * The folder a crawl from {@code reference} would start at. Only for a connector that crawls a folder tree: one
     * set up to crawl a query ignores the start folder in {@link #scan(String)}. The node's {@code sourceId} is the
     * folder's node id as the repository reports it, which {@code scan} reads as a start node.
     */
    @Override
    public DiscoveryNode crawlStart(String reference, VisibilityFilter filter) {
        if (crawlMode == AlfrescoCrawlMode.QUERY) {
            throw new IllegalArgumentException(NOT_A_FOLDER_CRAWL);
        }
        ensureConnected();
        String folderRef = reference == null || reference.isBlank() ? configuredRootRef() : reference.strip();
        try {
            // A query string is not a folder, so it is refused as missing; scan() only ever gets the folder's id.
            return visibleFolder(folderInScope(folderRef), folderRef, filter);
        } catch (IOException e) {
            throw new DiscoveryException("Alfresco folder lookup failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("Alfresco folder lookup was interrupted.", e);
        }
    }

    static final String NOT_A_FOLDER_CRAWL =
            "This connector crawls a query, not a folder tree, so a crawl cannot start from a folder.";

    /** The starting folder as a node, refused as not found when the filter hides it. */
    private DiscoveryNode visibleFolder(JsonNode folder, String reference, VisibilityFilter filter) {
        DiscoveryNode node = folderNode(folder, null, 0);
        if (node == null || !VisibilityFilter.decide(filter, List.of(node))[0]) {
            throw new IllegalArgumentException(notFound(reference));
        }
        return node;
    }

    /** A node by id or by path from the repository root, with its path and permissions; {@code null} when absent. */
    private JsonNode discoveryNode(String reference) throws IOException, InterruptedException {
        String ref = reference == null ? "/" : reference.strip();
        StringBuilder uri = new StringBuilder(url).append("/nodes/");
        if (ref.startsWith("/")) {
            uri.append("-root-?include=path,permissions");
            String relative = ref.replaceAll("^/+", "").replaceAll("/+$", "");
            if (!relative.isEmpty()) {
                uri.append("&relativePath=").append(URLEncoder.encode(relative, StandardCharsets.UTF_8));
            }
        } else if (NODE_ID.matcher(ref).matches()) {
            uri.append(ref).append("?include=path,permissions");
        } else {
            return null;
        }
        HttpResponse<String> response = get(uri.toString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("Alfresco returned HTTP " + response.statusCode() + " for node '" + ref + "'.");
        }
        return objectMapper.readTree(response.body()).path("entry");
    }

    private JsonNode discoveryChildren(String nodeId, int skipCount) throws IOException, InterruptedException {
        String uri = url + "/nodes/" + URLEncoder.encode(nodeId, StandardCharsets.UTF_8) + "/children?skipCount=" + skipCount
                + "&maxItems=" + batchSize + "&include=path,permissions";
        HttpResponse<String> response = get(uri);
        if (response.statusCode() != 200) {
            throw new IOException("Alfresco returned HTTP " + response.statusCode() + " listing node '" + nodeId + "'.");
        }
        return objectMapper.readTree(response.body());
    }

    private JsonNode discoverySearch(QueryInfo query, int skipCount) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "query", Map.of("language", query.language(), "query", query.query()),
                "paging", Map.of("maxItems", batchSize, "skipCount", skipCount),
                "include", List.of("path", "permissions"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveSearchUrl()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400) {
            throw new IllegalArgumentException("Alfresco did not accept the query.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("Alfresco search returned HTTP " + response.statusCode() + ".");
        }
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> get(String uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A child as a node, or {@code null} when the crawler would not take it. */
    private DiscoveryNode childNode(JsonNode entry, String parentId, int depth) {
        if (entry.path("isFolder").asBoolean(false)) {
            return isFolderExcluded(entry.path("name").asText(""), entry.path("id").asText("")) ? null
                    : folderNode(entry, parentId, depth);
        }
        if (entry.path("isFile").asBoolean(false)) {
            JsonNode content = entry.path("content");
            if (includeContentStream && content.isMissingNode()) {
                return null;
            }
            return matchesMimeType(content.path("mimeType").asText("")) ? documentNode(entry, parentId, depth) : null;
        }
        return null;
    }

    private DiscoveryNode folderNode(JsonNode entry, String parentId, int depth) {
        String id = entry.path("id").asText("");
        if (id.isBlank()) {
            return null;
        }
        return new DiscoveryNode("alfresco-folder:" + id, id, parentId, entry.path("name").asText(id), pathOf(entry),
                DiscoveryNode.NodeType.FOLDER, depth, null, null, modifiedAt(entry), null,
                discoverySecurity(entry.path("permissions")));
    }

    private DiscoveryNode documentNode(JsonNode entry, String parentId, int depth) {
        String id = entry.path("id").asText("");
        if (id.isBlank()) {
            return null;
        }
        JsonNode content = entry.path("content");
        String mimeType = content.path("mimeType").asText("");
        // The crawler indexes a file under its node id.
        return new DiscoveryNode(id, id, parentId, entry.path("name").asText(id), pathOf(entry),
                DiscoveryNode.NodeType.DOCUMENT, depth, mimeType.isBlank() ? null : mimeType,
                content.has("sizeInBytes") ? content.path("sizeInBytes").asLong() : null, modifiedAt(entry), null,
                discoverySecurity(entry.path("permissions")));
    }

    private static String pathOf(JsonNode entry) {
        String parentPath = entry.path("path").path("name").asText("");
        if (parentPath.isBlank()) {
            return null;
        }
        return (parentPath.endsWith("/") ? parentPath : parentPath + "/") + entry.path("name").asText("");
    }

    /** Alfresco writes offsets without a colon ({@code 2026-11-05T10:00:00.000+0000}). */
    private static final java.time.format.DateTimeFormatter ALFRESCO_TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS][XXX][XX]");

    static Instant modifiedAt(JsonNode entry) {
        String value = entry.path("modifiedAt").asText("");
        if (value.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value, ALFRESCO_TIME).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The node's effective permissions (locally set, plus inherited ones when inheritance is on), or {@code null}
     * when Alfresco returned none or none that allow anything.
     *
     * <p>Unlike the crawler's mapping, missing permissions are never read as public, and a DENIED entry becomes a
     * {@code deny} rule, so a caller Alfresco denies is not shown the node.
     */
    static SecurityConfig discoverySecurity(JsonNode permissions) {
        if (permissions == null || permissions.isMissingNode() || permissions.isNull()) {
            return null;
        }
        List<JsonNode> aces = new ArrayList<>();
        permissions.path("locallySet").forEach(aces::add);
        if (permissions.path("isInheritanceEnabled").asBoolean(true)) {
            permissions.path("inherited").forEach(aces::add);
        }
        List<PermissionRule> rules = new ArrayList<>();
        boolean allows = false;
        for (JsonNode ace : aces) {
            String authority = ace.path("authorityId").asText("").strip();
            if (authority.isEmpty()) {
                continue;
            }
            boolean denied = "DENIED".equalsIgnoreCase(ace.path("accessStatus").asText("ALLOWED"));
            boolean everyone = PUBLIC_AUTHORITIES.contains(authority.toLowerCase(Locale.ROOT));
            String type = everyone ? "public"
                    : authority.startsWith("GROUP_") || authority.startsWith("ROLE_") ? "group" : "user";
            String identity = everyone ? "public" : authority;
            if (denied) {
                // A denial of everyone denies no one in particular; it cannot be expressed as a rule.
                if (!everyone) {
                    rules.add(new PermissionRule(identity, type, authority, "deny"));
                }
            } else {
                rules.add(new PermissionRule(identity, type, authority, accessLevel(ace.path("name").asText("Consumer"))));
                allows = true;
            }
        }
        return allows ? new SecurityConfig(permissions.path("isInheritanceEnabled").asBoolean(true), rules) : null;
    }

    private static String accessLevel(String roleName) {
        String lower = roleName.toLowerCase(Locale.ROOT);
        if (lower.contains("coordinator") || lower.contains("admin")) {
            return "admin";
        }
        if (lower.contains("collaborator") || lower.contains("contributor") || lower.contains("editor") || lower.contains("write")) {
            return "write";
        }
        return "read";
    }
}
