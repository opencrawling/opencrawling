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
import org.opencrawling.doxis.output.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.DeleteMode;
import org.opencrawling.doxis.output.content.ContentPlan;
import org.opencrawling.doxis.output.content.ContentPlanner;
import org.opencrawling.doxis.output.content.PrefixLocatorResolver;
import org.opencrawling.doxis.output.schema.DoxisSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    private volatile String repositoryName;
    private volatile String documentTypeId;

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
    }

    /**
     * Builds a self-contained connector from per-job connector configuration.
     */
    public DoxisOutputConnector(DoxisOutputProperties properties) {
        this(newClient(properties), properties);
    }

    private DoxisOutputConnector(DoxisClient client, DoxisOutputProperties properties) {
        this(client, properties, new DoxisSchema(client),
                new ContentPlanner(properties.content(), new PrefixLocatorResolver(properties.locator())));
    }

    private DoxisOutputConnector(DoxisClient client, DoxisOutputProperties properties, DoxisSchema schema, ContentPlanner planner) {
        this(client, properties, schema, planner, new DoxisDocumentMapper(properties, schema), new DoxisAclMapper(schema));
    }

    @Autowired
    public DoxisOutputConnector(DoxisClient client, DoxisOutputProperties properties, DoxisSchema schema,
                                ContentPlanner contentPlanner, DoxisDocumentMapper mapper, DoxisAclMapper aclMapper) {
        this.client = client;
        this.properties = properties;
        this.schema = schema;
        this.contentPlanner = contentPlanner;
        this.mapper = mapper;
        this.aclMapper = aclMapper;
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
        if (client != null) {
            client.close();
        }
    }

    /**
     * Logs in, resolves the repository name (used in CQL) and the document type, and checks the external-id descriptor.
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
        repositoryName = repository.path("name").asText(properties.repository());
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
            Map<String, Object> params = mapper.documentParams(document, plan, documentTypeId);
            JsonNode created = client.createDocument(properties.repository(), params, plan.body());
            String documentId = created.path("uuid").asText();
            if (properties.applySecurityAcls()) {
                client.addPermissions(properties.repository(), documentId, aclMapper.aces(document.security()));
            }
            verify(documentId, plan);
            log.info("Archived document {} into Doxis repository '{}' as {} (content: {}).",
                    document.id(), repositoryName, documentId, describe(plan));
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
                log.info("Updated descriptors of Doxis document {} (version {}) for {}.", documentId, versionNr, document.id());
            }
            case NEW_VERSION -> {
                client.addVersion(properties.repository(), documentId, mapper.versionParams(document, plan), plan.body());
                verify(documentId, plan);
                log.info("Added a new version to Doxis document {} for {} (content: {}).", documentId, document.id(), describe(plan));
            }
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
        if (!properties.content().verify() || plan.strategy() == ContentStrategy.REFERENCE_ONLY
                || (plan.length() == null && plan.sha256() == null)) {
            return;
        }
        JsonNode contentObject = currentContentObject(documentId);
        if (contentObject == null) {
            log.warn("Could not read back the content object of Doxis document {} for verification.", documentId);
            return;
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
        String mimeType = schema.knownMimeType(plan.mimeType());
        if (mimeType.equals(plan.mimeType())) {
            return plan;
        }
        return new ContentPlan(plan.strategy(), plan.fileName(), mimeType, plan.length(), plan.sha256(), plan.locator(),
                plan.sourceUri(), plan.body());
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
            case AUTO -> "auto";
        };
    }
}
