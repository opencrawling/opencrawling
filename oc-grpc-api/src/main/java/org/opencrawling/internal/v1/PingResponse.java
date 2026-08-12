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
package org.opencrawling.internal.v1;

public class PingResponse implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private final long serverTimestamp;
    private final boolean healthy;

    public PingResponse(long serverTimestamp, boolean healthy) {
        this.serverTimestamp = serverTimestamp;
        this.healthy = healthy;
    }

    public long getServerTimestamp() {
        return serverTimestamp;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private long serverTimestamp;
        private boolean healthy;

        public Builder setServerTimestamp(long serverTimestamp) {
            this.serverTimestamp = serverTimestamp;
            return this;
        }

        public Builder setHealthy(boolean healthy) {
            this.healthy = healthy;
            return this;
        }

        public PingResponse build() {
            return new PingResponse(serverTimestamp, healthy);
        }
    }
}
