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
package org.opencrawling.doxis.output.config;

import jakarta.annotation.PostConstruct;
import org.opencrawling.doxis.output.DoxisOutputConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Validates the Doxis setup at startup (login, repository, document type, external-id descriptor). Failures are logged only;
 * the connector initializes again lazily on the first document.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "doxis")
public class DoxisConnectionInitializer {

    private static final Logger log = LoggerFactory.getLogger(DoxisConnectionInitializer.class);

    private final DoxisOutputConnector connector;

    public DoxisConnectionInitializer(DoxisOutputConnector connector) {
        this.connector = connector;
    }

    @PostConstruct
    public void initialize() {
        try {
            connector.initialize();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Doxis initialization was interrupted.");
        } catch (Exception e) {
            log.warn("Doxis output connector is not ready yet (CSB unreachable, credentials, repository or document type): {}",
                    e.getMessage());
        }
    }
}
