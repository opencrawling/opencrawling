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
package org.opencrawling.hyland.cic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring configuration properties for Hyland Content Innovation Cloud (HXP) repository and output connectors.
 */
@ConfigurationProperties(prefix = "spring.opencrawling.connector.hyland-cic")
public class HylandCicProperties {

    /** Base URL of the Hyland Content Innovation Cloud REST API. */
    private String baseUrl = "https://api.content.hylandcloud.com";

    /** URL of the Hyland Identity Provider (IdP) OAuth2 Token endpoint. */
    private String idpTokenUrl = "https://identity.hylandcloud.com/connect/token";

    /** OAuth2 client ID. */
    private String clientId = "";

    /** OAuth2 client secret. */
    private String clientSecret = "";

    /** OAuth2 scopes requested during token exchange. */
    private List<String> scopes = new ArrayList<>(List.of("hxp", "hxp.integrations", "hxp.content"));

    /** Optional direct Bearer token (bypasses IdP token exchange if set, useful for testing). */
    private String bearerToken = "";

    /** HXP Tenant identifier. */
    private String tenantId = "";

    /** Root folder path in HXP to begin crawling from (defaults to "/"). */
    private String rootFolderPath = "/";

    /** Optional root folder ID (UUID) in HXP (overrides rootFolderPath if provided). */
    private String rootFolderId = "";

    /** Optional filter restricting crawling to specific content types. */
    private List<String> contentTypes = new ArrayList<>();

    /** Whether to recursively crawl subfolders. */
    private boolean includeSubfolders = true;

    /** Specific folder paths to exclude from traversal. */
    private Set<String> excludedFolderPaths = new HashSet<>();

    /** Query string for search-based crawling. */
    private String query = "";

    /** Maximum documents fetched per page/batch. */
    private int batchSize = 100;

    /** Whether to include Zero-Trust Access Control Lists (ACLs). */
    private boolean includeAcls = true;

    /** Whether to download and stream binary document payloads. */
    private boolean includeContentStream = true;

    /** Maximum binary document size in bytes allowed for download (default: 50MB). */
    private long maxContentSizeBytes = 52428800L;

    /** Connection timeout in seconds. */
    private int connectionTimeoutSeconds = 30;

    /** Maximum retry attempts for rate-limited (HTTP 429) or transient errors. */
    private int maxRetries = 5;

    // --- Output Connector Properties ---

    /** Target folder path in Hyland CIC where documents are filed by the output connector. */
    private String targetFolderPath = "/Enterprise_Ingestion/Automated_Archive";

    /** Target folder ID in Hyland CIC (overrides targetFolderPath if set). */
    private String targetFolderId = "";

    /** Default content type applied when writing documents via the output connector. */
    private String defaultContentType = "ArchivedDocument";

