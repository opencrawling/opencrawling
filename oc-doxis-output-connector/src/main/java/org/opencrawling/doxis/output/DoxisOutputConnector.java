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
package org.opencrawling.doxis.output;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.doxis.client.DoxisApiException;
import org.opencrawling.doxis.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.DeleteMode;
import org.opencrawling.doxis.output.content.ContentLinkResolver;
import org.opencrawling.doxis.output.content.ContentLinkWriter;
import org.opencrawling.doxis.output.content.ContentLinkWriterLoader;
import org.opencrawling.doxis.output.content.ContentPlan;
import org.opencrawling.doxis.output.content.ContentPlanner;
import org.opencrawling.doxis.output.content.PrefixLocatorResolver;
import org.opencrawling.doxis.client.schema.DoxisSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Archives OpenCrawling documents into a Doxis 4 repository through the CSB REST API.
 *
 * <p>Metadata (document type, descriptors, ACLs, versions, deletes) always goes through the REST API; the binary follows the
 * {@link ContentPlan} chosen per document — streamed upload, registration of an already-placed binary via
 * {@code predefinedLocator} (no bytes transferred, for multi-terabyte or in-place content), or reference only.
 */
@Component
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "doxis")
public class DoxisOutputConnector implements OutputConnector {

    private static final Logger log = LoggerFactory.getLogger(DoxisOutputConnector.class);

    private final DoxisClient client;
    private final DoxisOutputProperties properties;
    private final DoxisSchema schema;
    private final ContentPlanner contentPlanner;
    private final DoxisDocumentMapper mapper;
    private final DoxisAclMapper aclMapper;
    private final ContentLinkWriter contentLinkWriter;
    private volatile String repositoryName;
    private volatile String contentLinkDocumentTypeId;
    private volatile String documentTypeId;
    private final DoxisRecordFiler filer;

    /**
     * No-arg constructor for {@link java.util.ServiceLoader} discovery; such an instance is not connected.
     */
    public DoxisOutputConnector() {
        this.client = null;
        this.properties = DoxisOutputProperties.defaults();
        this.schema = null;
        this.contentPlanner = null;
        this.mapper = null;
        this.aclMapper = null;
        this.contentLinkWriter = null;
        this.filer = null;
    }

    /**
     * Builds a self-contained connector from per-job connector configuration.
     */
    public DoxisOutputConnector(DoxisOutputProperties properties) {
        this(newClient(properties), properties);
    }

    private DoxisOutputConnector(DoxisClient client, DoxisOutputProperties properties) {
        this(client, properties, newContentLinkWriter(properties));
    }

    private DoxisOutputConnector(DoxisClient client, DoxisOutputProperties properties, ContentLinkWriter writer) {
        this(client, properties, new DoxisSchema(client), newContentPlanner(properties, writer), writer);
    }

    private DoxisOutputConnector(DoxisClient client, DoxisOutputProperties properties, DoxisSchema schema, ContentPlanner planner,
                                 ContentLinkWriter writer) {
        this(client, properties, schema, planner, new DoxisDocumentMapper(properties, schema), new DoxisAclMapper(schema), writer);
    }

    public DoxisOutputConnector(DoxisClient client, DoxisOutputProperties properties, DoxisSchema schema,
                                ContentPlanner contentPlanner, DoxisDocumentMapper mapper, DoxisAclMapper aclMapper) {
        this(client, properties, schema, contentPlanner, mapper, aclMapper, null);
    }

    @Autowired
    public DoxisOutputConnector(DoxisClient client, DoxisOutputProperties properties, DoxisSchema schema,
                                ContentPlanner contentPlanner, DoxisDocumentMapper mapper, DoxisAclMapper aclMapper,
                                @Autowired(required = false) ContentLinkWriter contentLinkWriter) {
        this.client = client;
        this.properties = properties;
        this.schema = schema;
        this.contentPlanner = contentPlanner;
        this.mapper = mapper;
        this.aclMapper = aclMapper;
        this.contentLinkWriter = contentLinkWriter;
        this.filer = client != null ? new DoxisRecordFiler(properties, client, schema, mapper, aclMapper) : null;
    }

