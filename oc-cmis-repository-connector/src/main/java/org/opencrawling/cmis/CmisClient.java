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
package org.opencrawling.cmis;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Modern, lightweight HTTP/REST client for OASIS CMIS 1.1 Browser Binding (JSON).
 * Implemented natively in Java 25 with java.net.http.HttpClient and Jackson.
 */
public class CmisClient {

    private static final Logger log = LoggerFactory.getLogger(CmisClient.class);

    private final String endpointUrl;
    private final String username;
    private final String password;
    private final String bearerToken;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;
    private String authHeader;
    private final Map<String, CmisRepositoryInfo> repositoriesCache = new ConcurrentHashMap<>();

    public record CmisRepositoryInfo(
        String repositoryId,
        String repositoryName,
        String repositoryDescription,
        String vendorName,
        String productName,
        String productVersion,
        String rootFolderId,
        String cmisVersionSupported,
        String repositoryUrl,
        String rootFolderUrl
    ) {
        public CmisRepositoryInfo(
                String repositoryId,
                String repositoryName,
                String repositoryDescription,
                String vendorName,
                String productName,
                String productVersion,
                String rootFolderId,
                String cmisVersionSupported) {
            this(repositoryId, repositoryName, repositoryDescription, vendorName, productName, productVersion, rootFolderId, cmisVersionSupported, "", "");
        }
    }

    public record CmisChildrenResult(
        List<JsonNode> objects,
        boolean hasMoreItems,
        int numItems
    ) {}

    public record CmisQueryResult(
        List<JsonNode> results,
        boolean hasMoreItems,
        int numItems
    ) {}

    public record CmisChangeEvent(
        String changeType, // created, updated, deleted, security
        String objectId,
        long changeTime
    ) {}

    public record CmisChangesResult(
        List<CmisChangeEvent> events,
        boolean hasMoreItems,
        String latestChangeLogToken
    ) {}

    public CmisClient(String endpointUrl, String username, String password) {
        this(endpointUrl, username, password, null, 30);
    }

    public CmisClient(String endpointUrl, String username, String password, String bearerToken, int timeoutSeconds) {
        this.endpointUrl = (endpointUrl != null && endpointUrl.endsWith("/"))
                ? endpointUrl.substring(0, endpointUrl.length() - 1)
                : endpointUrl;
        this.username = username;
        this.password = password;
        this.bearerToken = bearerToken;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
        this.objectMapper = new ObjectMapper();
        initHttpClient();
    }

    private void initHttpClient() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        if (bearerToken != null && !bearerToken.isBlank()) {
            this.authHeader = "Bearer " + bearerToken;
        } else if (username != null && !username.isBlank()) {
            String credentials = username + ":" + (password != null ? password : "");
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
    }

    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Discovers all repositories exposed at the CMIS Browser service endpoint.
     */
    public Map<String, CmisRepositoryInfo> getRepositories() throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET();

        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("CMIS service endpoint returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        Map<String, CmisRepositoryInfo> repositories = new HashMap<>();

        if (root.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode repoNode = field.getValue();
                if (repoNode.isObject()) {
                    String repoId = repoNode.path("repositoryId").asText(field.getKey());
                    String repoUrl = repoNode.path("repositoryUrl").asText("");
                    String rootFolderUrl = repoNode.path("rootFolderUrl").asText("");
                    repositories.put(repoId, new CmisRepositoryInfo(
                        repoId,
                        repoNode.path("repositoryName").asText(repoId),
                        repoNode.path("repositoryDescription").asText(""),
                        repoNode.path("vendorName").asText(""),
                        repoNode.path("productName").asText(""),
                        repoNode.path("productVersion").asText(""),
                        repoNode.path("rootFolderId").asText(""),
                        repoNode.path("cmisVersionSupported").asText("1.1"),
                        repoUrl,
                        rootFolderUrl
                    ));
                }
            }
        }

