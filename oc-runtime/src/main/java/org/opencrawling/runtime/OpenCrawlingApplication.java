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
package org.opencrawling.runtime;

import java.util.List;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@SpringBootApplication(
    scanBasePackages = "org.opencrawling",
    exclude = { PgVectorStoreAutoConfiguration.class }
)
public class OpenCrawlingApplication {

    private static final Logger log = LoggerFactory.getLogger(OpenCrawlingApplication.class);

    @Value("${spring.opencrawling.crawl-on-startup:false}")
    private boolean crawlOnStartup;

    @Value("${spring.opencrawling.scan-path:}")
    private String scanPath;

    @Value("${spring.opencrawling.transformation-connector:Ollama_Embedding_Default}")
    private String transformationConnector;

    @Value("${spring.opencrawling.repository-connector-type:filesystem}")
    private String repositoryConnectorType;

    @SuppressWarnings("unused")
    @Bean
    @Profile("!test")
    public CommandLineRunner runSampleJob(
            JobOrchestrator orchestrator,
            List<RepositoryConnector> repositoryConnectors,
            OutputConnector outputConnector) {
        return args -> {
            log.info("--- OpenCrawling Bootstrap ---");
            log.info("Requested Repository Connector Type: {}", repositoryConnectorType);

            RepositoryConnector activeConnector = repositoryConnectors.stream()
                .filter(c -> {
                    if ("alfresco".equalsIgnoreCase(repositoryConnectorType)) {
                        return c.getClass().getSimpleName().toLowerCase().contains("alfresco");
                    } else if ("iceberg".equalsIgnoreCase(repositoryConnectorType)) {
                        return c.getClass().getSimpleName().toLowerCase().contains("iceberg");
                    } else {
                        return c.getClass().getSimpleName().toLowerCase().contains("file");
                    }
                })
                .findFirst()
                .orElse(repositoryConnectors.isEmpty() ? null : repositoryConnectors.get(0));

            if (activeConnector == null) {
                log.error("No Repository Connector found!");
                return;
            }

            log.info("Detected Repository Connector: {}", activeConnector.getName());
            log.info("Detected Output Connector: {}", outputConnector.getName());

            if (crawlOnStartup) {
                if (scanPath == null || scanPath.isBlank()) {
                    log.warn("Crawl on startup is enabled, but spring.opencrawling.scan-path is not set. Skipping sample crawl.");
                } else {
                    log.info("Triggering sample crawl job on path: {} with transformation connector: {}", scanPath, transformationConnector);
                    orchestrator.runJob(activeConnector, outputConnector, scanPath, transformationConnector);
                }
            } else {
                log.info("Sample crawl job on startup is disabled. Use properties to enable it (spring.opencrawling.crawl-on-startup=true).");
            }
            
            log.info("--- Bootstrap sequence completed ---");
        };
    }
}
