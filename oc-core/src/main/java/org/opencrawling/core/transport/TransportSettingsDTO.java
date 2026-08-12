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
package org.opencrawling.core.transport;

public record TransportSettingsDTO(
    String mode,                // "AUTO", "GRPC", "REST"
    boolean grpcEnabled,         // true / false
    int grpcPort,               // default 9095
    int maxMessageSizeMb,       // default 32
    boolean fallbackToRest,     // default true
    long keepAliveTimeMs,       // default 30000
    long connectionTimeoutMs,   // default 5000
    boolean tlsEnabled,         // default false
    String certChainPath,       // default ""
    String privateKeyPath       // default ""
) {
    public static TransportSettingsDTO defaultSettings() {
        return new TransportSettingsDTO(
            "AUTO",
            true,
            9095,
            32,
            true,
            30000L,
            5000L,
            false,
            "",
            ""
        );
    }
}