        this.repositoriesCache.putAll(repositories);
        return repositories;
    }

    /**
     * Resolves the primary repository ID (either the first repository or default).
     */
    public String discoverRepositoryId() throws IOException, InterruptedException {
        Map<String, CmisRepositoryInfo> repos = getRepositories();
        if (repos.isEmpty()) {
            return "-default-";
        }
        return repos.keySet().iterator().next();
    }

    /**
     * Resolves the base URL for repository-level operations (e.g. query, types, changes).
     */
    public String getRepositoryUrl(String repositoryId) {
        if (repositoryId != null && !repositoryId.isBlank() && repositoriesCache.containsKey(repositoryId)) {
            String url = repositoriesCache.get(repositoryId).repositoryUrl();
            if (url != null && !url.isBlank()) {
                return rebaseUri(url);
            }
        }
        String cleanEndpoint = (endpointUrl.endsWith("/")) ? endpointUrl.substring(0, endpointUrl.length() - 1) : endpointUrl;
        if (repositoryId == null || repositoryId.isBlank()) {
            return cleanEndpoint;
        }
        String repoSegment = urlEncode(repositoryId);
        // If cleanEndpoint already contains "/<repositoryId>/" or ends with "/<repositoryId>"
        if (cleanEndpoint.endsWith("/" + repoSegment) || cleanEndpoint.contains("/" + repoSegment + "/")
                || cleanEndpoint.endsWith("/" + repositoryId) || cleanEndpoint.contains("/" + repositoryId + "/")) {
            return cleanEndpoint;
        }
        return cleanEndpoint + "/" + repoSegment;
    }

    /**
     * Resolves the base URL for root folder operations (e.g. browsing children, paths).
     */
    public String getRootFolderUrl(String repositoryId) {
        if (repositoryId != null && !repositoryId.isBlank() && repositoriesCache.containsKey(repositoryId)) {
            String url = repositoriesCache.get(repositoryId).rootFolderUrl();
            if (url != null && !url.isBlank()) {
                return rebaseUri(url);
            }
        }
        String repoUrl = getRepositoryUrl(repositoryId);
        if (repoUrl.endsWith("/root")) {
            return repoUrl;
        }
        return repoUrl + "/root";
    }

    private String rebaseUri(String uriStr) {
        if (uriStr == null || uriStr.isBlank()) {
            return null;
        }
        try {
            URI targetUri = URI.create(uriStr);
            URI baseUri = URI.create(endpointUrl);
            if (!targetUri.isAbsolute()) {
                String path = targetUri.getRawPath();
                if (path != null && path.startsWith("/")) {
                    return baseUri.getScheme() + "://" + baseUri.getAuthority() + path;
                }
                String cleanEndpoint = (endpointUrl.endsWith("/")) ? endpointUrl.substring(0, endpointUrl.length() - 1) : endpointUrl;
                return cleanEndpoint + "/" + (path != null ? path : uriStr);
            }
            String path = targetUri.getRawPath();
            String query = targetUri.getRawQuery();
            StringBuilder sb = new StringBuilder();
            sb.append(baseUri.getScheme()).append("://").append(baseUri.getAuthority()).append(path);
            if (query != null && !query.isEmpty()) {
                sb.append("?").append(query);
            }
            return sb.toString();
        } catch (Exception e) {
            return uriStr;
        }
    }

    /**
     * Retrieves the children of a folder (either by folder path or objectId).
     */
    public CmisChildrenResult getChildren(String repositoryId, String folderIdOrPath, int skipCount, int maxItems, boolean includeAcls)
            throws IOException, InterruptedException {
        
        StringBuilder urlBuilder = new StringBuilder(getRootFolderUrl(repositoryId));
        if (folderIdOrPath != null && folderIdOrPath.startsWith("/")) {
            if (!folderIdOrPath.equals("/")) {
                String encodedPath = encodePath(folderIdOrPath);
                urlBuilder.append(encodedPath);
            }
            urlBuilder.append("?cmisselector=children");
        } else if (folderIdOrPath != null && !folderIdOrPath.isBlank()) {
            urlBuilder.append("?objectId=").append(urlEncode(folderIdOrPath)).append("&cmisselector=children");
        } else {
            urlBuilder.append("?cmisselector=children");
        }

        urlBuilder.append("&skipCount=").append(Math.max(0, skipCount));
        if (maxItems > 0) {
            urlBuilder.append("&maxItems=").append(maxItems);
        }
        urlBuilder.append("&includeACL=").append(includeAcls);
        urlBuilder.append("&includeAllowableActions=false");

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET();

        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to get CMIS folder children from " + urlBuilder + ". HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode rootNode = objectMapper.readTree(response.body());
        List<JsonNode> objects = new ArrayList<>();
        JsonNode objectsArray = rootNode.path("objects");

        if (objectsArray.isArray()) {
            for (JsonNode item : objectsArray) {
                JsonNode actualDoc = item.has("object") ? item.get("object") : item;
                objects.add(actualDoc);
            }
        }

        boolean hasMoreItems = rootNode.path("hasMoreItems").asBoolean(false);
        int numItems = rootNode.path("numItems").asInt(objects.size());

        return new CmisChildrenResult(objects, hasMoreItems, numItems);
    }

    /**
     * Retrieves a single CMIS object by objectId or path.
     */
    public JsonNode getObject(String repositoryId, String objectIdOrPath, boolean includeAcls)
            throws IOException, InterruptedException {
        
        StringBuilder urlBuilder = new StringBuilder(getRootFolderUrl(repositoryId));
        if (objectIdOrPath != null && objectIdOrPath.startsWith("/")) {
            urlBuilder.append(encodePath(objectIdOrPath)).append("?cmisselector=object");
        } else {
            urlBuilder.append("?objectId=").append(urlEncode(objectIdOrPath)).append("&cmisselector=object");
        }

        urlBuilder.append("&includeACL=").append(includeAcls);
        urlBuilder.append("&includeAllowableActions=false");

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET();

        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to get CMIS object from " + urlBuilder + ". HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode rootNode = objectMapper.readTree(response.body());
        return rootNode.has("object") ? rootNode.get("object") : rootNode;
    }

    /**
     * Executes a CMISQL query.
     */
    public CmisQueryResult query(String repositoryId, String cmisQuery, boolean searchAllVersions, int maxItems, int skipCount)
            throws IOException, InterruptedException {
        
        StringBuilder urlBuilder = new StringBuilder(getRepositoryUrl(repositoryId))
                .append("?cmisselector=query")
                .append("&q=").append(urlEncode(cmisQuery))
                .append("&searchAllVersions=").append(searchAllVersions)
                .append("&skipCount=").append(Math.max(0, skipCount));

        if (maxItems > 0) {
            urlBuilder.append("&maxItems=").append(maxItems);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET();

        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to execute CMISQL query. HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode rootNode = objectMapper.readTree(response.body());
        List<JsonNode> results = new ArrayList<>();
        JsonNode resultsArray = rootNode.path("results");

        if (resultsArray.isArray()) {
            for (JsonNode item : resultsArray) {
                results.add(item);
            }
        }

        boolean hasMoreItems = rootNode.path("hasMoreItems").asBoolean(false);
        int numItems = rootNode.path("numItems").asInt(results.size());

        return new CmisQueryResult(results, hasMoreItems, numItems);
    }

    /**
     * Downloads the binary content stream of a document.
     */
    public InputStream getContentStream(String repositoryId, String objectId) throws IOException, InterruptedException {
        String url = getRootFolderUrl(repositoryId) + "?objectId=" + urlEncode(objectId) + "&cmisselector=content";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds * 2L))
                .GET();

        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }

        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to download CMIS document content from " + url + ". HTTP " + response.statusCode());
        }

        return response.body();
    }

    /**
     * Fetches changes from the CMIS repository Change Log service.
     */
    public CmisChangesResult getContentChanges(String repositoryId, String changeLogToken, int maxItems)
            throws IOException, InterruptedException {
        
        StringBuilder urlBuilder = new StringBuilder(getRepositoryUrl(repositoryId))
                .append("?cmisselector=contentChanges")
                .append("&includeProperties=true");

        if (changeLogToken != null && !changeLogToken.isBlank()) {
            urlBuilder.append("&changeLogToken=").append(urlEncode(changeLogToken));
        }
        if (maxItems > 0) {
            urlBuilder.append("&maxItems=").append(maxItems);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET();

        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to fetch CMIS content changes. HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode rootNode = objectMapper.readTree(response.body());
        List<CmisChangeEvent> events = new ArrayList<>();
        JsonNode eventsArray = rootNode.path("changeEvents");

        if (eventsArray.isArray()) {
            for (JsonNode event : eventsArray) {
                String changeType = event.path("changeType").asText("updated").toLowerCase();
                String objectId = event.path("objectId").asText();
                long changeTime = event.path("changeTime").asLong(System.currentTimeMillis());
                events.add(new CmisChangeEvent(changeType, objectId, changeTime));
            }
        }

        boolean hasMoreItems = rootNode.path("hasMoreItems").asBoolean(false);
        String latestToken = rootNode.path("latestChangeLogToken").asText("");

        return new CmisChangesResult(events, hasMoreItems, latestToken);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "";
        }
        String[] segments = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (!segment.isBlank()) {
                sb.append("/").append(urlEncode(segment));
            }
        }
        return sb.toString();
    }
}
