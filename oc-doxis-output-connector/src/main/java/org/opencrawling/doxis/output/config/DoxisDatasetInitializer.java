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
import org.opencrawling.doxis.output.DoxisDatasetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Resolves (and if needed provisions) the target dataset at startup. Failures are logged only:
 * the dataset is resolved again lazily on the first write.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "doxis")
public class DoxisDatasetInitializer {

    private static final Logger log = LoggerFactory.getLogger(DoxisDatasetInitializer.class);

    private final DoxisDatasetManager datasetManager;

    public DoxisDatasetInitializer(DoxisDatasetManager datasetManager) {
        this.datasetManager = datasetManager;
    }

    @PostConstruct
    public void initializeDataset() {
        try {
            String datasetId = datasetManager.datasetId();
            log.info("Doxis dataset '{}' is ready for ingestion.", datasetId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Doxis dataset initialization was interrupted.");
        } catch (Exception e) {
            log.warn("Doxis dataset provisioning encountered an issue (API unreachable or key lacks dataset rights): {}",
                    e.getMessage());
        }
    }
}
