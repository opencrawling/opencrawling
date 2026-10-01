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
package org.opencrawling.doxis.output.config;

import org.opencrawling.doxis.output.DoxisConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Configuration of the Doxis 4 output connector ({@code spring.opencrawling.output.doxis.*}).
 *
 * <p>The connector separates the <em>metadata plane</em> (document, descriptors, ACLs, versions — always written through the
 * CSB REST API) from the <em>content plane</em> (the binary), which is handled according to {@link Content#strategy()}.
 */
@ConfigurationProperties(prefix = "spring.opencrawling.output.doxis")
public record DoxisOutputProperties(
    @DefaultValue(DoxisConstants.DEFAULT_BASE_URL) String baseUrl,
    String customerName,
    String username,
    String password,
    String role,
    String repository,
    @DefaultValue(DoxisConstants.DEFAULT_DOCUMENT_TYPE) String documentType,
    @DefaultValue(DoxisConstants.DEFAULT_EXTERNAL_ID_ATTRIBUTE) String externalIdAttribute,
    @DefaultValue(DoxisConstants.DEFAULT_TITLE_ATTRIBUTE) String titleAttribute,
    String referenceAttribute,
    Map<String, String> attributeMapping,
    @DefaultValue("NEW_VERSION") ConflictResolution conflictResolution,
    @DefaultValue("LOGICAL") DeleteMode deleteMode,
    @DefaultValue("true") boolean applySecurityAcls,
    @DefaultValue Content content,
    @DefaultValue Locator locator,
    @DefaultValue Filing filing,
    @DefaultValue("3") int maxRetries,
    @DefaultValue("120") int timeoutSeconds
) {

    /** What to do when a document with the same external id already exists in the repository. */
    public enum ConflictResolution {
        /** Add a new document version carrying the new content (per content strategy) and descriptors. */
        NEW_VERSION,
        /** Only overwrite the descriptors of the current version; content is left untouched. */
        UPDATE_METADATA,
        /** Leave the existing document unchanged. */
        SKIP
    }

    /** How OIS DELETE tombstones are applied. */
    public enum DeleteMode {
        /** {@code POST …/remove}: reversible, binary is retained. Recommended for in-place content. */
        LOGICAL,
        /** {@code DELETE …}: irrevocable. May remove the binary from the data store. */
        PHYSICAL
    }

    /** How the binary of a document reaches Doxis. */
    public enum ContentStrategy {
        /** Stream the binary to Doxis in the create/version request (bounded by {@code upload-max-bytes}). */
        UPLOAD,
        /** Register a binary that already sits in the Doxis data store via {@code predefinedLocator}; no bytes are sent. */
        PREDEFINED_LOCATOR,
        /** Create the document without content; the source URI is recorded in {@code reference-attribute}. */
        REFERENCE_ONLY,
        /** Per document: locator if one resolves, else upload if small enough, else {@link Content#fallback()}. */
        AUTO
    }

    public record Content(
        @DefaultValue("AUTO") ContentStrategy strategy,
        @DefaultValue("2147483648") long uploadMaxBytes,
        @DefaultValue("REFERENCE_ONLY") ContentStrategy fallback,
        @DefaultValue("true") boolean verify
    ) {
        public Content {
            if (strategy == null) strategy = ContentStrategy.AUTO;
            if (uploadMaxBytes <= 0) uploadMaxBytes = DoxisConstants.DEFAULT_UPLOAD_MAX_BYTES;
            if (fallback == null || fallback == ContentStrategy.AUTO) fallback = ContentStrategy.REFERENCE_ONLY;
        }

        public static Content defaults() {
            return new Content(ContentStrategy.AUTO, DoxisConstants.DEFAULT_UPLOAD_MAX_BYTES, ContentStrategy.REFERENCE_ONLY, true);
        }
    }

    /**
     * How the {@code predefinedLocator} of a document is derived: an explicit metadata value wins; otherwise the document URI
     * is matched against {@code uri-prefix} and the remainder becomes the locator.
     */
    public record Locator(
        @DefaultValue(DoxisConstants.DEFAULT_LOCATOR_METADATA_KEY) String metadataKey,
        String uriPrefix,
        String prefix
    ) {
        public Locator {
            if (metadataKey == null || metadataKey.isBlank()) metadataKey = DoxisConstants.DEFAULT_LOCATOR_METADATA_KEY;
            if (prefix == null) prefix = "";
        }

        public static Locator defaults() {
            return new Locator(DoxisConstants.DEFAULT_LOCATOR_METADATA_KEY, null, "");
        }
    }

    /**
     * Files new documents into a record (e-file / dossier) via {@code relationshipParams}: a fixed {@code record-id}, or a
     * per-document record id from metadata ({@code record-id-metadata-key}), optionally into a folder node of that record.
     */
    public record Filing(
        String recordId,
        String recordRepository,
        String folderNodeId,
        @DefaultValue("doxisRecordId") String recordIdMetadataKey,
        @DefaultValue("doxisFolderNodeId") String folderNodeMetadataKey
    ) {
        public Filing {
            if (recordId != null && recordId.isBlank()) recordId = null;
            if (recordRepository != null && recordRepository.isBlank()) recordRepository = null;
            if (folderNodeId != null && folderNodeId.isBlank()) folderNodeId = null;
            if (recordIdMetadataKey == null || recordIdMetadataKey.isBlank()) recordIdMetadataKey = "doxisRecordId";
            if (folderNodeMetadataKey == null || folderNodeMetadataKey.isBlank()) folderNodeMetadataKey = "doxisFolderNodeId";
        }

        public static Filing defaults() {
            return new Filing(null, null, null, null, null);
        }
    }

    public DoxisOutputProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = DoxisConstants.DEFAULT_BASE_URL;
        if (documentType == null || documentType.isBlank()) documentType = DoxisConstants.DEFAULT_DOCUMENT_TYPE;
        if (externalIdAttribute == null || externalIdAttribute.isBlank()) externalIdAttribute = DoxisConstants.DEFAULT_EXTERNAL_ID_ATTRIBUTE;
        if (titleAttribute == null || titleAttribute.isBlank()) titleAttribute = DoxisConstants.DEFAULT_TITLE_ATTRIBUTE;
        if (referenceAttribute != null && referenceAttribute.isBlank()) referenceAttribute = null;
        if (role != null && role.isBlank()) role = null;
        attributeMapping = attributeMapping == null ? Map.of() : Map.copyOf(attributeMapping);
        if (conflictResolution == null) conflictResolution = ConflictResolution.NEW_VERSION;
        if (deleteMode == null) deleteMode = DeleteMode.LOGICAL;
        if (content == null) content = Content.defaults();
        if (locator == null) locator = Locator.defaults();
        if (filing == null) filing = Filing.defaults();
        if (maxRetries < 0) maxRetries = DoxisConstants.DEFAULT_MAX_RETRIES;
        if (timeoutSeconds <= 0) timeoutSeconds = DoxisConstants.DEFAULT_TIMEOUT_SECONDS;
    }

    public static DoxisOutputProperties defaults() {
        return new DoxisOutputProperties(null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, null, null, null, DoxisConstants.DEFAULT_MAX_RETRIES, DoxisConstants.DEFAULT_TIMEOUT_SECONDS);
    }
}
