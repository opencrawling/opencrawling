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
package org.opencrawling.push.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the OpenCrawling gRPC Push Repository Connector.
 */
@ConfigurationProperties(prefix = "opencrawling.connector.grpc-push")
public class GrpcPushProperties {

    /**
     * Whether the gRPC push connector listener is enabled.
     */
    private boolean enabled = true;

    /**
     * Inbound port for incoming gRPC ingestion requests.
     */
    private int port = 9096;

    /**
     * Maximum incoming gRPC frame size in megabytes.
     */
    private int maxMessageSizeMb = 64;

    /**
     * Payloads with inline raw bytes exceeding this threshold (in KB) are automatically
     * offloaded into the ClaimCheckStore.
     */
    private int inlineBinaryThresholdKb = 512;

    /**
     * Optional Bearer token required for document ingestion requests.
     */
    private String token = "";

    /**
     * TLS transport encryption configuration.
     */
    private TlsProperties tls = new TlsProperties();

    /**
     * gRPC Server Reflection configuration.
     */
    private ReflectionProperties reflection = new ReflectionProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxMessageSizeMb() {
        return maxMessageSizeMb;
    }

    public void setMaxMessageSizeMb(int maxMessageSizeMb) {
        this.maxMessageSizeMb = maxMessageSizeMb;
    }

    public int getInlineBinaryThresholdKb() {
        return inlineBinaryThresholdKb;
    }

    public void setInlineBinaryThresholdKb(int inlineBinaryThresholdKb) {
        this.inlineBinaryThresholdKb = inlineBinaryThresholdKb;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public TlsProperties getTls() {
        return tls;
    }

    public void setTls(TlsProperties tls) {
        this.tls = tls;
    }

    public ReflectionProperties getReflection() {
        return reflection;
    }

    public void setReflection(ReflectionProperties reflection) {
        this.reflection = reflection;
    }

    public enum ClientAuth {
        NONE,
        OPTIONAL,
        REQUIRE
    }

    public static class TlsProperties {
        private boolean enabled = false;
        private String certChainPath = "";
        private String privateKeyPath = "";
        private String trustCertPath = "";
        private ClientAuth clientAuth = ClientAuth.NONE;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCertChainPath() {
            return certChainPath;
        }

        public void setCertChainPath(String certChainPath) {
            this.certChainPath = certChainPath;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public String getTrustCertPath() {
            return trustCertPath;
        }

        public void setTrustCertPath(String trustCertPath) {
            this.trustCertPath = trustCertPath;
        }

        public ClientAuth getClientAuth() {
            return clientAuth;
        }

        public void setClientAuth(ClientAuth clientAuth) {
            this.clientAuth = clientAuth;
        }
    }

    public static class ReflectionProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