    /**
     * Planner wired with the content-link resolver; content links are only planned when a writer is available.
     */
    public static ContentPlanner newContentPlanner(DoxisOutputProperties properties, ContentLinkWriter writer) {
        return new ContentPlanner(properties.content(), new PrefixLocatorResolver(properties.locator()),
                new ContentLinkResolver(properties.contentLink()), writer != null);
    }

    /**
     * Loads the optional content-link writer from {@code content-link.client-lib-dir}; {@code null} when not configured.
     */
    public static ContentLinkWriter newContentLinkWriter(DoxisOutputProperties properties) {
        DoxisOutputProperties.ContentLink config = properties.contentLink();
        if (config.clientLibDir() == null) {
            return null;
        }
        URI base = URI.create(properties.baseUrl());
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("host", config.csbHost() != null ? config.csbHost() : base.getHost());
        settings.put("port", String.valueOf(config.csbPort() > 0 ? config.csbPort() : (base.getPort() > 0 ? base.getPort() : 8080)));
        settings.put("customer", properties.customerName());
        settings.put("username", properties.username());
        settings.put("password", properties.password());
        try {
            return ContentLinkWriterLoader.load(Path.of(config.clientLibDir()), settings);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load the Doxis content-link writer: " + e.getMessage(), e);
        }
    }

    public static DoxisClient newClient(DoxisOutputProperties properties) {
        return new DoxisClient(properties.baseUrl(), properties.customerName(), properties.username(), properties.password(),
                properties.role(), DoxisConstants.DEFAULT_CLIENT_ID, Duration.ofSeconds(properties.timeoutSeconds()),
                properties.maxRetries());
    }

    @Override
    public String getName() {
        return "DoxisOutputConnector";
    }

    @Override
    public void connect() throws Exception {
        if (client != null) {
            initialize();
        }
    }

    @Override
    public void disconnect() throws Exception {
        if (contentLinkWriter != null) {
            contentLinkWriter.close();
        }
        if (client != null) {
            client.close();
        }
    }

    /**
     * Logs in, resolves the repository short name (used in CQL) and the document type, and checks the external-id descriptor.
     */
    public void initialize() throws IOException, InterruptedException {
        if (repositoryName != null && documentTypeId != null) {
            return;
        }
        if (properties.repository() == null || properties.repository().isBlank()) {
            throw new IllegalStateException("spring.opencrawling.output.doxis.repository is not configured");
        }
        JsonNode repository = client.getRepository(properties.repository());
        String typeId = schema.documentTypeId(properties.documentType());
        schema.attribute(properties.externalIdAttribute());
        // CQL needs the repository short name: full names may contain dots (e.g. "de.ser.doxis4.sp.common.templates"),
        // which the CQL parser rejects (INSTANCE0107).
        String shortName = repository.path("shortName").asText("");
        repositoryName = !shortName.isBlank() ? shortName : repository.path("name").asText(properties.repository());
        String linkType = properties.contentLink().documentType();
        contentLinkDocumentTypeId = linkType != null ? schema.documentTypeId(linkType) : typeId;
        documentTypeId = typeId;
        log.info("Doxis output ready: repository '{}', document type '{}' ({}), external id descriptor '{}', content strategy {}.",
                repositoryName, properties.documentType(), typeId, properties.externalIdAttribute(), properties.content().strategy());
    }

