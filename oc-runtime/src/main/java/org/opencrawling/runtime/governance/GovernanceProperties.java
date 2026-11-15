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
package org.opencrawling.runtime.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The pre-embedding governance gate ({@value #PREFIX}). Off by default. When on, every document the ingestion consumer
 * would chunk goes through the listed hooks first, and jobs that would hand documents straight to an output connector
 * in RAG mode are refused, since that path does not pass the gate.
 */
@ConfigurationProperties(prefix = GovernanceProperties.PREFIX)
public class GovernanceProperties {

    public static final String PREFIX = "opencrawling.governance";

    /** The built-in hook that masks personal data. */
    public static final String PII_MASKING = "pii-masking";
    /** The built-in hook that asks an HTTP governance service. */
    public static final String HTTP = "http";

    /** What happens to a document's earlier chunks when a new version of it is rejected. */
    public enum OnRejectedUpdate {
        /** Remove them: the document stops being searchable rather than serving text that was not re-approved. */
        PURGE,
        /** Keep the last approved version. */
        KEEP
    }

    private boolean enabled = false;

    /**
     * The hooks to run, in order: {@value #PII_MASKING}, {@value #HTTP}, or the {@code name()} of a
     * {@code PreEmbeddingGovernanceHook} bean. Must not be empty when the gate is on.
     */
    private List<String> hooks = new ArrayList<>();

    private OnRejectedUpdate onRejectedUpdate = OnRejectedUpdate.PURGE;

    private final Pii pii = new Pii();

    private final Http http = new Http();

    /** The {@value #PII_MASKING} hook. */
    public static class Pii {

        /** Personal data the built-in masker finds. */
        public enum Type {
            /** E-mail addresses. */
            EMAIL,
            /** Payment card numbers of 13 to 19 digits that pass the Luhn check. */
            PAYMENT_CARD,
            /** IBANs that pass the ISO 13616 check. */
            IBAN
        }

        private Set<Type> types = EnumSet.allOf(Type.class);

        /** Whether metadata values are masked too (keys carrying permissions or identity never are). */
        private boolean maskMetadata = true;

        public Set<Type> getTypes() {
            return types;
        }

        public void setTypes(Set<Type> types) {
            this.types = types != null && !types.isEmpty() ? EnumSet.copyOf(types) : EnumSet.noneOf(Type.class);
        }

        public boolean isMaskMetadata() {
            return maskMetadata;
        }

        public void setMaskMetadata(boolean maskMetadata) {
            this.maskMetadata = maskMetadata;
        }
    }

    /** The {@value #HTTP} hook. */
    public static class Http {

        /** The governance service's endpoint (https recommended). */
        private String url;

        /** Signs each request (HMAC-SHA256); at least 32 characters. */
        private String secret;

        private Duration connectTimeout = Duration.ofSeconds(5);

        /** How long one attempt may take. */
        private Duration timeout = Duration.ofSeconds(30);

        /** Attempts per document, the first included; retries only follow network errors, 429 and 5xx. */
        private int maxAttempts = 3;

        private Duration initialBackoff = Duration.ofMillis(500);

        private Duration maxBackoff = Duration.ofSeconds(5);

        /** The largest answer read; a longer one counts as no answer. */
        private int maxResponseBytes = 32 * 1024 * 1024;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getHooks() {
        return hooks;
    }

    public void setHooks(List<String> hooks) {
        this.hooks = hooks != null ? new ArrayList<>(hooks) : new ArrayList<>();
    }

    public OnRejectedUpdate getOnRejectedUpdate() {
        return onRejectedUpdate;
    }

    public void setOnRejectedUpdate(OnRejectedUpdate onRejectedUpdate) {
        this.onRejectedUpdate = onRejectedUpdate != null ? onRejectedUpdate : OnRejectedUpdate.PURGE;
    }

    public Pii getPii() {
        return pii;
    }

    public Http getHttp() {
        return http;
    }
}
