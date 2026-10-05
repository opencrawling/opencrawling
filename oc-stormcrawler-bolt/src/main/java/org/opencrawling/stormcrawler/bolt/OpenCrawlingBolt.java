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
package org.opencrawling.stormcrawler.bolt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.stormcrawler.Constants;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.indexing.AbstractIndexerBolt;
import org.apache.stormcrawler.persistence.Status;
import org.opencrawling.stormcrawler.bolt.dispatcher.HttpPayloadDispatcher;
import org.opencrawling.stormcrawler.bolt.dispatcher.InMemoryPayloadDispatcher;
import org.opencrawling.stormcrawler.bolt.dispatcher.PayloadDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * StormCrawler indexer that sends crawled pages to OpenCrawling as Open Ingestion Standard (OIS)
 * payloads.
 *
 * <p>Parsed documents become OIS {@code UPSERT} payloads whose {@code content.text} is the
 * parser's {@code text} field and whose {@code id} is the fetched URL. After a successful
 * dispatch the bolt emits {@code (url, metadata, FETCHED)} on the {@code status} stream, the only
 * stream it declares; a failed dispatch fails the tuple. Documents excluded by
 * {@code robots.noIndex} or {@code indexer.md.filter} are not dispatched but are still reported as
 * {@code FETCHED}.
 *
 * <p>Tuples on the status updater's {@code deletion} stream become OIS {@code DELETE} payloads with
 * the same {@code id}, unless {@code opencrawling.emit.deletions} is {@code false}. Tuples on a
 * {@code status} stream are acknowledged and not dispatched. The status updater emits deletion
 * tuples unanchored, so a failed DELETE dispatch is not replayed.
 *
 * <p>It reads the StormCrawler settings {@code indexer.md.mapping},
 * {@code indexer.canonical.name} and {@code indexer.md.filter}, plus its own
 * {@code opencrawling.*} settings.
 */
public class OpenCrawlingBolt extends AbstractIndexerBolt {

    private static final Logger log = LoggerFactory.getLogger(OpenCrawlingBolt.class);

    public static final String CONF_TARGET_ENDPOINT = "opencrawling.target.endpoint";
    public static final String CONF_TRANSPORT_MODE = "opencrawling.transport.mode";
    public static final String CONF_EMIT_DELETIONS = "opencrawling.emit.deletions";
    public static final String CONF_HASH_ALGORITHM = "opencrawling.hash.algorithm";
    public static final String CONF_INSTANCE_ID = "opencrawling.instance.id";
    public static final String CONF_DEFAULT_ROLE = "opencrawling.security.default.role";

    public static final String ACTION_UPSERT = "UPSERT";
    public static final String ACTION_DELETE = "DELETE";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private transient OutputCollector collector;
    private PayloadDispatcher dispatcher;

    private String targetEndpoint = "http://localhost:8080/api/v1/ingest/ois";
    private String transportMode = "REST";
    private boolean emitDeletions = true;
    private String hashAlgorithm = "SHA-256";
    private String instanceId = "stormcrawler-cluster";
    private String defaultRole = "ROLE_USER";

    public OpenCrawlingBolt() {
    }