    @Override
    public Mono<Void> send(RepositoryDocument document) {
        return Mono.fromRunnable(() -> {
            if (client == null) {
                throw new IllegalStateException("DoxisOutputConnector is not configured (no DoxisClient)");
            }
            try {
                initialize();
                if (document.action() == DocumentAction.DELETE) {
                    delete(document);
                } else {
                    upsert(document);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while archiving document into Doxis: " + document.id(), e);
            } catch (Exception e) {
                log.error("Error archiving document {} into Doxis: {}", document.id(), e.getMessage(), e);
                throw new RuntimeException("Failed to archive document into Doxis: " + document.id(), e);
            }
        });
    }

    private void upsert(RepositoryDocument document) throws IOException, InterruptedException {
        ContentPlan plan = contentPlanner.plan(document);
        plan = withKnownMimeType(plan);
        List<String> existing = client.searchDocumentIds(mapper.lookupStatement(repositoryName, document.id()), false);

        if (existing.isEmpty()) {
            Optional<DoxisRecordFiler.Target> eFile = filer.target(document);
            boolean fileByRelationship = eFile.isPresent() && plan.strategy() != ContentStrategy.CONTENT_LINK
                    && properties.filing().method() == DoxisOutputProperties.FilingMethod.RELATIONSHIP;
            String documentId;
            if (plan.strategy() == ContentStrategy.CONTENT_LINK) {
                documentId = createLinkedDocument(document, plan);
            } else {
                Map<String, Object> params = mapper.documentParams(document, plan, documentTypeId);
                JsonNode created;
                try {
                    created = client.createDocument(properties.repository(), params,
                            fileByRelationship ? filer.relationshipParams(eFile.get(), documentTypeId) : null, plan.body());
                } catch (IOException e) {
                    if (fileByRelationship) {
                        filer.evictIfStale(eFile.get().recordId(), e);
                    }
                    throw e;
                }
                documentId = created.path("uuid").asText();
            }
            try {
                if (eFile.isPresent() && !fileByRelationship) {
                    client.setDocumentPrimaryParent(properties.repository(), documentId, eFile.get().recordId());
                }
                if (properties.security().documentAcls()) {
                    String typeLabel = plan.strategy() == ContentStrategy.CONTENT_LINK && properties.contentLink().documentType() != null
                            ? properties.contentLink().documentType() : properties.documentType();
                    applyPermissions(documentId, document, typeLabel);
                }
                verify(documentId, plan);
            } catch (IOException | RuntimeException e) {
                // Do not leave an empty, unfiled or unprotected document behind: remove what this call just created.
                eFile.ifPresent(target -> filer.evictIfStale(target.recordId(), e));
                log.warn("Rolling back Doxis document {} for {}: {}", documentId, document.id(), e.getMessage());
                try {
                    client.deleteDocumentPhysically(properties.repository(), documentId);
                } catch (IOException rollback) {
                    log.error("Rollback of Doxis document {} failed: {}", documentId, rollback.getMessage());
                }
                throw e;
            }
            log.info("Archived document {} into Doxis repository '{}' as {} (content: {}{}).",
                    document.id(), repositoryName, documentId, describe(plan),
                    eFile.map(t -> "; e-file " + t.recordId() + (t.createdNow() ? " (new)" : "")).orElse(""));
            return;
        }

        String documentId = existing.getFirst();
        if (existing.size() > 1) {
            log.warn("External id {} matches {} Doxis documents {}; updating {}.", document.id(), existing.size(), existing, documentId);
        }
        switch (properties.conflictResolution()) {
            case SKIP -> {
                discard(plan);
                log.info("Document {} already archived as {}; conflict-resolution SKIP.", document.id(), documentId);
            }
            case UPDATE_METADATA -> {
                discard(plan);
                String versionNr = currentVersionNumber(documentId);
                client.updateAttributes(properties.repository(), documentId, versionNr, mapper.attributes(document, plan));
                syncPermissions(documentId, document);
                log.info("Updated descriptors of Doxis document {} (version {}) for {}.", documentId, versionNr, document.id());
            }
            case NEW_VERSION -> {
                if (plan.strategy() == ContentStrategy.CONTENT_LINK) {
                    // Content-link versions cannot be added through REST; keep the link, refresh the descriptors.
                    String versionNr = currentVersionNumber(documentId);
                    client.updateAttributes(properties.repository(), documentId, versionNr, mapper.attributes(document, plan));
                    syncPermissions(documentId, document);
                    log.info("Updated descriptors of linked Doxis document {} for {} (content link unchanged: {}).",
                            documentId, document.id(), plan.contentLink().link());
                    return;
                }
                if (unchanged(documentId, document, plan)) {
                    discard(plan);
                    log.info("Document {} is unchanged since Doxis document {} was archived; no new version.", document.id(), documentId);
                    return;
                }
                client.addVersion(properties.repository(), documentId, mapper.versionParams(document, plan), plan.body());
                verify(documentId, plan);
                syncPermissions(documentId, document);
                log.info("Added a new version to Doxis document {} for {} (content: {}).", documentId, document.id(), describe(plan));
            }
        }
    }

    /**
     * Creates the document through the content-link writer (Doxis Java API): descriptors and an external UNC/URL link, no bytes.
     */
    private String createLinkedDocument(RepositoryDocument document, ContentPlan plan) throws IOException, InterruptedException {
        if (contentLinkWriter == null) {
            throw new IllegalStateException("CONTENT_LINK planned without a content-link writer");
        }
        List<ContentLinkWriter.Descriptor> descriptors = new ArrayList<>();
        for (Map<String, Object> attribute : mapper.attributes(document, plan)) {
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) attribute.get("values");
            descriptors.add(new ContentLinkWriter.Descriptor((String) attribute.get("attributeDefinitionUUID"),
                    (String) attribute.get("attributeDataType"), values));
        }
        try {
            return contentLinkWriter.createLinkedDocument(new ContentLinkWriter.Request(repositoryName, contentLinkDocumentTypeId,
                    descriptors, plan.contentLink().type(), plan.contentLink().link()));
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Content-link creation failed for " + document.id() + ": " + e.getMessage(), e);
        }
    }

