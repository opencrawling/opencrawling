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
package org.opencrawling.runtime.discovery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Repository discovery and dry runs ({@code opencrawling.discovery.*}).
 *
 * <p>Off by default. The limits are both what a request gets when it asks for nothing and the most it may ask
 * for; each is also held under a hard cap.
 */
@ConfigurationProperties(prefix = DiscoveryProperties.PREFIX)
public class DiscoveryProperties {

    public static final String PREFIX = "opencrawling.discovery";

    static final int MAX_DEPTH_CAP = 10;
    static final int MAX_NODES_CAP = 1_000;
    static final int SAMPLE_SIZE_CAP = 50;
    static final int MAX_SCAN_CAP = 100_000;
    static final Duration TIMEOUT_CAP = Duration.ofMinutes(2);

    /** Whether the discovery endpoints and MCP tools exist. */
    private boolean enabled = false;
    /**
     * Whether a caller whose identity was only asserted (tool arguments or query parameters, identity mode
     * {@code none}) may use discovery. For development and tests only: discovery reads live repositories with the
     * connector's credentials, so in production the caller must be verified by token.
     */
    private boolean allowAssertedIdentity = false;
    /** Whether results say how many items were hidden from the caller, and give a query's total match count. */
    private boolean revealHiddenCounts = false;
    private int maxDepth = 3;
    private int maxNodes = 200;
    private int sampleSize = 10;
    private int maxScan = 10_000;
    private Duration timeout = Duration.ofSeconds(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowAssertedIdentity() {
        return allowAssertedIdentity;
    }

    public void setAllowAssertedIdentity(boolean allowAssertedIdentity) {
        this.allowAssertedIdentity = allowAssertedIdentity;
    }

    public boolean isRevealHiddenCounts() {
        return revealHiddenCounts;
    }

    public void setRevealHiddenCounts(boolean revealHiddenCounts) {
        this.revealHiddenCounts = revealHiddenCounts;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxNodes() {
        return maxNodes;
    }

    public void setMaxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public void setSampleSize(int sampleSize) {
        this.sampleSize = sampleSize;
    }

    public int getMaxScan() {
        return maxScan;
    }

    public void setMaxScan(int maxScan) {
        this.maxScan = maxScan;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    int depthLimit() {
        return clamp(maxDepth, 1, MAX_DEPTH_CAP);
    }

    int nodeLimit() {
        return clamp(maxNodes, 1, MAX_NODES_CAP);
    }

    int sampleLimit() {
        return clamp(sampleSize, 0, SAMPLE_SIZE_CAP);
    }

    int scanLimit() {
        return clamp(maxScan, 1, MAX_SCAN_CAP);
    }

    Duration timeoutLimit() {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            return Duration.ofSeconds(30);
        }
        return timeout.compareTo(TIMEOUT_CAP) > 0 ? TIMEOUT_CAP : timeout;
    }

    /** A requested value held within {@code [min, limit]}; {@code null} means the limit. */
    static int bounded(Integer requested, int min, int limit) {
        return requested == null ? limit : clamp(requested, min, limit);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
