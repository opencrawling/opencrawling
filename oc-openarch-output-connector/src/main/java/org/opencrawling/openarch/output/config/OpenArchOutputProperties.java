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
package org.opencrawling.openarch.output.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.opencrawling.output.openarch")
public class OpenArchOutputProperties {

    private String endpointUrl = "http://localhost:8080";
    private String repositoryId = "default";
    private String username = "Administrator";
    private String password = "Administrator";
    private String rootFolderId = "00000000-0000-0000-0000-000000000000";
    private int parquetBatchThreshold = 50;
    private String embeddingsType = "mxbai-embed-large";
    private int dimensions = 1024;
    private boolean upsert = true;
    private boolean syncAcls = true;
    private boolean uploadBinaries = true;

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

    public String getRootFolderId() {
        return rootFolderId;
    }

    public void setRootFolderId(String rootFolderId) {
        this.rootFolderId = rootFolderId;
    }

    public int getParquetBatchThreshold() {
        return parquetBatchThreshold;
    }

    public void setParquetBatchThreshold(int parquetBatchThreshold) {
        this.parquetBatchThreshold = parquetBatchThreshold;
    }

    public String getEmbeddingsType() {
        return embeddingsType;
    }

    public void setEmbeddingsType(String embeddingsType) {
        this.embeddingsType = embeddingsType;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public boolean isUpsert() {
        return upsert;
    }

    public void setUpsert(boolean upsert) {
        this.upsert = upsert;
    }

    public boolean isSyncAcls() {
        return syncAcls;
    }

    public void setSyncAcls(boolean syncAcls) {
        this.syncAcls = syncAcls;
    }

    public boolean isUploadBinaries() {
        return uploadBinaries;
    }

    public void setUploadBinaries(boolean uploadBinaries) {
        this.uploadBinaries = uploadBinaries;
    }
}
