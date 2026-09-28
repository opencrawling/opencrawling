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
package org.opencrawling.seatunnel.messaging;

import jakarta.annotation.PostConstruct;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.opencrawling.seatunnel.SeaTunnelConstants;
import org.opencrawling.seatunnel.client.SeaTunnelRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "opencrawling.consumer.writer.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${spring.opencrawling.output.type:pgvector}' == 'seatunnel'")
public class SeaTunnelStoreWriterConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeaTunnelStoreWriterConsumer.class);

    private final SeaTunnelRestClient restClient;
    private final AtomicLong upsertCount = new AtomicLong(0);
    private final AtomicLong deleteCount = new AtomicLong(0);

    @Value("${spring.opencrawling.output.seatunnel.rest-url:http://localhost:8080}")
    private String restUrl;

    @Value("${spring.opencrawling.output.seatunnel.job-name:opencrawling_ingestion_pipeline}")
    private String jobName;

    @Autowired
    public SeaTunnelStoreWriterConsumer(@Autowired(required = false) SeaTunnelRestClient restClient) {
        this.restClient = restClient;
    }

    @PostConstruct
    public void init() {
        log.info("SeaTunnelStoreWriterConsumer initialized successfully. Monitoring pipeline '{}' at '{}'", jobName, restUrl);
    }

    @KafkaListener(topics = "${spring.opencrawling.output.seatunnel.kafka.topic:opencrawling-embedded}")
    public void consume(DocumentEmbeddedMessage message) {
        if (message == null) {
            return;
        }

        try {
            if (message.action() == DocumentAction.DELETE) {
                deleteCount.incrementAndGet();
                log.info("Received DELETE tombstone for SeaTunnel stream (RowKind: {}): document {} / chunk {}",
                        SeaTunnelConstants.ROW_KIND_DELETE, message.documentId(), message.chunkId());
                return;
            }

            upsertCount.incrementAndGet();
            int dims = message.embedding() != null ? message.embedding().length : 0;
            log.info("Received embedded chunk for SeaTunnel stream (RowKind: {}): chunk {} (Doc: {}, Dimensions: {})",
                    SeaTunnelConstants.ROW_KIND_INSERT, message.chunkId(), message.documentId(), dims);
        } catch (Exception e) {
            log.error("Error processing message for SeaTunnel stream: {}", e.getMessage(), e);
        }
    }

    public long getUpsertCount() {
        return upsertCount.get();
    }

    public long getDeleteCount() {
        return deleteCount.get();
    }
}