    /** Conflict resolution policy: "create-version", "overwrite", or "skip". */
    private String conflictResolution = "create-version";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl != null ? baseUrl.strip() : "";
    }

    public String getIdpTokenUrl() {
        return idpTokenUrl;
    }

    public void setIdpTokenUrl(String idpTokenUrl) {
        this.idpTokenUrl = idpTokenUrl != null ? idpTokenUrl.strip() : "";
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId != null ? clientId.strip() : "";
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret != null ? clientSecret.strip() : "";
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes != null ? scopes : new ArrayList<>();
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken != null ? bearerToken.strip() : "";
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId != null ? tenantId.strip() : "";
    }

    public String getRootFolderPath() {
        return rootFolderPath;
    }

    public void setRootFolderPath(String rootFolderPath) {
        this.rootFolderPath = rootFolderPath != null ? rootFolderPath.strip() : "/";
    }

    public String getRootFolderId() {
        return rootFolderId;
    }

    public void setRootFolderId(String rootFolderId) {
        this.rootFolderId = rootFolderId != null ? rootFolderId.strip() : "";
    }

    public List<String> getContentTypes() {
        return contentTypes;
    }

    public void setContentTypes(List<String> contentTypes) {
        this.contentTypes = contentTypes != null ? contentTypes : new ArrayList<>();
    }

    public boolean isIncludeSubfolders() {
        return includeSubfolders;
    }

    public void setIncludeSubfolders(boolean includeSubfolders) {
        this.includeSubfolders = includeSubfolders;
    }

    public Set<String> getExcludedFolderPaths() {
        return excludedFolderPaths;
    }

    public void setExcludedFolderPaths(Set<String> excludedFolderPaths) {
        this.excludedFolderPaths = excludedFolderPaths != null ? excludedFolderPaths : new HashSet<>();
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query != null ? query.strip() : "";
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize > 0 ? batchSize : 100;
    }

    public boolean isIncludeAcls() {
        return includeAcls;
    }

    public void setIncludeAcls(boolean includeAcls) {
        this.includeAcls = includeAcls;
    }

    public boolean isIncludeContentStream() {
        return includeContentStream;
    }

    public void setIncludeContentStream(boolean includeContentStream) {
        this.includeContentStream = includeContentStream;
    }

    public long getMaxContentSizeBytes() {
        return maxContentSizeBytes;
    }

    public void setMaxContentSizeBytes(long maxContentSizeBytes) {
        this.maxContentSizeBytes = maxContentSizeBytes > 0 ? maxContentSizeBytes : 52428800L;
    }

    public int getConnectionTimeoutSeconds() {
        return connectionTimeoutSeconds;
    }

    public void setConnectionTimeoutSeconds(int connectionTimeoutSeconds) {
        this.connectionTimeoutSeconds = connectionTimeoutSeconds > 0 ? connectionTimeoutSeconds : 30;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public String getTargetFolderPath() {
        return targetFolderPath;
    }

    public void setTargetFolderPath(String targetFolderPath) {
        this.targetFolderPath = targetFolderPath != null ? targetFolderPath.strip() : "/";
    }

    public String getTargetFolderId() {
        return targetFolderId;
    }

    public void setTargetFolderId(String targetFolderId) {
        this.targetFolderId = targetFolderId != null ? targetFolderId.strip() : "";
    }

    public String getDefaultContentType() {
        return defaultContentType;
    }

    public void setDefaultContentType(String defaultContentType) {
        this.defaultContentType = defaultContentType != null ? defaultContentType.strip() : "ArchivedDocument";
    }

    public String getConflictResolution() {
        return conflictResolution;
    }

    public void setConflictResolution(String conflictResolution) {
        this.conflictResolution = conflictResolution != null ? conflictResolution.strip() : "create-version";
    }

    public static HylandCicProperties fromConfiguration(Map<String, String> config) {
        HylandCicProperties properties = new HylandCicProperties();
        if (config == null || config.isEmpty()) {
            return properties;
        }

        String baseUrl = value(config, "baseUrl", value(config, "url", properties.getBaseUrl()));
        if (baseUrl != null && !baseUrl.isBlank()) {
            properties.setBaseUrl(baseUrl);
        }

        String idpTokenUrl = value(config, "idpTokenUrl", value(config, "oauth2TokenUrl", properties.getIdpTokenUrl()));
        if (idpTokenUrl != null && !idpTokenUrl.isBlank()) {
            properties.setIdpTokenUrl(idpTokenUrl);
        }

        properties.setClientId(value(config, "clientId", value(config, "oauth2ClientId", properties.getClientId())));
        properties.setClientSecret(value(config, "clientSecret", value(config, "oauth2ClientSecret", properties.getClientSecret())));

        String scopesStr = value(config, "scopes", value(config, "oauth2Scopes", ""));
        if (!scopesStr.isBlank()) {
            properties.setScopes(list(scopesStr));
        }

        properties.setBearerToken(value(config, "bearerToken", value(config, "oauth2AccessToken", properties.getBearerToken())));
        properties.setTenantId(value(config, "tenantId", properties.getTenantId()));
        properties.setRootFolderPath(value(config, "rootFolderPath", properties.getRootFolderPath()));
        properties.setRootFolderId(value(config, "rootFolderId", properties.getRootFolderId()));

        String contentTypesStr = value(config, "contentTypes", "");
        if (!contentTypesStr.isBlank()) {
            properties.setContentTypes(list(contentTypesStr));
        }

        properties.setIncludeSubfolders(bool(config, "includeSubfolders", properties.isIncludeSubfolders()));

        String excludedStr = value(config, "excludedFolderPaths", "");
        if (!excludedStr.isBlank()) {
            properties.setExcludedFolderPaths(new HashSet<>(list(excludedStr)));
        }

        properties.setQuery(value(config, "query", properties.getQuery()));
        properties.setBatchSize(integer(config, "batchSize", properties.getBatchSize()));
        properties.setIncludeAcls(bool(config, "includeAcls", properties.isIncludeAcls()));
        properties.setIncludeContentStream(bool(config, "includeContentStream", properties.isIncludeContentStream()));
        properties.setMaxContentSizeBytes(longValue(config, "maxContentSizeBytes", properties.getMaxContentSizeBytes()));
        properties.setConnectionTimeoutSeconds(integer(config, "connectionTimeoutSeconds", properties.getConnectionTimeoutSeconds()));
        properties.setMaxRetries(integer(config, "maxRetries", properties.getMaxRetries()));

        properties.setTargetFolderPath(value(config, "targetFolderPath", properties.getTargetFolderPath()));
        properties.setTargetFolderId(value(config, "targetFolderId", properties.getTargetFolderId()));
        properties.setDefaultContentType(value(config, "defaultContentType", properties.getDefaultContentType()));
        properties.setConflictResolution(value(config, "conflictResolution", properties.getConflictResolution()));

        return properties;
    }

    public static HylandCicProperties fromEnvironment(Environment env) {
        HylandCicProperties properties = new HylandCicProperties();
        if (env == null) {
            return properties;
        }

        String prefix = "spring.opencrawling.connector.hyland-cic.";
        String baseUrl = env.getProperty(prefix + "base-url", env.getProperty(prefix + "url", properties.getBaseUrl()));
        if (baseUrl != null) {
            properties.setBaseUrl(baseUrl);
        }

        String idpTokenUrl = env.getProperty(prefix + "idp-token-url", env.getProperty(prefix + "token-url", properties.getIdpTokenUrl()));
        if (idpTokenUrl != null) {
            properties.setIdpTokenUrl(idpTokenUrl);
        }

        properties.setClientId(env.getProperty(prefix + "client-id", properties.getClientId()));
        properties.setClientSecret(env.getProperty(prefix + "client-secret", properties.getClientSecret()));
        properties.setBearerToken(env.getProperty(prefix + "bearer-token", properties.getBearerToken()));
        properties.setTenantId(env.getProperty(prefix + "tenant-id", properties.getTenantId()));
        properties.setRootFolderPath(env.getProperty(prefix + "root-folder-path", properties.getRootFolderPath()));
        properties.setRootFolderId(env.getProperty(prefix + "root-folder-id", properties.getRootFolderId()));
        properties.setQuery(env.getProperty(prefix + "query", properties.getQuery()));

        String batchSize = env.getProperty(prefix + "batch-size");
        if (batchSize != null) {
            properties.setBatchSize(Integer.parseInt(batchSize));
        }

        String subfolders = env.getProperty(prefix + "include-subfolders");
        if (subfolders != null) {
            properties.setIncludeSubfolders(Boolean.parseBoolean(subfolders));
        }

        String acls = env.getProperty(prefix + "include-acls");
        if (acls != null) {
            properties.setIncludeAcls(Boolean.parseBoolean(acls));
        }

        String stream = env.getProperty(prefix + "include-content-stream");
        if (stream != null) {
            properties.setIncludeContentStream(Boolean.parseBoolean(stream));
        }

        String maxBytes = env.getProperty(prefix + "max-content-size-bytes");
        if (maxBytes != null) {
            properties.setMaxContentSizeBytes(Long.parseLong(maxBytes));
        }

        String targetPath = env.getProperty(prefix + "target-folder-path");
        if (targetPath != null) {
            properties.setTargetFolderPath(targetPath);
        }

        String targetId = env.getProperty(prefix + "target-folder-id");
        if (targetId != null) {
            properties.setTargetFolderId(targetId);
        }

        String defaultType = env.getProperty(prefix + "default-content-type");
        if (defaultType != null) {
            properties.setDefaultContentType(defaultType);
        }

        String conflict = env.getProperty(prefix + "conflict-resolution");
        if (conflict != null) {
            properties.setConflictResolution(conflict);
        }

        return properties;
    }

    private static String value(Map<String, String> config, String key, String defaultValue) {
        String val = config.get(key);
        return (val != null && !val.isBlank()) ? val.strip() : defaultValue;
    }

    private static boolean bool(Map<String, String> config, String key, boolean defaultValue) {
        String val = config.get(key);
        return (val != null && !val.isBlank()) ? Boolean.parseBoolean(val.strip()) : defaultValue;
    }

    private static int integer(Map<String, String> config, String key, int defaultValue) {
        String val = config.get(key);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long longValue(Map<String, String> config, String key, long defaultValue) {
        String val = config.get(key);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(val.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static List<String> list(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[,\\s]+"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
