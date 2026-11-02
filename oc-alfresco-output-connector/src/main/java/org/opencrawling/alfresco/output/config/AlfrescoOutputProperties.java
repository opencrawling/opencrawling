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
package org.opencrawling.alfresco.output.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Alfresco Content Services Output Connector (Migration Mode).
 */
@ConfigurationProperties(prefix = "spring.opencrawling.output.alfresco")
public class AlfrescoOutputProperties {

    private boolean enabled = true;
    private String url = "http://localhost:8080/alfresco";
    private String username = "admin";
    private String password = "admin";
    private String authType = "basic"; // basic | bearer
    private String bearerToken = "";
    private String targetParentNodeId = "-root-"; // e.g. -root-, -my-, or node GUID
    private String targetPathPrefix = ""; // e.g. MigratedContent/v1
    private String pathStrategy = "HIERARCHICAL"; // HIERARCHICAL | FLAT
    private String nodeType = "cm:content";
    private List<String> aspects = new ArrayList<>(List.of("cm:versionable", "cm:titled"));
    private String conflictStrategy = "OVERWRITE"; // OVERWRITE | AUTO_RENAME | FAIL
    private boolean syncPermissions = true;
    private String defaultPermissionRole = "Consumer";
    private boolean isInheritanceEnabled = true;
    private String tombstoneAction = "MOVE_TO_TRASH"; // MOVE_TO_TRASH | PERMANENT_DELETE
    private boolean validateAuthorities = true;
    private String authorityValidationMode = "IN_FLIGHT"; // IN_FLIGHT | PRE_FLIGHT | BOTH | DISABLED
    private String missingAuthorityAction = "SKIP"; // SKIP | FAIL
    private String authorityGapReportPath = "reports/alfresco-authority-gap.json";
    private int timeoutSeconds = 60;
    private int parallelism = 4;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url != null ? url.replaceAll("/+$", "") : "http://localhost:8080/alfresco";
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

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType != null ? authType.trim().toLowerCase() : "basic";
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public String getTargetParentNodeId() {
        return targetParentNodeId;
    }

    public void setTargetParentNodeId(String targetParentNodeId) {
        this.targetParentNodeId = targetParentNodeId != null && !targetParentNodeId.isBlank()
                ? targetParentNodeId : "-root-";
    }

    public String getTargetPathPrefix() {
        return targetPathPrefix;
    }

    public void setTargetPathPrefix(String targetPathPrefix) {
        this.targetPathPrefix = targetPathPrefix != null ? targetPathPrefix.trim() : "";
    }

    public String getPathStrategy() {
        return pathStrategy;
    }

    public void setPathStrategy(String pathStrategy) {
        this.pathStrategy = pathStrategy != null ? pathStrategy.trim().toUpperCase() : "HIERARCHICAL";
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType != null && !nodeType.isBlank() ? nodeType.trim() : "cm:content";
    }

    public List<String> getAspects() {
        return aspects;
    }

    public void setAspects(List<String> aspects) {
        this.aspects = aspects != null ? new ArrayList<>(aspects) : new ArrayList<>();
    }

    public String getConflictStrategy() {
        return conflictStrategy;
    }

    public void setConflictStrategy(String conflictStrategy) {
        this.conflictStrategy = conflictStrategy != null ? conflictStrategy.trim().toUpperCase() : "OVERWRITE";
    }

    public boolean isSyncPermissions() {
        return syncPermissions;
    }

    public void setSyncPermissions(boolean syncPermissions) {
        this.syncPermissions = syncPermissions;
    }

    public String getDefaultPermissionRole() {
        return defaultPermissionRole;
    }

    public void setDefaultPermissionRole(String defaultPermissionRole) {
        this.defaultPermissionRole = defaultPermissionRole != null && !defaultPermissionRole.isBlank()
                ? defaultPermissionRole : "Consumer";
    }

    public boolean isInheritanceEnabled() {
        return isInheritanceEnabled;
    }

    public void setInheritanceEnabled(boolean inheritanceEnabled) {
        isInheritanceEnabled = inheritanceEnabled;
    }

    public String getTombstoneAction() {
        return tombstoneAction;
    }

    public void setTombstoneAction(String tombstoneAction) {
        this.tombstoneAction = tombstoneAction != null ? tombstoneAction.trim().toUpperCase() : "MOVE_TO_TRASH";
    }

    public boolean isValidateAuthorities() {
        return validateAuthorities;
    }

    public void setValidateAuthorities(boolean validateAuthorities) {
        this.validateAuthorities = validateAuthorities;
    }

    public String getAuthorityValidationMode() {
        return authorityValidationMode;
    }

    public void setAuthorityValidationMode(String authorityValidationMode) {
        this.authorityValidationMode = authorityValidationMode != null ? authorityValidationMode.trim().toUpperCase() : "IN_FLIGHT";
    }

    public String getMissingAuthorityAction() {
        return missingAuthorityAction;
    }

    public void setMissingAuthorityAction(String missingAuthorityAction) {
        this.missingAuthorityAction = missingAuthorityAction != null ? missingAuthorityAction.trim().toUpperCase() : "SKIP";
    }

    public String getAuthorityGapReportPath() {
        return authorityGapReportPath;
    }

    public void setAuthorityGapReportPath(String authorityGapReportPath) {
        this.authorityGapReportPath = authorityGapReportPath != null ? authorityGapReportPath.trim() : "";
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 60;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism > 0 ? parallelism : 4;
    }
}