    /**
     * True when {@code content.change-marker-attribute} is configured and the current version carries the same marker.
     */
    private boolean unchanged(String documentId, RepositoryDocument document, ContentPlan plan) throws IOException, InterruptedException {
        String markerAttribute = properties.content().changeMarkerAttribute();
        if (markerAttribute == null) {
            return false;
        }
        String attributeId = schema.attribute(markerAttribute).uuid();
        JsonNode version = currentVersion(documentId);
        if (version == null) {
            return false;
        }
        String expected = DoxisDocumentMapper.changeMarker(document, plan);
        for (JsonNode attribute : version.path("attributes")) {
            if (attributeId.equals(attribute.path("attributeDefinitionUUID").asText())) {
                return expected.equals(attribute.path("values").path(0).asText(null));
            }
        }
        return false;
    }

    /**
     * Additive ACL sync for documents that already exist: source permissions missing on the document are added; existing
     * entries (including ones set by Doxis administrators) are never removed. Best effort — failures are logged.
     */
    private void syncPermissions(String documentId, RepositoryDocument document) throws InterruptedException {
        try {
            filer.syncExisting(document);
        } catch (IOException e) {
            log.warn("E-file ACL sync for {} failed: {}", document.id(), e.getMessage());
        }
        if (!properties.security().documentAcls()) {
            return;
        }
        try {
            List<Map<String, Object>> desired = aclMapper.aces(document.security());
            Set<String> desiredKeys = new HashSet<>();
            for (Map<String, Object> ace : desired) {
                desiredKeys.add(ace.get("organizationalElementId") + "|" + ace.get("permission") + "|" + ace.get("authorizationVariant"));
            }
            Set<String> existing = new HashSet<>();
            List<JsonNode> current = client.getPermissions(properties.repository(), documentId);
            for (JsonNode ace : current) {
                existing.add(ace.path("organizationalElementId").asText() + "|" + ace.path("permissionName").asText()
                        + "|" + ace.path("authorizationVariant").asText());
            }
            List<Map<String, Object>> missing = new ArrayList<>();
            for (Map<String, Object> ace : desired) {
                if (!existing.contains(ace.get("organizationalElementId") + "|" + ace.get("permission") + "|"
                        + ace.get("authorizationVariant"))) {
                    missing.add(ace);
                }
            }
            if (!missing.isEmpty()) {
                client.addPermissions(properties.repository(), documentId, missing);
                log.info("Added {} missing permission(s) to Doxis document {} for {}.", missing.size(), documentId, document.id());
            }
            if (properties.security().removeStale()) {
                int removed = 0;
                for (JsonNode ace : current) {
                    String permission = ace.path("permissionName").asText();
                    String key = ace.path("organizationalElementId").asText() + "|" + permission + "|" + ace.path("authorizationVariant").asText();
                    if (DoxisAclMapper.MANAGED_DOCUMENT_PERMISSIONS.contains(permission) && !desiredKeys.contains(key)) {
                        client.deleteDocumentPermission(properties.repository(), documentId, permission,
                                ace.path("organizationalElementId").asText(), ace.path("authorizationVariant").asText());
                        removed++;
                    }
                }
                if (removed > 0) {
                    log.info("Removed {} permission(s) of Doxis document {} that no longer exist at the source ({}).",
                            removed, documentId, document.id());
                }
            }
        } catch (DoxisApiException e) {
            if ("SECU0050".equals(e.getErrorCode())) {
                log.debug("Document type does not allow per-document permissions; ACL sync skipped for {}.", documentId);
            } else {
                log.warn("ACL sync for Doxis document {} failed: {}", documentId, e.getMessage());
            }
        } catch (IOException e) {
            log.warn("ACL sync for Doxis document {} failed: {}", documentId, e.getMessage());
        }
    }

