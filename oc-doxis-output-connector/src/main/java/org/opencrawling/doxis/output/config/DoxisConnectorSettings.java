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
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ConflictResolution;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.DeleteMode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts the admin UI's per-connector JSON configuration ({@code doxis*} keys in {@code connectors.json}) into
 * {@link DoxisOutputProperties}. {@code doxisAttributeMapping} uses {@code metadataKey=AttributeName} pairs separated by
 * commas or new lines.
 */
public final class DoxisConnectorSettings {

    private DoxisConnectorSettings() {
    }

    public static DoxisOutputProperties fromConfiguration(Map<String, String> config) {
        Map<String, String> c = config != null ? config : Map.of();
        return new DoxisOutputProperties(
                c.get("doxisBaseUrl"),
                c.get("doxisCustomerName"),
                c.get("doxisUsername"),
                c.get("doxisPassword"),
                c.get("doxisRole"),
                c.get("doxisRepository"),
                c.get("doxisDocumentType"),
                c.get("doxisExternalIdAttribute"),
                c.get("doxisTitleAttribute"),
                c.get("doxisReferenceAttribute"),
                attributeMapping(c.get("doxisAttributeMapping")),
                enumValue(ConflictResolution.class, c.get("doxisConflictResolution"), ConflictResolution.NEW_VERSION),
                enumValue(DeleteMode.class, c.get("doxisDeleteMode"), DeleteMode.LOGICAL),
                bool(c.get("doxisApplySecurityAcls"), true),
                new DoxisOutputProperties.Content(
                        enumValue(ContentStrategy.class, c.get("doxisContentStrategy"), ContentStrategy.AUTO),
                        longValue(c.get("doxisUploadMaxBytes"), DoxisConstants.DEFAULT_UPLOAD_MAX_BYTES),
                        enumValue(ContentStrategy.class, c.get("doxisContentFallback"), ContentStrategy.REFERENCE_ONLY),
                        bool(c.get("doxisVerifyContent"), true),
                        c.get("doxisChangeMarkerAttribute")),
                new DoxisOutputProperties.Locator(
                        c.get("doxisLocatorMetadataKey"),
                        blankToNull(c.get("doxisLocatorUriPrefix")),
                        c.get("doxisLocatorPrefix")),
                new DoxisOutputProperties.Filing(
                        enumValue(DoxisOutputProperties.FilingMode.class, c.get("doxisFilingMode"), null),
                        c.get("doxisFilingRecordId"),
                        c.get("doxisFilingRecordRepository"),
                        c.get("doxisFilingFolderNodeId"),
                        c.get("doxisFilingRecordIdMetadataKey"),
                        c.get("doxisFilingFolderNodeMetadataKey"),
                        c.get("doxisFilingRecordKeyMetadataKey"),
                        c.get("doxisFilingRecordClass"),
                        c.get("doxisFilingRecordKeyAttribute"),
                        c.get("doxisFilingRecordTitleAttribute"),
                        bool(c.get("doxisFilingAutoCreate"), true),
                        enumValue(DoxisOutputProperties.FilingMethod.class, c.get("doxisFilingMethod"), DoxisOutputProperties.FilingMethod.PRIMARY_PARENT),
                        c.get("doxisFilingFolderNodeName")),
                new DoxisOutputProperties.Security(
                        enumValue(DoxisOutputProperties.SecurityMode.class, c.get("doxisSecurityMode"), DoxisOutputProperties.SecurityMode.DOCUMENT),
                        bool(c.get("doxisSecurityStrict"), false),
                        bool(c.get("doxisSecurityRemoveStale"), false),
                        enumValue(DoxisOutputProperties.RecordAclSync.class, c.get("doxisSecurityRecordAclSync"), DoxisOutputProperties.RecordAclSync.CREATE_ONLY),
                        bool(c.get("doxisSecurityGrantConnectorUser"), true)),
                new DoxisOutputProperties.ContentLink(
                        c.get("doxisContentLinkClientLibDir"),
                        c.get("doxisContentLinkCsbHost"),
                        (int) longValue(c.get("doxisContentLinkCsbPort"), 0),
                        c.get("doxisContentLinkUriPrefix"),
                        c.get("doxisContentLinkPrefix"),
                        enumValue(org.opencrawling.doxis.output.content.ContentLinkWriter.LinkType.class, c.get("doxisContentLinkType"),
                                org.opencrawling.doxis.output.content.ContentLinkWriter.LinkType.UNC),
                        c.get("doxisContentLinkMetadataKey"),
                        c.get("doxisContentLinkDocumentType")),
                (int) longValue(c.get("doxisMaxRetries"), DoxisConstants.DEFAULT_MAX_RETRIES),
                (int) longValue(c.get("doxisTimeoutSeconds"), DoxisConstants.DEFAULT_TIMEOUT_SECONDS));
    }

    static Map<String, String> attributeMapping(String value) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return mapping;
        }
        for (String pair : value.split("[,\\n]")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && eq < pair.length() - 1) {
                mapping.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
            }
        }
        return mapping;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static boolean bool(String value, boolean fallback) {
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.strip());
    }

    private static long longValue(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
