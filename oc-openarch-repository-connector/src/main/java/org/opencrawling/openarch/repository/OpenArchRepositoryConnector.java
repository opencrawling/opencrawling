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
package org.opencrawling.openarch.repository;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.observability.concurrency.ObservabilityTask;
import org.opencrawling.openarch.repository.config.OpenArchRepositoryProperties;
import org.opencrawling.openarch.repository.model.OpenArchRepositoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;

/**
 * High-throughput Hyland OpenArch Enterprise Repository Connector.
 * Harvests documents, metadata, content streams, and Zero-Trust ACLs from OpenArch,
 * leveraging Java 25 Virtual Threads (StructuredTaskScope) and Project Reactor.
 */
@Component
public class OpenArchRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(OpenArchRepositoryConnector.class);

    private final OpenArchRepositoryProperties properties;
    private final OpenArchRepositoryClient client;
    private volatile boolean connected = false;

    public OpenArchRepositoryConnector() {
        this(new OpenArchRepositoryProperties(), null);
    }

    @Autowired
    public OpenArchRepositoryConnector(
            @Value("${spring.opencrawling.connector.openarch.endpoint-url:http://localhost:8080}") String endpointUrl,
            @Value("${spring.opencrawling.connector.openarch.repository-id:default}") String repositoryId,
            @Value("${spring.opencrawling.connector.openarch.username:Administrator}") String username,
            @Value("${spring.opencrawling.connector.openarch.password:Administrator}") String password,
            @Value("${spring.opencrawling.connector.openarch.crawl-mode:QUERY}") String crawlMode,
            @Value("${spring.opencrawling.connector.openarch.hxql-query:SELECT * FROM SysFile}") String hxqlQuery,
            @Value("${spring.opencrawling.connector.openarch.root-folder-id:00000000-0000-0000-0000-000000000000}") String rootFolderId,
            @Value("${spring.opencrawling.connector.openarch.include-subfolders:true}") boolean includeSubfolders,
            @Value("${spring.opencrawling.connector.openarch.batch-size:100}") int batchSize,
            @Value("${spring.opencrawling.connector.openarch.include-acls:true}") boolean includeAcls,
            @Value("${spring.opencrawling.connector.openarch.include-content-stream:true}") boolean includeContentStream,
            @Value("${spring.opencrawling.connector.openarch.incremental:false}") boolean incremental,
            @Value("${spring.opencrawling.connector.openarch.last-crawl-timestamp:0}") long lastCrawlTimestamp) {

        OpenArchRepositoryProperties props = new OpenArchRepositoryProperties();
        props.setEndpointUrl(endpointUrl);
        props.setRepositoryId(repositoryId);
        props.setUsername(username);
        props.setPassword(password);
        props.setCrawlMode(crawlMode);
        props.setHxqlQuery(hxqlQuery);
        props.setRootFolderId(rootFolderId);
        props.setIncludeSubfolders(includeSubfolders);
        props.setBatchSize(batchSize);
        props.setIncludeAcls(includeAcls);
        props.setIncludeContentStream(includeContentStream);
        props.setIncremental(incremental);
        props.setLastCrawlTimestamp(lastCrawlTimestamp);

        this.properties = props;
        this.client = new OpenArchRepositoryClient(props.getEndpointUrl(), props.getUsername(), props.getPassword());
    }

    public OpenArchRepositoryConnector(OpenArchRepositoryProperties properties, OpenArchRepositoryClient client) {
        this.properties = properties != null ? properties : new OpenArchRepositoryProperties();
        this.client = client != null ? client : new OpenArchRepositoryClient(
                this.properties.getEndpointUrl(),
                this.properties.getUsername(),
                this.properties.getPassword()
        );
    }

    @Override
    public String getName() {
        return "OpenArch_Repository";
    }

    @Override
    public String sourceInstance() {
        return properties.getEndpointUrl();
    }

    @Override
    public void connect() throws Exception {
        log.info("Connecting to Hyland OpenArch Repository at: {} (Repo: {})", properties.getEndpointUrl(), properties.getRepositoryId());
        boolean ping = client.ping(properties.getRepositoryId());
        if (!ping) {
            log.warn("OpenArch cluster at {} did not answer ping with 200/404, proceeding anyway.", properties.getEndpointUrl());
        } else {
            log.info("Successfully connected to OpenArch repository '{}'.", properties.getRepositoryId());
        }
        connected = true;
    }

    @Override
    public void disconnect() throws Exception {
        log.info("Disconnecting OpenArch Repository Connector.");
        connected = false;
    }

    @Override
    public boolean supportsConcurrentProcessing() {
        return true;
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        return new ConnectorSchema(List.of(
                new ConnectorSchema.SchemaField("sys_id", "string", "Document identifier"),
                new ConnectorSchema.SchemaField("sys_name", "string", "Document name"),
                new ConnectorSchema.SchemaField("sys_title", "string", "Document title"),
                new ConnectorSchema.SchemaField("sys_description", "string", "Document description"),
                new ConnectorSchema.SchemaField("sys_primaryType", "string", "Primary object type (SysFile, SysFolder)"),
                new ConnectorSchema.SchemaField("sys_mixinTypes", "array", "Document mixin types"),
                new ConnectorSchema.SchemaField("sys_path", "string", "Document hierarchical path"),
                new ConnectorSchema.SchemaField("sys_created", "datetime", "Creation timestamp"),
                new ConnectorSchema.SchemaField("sys_modified", "datetime", "Last modification timestamp"),
                new ConnectorSchema.SchemaField("sys_acl", "array", "Access Control Entries"),
                new ConnectorSchema.SchemaField("sys_effectivePermissions", "array", "Evaluated user permissions")
        ));
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            Thread.ofVirtual().name("openarch-crawler-lane").start(() -> {
                try {
                    crawlOpenArch(sink);
                    sink.complete();
                } catch (Exception e) {
                    log.error("Fatal error during OpenArch scan: {}", e.getMessage(), e);
                    sink.error(e);
                }
            });
        });
    }

    private void crawlOpenArch(FluxSink<RepositoryDocument> sink) throws Exception {
        String repoId = properties.getRepositoryId();
        String hxql = buildQuery();
        int batchSize = properties.getBatchSize();
        long offset = 0;
        String searchAfter = null;

        log.info("Starting OpenArch crawl with query: '{}' (Batch size: {})", hxql, batchSize);

        while (!sink.isCancelled()) {
            JsonNode queryResult = client.query(repoId, hxql, batchSize, offset, searchAfter);
            if (queryResult == null || !queryResult.has("documents")) {
                break;
            }

            JsonNode docsNode = queryResult.get("documents");
            if (!docsNode.isArray() || docsNode.isEmpty()) {
                log.info("No more documents matching OpenArch query. Crawl completed.");
                break;
            }

            List<JsonNode> batch = new ArrayList<>();
            for (JsonNode docNode : docsNode) {
                batch.add(docNode);
            }

            processBatchConcurrently(batch, sink);

            if (queryResult.hasNonNull("nextSearchAfter")) {
                searchAfter = queryResult.get("nextSearchAfter").asText();
            } else {
                offset += batch.size();
            }

            if (batch.size() < batchSize) {
                break;
            }
        }
    }

    @SuppressWarnings("preview")
    private void processBatchConcurrently(List<JsonNode> documents, FluxSink<RepositoryDocument> sink) throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode docNode : documents) {
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        RepositoryDocument doc = mapToRepositoryDocument(docNode);
                        synchronized (sink) {
                            if (!sink.isCancelled()) {
                                sink.next(doc);
                            }
                        }
                    } catch (Exception docErr) {
                        log.error("Failed to map OpenArch document node: {}", docErr.getMessage(), docErr);
                    }
                    return null;
                }));
            }
            scope.join();
        }
    }

    public RepositoryDocument mapToRepositoryDocument(JsonNode docNode) {
        String docId = docNode.hasNonNull("id") ? docNode.get("id").asText()
                : (docNode.hasNonNull("sys_id") ? docNode.get("sys_id").asText() : UUID.randomUUID().toString());

        String repoId = properties.getRepositoryId();
        String uri = "openarch://" + repoId + "/" + docId;

        // Metadata extraction
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = docNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode val = field.getValue();
            if (val.isArray()) {
                List<String> list = new ArrayList<>();
                for (JsonNode elem : val) {
                    list.add(elem.asText());
                }
                metadata.put(key, list);
            } else if (!val.isNull()) {
                metadata.put(key, List.of(val.asText()));
            }
        }

        // Title and Description
        String title = docNode.hasNonNull("sys_title") ? docNode.get("sys_title").asText()
                : (docNode.hasNonNull("sys_name") ? docNode.get("sys_name").asText() : docId);
        metadata.put("title", List.of(title));

        if (docNode.hasNonNull("sys_description")) {
            metadata.put("description", List.of(docNode.get("sys_description").asText()));
        }

        // Timestamp
        Instant lastModified = Instant.now();
        if (docNode.hasNonNull("sys_modified")) {
            try {
                lastModified = Instant.parse(docNode.get("sys_modified").asText());
            } catch (Exception ignored) {
            }
        }

        // Security / ACLs
        SecurityConfig securityConfig = SecurityConfig.createPublic();
        if (properties.isIncludeAcls() && docNode.hasNonNull("sys_acl")) {
            securityConfig = extractSecurityConfig(docNode.get("sys_acl"));
        }

        // Lazy content stream
        InputStream contentStream = null;
        if (properties.isIncludeContentStream()) {
            String primaryType = docNode.hasNonNull("sys_primaryType") ? docNode.get("sys_primaryType").asText() : "";
            if (!"SysFolder".equalsIgnoreCase(primaryType)) {
                try {
                    contentStream = client.downloadBlob(repoId, docId);
                } catch (Exception dlEx) {
                    log.debug("No content stream downloaded for document {}: {}", docId, dlEx.getMessage());
                }
            }
        }

        return new RepositoryDocument(
                docId,
                uri,
                contentStream,
                metadata,
                "openarch:acl:" + docId,
                securityConfig,
                lastModified,
                DocumentAction.UPSERT
        );
    }

    private SecurityConfig extractSecurityConfig(JsonNode aclNode) {
        if (!aclNode.isArray() || aclNode.isEmpty()) {
            return SecurityConfig.createPublic();
        }

        List<PermissionRule> rules = new ArrayList<>();
        for (JsonNode ace : aclNode) {
            boolean granted = !ace.has("granted") || ace.get("granted").asBoolean(true);
            String permission = ace.hasNonNull("permission") ? ace.get("permission").asText() : "Read";
            String access = granted ? mapOpenArchPermissionToOis(permission) : "deny";

            String identity = null;
            String identityType = "user";

            if (ace.hasNonNull("user")) {
                JsonNode uNode = ace.get("user");
                identity = uNode.hasNonNull("username") ? uNode.get("username").asText() : uNode.get("id").asText();
                identityType = "user";
            } else if (ace.hasNonNull("group")) {
                JsonNode gNode = ace.get("group");
                identity = "group:" + (gNode.hasNonNull("groupname") ? gNode.get("groupname").asText() : gNode.get("id").asText());
                identityType = "group";
            } else if (ace.hasNonNull("username")) {
                identity = ace.get("username").asText();
            }

            if (identity != null && !identity.isBlank()) {
                rules.add(new PermissionRule(identity, identityType, identity, access));
            }
        }
        return new SecurityConfig(true, rules);
    }

    private String mapOpenArchPermissionToOis(String openArchPermission) {
        return switch (openArchPermission.toLowerCase(Locale.ROOT)) {
            case "read" -> "read";
            case "write", "readwrite" -> "write";
            case "delete", "deletechild" -> "delete";
            case "managesecurity", "managelock", "all" -> "admin";
            default -> "read";
        };
    }

    private String buildQuery() {
        String baseQuery = properties.getHxqlQuery();
        if (baseQuery == null || baseQuery.isBlank()) {
            baseQuery = "SELECT * FROM SysFile";
        }
        if (properties.isIncremental() && properties.getLastCrawlTimestamp() > 0) {
            String isoTimestamp = Instant.ofEpochMilli(properties.getLastCrawlTimestamp()).toString();
            if (baseQuery.toUpperCase(Locale.ROOT).contains("WHERE")) {
                baseQuery += " AND sys_modified >= '" + isoTimestamp + "'";
            } else {
                baseQuery += " WHERE sys_modified >= '" + isoTimestamp + "'";
            }
        }
        return baseQuery;
    }
}
