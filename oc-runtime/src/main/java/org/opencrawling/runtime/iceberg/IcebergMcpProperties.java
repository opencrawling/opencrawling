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
package org.opencrawling.runtime.iceberg;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration settings and safety bounds for the Apache Iceberg MCP Lakehouse tools
 * under {@code opencrawling.mcp.iceberg.*}.
 */
@ConfigurationProperties(prefix = IcebergMcpProperties.PREFIX)
public class IcebergMcpProperties {

    public static final String PREFIX = "opencrawling.mcp.iceberg";

    public static final int HARD_MAX_RESULTS = 500;
    public static final int HARD_MAX_SCAN = 100_000;
    public static final int HARD_MAX_SNAPSHOTS = 500;

    private boolean enabled = false;
    private int defaultMaxResults = 25;
    private int maxResults = 200;
    private int maxScan = 20_000;
    private int maxSnapshots = 100;
    private boolean requireVerifiedCaller = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultMaxResults() {
        return defaultMaxResults;
    }

    public void setDefaultMaxResults(int defaultMaxResults) {
        this.defaultMaxResults = defaultMaxResults;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public int getMaxScan() {
        return maxScan;
    }

    public void setMaxScan(int maxScan) {
        this.maxScan = maxScan;
    }

    public int getMaxSnapshots() {
        return maxSnapshots;
    }

    public void setMaxSnapshots(int maxSnapshots) {
        this.maxSnapshots = maxSnapshots;
    }

    public boolean isRequireVerifiedCaller() {
        return requireVerifiedCaller;
    }

    public void setRequireVerifiedCaller(boolean requireVerifiedCaller) {
        this.requireVerifiedCaller = requireVerifiedCaller;
    }

    /** Clamps a caller-supplied result limit into {@code [1, min(maxResults, HARD_MAX_RESULTS)]}. */
    public int effectiveLimit(Integer requested) {
        int hardMax = Math.max(1, Math.min(maxResults, HARD_MAX_RESULTS));
        int candidate = requested != null ? requested : defaultMaxResults;
        return Math.max(1, Math.min(candidate, hardMax));
    }

    /** Effective row scan cap per request, never exceeding {@link #HARD_MAX_SCAN}. */
    public int effectiveMaxScan() {
        return Math.max(1, Math.min(maxScan, HARD_MAX_SCAN));
    }

    /** Effective snapshot listing cap per request, never exceeding {@link #HARD_MAX_SNAPSHOTS}. */
    public int effectiveMaxSnapshots() {
        return Math.max(1, Math.min(maxSnapshots, HARD_MAX_SNAPSHOTS));
    }
}
