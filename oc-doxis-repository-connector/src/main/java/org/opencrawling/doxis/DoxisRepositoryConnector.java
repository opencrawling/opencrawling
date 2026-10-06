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
package org.opencrawling.doxis;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.DoxisRepositorySettings.CrawlMode;
import org.opencrawling.doxis.DoxisRepositorySettings.VersionMode;
import org.opencrawling.doxis.client.DoxisClient;
import org.opencrawling.doxis.client.schema.DoxisSchema;
import org.opencrawling.observability.concurrency.ObservabilityTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Doxis 4 CSB repository connector (issue #122): crawls a DMS repository through the CSB REST API.
 *
 * <p>Crawl modes:
 * <ul>
 *   <li>{@code SEARCH}: one CQL search ({@code SELECT * FROM <repository short name> [WHERE …]}) paged through
 *       {@code /documents/searchResults/{searchId}} and always closed;</li>
 *   <li>{@code FOLDER}: an e-file (Akte) and its folder nodes, optionally descending into child nodes and sub-e-files.</li>
 * </ul>
 * For every document the current version (or every version) is read with its descriptors and representations, the default
 * content object is downloaded to a temporary file (deleted when the stream is closed), and the document's ACEs — merged with
 * its e-file's when it has a primary parent — become the OIS security. Logically removed documents become DELETE tombstones.
 * Content-link documents are emitted without content; their link is in {@code doxis.contentLink}.
 *
 * <p>CSB counts technical sessions, so a scan uses exactly one session and always logs out when it ends, successfully or not.
 * Concurrency is bounded by {@code parallelism}.
 *
 * <p>As a Spring bean it is active with {@code spring.opencrawling.connector.type=doxis} and reads
 * {@code spring.opencrawling.connector.doxis.*}; jobs build it from their connector configuration instead.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.connector.type", havingValue = "doxis")
public class DoxisRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(DoxisRepositoryConnector.class);

    private final DoxisRepositorySettings settings;
    private DoxisClient client;
    private DoxisSchema schema;
    private String repositoryShortName;
    private Set<String> documentClassIds = Set.of();

    /** Used by SPI discovery only. */
    public DoxisRepositoryConnector() {
        this(DoxisRepositorySettings.fromConfiguration(Map.of()));
    }

    @Autowired
    public DoxisRepositoryConnector(Environment environment) {
        this(DoxisRepositorySettings.fromEnvironment(environment));
    }

    public DoxisRepositoryConnector(DoxisRepositorySettings settings) {
        this.settings = settings;
    }

    DoxisRepositoryConnector(DoxisRepositorySettings settings, DoxisClient client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public String getName() {
        return "DoxisRepositoryConnector";
    }

    public DoxisRepositorySettings settings() {
        return settings;
    }

    @Override
    public synchronized void connect() throws Exception {
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Doxis repository connector is not configured: " + String.join(" ", problems));
        }
        if (client == null) {
            client = new DoxisClient(settings.url(), settings.customerName(), settings.username(), settings.password(),
                    settings.role(), settings.clientId(), settings.timeout(), settings.maxRetries());
        }
        schema = new DoxisSchema(client);
        JsonNode repository = client.getRepository(settings.repositoryId());
        String shortName = repository.path("shortName").asText("");
        repositoryShortName = !shortName.isBlank() ? shortName : repository.path("name").asText(settings.repositoryId());
        Set<String> ids = new HashSet<>();
        for (String documentClass : settings.documentClasses()) {
            ids.add(schema.documentTypeId(documentClass).toLowerCase(Locale.ROOT));
        }
        documentClassIds = ids;
        log.info("Connected to Doxis repository '{}' at {} ({}).", repositoryShortName, client.getBaseUrl(), settings);
    }

    @Override
    public synchronized void disconnect() {
        if (client != null) {
            client.logout();
        }
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            try {
                connect();
                Crawl crawl = new Crawl();
                String path = basePath == null ? "" : basePath.strip();
                if (path.toLowerCase(Locale.ROOT).startsWith("select ")) {
                    crawlSearch(path, crawl, sink);
                } else if (settings.crawlMode() == CrawlMode.FOLDER) {
                    boolean override = !path.isEmpty() && !"default".equals(path) && !"/".equals(path);
                    crawlFolder(override ? path : settings.rootFolderId(), crawl, sink);
                } else {
                    crawlSearch(settings.cql(repositoryShortName), crawl, sink);
                }
                log.info("Doxis scan of '{}' finished: {} documents, {} tombstones, {} skipped, {} failed.", repositoryShortName,
                        crawl.documents, crawl.tombstones, crawl.skipped, crawl.failed);
                sink.complete();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("Doxis scan failed: {}", e.getMessage(), e);
                sink.error(e);
            } finally {
                // one session per scan; the licence counts technical sessions
                disconnect();
            }
        });
    }

    /** State of one scan. */
    private final class Crawl {
        final AtomicLong documents = new AtomicLong();
        final AtomicLong tombstones = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        final Set<String> visitedRecords = new HashSet<>();
        final Map<String, List<JsonNode>> recordAcls = new ConcurrentHashMap<>();
        final Map<String, Optional<String>> recordNames = new ConcurrentHashMap<>();
        private int accepted;

        /** The hits not seen before, cut to {@code maxDocuments}. */
        synchronized List<JsonNode> take(List<JsonNode> hits) {
            List<JsonNode> taken = new ArrayList<>();
            for (JsonNode hit : hits) {
                if (limitReached()) {
                    break;
                }
                String uuid = hit.path("uuid").asText("");
                if (!uuid.isEmpty() && seen.add(uuid)) {
                    taken.add(hit);
                    accepted++;
                }
            }
            return taken;
        }

        synchronized boolean limitReached() {
            return settings.maxDocuments() > 0 && accepted >= settings.maxDocuments();
        }
    }

    // ------------------------------------------------------------------ SEARCH mode

    private void crawlSearch(String cql, Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        String filter = settings.emitLogicalDeletes() ? "ANY_OBJECTS" : "NON_DELETED_OBJECTS";
        log.info("Doxis search: {} ({}; page size {}).", cql, filter, settings.batchSize());
        DoxisClient.SearchPage page = client.searchDocuments(cql, filter, settings.batchSize());
        String searchId = page.searchId();
        try {
            if (page.maxSearchResults() > 0 && page.totalHitCount() > page.maxSearchResults()) {
                log.warn("Doxis reports {} hits but serves at most {}; narrow search-query to crawl everything.",
                        page.totalHitCount(), page.maxSearchResults());
            }
            // CSB 14.4 pages with a 1-based offset (verified on DX4 2026-10-06: the first page reports start=1, offset=1
            // repeats it, offset=3 returns hits 3-4), so the next offset is start + hits on the page. The listing also ends
            // on the number of DISTINCT documents seen, so a repeated hit can neither loop nor hide the last one.
            int offset = 0;
            int nextOffset = 1;
            int total = page.totalHitCount();
            Set<String> listed = new HashSet<>();
            while (true) {
                List<JsonNode> hits = page.hits();
                int before = listed.size();
                hits.forEach(hit -> listed.add(hit.path("uuid").asText("")));
                process(crawl.take(hits), null, crawl, sink);
                offset += hits.size();
                nextOffset = (page.start() > 0 ? page.start() : nextOffset) + hits.size();
                // an empty page or a page with nothing new ends the listing, whatever the restriction mode says
                // (RESTRICTED_BY_SERVER only means the result is paged)
                if (hits.isEmpty() || listed.size() == before || searchId == null || (total >= 0 && listed.size() >= total)) {
                    break;
                }
                if (crawl.limitReached()) {
                    log.info("Doxis scan stopped at max-documents={}.", settings.maxDocuments());
                    return;
                }
                page = client.nextSearchResults(searchId, nextOffset, settings.batchSize());
            }
            if (total >= 0 && listed.size() < total) {
                log.warn("Doxis search delivered {} of {} hits (restriction mode {}).", listed.size(), total, page.restrictionMode());
            }
        } finally {
            client.closeSearch(searchId);
        }
    }

    // ------------------------------------------------------------------ FOLDER mode

    private void crawlFolder(String recordId, Crawl crawl, FluxSink<RepositoryDocument> sink) throws IOException, InterruptedException {
        if (recordId == null || crawl.limitReached() || !crawl.visitedRecords.add(recordId)) {
            return;
        }
        JsonNode record = client.getRecordWithNodes(settings.repositoryId(), recordId);
        crawl.recordNames.putIfAbsent(recordId, recordName(record));
        Optional<JsonNode> version = DoxisDocumentBuilder.currentVersion(record);
        if (version.isEmpty()) {
            log.warn("Doxis e-file {} has no versions; nothing to crawl.", recordId);
            return;
        }
        log.info("Doxis e-file crawl: {} ({}).", recordId, crawl.recordNames.get(recordId).orElse("unnamed"));
        for (JsonNode node : version.get().path("folderNodes")) {
            crawlNode(recordId, node, crawl, sink);
        }
    }

    private void crawlNode(String recordId, JsonNode node, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        String nodeId = node.path("uuid").asText(null);
        if (nodeId == null || crawl.limitReached()) {
            return;
        }
        JsonNode objects = client.getNodeReferencedObjects(settings.repositoryId(), recordId, nodeId);
        List<JsonNode> documents = new ArrayList<>();
        objects.path("searchHitsDocumentWsTO").forEach(documents::add);
        process(crawl.take(documents), recordId, crawl, sink);
        if (settings.includeSubfolders()) {
            for (JsonNode child : node.path("childrenFolderNodes")) {
                crawlNode(recordId, child, crawl, sink);
            }
            for (JsonNode subRecord : objects.path("searchHitsCompoundEntityWsTO")) {
                crawlFolder(subRecord.path("uuid").asText(null), crawl, sink);
            }
        }
    }

    // ------------------------------------------------------------------ documents

    @SuppressWarnings("preview")
    private void process(List<JsonNode> hits, String folderRecordId, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws InterruptedException {
        Semaphore permits = new Semaphore(settings.parallelism());
        try (var scope = StructuredTaskScope.open()) {
            for (JsonNode hit : hits) {
                permits.acquire();
                scope.fork(ObservabilityTask.observed(() -> {
                    try {
                        handle(hit, folderRecordId, crawl, sink);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        crawl.failed.incrementAndGet();
                        log.error("Doxis document {} could not be crawled: {}", hit.path("uuid").asText("?"), e.getMessage());
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            scope.join();
        }
    }

    private boolean classAllowed(JsonNode node) {
        if (documentClassIds.isEmpty()) {
            return true;
        }
        String type = node.path("documentTypeUUID").asText("").toLowerCase(Locale.ROOT);
        return type.isEmpty() || documentClassIds.contains(type);
    }

    private void handle(JsonNode hit, String folderRecordId, Crawl crawl, FluxSink<RepositoryDocument> sink)
            throws IOException, InterruptedException {
        String uuid = hit.path("uuid").asText();
        if (!classAllowed(hit)) {
            crawl.skipped.incrementAndGet();
            return;
        }
        String customer = settings.customerName();
        String baseId = DoxisDocumentBuilder.documentId(customer, repositoryShortName, uuid);
        if (hit.path("logicalDeleted").asBoolean(false)) {
            emitTombstones(uuid, baseId, crawl, sink);
            return;
        }

        JsonNode document = client.getDocumentWithVersions(settings.repositoryId(), uuid);
        if (documentClassIds.size() > 0 && !documentClassIds.contains(document.path("documentTypeUUID").asText("").toLowerCase(Locale.ROOT))) {
            crawl.skipped.incrementAndGet();
            return;
        }
        Optional<JsonNode> current = DoxisDocumentBuilder.currentVersion(document);
        if (current.isEmpty()) {
            log.warn("Doxis document {} has no versions; skipped.", uuid);
            crawl.skipped.incrementAndGet();
            return;
        }

        String parentId = Optional.ofNullable(DoxisDocumentBuilder.text(document, "primaryParentObjectUUID"))
                .filter(s -> !s.isBlank() && !"null".equals(s)).orElse(folderRecordId);
        SecurityConfig security = settings.includeAcls()
                ? new DoxisSecurityMapper(schema::principalById, settings.fallbackPrincipals())
                        .map(client.getPermissions(settings.repositoryId(), uuid), recordAcl(parentId, crawl))
                : SecurityConfig.createPublic();
        String documentClass = schema.documentTypeName(DoxisDocumentBuilder.text(document, "documentTypeUUID")).orElse(null);
        String createdBy = principalName(DoxisDocumentBuilder.text(document, "ownerId"));
        String parentName = parentId == null ? null : recordName(parentId, crawl);

        List<JsonNode> versions = new ArrayList<>();
        if (settings.versionMode() == VersionMode.ALL_VERSIONS) {
            document.path("versions").forEach(versions::add);
        } else {
            versions.add(current.get());
        }
        for (JsonNode version : versions) {
            boolean latest = version == current.get();
            String id = latest ? baseId
                    : DoxisDocumentBuilder.versionId(customer, repositoryShortName, uuid, version.path("versionNumber").asText());
            DoxisDocumentBuilder.Context context = new DoxisDocumentBuilder.Context(customer, repositoryShortName, documentClass,
                    createdBy, principalName(DoxisDocumentBuilder.text(version, "modificatorId")), parentId, parentName);
            DoxisDocumentBuilder.ContentRef content = DoxisDocumentBuilder.content(version).orElse(null);
            if (content == null && version.path("representations").isEmpty()) {
                // CSB 14.4 answers 200 without representations when the caller lacks read on the class
                log.warn("Doxis document {} version {} has no representations: no content, or the technical user lacks "
                        + "read rights on its class.", uuid, version.path("versionNumber").asText("?"));
            }
            InputStream stream = contentStream(uuid, content);
            RepositoryDocument built;
            try {
                built = DoxisDocumentBuilder.build(id, context, document, version, latest, content, settings.includeDescriptors(),
                        settings.descriptorPrefix(), uuidOrName -> schema.findAttribute(uuidOrName).map(DoxisSchema.Attribute::name),
                        stream, security);
            } catch (IOException | RuntimeException e) {
                if (stream != null) {
                    stream.close();
                }
                throw e;
            }
            emit(sink, built);
            crawl.documents.incrementAndGet();
        }
    }

    private void emitTombstones(String uuid, String baseId, Crawl crawl, FluxSink<RepositoryDocument> sink) {
        String customer = settings.customerName();
        emit(sink, DoxisDocumentBuilder.tombstone(baseId, customer, repositoryShortName, uuid));
        crawl.tombstones.incrementAndGet();
        if (settings.versionMode() != VersionMode.ALL_VERSIONS) {
            return;
        }
        try {
            JsonNode document = client.getDocumentWithVersions(settings.repositoryId(), uuid);
            JsonNode current = DoxisDocumentBuilder.currentVersion(document).orElse(null);
            for (JsonNode version : document.path("versions")) {
                if (version != current) {
                    emit(sink, DoxisDocumentBuilder.tombstone(DoxisDocumentBuilder.versionId(customer, repositoryShortName, uuid,
                            version.path("versionNumber").asText()), customer, repositoryShortName, uuid));
                    crawl.tombstones.incrementAndGet();
                }
            }
        } catch (Exception e) {
            log.warn("Versions of removed Doxis document {} are unreadable; only its current id is deleted: {}", uuid, e.getMessage());
        }
    }

    private InputStream contentStream(String uuid, DoxisDocumentBuilder.ContentRef content) throws IOException, InterruptedException {
        if (!settings.includeContentStream() || content == null || content.isLink() || content.contentObjectId() == null) {
            return null;
        }
        if (content.length() > settings.maxContentSizeBytes()) {
            log.info("Doxis document {}: content ({} bytes) exceeds max-content-size-bytes; metadata only.", uuid, content.length());
            return null;
        }
        Path file = Files.createTempFile("oc-doxis-", ".bin");
        try {
            client.downloadContentObject(settings.repositoryId(), uuid, content.versionNr(), content.representationId(),
                    content.contentObjectId(), file);
            return new TempFileInputStream(file);
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    private List<JsonNode> recordAcl(String recordId, Crawl crawl) throws IOException, InterruptedException {
        if (recordId == null) {
            return List.of();
        }
        List<JsonNode> cached = crawl.recordAcls.get(recordId);
        if (cached == null) {
            cached = client.getRecordPermissions(settings.repositoryId(), recordId);
            crawl.recordAcls.put(recordId, cached);
        }
        return cached;
    }

    private String recordName(String recordId, Crawl crawl) {
        return crawl.recordNames.computeIfAbsent(recordId, id -> {
            try {
                return recordName(client.getRecord(settings.repositoryId(), id));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                log.debug("Doxis e-file {} name unreadable: {}", id, e.getMessage());
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** The e-file's {@code name}, else its {@code ObjectName} descriptor. */
    private Optional<String> recordName(JsonNode record) throws IOException, InterruptedException {
        String name = DoxisDocumentBuilder.text(record, "name");
        if (name != null && !name.isBlank()) {
            return Optional.of(name);
        }
        Optional<JsonNode> version = DoxisDocumentBuilder.currentVersion(record);
        if (version.isPresent()) {
            for (JsonNode attribute : version.get().path("attributes")) {
                Optional<DoxisSchema.Attribute> definition = schema.findAttribute(attribute.path("attributeDefinitionUUID").asText(""));
                if (definition.isPresent() && "ObjectName".equals(definition.get().name()) && attribute.path("values").size() > 0) {
                    return Optional.of(attribute.path("values").get(0).asText());
                }
            }
        }
        return Optional.empty();
    }

    private String principalName(String organizationalElementId) throws IOException, InterruptedException {
        return schema.principalById(organizationalElementId).map(DoxisSchema.Principal::name).orElse(null);
    }

    private static void emit(FluxSink<RepositoryDocument> sink, RepositoryDocument document) {
        synchronized (sink) {
            sink.next(document);
        }
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        return new ConnectorSchema(List.of(
                new ConnectorSchema.SchemaField("doxis.documentId", "STRING", "Doxis document UUID"),
                new ConnectorSchema.SchemaField("doxis.repository", "STRING", "DMS repository short name"),
                new ConnectorSchema.SchemaField("doxis.documentClass", "STRING", "Document class"),
                new ConnectorSchema.SchemaField("doxis.version", "STRING", "Version number"),
                new ConnectorSchema.SchemaField("doxis.isLatestVersion", "BOOLEAN", "Whether this is the current version"),
                new ConnectorSchema.SchemaField("doxis.createdDate", "TIMESTAMP", "Creation timestamp"),
                new ConnectorSchema.SchemaField("doxis.createdBy", "STRING", "Owner"),
                new ConnectorSchema.SchemaField("doxis.modifiedDate", "TIMESTAMP", "Modification timestamp"),
                new ConnectorSchema.SchemaField("doxis.modifiedBy", "STRING", "Last modifier"),
                new ConnectorSchema.SchemaField("doxis.parentFolderId", "STRING", "UUID of the e-file the document is filed in"),
                new ConnectorSchema.SchemaField("doxis.parentFolderName", "STRING", "Name of that e-file"),
                new ConnectorSchema.SchemaField("doxis.fileName", "STRING", "Original file name"),
                new ConnectorSchema.SchemaField("doxis.fileSize", "LONG", "Binary size in bytes"),
                new ConnectorSchema.SchemaField("doxis.contentLink", "STRING", "External location of a content-link document"),
                new ConnectorSchema.SchemaField(settings.descriptorPrefix() + "ObjectName", "STRING", "Descriptor ObjectName (title)"),
                new ConnectorSchema.SchemaField(settings.descriptorPrefix() + "ObjectNumberExternal", "STRING", "Descriptor ObjectNumberExternal"),
                new ConnectorSchema.SchemaField(settings.descriptorPrefix() + "ObjectDate", "DATE", "Descriptor ObjectDate"),
                new ConnectorSchema.SchemaField(settings.descriptorPrefix() + "URL", "STRING", "Descriptor URL (source URI)")
        ));
    }
}
