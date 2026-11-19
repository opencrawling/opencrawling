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
package org.opencrawling.vespa.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Probes {@code /state/v1/health} on startup, kept separate from {@link VespaClientConfig} so
 * client construction has no I/O side effects. The schema itself is not deployed here - the Vespa
 * application package (schema + services.xml) is deployed out of band and left to the operator.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "vespa")
public class VespaConnectionVerifier {

    private static final Logger log = LoggerFactory.getLogger(VespaConnectionVerifier.class);

    private final VespaOutputProperties properties;

    public VespaConnectionVerifier(VespaOutputProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void verifyConnectivity() {
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.endpoint() + "/state/v1/health"))
                    .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("Connected to Vespa at {} (namespace: {}, document type: {}).",
                        properties.endpoint(), properties.namespace(), properties.documentType());
            } else {
                log.warn("Vespa health check at {} returned HTTP {}.", properties.endpoint(), response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Could not verify Vespa connectivity at {}: {}", properties.endpoint(), e.getMessage());
        }
    }
}
