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
package org.opencrawling.push.grpc;

import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.ConnectorSchema.SchemaField;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * OpenCrawling Repository Connector for gRPC Push streaming ingestion.
 * Enables external high-throughput producers (Flink, Spark, Kafka Connect, CDC Debezium, StormCrawler)
 * to push OIS documents and lifecycle events into OpenCrawling over HTTP/2 Protobuf streams.
 */
public class GrpcPushRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(GrpcPushRepositoryConnector.class);

    private final GrpcPushProperties properties;
    private final GrpcPushServerManager serverManager;

    public GrpcPushRepositoryConnector() {
        this(new GrpcPushProperties(), null);
    }

    public GrpcPushRepositoryConnector(GrpcPushProperties properties) {
        this(properties, null);
    }

    public GrpcPushRepositoryConnector(GrpcPushProperties properties, GrpcPushServerManager serverManager) {
        this.properties = properties != null ? properties : new GrpcPushProperties();
        this.serverManager = serverManager;
    }

    @Override
    public String getName() {
        return "GrpcPushConnector";
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting gRPC Push Repository Connector. Listener port: {}", properties.getPort());
        if (serverManager != null && !serverManager.isRunning()) {
            serverManager.start();
        }
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting gRPC Push Repository Connector.");
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        // gRPC Push is an event-driven inbound receiver. Documents are ingested asynchronously
        // via OisPushService RPCs rather than active pull scans.
        log.info("gRPC Push Connector scan initiated on base path '{}'. Awaiting inbound streaming push events on port {}.",
                basePath, properties.getPort());
        return Flux.empty();
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        return new ConnectorSchema(List.of(
                new SchemaField("id", "String", "Unique document identifier (URI or primary key)"),
                new SchemaField("action", "String", "Lifecycle action (UPSERT or DELETE tombstone)"),
                new SchemaField("mime_type", "String", "Content MIME type (e.g. application/pdf, text/plain)"),
                new SchemaField("timestamp", "Long", "Event occurrence epoch timestamp in milliseconds"),
                new SchemaField("text_content", "String", "Plaintext content payload for direct chunking"),
                new SchemaField("raw_bytes", "Bytes", "Inline binary content payload"),
                new SchemaField("claim_check_uri", "String", "Pre-uploaded storage URI (file://, ozone://, s3://)"),
                new SchemaField("metadata", "Map<String, String>", "Dynamic structured metadata attributes"),
                new SchemaField("security", "SecurityConfig", "Zero-Trust allow/deny SIDs and tenant boundaries"),
                new SchemaField("checksum_sha256", "String", "Cryptographic SHA-256 integrity checksum")
        ));
    }

    public GrpcPushProperties getProperties() {
        return properties;
    }

    public GrpcPushServerManager getServerManager() {
        return serverManager;
    }
}
