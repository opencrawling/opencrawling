package org.apache.manifoldcf.runtime.orchestrator;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import org.apache.manifoldcf.core.connector.RepositoryConnector;
import org.apache.manifoldcf.core.connector.OutputConnector;
import org.apache.manifoldcf.core.result.ScanResult;
import org.apache.manifoldcf.runtime.config.KafkaConfig;
import org.apache.manifoldcf.runtime.messaging.IngestionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.StructuredTaskScope;
import java.util.List;
import java.util.ArrayList;

@Service
public class JobOrchestrator {
    
    private static final Logger log = LoggerFactory.getLogger(JobOrchestrator.class);
    
    private final KafkaTemplate<String, IngestionMessage> kafkaTemplate;

    public JobOrchestrator(KafkaTemplate<String, IngestionMessage> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @SuppressWarnings("preview")
	public void runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path) {
        log.info("Starting job for path: {}", path);
        
        try (var scope = StructuredTaskScope.open()) {
            
            StructuredTaskScope.Subtask<List<ScanResult>> scanTask = scope.fork(() -> {
                List<ScanResult> results = new ArrayList<>();
                repositoryConnector.scan(path)
                    .flatMap(doc -> {
                        try {
                            IngestionMessage msg = new IngestionMessage(
                                doc.id(),
                                doc.uri(),
                                doc.metadata(),
                                doc.acl(),
                                doc.lastModified().toString()
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
}
