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
    @DefaultValue Security security,
    @DefaultValue ContentLink contentLink,
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
        /**
         * Create the document with an external content link (UNC path or URL) via the Doxis Java API: zero-copy, the binary
         * stays where it is and is never stored by Doxis. Requires {@code content-link.client-lib-dir}.
         */
        CONTENT_LINK,
        /**
         * Per document: locator if one resolves (experimental), else upload if small enough, else a content link if one resolves
         * and a content-link writer is configured, else {@link Content#fallback()}.
         */
        AUTO
    }

    /**
     * @param changeMarkerAttribute optional descriptor that stores {@code <lastModified>|<length>} of the source; when set, a
     *                              re-crawl whose marker is unchanged does not create a new version
     */
    public record Content(
        @DefaultValue("AUTO") ContentStrategy strategy,
        @DefaultValue("2147483648") long uploadMaxBytes,
        @DefaultValue("REFERENCE_ONLY") ContentStrategy fallback,
        @DefaultValue("true") boolean verify,
        String changeMarkerAttribute
    ) {
        public Content {
            if (strategy == null) strategy = ContentStrategy.AUTO;
            if (uploadMaxBytes <= 0) uploadMaxBytes = DoxisConstants.DEFAULT_UPLOAD_MAX_BYTES;
            if (fallback == null || fallback == ContentStrategy.AUTO) fallback = ContentStrategy.REFERENCE_ONLY;
            if (changeMarkerAttribute != null && changeMarkerAttribute.isBlank()) changeMarkerAttribute = null;
        }

        public static Content defaults() {
            return new Content(ContentStrategy.AUTO, DoxisConstants.DEFAULT_UPLOAD_MAX_BYTES, ContentStrategy.REFERENCE_ONLY, true, null);
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

    /** Which e-file (record) a new document is filed into. */
    public enum FilingMode {
        /** No filing. */
        NONE,
        /** Always {@code filing.record-id}. */
        FIXED,
        /** The record UUID given in the document metadata ({@code filing.record-id-metadata-key}). */
        METADATA,
        /** One e-file per source folder (the parent of the document URI), found by key or auto-created. */
        SOURCE_FOLDER,
        /** One e-file per metadata value ({@code filing.record-key-metadata-key}, e.g. a customer id), found or auto-created. */
        KEY_METADATA
    }

    /** How a document is attached to its e-file. */
    public enum FilingMethod {
        /** {@code PUT …/documents/{uuid}/primaryParent} after creation — works for uploads and content links. */
        PRIMARY_PARENT,
        /** {@code relationshipParams} in the REST create (uploads only; supports a folder node). */
        RELATIONSHIP
    }

    /**
     * Files new documents into records (e-files / dossiers). With {@code SOURCE_FOLDER} / {@code KEY_METADATA} the e-file is
     * looked up by {@code record-key-attribute} in {@code record-class} and, with {@code auto-create}, created when missing
     * (title in {@code record-title-attribute}). Permissions set on the e-file apply to everything inside when the document
     * class has "Primary parent objects → Pass down permissions" enabled — see {@link Security}.
     */
    public record Filing(
        FilingMode mode,
        String recordId,
        String recordRepository,
        String folderNodeId,
        @DefaultValue("doxisRecordId") String recordIdMetadataKey,
        @DefaultValue("doxisFolderNodeId") String folderNodeMetadataKey,
        String recordKeyMetadataKey,
        String recordClass,
        @DefaultValue("ObjectNumberExternal") String recordKeyAttribute,
        @DefaultValue("ObjectName") String recordTitleAttribute,
        @DefaultValue("true") boolean autoCreate,
        @DefaultValue("PRIMARY_PARENT") FilingMethod method
    ) {
        public Filing {
            if (recordId != null && recordId.isBlank()) recordId = null;
            if (recordRepository != null && recordRepository.isBlank()) recordRepository = null;
            if (folderNodeId != null && folderNodeId.isBlank()) folderNodeId = null;
            if (recordIdMetadataKey == null || recordIdMetadataKey.isBlank()) recordIdMetadataKey = "doxisRecordId";
            if (folderNodeMetadataKey == null || folderNodeMetadataKey.isBlank()) folderNodeMetadataKey = "doxisFolderNodeId";
            if (recordKeyMetadataKey != null && recordKeyMetadataKey.isBlank()) recordKeyMetadataKey = null;
            if (recordClass != null && recordClass.isBlank()) recordClass = null;
            if (recordKeyAttribute == null || recordKeyAttribute.isBlank()) recordKeyAttribute = "ObjectNumberExternal";
            if (recordTitleAttribute == null || recordTitleAttribute.isBlank()) recordTitleAttribute = "ObjectName";
            if (method == null) method = FilingMethod.PRIMARY_PARENT;
            if (mode == null) {
                // backward compatible: a configured fixed record id implies FIXED, otherwise metadata-driven filing
                mode = recordId != null ? FilingMode.FIXED : FilingMode.METADATA;
            }
        }

        public static Filing defaults() {
            return new Filing(FilingMode.METADATA, null, null, null, null, null, null, null, null, null, true, FilingMethod.PRIMARY_PARENT);
        }
    }

    /** Where OIS permissions are applied. */
    public enum SecurityMode {
        /** Permissions are left to the document class / e-file configuration in Doxis. */
        NONE,
        /** Per-document ACEs (needs instance rights on the document class). */
        DOCUMENT,
        /** ACEs on the e-file; documents inherit them ("Pass down permissions"). Needs a filing mode and instance rights on the e-file class. */
        RECORD,
        /** Both. */
        DOCUMENT_AND_RECORD
    }

    /** How permissions of an existing e-file are maintained. */
    public enum RecordAclSync {
        /** Set once, when the connector creates the e-file. */
        CREATE_ONLY,
        /** Later documents add their missing ACEs to the e-file (never removes). */
        ADDITIVE
    }

    /**
     * @param strict      fail (and roll back) a new document when its permissions cannot be applied, instead of logging a warning
     * @param removeStale document mode: on re-crawl also remove the connector-managed permissions (view/update/version) that no
     *                    longer exist at the source
     */
    public record Security(
        @DefaultValue("DOCUMENT") SecurityMode mode,
        @DefaultValue("false") boolean strict,
        @DefaultValue("false") boolean removeStale,
        @DefaultValue("CREATE_ONLY") RecordAclSync recordAclSync
    ) {
        public Security {
            if (mode == null) mode = SecurityMode.DOCUMENT;
            if (recordAclSync == null) recordAclSync = RecordAclSync.CREATE_ONLY;
        }

        public static Security defaults() {
            return new Security(SecurityMode.DOCUMENT, false, false, RecordAclSync.CREATE_ONLY);
        }

        public boolean documentAcls() {
            return mode == SecurityMode.DOCUMENT || mode == SecurityMode.DOCUMENT_AND_RECORD;
        }

        public boolean recordAcls() {
            return mode == SecurityMode.RECORD || mode == SecurityMode.DOCUMENT_AND_RECORD;
        }
    }

    /**
     * Zero-copy content links for large / in-place binaries. {@code client-lib-dir} holds the
     * {@code oc-doxis-blueline-content-link} jar plus the SER Doxis Java client jars; the CSB SOAP endpoint defaults to the host
     * and port of {@code base-url}.
     */
    public record ContentLink(
        String clientLibDir,
        String csbHost,
        @DefaultValue("0") int csbPort,
        String uriPrefix,
        String linkPrefix,
        @DefaultValue("UNC") org.opencrawling.doxis.output.content.ContentLinkWriter.LinkType linkType,
        @DefaultValue("doxisContentLink") String metadataKey,
        String documentType
    ) {
        public ContentLink {
            if (clientLibDir != null && clientLibDir.isBlank()) clientLibDir = null;
            if (csbHost != null && csbHost.isBlank()) csbHost = null;
            if (uriPrefix != null && uriPrefix.isBlank()) uriPrefix = null;
            if (linkPrefix == null) linkPrefix = "";
            if (linkType == null) linkType = org.opencrawling.doxis.output.content.ContentLinkWriter.LinkType.UNC;
            if (metadataKey == null || metadataKey.isBlank()) metadataKey = "doxisContentLink";
            if (documentType != null && documentType.isBlank()) documentType = null;
        }

        public static ContentLink defaults() {
            return new ContentLink(null, null, 0, null, "", null, null, null);
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
        if (security == null) security = Security.defaults();
        if (!applySecurityAcls) security = new Security(SecurityMode.NONE, security.strict(), security.removeStale(), security.recordAclSync());
        if (contentLink == null) contentLink = ContentLink.defaults();
        if (maxRetries < 0) maxRetries = DoxisConstants.DEFAULT_MAX_RETRIES;
        if (timeoutSeconds <= 0) timeoutSeconds = DoxisConstants.DEFAULT_TIMEOUT_SECONDS;
    }

    public static DoxisOutputProperties defaults() {
        return new DoxisOutputProperties(null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, null, null, null, null, null, DoxisConstants.DEFAULT_MAX_RETRIES, DoxisConstants.DEFAULT_TIMEOUT_SECONDS);
    }
}
