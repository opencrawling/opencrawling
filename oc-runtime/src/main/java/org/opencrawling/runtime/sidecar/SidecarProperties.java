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
package org.opencrawling.runtime.sidecar;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading OIS sidecars ({@value #PREFIX}): the {@code <key>.ois.json} metadata files the Ozone and S3 migration
 * writers store next to each migrated binary. Off by default.
 */
@ConfigurationProperties(prefix = SidecarProperties.PREFIX)
public class SidecarProperties {

    public static final String PREFIX = "opencrawling.sidecars";

    /** The most sidecars one request may return, whatever {@code max-limit} says. */
    public static final int HARD_MAX_LIMIT = 1000;
    /** The most keys one request may list, whatever {@code max-scan} says. */
    public static final int HARD_MAX_SCAN = 50_000;
    /** The largest sidecar read, whatever {@code max-sidecar-bytes} says. */
    public static final int HARD_MAX_SIDECAR_BYTES = 16 * 1024 * 1024;
    /** The shortest {@code cursor-secret} accepted. */
    public static final int MIN_CURSOR_SECRET_LENGTH = 32;

    private boolean enabled = false;

    /** The registered Ozone or S3 output connectors (names in {@code connectors.json}) whose sidecars may be read. */
    private List<String> connectors = new ArrayList<>();

    /** The OAuth2 scope a verified caller's token must carry; blank: not checked. */
    private String requiredScope = "oc:sidecars:read";

    /** Whether asserted identities (no token) are accepted: development and tests only. */
    private boolean allowAssertedIdentity = false;

    /** Sidecars returned when a request does not say. */
    private int defaultLimit = 50;

    /** The most sidecars a request may ask for. */
    private int maxLimit = 200;

    /** The most keys a request lists before it stops and returns a cursor. */
    private int maxScan = 5000;

    /** Sidecars larger than this are skipped. */
    private int maxSidecarBytes = 1024 * 1024;

    /**
     * Seals the paging cursors. Blank: a random key per process, so a cursor only works on the instance that gave it
     * and until it restarts. Set the same secret (at least 32 characters) on every instance behind a load balancer.
     */
    private String cursorSecret = "";

    /** How long a cursor stays valid. */
    private Duration cursorTtl = Duration.ofHours(1);

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
        this.connectors = connectors != null ? new ArrayList<>(connectors) : new ArrayList<>();
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public void setRequiredScope(String requiredScope) {
        this.requiredScope = requiredScope;
    }

    public boolean isAllowAssertedIdentity() {
        return allowAssertedIdentity;
    }

    public void setAllowAssertedIdentity(boolean allowAssertedIdentity) {
        this.allowAssertedIdentity = allowAssertedIdentity;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public int getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    public int getMaxScan() {
        return maxScan;
    }

    public void setMaxScan(int maxScan) {
        this.maxScan = maxScan;
    }

    public int getMaxSidecarBytes() {
        return maxSidecarBytes;
    }

    public void setMaxSidecarBytes(int maxSidecarBytes) {
        this.maxSidecarBytes = maxSidecarBytes;
    }

    public String getCursorSecret() {
        return cursorSecret;
    }

    public void setCursorSecret(String cursorSecret) {
        this.cursorSecret = cursorSecret;
    }

    public Duration getCursorTtl() {
        return cursorTtl;
    }

    public void setCursorTtl(Duration cursorTtl) {
        this.cursorTtl = cursorTtl;
    }

    /** The effective limit for a request: the default when absent, within 1 and the (hard) maximum. */
    int limit(Integer requested) {
        int max = Math.max(1, Math.min(maxLimit, HARD_MAX_LIMIT));
        int value = requested != null ? requested : defaultLimit;
        return Math.max(1, Math.min(value, max));
    }

    int effectiveMaxScan() {
        return Math.max(1, Math.min(maxScan, HARD_MAX_SCAN));
    }

    int effectiveMaxSidecarBytes() {
        return Math.max(1024, Math.min(maxSidecarBytes, HARD_MAX_SIDECAR_BYTES));
    }
}