    public OpenCrawlingBolt(PayloadDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void prepare(Map<String, Object> topoConf, TopologyContext context, OutputCollector collector) {
        super.prepare(topoConf, context, collector);
        this.collector = collector;

        if (topoConf != null) {
            if (topoConf.containsKey(CONF_TARGET_ENDPOINT)) {
                this.targetEndpoint = topoConf.get(CONF_TARGET_ENDPOINT).toString();
            }
            if (topoConf.containsKey(CONF_TRANSPORT_MODE)) {
                this.transportMode = topoConf.get(CONF_TRANSPORT_MODE).toString();
            }
            if (topoConf.containsKey(CONF_EMIT_DELETIONS)) {
                this.emitDeletions = Boolean.parseBoolean(topoConf.get(CONF_EMIT_DELETIONS).toString());
            }
            if (topoConf.containsKey(CONF_HASH_ALGORITHM)) {
                this.hashAlgorithm = topoConf.get(CONF_HASH_ALGORITHM).toString();
            }
            if (topoConf.containsKey(CONF_INSTANCE_ID)) {
                this.instanceId = topoConf.get(CONF_INSTANCE_ID).toString();
            }
            if (topoConf.containsKey(CONF_DEFAULT_ROLE)) {
                this.defaultRole = topoConf.get(CONF_DEFAULT_ROLE).toString();
            }
        }

        if (this.dispatcher == null) {
            if ("MEMORY".equalsIgnoreCase(transportMode)) {
                this.dispatcher = new InMemoryPayloadDispatcher();
            } else {
                this.dispatcher = new HttpPayloadDispatcher(targetEndpoint);
            }
        }

        try {
            this.dispatcher.init(topoConf != null ? topoConf : Map.of());
            log.info("OpenCrawlingBolt initialized successfully (transportMode={}, emitDeletions={})",
                    transportMode, emitDeletions);
        } catch (Exception e) {
            log.error("Failed to initialize OpenCrawlingBolt dispatcher: {}", e.getMessage(), e);
            throw new RuntimeException("Dispatcher init failed", e);
        }
    }

    @Override
    public void execute(Tuple tuple) {
        try {
            String streamId = tuple.getSourceStreamId();

            if (Constants.DELETION_STREAM_NAME.equals(streamId)) {
                processDeletionTuple(tuple);
            } else if (Constants.StatusStreamName.equals(streamId)) {
                // a status update is not a document; deletions come on the status updater's deletion stream
                collector.ack(tuple);
            } else {
                processContentTuple(tuple);
            }
        } catch (Exception e) {
            log.error("Error processing tuple {}: {}", tuple, e.getMessage(), e);
            collector.fail(tuple);
        }
    }

    private void processDeletionTuple(Tuple tuple) throws Exception {
        if (!emitDeletions) {
            collector.ack(tuple);
            return;
        }
        String url = tuple.getStringByField("url");
        Metadata metadata = getMetadataFromTuple(tuple);
        String httpStatus = metadata.getFirstValue("fetch.statusCode");

        // the status updater emits on the deletion stream only the URLs whose status became ERROR
        String json = objectMapper.writeValueAsString(createTombstonePayload(url, Status.ERROR.name(), httpStatus));
        dispatcher.dispatch(url, ACTION_DELETE, json);
        collector.ack(tuple);
        log.info("Dispatched OIS DELETE tombstone for removed URL: {} (http={})", url, httpStatus);
    }

    private void processContentTuple(Tuple tuple) throws Exception {
        String url = tuple.getStringByField("url");
        Object contentObj = tuple.contains("content") ? tuple.getValueByField("content") : null;
        Metadata metadata = getMetadataFromTuple(tuple);

        // noindex or indexer.md.filter: not ingested, but fetched, as for StormCrawler indexers
        if (!filterDocument(metadata)) {
            collector.emit(Constants.StatusStreamName, tuple, new Values(url, metadata, Status.FETCHED));
            collector.ack(tuple);
            return;
        }

        byte[] rawBytes;
        if (contentObj instanceof byte[] bytes) {
            rawBytes = bytes;
        } else if (contentObj instanceof String str) {
            rawBytes = str.getBytes(StandardCharsets.UTF_8);
        } else {
            rawBytes = new byte[0];
        }
        // the parser's extracted text; "content" holds the raw fetched bytes
        String textContent = tuple.contains("text") ? Objects.toString(tuple.getStringByField("text"), "") : "";

        String contentHash = computeHash(rawBytes, hashAlgorithm);

        // valueForURL reads the tuple's metadata field and needs a Metadata there
        boolean hasMetadata = tuple.contains("metadata") && tuple.getValueByField("metadata") instanceof Metadata;
        String canonicalUrl = hasMetadata ? valueForURL(tuple) : url;

        Map<String, Object> upsertPayload = createUpsertPayload(url, canonicalUrl, textContent, contentHash, metadata);
        String json = objectMapper.writeValueAsString(upsertPayload);

        dispatcher.dispatch(url, ACTION_UPSERT, json);
        collector.emit(Constants.StatusStreamName, tuple, new Values(url, metadata, Status.FETCHED));
        collector.ack(tuple);
        log.info("Dispatched OIS UPSERT document for URL: {} (hash={})", url, contentHash);
    }

    private Metadata getMetadataFromTuple(Tuple tuple) {
        if (!tuple.contains("metadata")) {
            return new Metadata();
        }
        Object obj = tuple.getValueByField("metadata");
        if (obj instanceof Metadata md) {
            return md;
        }
        return new Metadata();
    }

    /**
     * Builds the OIS DELETE payload for {@code url}. {@code status} is the StormCrawler status
     * recorded as {@code metadata.stormcrawler.status}. {@code httpStatus} is the status code of
     * the last fetch; when it is {@code null} the payload has no {@code http.status}.
     */
    public Map<String, Object> createTombstonePayload(String url, String status, String httpStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", url);
        payload.put("action", ACTION_DELETE);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "stormcrawler");
        source.put("instance", instanceId);
        source.put("connectorVersion", "1.0.0");
        payload.put("source", source);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("stormcrawler.status", status);
        if (httpStatus != null) {
            meta.put("http.status", httpStatus);
        }
        meta.put("deletedAt", Instant.now().toString());
        payload.put("metadata", meta);

        return payload;
    }

