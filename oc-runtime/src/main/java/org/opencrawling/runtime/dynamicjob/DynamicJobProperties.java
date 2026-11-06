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
package org.opencrawling.runtime.dynamicjob;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Jobs that a caller (typically an agent) describes and starts itself ({@code opencrawling.jobs.dynamic.*}).
 *
 * <p>Off by default. A caller never configures a connector: it names one that an administrator registered and
 * listed in {@link #getConnectors() connectors}, and may only choose a start folder inside that connector's scope
 * that it can read itself.
 */
@ConfigurationProperties(prefix = DynamicJobProperties.PREFIX)
public class DynamicJobProperties {

    public static final String PREFIX = "opencrawling.jobs.dynamic";

    /** Whether the dynamic job endpoints and MCP tools exist. */
    private boolean enabled = false;
    /** Names of the registered repository connectors that dynamic jobs may use. Empty: none. */
    private List<String> connectors = new ArrayList<>();
    /** The OAuth2 scope the caller's token must carry to start, stop or read dynamic jobs. Blank: not checked. */
    private String requiredScope = "oc:jobs:write";
    /** Groups whose members may read and stop every dynamic job, not only their own. */
    private List<String> adminGroups = new ArrayList<>();
    /** How many of its dynamic jobs one user may have running at the same time (0: no limit of its own). */
    private int maxRunningPerUser = 2;
    /** How long a finished job's status, and the idempotency key that started it, are kept. */
    private Duration retention = Duration.ofHours(24);
    /** The most finished jobs kept; the oldest go first. */
    private int maxRetained = 1_000;
    /** The transformation (embedding) connector dynamic jobs use. */
    private String transformationConnector = "Ollama_Embedding_Default";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getConnectors() {
        return connectors;
    }

    public void setConnectors(List<String> connectors) {
        this.connectors = connectors == null ? new ArrayList<>() : connectors;
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public void setRequiredScope(String requiredScope) {
        this.requiredScope = requiredScope;
    }

    public List<String> getAdminGroups() {
        return adminGroups;
    }

    public void setAdminGroups(List<String> adminGroups) {
        this.adminGroups = adminGroups == null ? new ArrayList<>() : adminGroups;
    }

    public int getMaxRunningPerUser() {
        return maxRunningPerUser;
    }

    public void setMaxRunningPerUser(int maxRunningPerUser) {
        this.maxRunningPerUser = maxRunningPerUser;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public int getMaxRetained() {
        return maxRetained;
    }

    public void setMaxRetained(int maxRetained) {
        this.maxRetained = maxRetained;
    }

    public String getTransformationConnector() {
        return transformationConnector;
    }

    public void setTransformationConnector(String transformationConnector) {
        this.transformationConnector = transformationConnector;
    }
}
