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
package org.opencrawling.openarch.repository.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.opencrawling.connector.openarch")
public class OpenArchRepositoryProperties {

    private String endpointUrl = "http://localhost:8080";
    private String repositoryId = "default";
    private String username = "Administrator";
    private String password = "Administrator";
    private String crawlMode = "QUERY"; // QUERY or FOLDER
    private String hxqlQuery = "SELECT * FROM SysFile";
    private String rootFolderId = "00000000-0000-0000-0000-000000000000";
    private boolean includeSubfolders = true;
    private int batchSize = 100;
    private boolean includeAcls = true;
    private boolean includeContentStream = true;
    private boolean incremental = false;
    private long lastCrawlTimestamp = 0L;

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getCrawlMode() {
        return crawlMode;
    }

    public void setCrawlMode(String crawlMode) {
        this.crawlMode = crawlMode;
    }

    public String getHxqlQuery() {
        return hxqlQuery;
    }

    public void setHxqlQuery(String hxqlQuery) {
        this.hxqlQuery = hxqlQuery;
    }

    public String getRootFolderId() {
        return rootFolderId;
    }

    public void setRootFolderId(String rootFolderId) {
        this.rootFolderId = rootFolderId;
    }

    public boolean isIncludeSubfolders() {
        return includeSubfolders;
    }

    public void setIncludeSubfolders(boolean includeSubfolders) {
        this.includeSubfolders = includeSubfolders;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
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

    public boolean isIncremental() {
        return incremental;
    }

    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    public long getLastCrawlTimestamp() {
        return lastCrawlTimestamp;
    }

    public void setLastCrawlTimestamp(long lastCrawlTimestamp) {
        this.lastCrawlTimestamp = lastCrawlTimestamp;
    }
}