    /**
     * Builds the OIS UPSERT payload. The id is the fetched URL, so that a later deletion of the
     * same URL matches it; the canonical URL only goes in the metadata.
     */
    public Map<String, Object> createUpsertPayload(String url, String canonicalUrl, String textContent, String contentHash, Metadata metadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", url);
        payload.put("action", ACTION_UPSERT);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "stormcrawler");
        source.put("instance", instanceId);
        source.put("connectorVersion", "1.0.0");
        payload.put("source", source);

        String mimeType = "text/html";
        if (metadata != null && metadata.getFirstValue("parse.Content-Type") != null) {
            mimeType = metadata.getFirstValue("parse.Content-Type");
        } else if (metadata != null && metadata.getFirstValue("http.headers.content-type") != null) {
            mimeType = metadata.getFirstValue("http.headers.content-type");
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("mimeType", mimeType);
        content.put("text", textContent);
        payload.put("content", content);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("contentHash", contentHash);
        meta.put("crawledAt", Instant.now().toString());

        // keys from indexer.md.mapping; OIS metadata values such as title are strings, so keep the first value
        if (metadata != null) {
            filterMetadata(metadata).forEach((key, values) -> {
                if (values.length > 0 && values[0] != null) {
                    meta.put(key, values[0]);
                }
            });
        }

        if (!meta.containsKey("canonical.url")) {
            meta.put("canonical.url", canonicalUrl);
        }
        if (!meta.containsKey("title")) {
            meta.put("title", url);
        }

        payload.put("metadata", meta);

        Map<String, Object> security = new LinkedHashMap<>();
        security.put("inheritanceEnabled", false);
        List<Map<String, Object>> permissions = new ArrayList<>();
        permissions.add(Map.of("identity", defaultRole, "identityType", "role", "access", "read"));
        security.put("permissions", permissions);
        payload.put("security", security);

        return payload;
    }

    public static String computeHash(byte[] content, String algorithm) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hash = digest.digest(content);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Hash calculation error", e);
        }
    }

    @Override
    public void cleanup() {
        if (dispatcher != null) {
            try {
                dispatcher.close();
            } catch (Exception e) {
                log.warn("Error closing dispatcher: {}", e.getMessage());
            }
        }
    }

    // --- Fluent Builder / Configuration Setters for Topology Setup ---

    public OpenCrawlingBolt withTargetEndpoint(String endpoint) {
        this.targetEndpoint = endpoint;
        return this;
    }

    public OpenCrawlingBolt withTransportMode(String mode) {
        this.transportMode = mode;
        return this;
    }

    public OpenCrawlingBolt withEmitDeletions(boolean emit) {
        this.emitDeletions = emit;
        return this;
    }

    public OpenCrawlingBolt withInstanceId(String instanceId) {
        this.instanceId = instanceId;
        return this;
    }

    public OpenCrawlingBolt withDispatcher(PayloadDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        return this;
    }

    public PayloadDispatcher getDispatcher() {
        return dispatcher;
    }
}