    private void applyPermissions(String documentId, RepositoryDocument document, String typeLabel) throws IOException, InterruptedException {
        try {
            client.addPermissions(properties.repository(), documentId, aclMapper.aces(document.security()));
        } catch (DoxisApiException e) {
            if (!"SECU0050".equals(e.getErrorCode())) {
                throw e;
            }
            if (properties.security().strict()) {
                throw new IOException("Document type '" + typeLabel + "' does not allow per-document permissions (security.strict)", e);
            }
            // The document type's security object type does not allow instance-level rights: the type's ACL applies.
            log.warn("Document type '{}' does not allow per-document permissions; source ACLs of {} were not applied.",
                    typeLabel, document.id());
            return;
        }
        List<String> unresolved = aclMapper.unresolvedIdentities(document.security());
        if (properties.security().strict() && !unresolved.isEmpty()) {
            throw new IOException("Identities " + unresolved + " of " + document.id() + " have no Doxis user or group (security.strict)");
        }
    }

    private void delete(RepositoryDocument document) throws IOException, InterruptedException {
        boolean physical = properties.deleteMode() == DeleteMode.PHYSICAL;
        List<String> ids = client.searchDocumentIds(mapper.lookupStatement(repositoryName, document.id()), physical);
        for (String id : ids) {
            if (physical) {
                client.deleteDocumentPhysically(properties.repository(), id);
            } else {
                client.removeDocumentLogically(properties.repository(), id);
            }
        }
        log.info("Processed DELETE tombstone for {}: {} {} Doxis document(s).", document.id(),
                physical ? "physically deleted" : "logically removed", ids.size());
    }

