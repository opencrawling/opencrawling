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
package org.opencrawling.authorization.openfga;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection and behaviour settings for the OpenFGA provider
 * ({@code opencrawling.security.authorization.openfga.*}).
 *
 * @param apiUrl              OpenFGA HTTP API base URL
 * @param storeId             the store to use; when blank and {@code autoProvision} is on, the store named
 *                            {@code storeName} is looked up or created
 * @param modelId             the authorization model to pin; when blank and {@code autoProvision} is on, the
 *                            bundled model is written unless the store's latest model already matches it
 * @param storeName           store name used by auto-provisioning
 * @param autoProvision       create the store and model when they are not pinned (development and tests);
 *                            production should pin {@code storeId} and {@code modelId}
 * @param apiToken            pre-shared key sent as a bearer token, or blank when OpenFGA has no auth
 * @param timeout             per-request timeout
 * @param batchSize           checks per BatchCheck request (OpenFGA's default server limit is 50)
 * @param maxParallelRequests BatchCheck requests sent in parallel for one decision
 */
@ConfigurationProperties(prefix = "opencrawling.security.authorization.openfga")
public record OpenFgaProperties(
        String apiUrl,
        String storeId,
        String modelId,
        String storeName,
        Boolean autoProvision,
        String apiToken,
        Duration timeout,
        Integer batchSize,
        Integer maxParallelRequests
) {

    public OpenFgaProperties {
        apiUrl = blank(apiUrl) ? "http://localhost:8080" : apiUrl.trim();
        storeName = blank(storeName) ? "opencrawling" : storeName.trim();
        autoProvision = autoProvision != null && autoProvision;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        batchSize = batchSize == null || batchSize < 1 ? 50 : batchSize;
        maxParallelRequests = maxParallelRequests == null || maxParallelRequests < 1 ? 10 : maxParallelRequests;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
