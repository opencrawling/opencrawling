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
package org.opencrawling.runtime.orchestrator;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.result.ScanResult;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.core.messaging.IngestionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.opencrawling.core.claimcheck.ClaimCheckStore;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.StructuredTaskScope;
import java.util.List;
import java.util.ArrayList;

import org.springframework.beans.factory.annotation.Qualifier;

@Service
public class JobOrchestrator {
    
    private static final Logger log = LoggerFactory.getLogger(JobOrchestrator.class);
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ClaimCheckStore claimCheckStore;

    public JobOrchestrator(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Qualifier("claimCheckStore") ClaimCheckStore claimCheckStore) {
        this.kafkaTemplate = kafkaTemplate;
        this.claimCheckStore = claimCheckStore;
    }

    @SuppressWarnings("preview")
    public void runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path) {
        runJob(repositoryConnector, outputConnector, path, null);
    }

    @SuppressWarnings("preview")
    public void runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path, String transformationConnector) {
        log.info("Starting job for path: {} with transformation connector: {}", path, transformationConnector);
        
        String engine = "ollama";
        java.util.Map<String, String> config = java.util.Map.of("model", "mxbai-embed-large");

        if (transformationConnector != null && !transformationConnector.isBlank()) {
            try {
                java.util.List<org.opencrawling.runtime.api.ConnectorController.ConnectorDTO> connectors = 
                    org.opencrawling.runtime.api.PersistenceHelper.loadList("connectors.json", org.opencrawling.runtime.api.ConnectorController.ConnectorDTO.class, java.util.List.of());
                for (org.opencrawling.runtime.api.ConnectorController.ConnectorDTO conn : connectors) {
                    if (conn.name().equals(transformationConnector)) {
                        engine = conn.configuration().getOrDefault("engine", "ollama");
                        config = conn.configuration();
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load connector configuration for {}: {}", transformationConnector, e.getMessage());
            }
        }

        final String finalEngine = engine;
        final java.util.Map<String, String> finalConfig = config;

        try (var scope = StructuredTaskScope.open()) {
            
            StructuredTaskScope.Subtask<List<ScanResult>> scanTask = scope.fork(() -> {
                List<ScanResult> results = new ArrayList<>();
                repositoryConnector.scan(path)
                    .flatMap(doc -> {
                        try {
                            String finalUri = doc.uri();
                            boolean isLocalFileUri = finalUri != null && finalUri.startsWith("file:");
                            boolean isSupportedByStore = finalUri != null && claimCheckStore.supports(URI.create(finalUri));

                            // Save stream via ClaimCheckStore if remote stream OR store requires non-local persistence
                            if (doc.contentStream() != null && (!isLocalFileUri || !isSupportedByStore)) {
                                String filename = doc.id() + "_" + doc.metadata().getOrDefault("name", List.of("document")).get(0);
                                filename = filename.replaceAll("[^a-zA-Z0-9.-]", "_");
                                String mimeType = null;
                                List<String> mimeList = doc.metadata().get("mimeType");
                                if (mimeList != null && !mimeList.isEmpty()) {
                                    mimeType = mimeList.get(0);
                                }
                                
                                try (InputStream in = doc.contentStream()) {
                                    if (in != null) {
                                        URI claimUri = claimCheckStore.put(filename, in, -1, mimeType);
                                        finalUri = claimUri.toString();
                                        log.info("Saved document content to Claim Check store: {}", finalUri);
                                    } else {
                                        log.warn("Document content stream is null for id: {}", doc.id());
                                    }
                                } catch (Exception e) {
                                    log.error("Failed to write Claim Check content for doc: {}", doc.id(), e);
                                }
                            }

                            IngestionMessage msg = new IngestionMessage(
                                doc.id(),
                                finalUri,
                                doc.metadata(),
                                doc.acl(),
                                doc.security(),
                                doc.lastModified().toString(),
                                transformationConnector,
                                finalEngine,
                                finalConfig
                            );
                            
                            // Publish document metadata to Kafka topic and wait for confirmation
                            kafkaTemplate.send(KafkaConfig.TOPIC_NAME, doc.id(), msg).get();
                            log.info("Published document reference to Kafka: {}", doc.id());
                            
                            return Mono.just((ScanResult) new ScanResult.Success(doc.id(), "1.0"));
                        } catch (Exception e) {
                            return Mono.just(new ScanResult.Failure(doc.id(), e));
                        }
                    })
                    .doOnNext(results::add)
                    .blockLast(); 
                return results;
            });
            
            scope.join();
            
            List<ScanResult> scanResults = scanTask.get();
            scanResults.forEach(r -> log.info(r.summarize()));
            
            log.info("Job scanning phase completed. Documents published to Kafka.");
            
        } catch (StructuredTaskScope.FailedException e) {
            log.error("Job execution failed due to subtask failure: ", e.getCause());
        } catch (Exception e) {
            log.error("Job execution failed: ", e);
        }
    }

    private String resolveSharedDir() {
        File dockerData = new File("/data");
        if (dockerData.exists() && dockerData.isDirectory() && dockerData.canWrite()) {
            return "/data";
        }
        File localData = new File("data");
        if (!localData.exists()) {
            localData.mkdirs();
        }
        return localData.getAbsolutePath();
    }
}