    /**
     * Reads back the current version's content object and checks length/hash against what was registered or uploaded.
     * This is how in-place registrations are confirmed without touching the binary.
     */
    private void verify(String documentId, ContentPlan plan) throws IOException, InterruptedException {
        if (!properties.content().verify() || plan.strategy() == ContentStrategy.REFERENCE_ONLY) {
            return;
        }
        JsonNode contentObject = currentContentObject(documentId);
        if (plan.strategy() == ContentStrategy.CONTENT_LINK) {
            // Linked content has no stored bytes (length 0); REST 14.4.x shows a UNC link as the content object's file name.
            if (contentObject == null) {
                throw new IOException("Doxis document " + documentId + " has no content object after a CONTENT_LINK write");
            }
            String fileName = contentObject.path("fullFilename").asText(null);
            if (plan.contentLink().type() == ContentLinkWriter.LinkType.UNC && fileName != null
                    && !fileName.equalsIgnoreCase(plan.contentLink().link())) {
                throw new IOException("Doxis document " + documentId + " links to '" + fileName + "', expected '"
                        + plan.contentLink().link() + "'");
            }
            return;
        }
        if (contentObject == null) {
            // e.g. CSB silently ignores a predefinedLocator sent without content and stores the version as NO_CONTENT
            throw new IOException("Doxis document " + documentId + " has no content object after a " + plan.strategy()
                    + " write (content status NO_CONTENT)");
        }
        if (plan.length() != null && contentObject.has("length") && contentObject.path("length").asLong() != plan.length()) {
            throw new IOException("Doxis document " + documentId + " content length " + contentObject.path("length").asLong()
                    + " does not match the source (" + plan.length() + " bytes)");
        }
        String hash = contentObject.path("hashValue").asText(null);
        if (plan.sha256() != null && hash != null && !hash.isBlank()
                && DoxisConstants.HASH_ALGORITHM_SHA256.equalsIgnoreCase(contentObject.path("hashAlgorithm").asText(""))
                && !hash.equalsIgnoreCase(plan.sha256())) {
            throw new IOException("Doxis document " + documentId + " SHA-256 " + hash + " does not match the source " + plan.sha256());
        }
        log.debug("Verified content object {} of Doxis document {}.", contentObject.path("uuid").asText(), documentId);
    }

    private JsonNode currentContentObject(String documentId) throws IOException, InterruptedException {
        JsonNode version = currentVersion(documentId);
        if (version == null) {
            return null;
        }
        JsonNode fallback = null;
        for (JsonNode representation : version.path("representations")) {
            for (JsonNode contentObject : representation.path("contentObjects")) {
                if (representation.path("defaultRepresentation").asBoolean(false)) {
                    return contentObject;
                }
                if (fallback == null) {
                    fallback = contentObject;
                }
            }
        }
        return fallback;
    }

    private String currentVersionNumber(String documentId) throws IOException, InterruptedException {
        JsonNode version = currentVersion(documentId);
        if (version == null) {
            throw new IOException("Doxis document " + documentId + " has no versions");
        }
        return version.path("versionNumber").asText();
    }

    private JsonNode currentVersion(String documentId) throws IOException, InterruptedException {
        List<JsonNode> versions = client.getVersions(properties.repository(), documentId);
        JsonNode last = null;
        for (JsonNode version : versions) {
            if (version.path("currentVersion").asBoolean(false)) {
                return version;
            }
            last = version;
        }
        return last;
    }

    private ContentPlan withKnownMimeType(ContentPlan plan) throws IOException, InterruptedException {
        String mimeType = schema.allowedMimeType(properties.documentType(), schema.knownMimeType(plan.mimeType()));
        if (mimeType.equals(plan.mimeType())) {
            return plan;
        }
        return plan.withMimeType(mimeType);
    }

    private static void discard(ContentPlan plan) {
        if (plan.body() != null && !plan.body().reopenable()) {
            try (InputStream ignored = plan.body().stream().get()) {
                // close the single-use source stream
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    private static String describe(ContentPlan plan) {
        return switch (plan.strategy()) {
            case UPLOAD -> "uploaded" + (plan.length() != null ? " " + plan.length() + " bytes" : "");
            case PREDEFINED_LOCATOR -> "registered in place at locator '" + plan.locator() + "'";
            case REFERENCE_ONLY -> "reference only (" + plan.sourceUri() + ")";
            case CONTENT_LINK -> "linked in place (" + plan.contentLink().type() + " " + plan.contentLink().link() + ")";
            case AUTO -> "auto";
        };
    }
}
