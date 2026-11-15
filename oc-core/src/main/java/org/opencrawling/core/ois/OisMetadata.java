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
package org.opencrawling.core.ois;

import org.opencrawling.core.reporting.ExecutionProvenance;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Open Ingestion Standard blocks that OpenCrawling adds to a document's metadata on its way through the pipeline:
 * where it came from ({@code ois:source}), who ingested it and how ({@code ois:provenance}), and what the
 * pre-embedding governance decided ({@code ois:governance}).
 *
 * <p>Document metadata is a map of string lists, so each block travels as flat keys, one per field, for example
 * {@code ois:provenance.jobId}. That keeps the fields filterable in the vector stores. The sidecar writers fold them
 * back into the nested objects the OIS document schema defines ({@link OisSidecar}).
 *
 * <p>Only OpenCrawling writes these keys. Values a source or a pushed document brings with it are removed
 * ({@link #withoutOisBlocks}) before the pipeline sets its own, and governance hooks cannot change them.
 */
public final class OisMetadata {

    public static final String SOURCE = "ois:source";
    public static final String PROVENANCE = "ois:provenance";
    public static final String GOVERNANCE = "ois:governance";

    public static final String SOURCE_TYPE = SOURCE + ".type";
    public static final String SOURCE_INSTANCE = SOURCE + ".instance";
    public static final String SOURCE_CONNECTOR_VERSION = SOURCE + ".connectorVersion";

    public static final String PROVENANCE_JOB_ID = PROVENANCE + ".jobId";
    public static final String PROVENANCE_TRIGGERED_BY = PROVENANCE + ".triggeredBy";
    public static final String PROVENANCE_ON_BEHALF_OF = PROVENANCE + ".onBehalfOf";
    public static final String PROVENANCE_ACTOR_AGENT = PROVENANCE + ".actorAgent";
    public static final String PROVENANCE_INGESTED_AT = PROVENANCE + ".ingestedAt";

    public static final String GOVERNANCE_DECISION = GOVERNANCE + ".decision";
    public static final String GOVERNANCE_REDACTED = GOVERNANCE + ".redacted";
    public static final String GOVERNANCE_REDACTION_CATEGORIES = GOVERNANCE + ".redactionCategories";
    public static final String GOVERNANCE_POLICY_ID = GOVERNANCE + ".policyId";
    public static final String GOVERNANCE_EVALUATED_AT = GOVERNANCE + ".evaluatedAt";

    /** The {@code ois:governance} decisions. */
    public enum Decision {
        /** The document passed as it was. */
        ALLOW,
        /** Sensitive content was removed before the document went on. */
        REDACT,
        /** The document was stopped. */
        DROP
    }

    private OisMetadata() {
    }

    /** Whether the key belongs to one of the blocks only OpenCrawling may write. */
    public static boolean isOisBlockKey(String key) {
        return key != null && (inBlock(key, SOURCE) || inBlock(key, PROVENANCE) || inBlock(key, GOVERNANCE));
    }

    /** Whether the key belongs to the {@code ois:governance} block. */
    public static boolean isGovernanceKey(String key) {
        return key != null && inBlock(key, GOVERNANCE);
    }

    private static boolean inBlock(String key, String block) {
        return key.equals(block) || key.startsWith(block + ".");
    }

    /** A copy of the metadata without any {@code ois:source}, {@code ois:provenance} or {@code ois:governance} key. */
    public static Map<String, List<String>> withoutOisBlocks(Map<String, List<String>> metadata) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, values) -> {
                if (!isOisBlockKey(key)) {
                    result.put(key, values);
                }
            });
        }
        return result;
    }

    /** A copy of the metadata without any {@code ois:governance} key. */
    public static Map<String, List<String>> withoutGovernance(Map<String, List<String>> metadata) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, values) -> {
                if (!isGovernanceKey(key)) {
                    result.put(key, values);
                }
            });
        }
        return result;
    }

    /**
     * A copy of the metadata with the {@code ois:source} and {@code ois:provenance} blocks set by OpenCrawling,
     * replacing whatever the document carried under those keys.
     *
     * @param sourceType       the repository type, e.g. {@code alfresco}; {@code null} to leave it out
     * @param sourceInstance   the repository instance's base URI; {@code null} to leave it out
     * @param connectorVersion the repository connector's version; {@code null} to leave it out
     * @param jobId            the job that produced the document
     * @param provenance       who started the run and how
     * @param ingestedAt       when the document was taken in
     */
    public static Map<String, List<String>> withSourceAndProvenance(Map<String, List<String>> metadata,
            String sourceType, String sourceInstance, String connectorVersion, String jobId,
            ExecutionProvenance provenance, Instant ingestedAt) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, values) -> {
                if (!inBlock(key, SOURCE) && !inBlock(key, PROVENANCE)) {
                    result.put(key, values);
                }
            });
        }
        put(result, SOURCE_TYPE, sourceType);
        put(result, SOURCE_INSTANCE, sourceInstance);
        put(result, SOURCE_CONNECTOR_VERSION, connectorVersion);
        put(result, PROVENANCE_JOB_ID, jobId);
        if (provenance != null) {
            put(result, PROVENANCE_TRIGGERED_BY, triggeredBy(provenance.triggerSource()));
            if (provenance.callerVerified()) {
                boolean delegated = provenance.onBehalfOf() != null;
                // ExecutionProvenance records the agent as triggeredBy and the user as onBehalfOf when delegated.
                put(result, PROVENANCE_ON_BEHALF_OF, delegated ? provenance.onBehalfOf() : provenance.triggeredBy());
                put(result, PROVENANCE_ACTOR_AGENT, delegated ? provenance.triggeredBy() : null);
            }
        }
        put(result, PROVENANCE_INGESTED_AT, ingestedAt != null ? ingestedAt.toString() : null);
        return result;
    }

    /**
     * A copy of the metadata with the {@code ois:governance} block replaced by the given decision.
     *
     * @param categories what was redacted, e.g. {@code EMAIL}; empty when nothing was
     * @param policyId   the governance policy or hooks that decided
     */
    public static Map<String, List<String>> withGovernance(Map<String, List<String>> metadata, Decision decision,
            Collection<String> categories, String policyId, Instant evaluatedAt) {
        Map<String, List<String>> result = withoutGovernance(metadata);
        result.put(GOVERNANCE_DECISION, List.of(decision.name()));
        boolean redacted = decision == Decision.REDACT;
        result.put(GOVERNANCE_REDACTED, List.of(String.valueOf(redacted)));
        if (categories != null && !categories.isEmpty()) {
            result.put(GOVERNANCE_REDACTION_CATEGORIES, List.copyOf(categories));
        }
        put(result, GOVERNANCE_POLICY_ID, policyId);
        put(result, GOVERNANCE_EVALUATED_AT, evaluatedAt != null ? evaluatedAt.toString() : null);
        return result;
    }

    /**
     * The OIS {@code triggeredBy} value for an OpenCrawling trigger source. The list is open: sources without a
     * recommended OIS value are passed on as they are.
     */
    public static String triggeredBy(String triggerSource) {
        if (triggerSource == null || triggerSource.isBlank()) {
            return null;
        }
        return switch (triggerSource) {
            case ExecutionProvenance.SOURCE_API -> "manual";
            case ExecutionProvenance.SOURCE_MCP -> "mcp-agent";
            case ExecutionProvenance.SOURCE_BPM -> "webhook";
            case ExecutionProvenance.SOURCE_STARTUP -> "system";
            default -> triggerSource;
        };
    }

    /**
     * The repository type for a connector's name, e.g. {@code alfresco} for {@code AlfrescoConnector} and
     * {@code cmis} for {@code CmisRepositoryConnector}.
     */
    public static String sourceType(String connectorName) {
        if (connectorName == null || connectorName.isBlank()) {
            return null;
        }
        String type = connectorName.strip();
        for (String suffix : List.of("RepositoryConnector", "_Repository", "Connector", "Repository")) {
            if (type.length() > suffix.length() && type.endsWith(suffix)) {
                type = type.substring(0, type.length() - suffix.length());
                break;
            }
        }
        return type.toLowerCase(Locale.ROOT);
    }

    /**
     * A repository instance's base URI without user information, query or fragment, so that no credentials end up
     * in documents; {@code null} when the value is not an absolute URI.
     */
    public static String instanceUri(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.strip());
            if (uri.getScheme() == null) {
                return null;
            }
            if (uri.isOpaque()) {
                // jdbc:postgresql://host/db and the like: keep the scheme chain, clean the inner URI.
                String inner = instanceUri(uri.getSchemeSpecificPart());
                return inner != null ? uri.getScheme() + ":" + inner : null;
            }
            String path = uri.getPath() != null ? uri.getPath() : "";
            if (uri.getHost() == null) {
                // Only a local file has no host. Anything else without a parsable host (e.g. a SQL Server URL whose
                // "authority" carries ;user=...;password=...) is not given out at all.
                return "file".equalsIgnoreCase(uri.getScheme()) && !path.isEmpty()
                        ? new java.net.URI("file", null, path, null).toString() : null;
            }
            return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path, null, null).toString();
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            return null;
        }
    }

    /** The URN that names a repository connector when it cannot tell its instance URI. */
    public static String connectorUrn(String connectorName) {
        String name = connectorName != null && !connectorName.isBlank() ? connectorName.strip() : "unknown";
        return "urn:opencrawling:connector:" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void put(Map<String, List<String>> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, List.of(value));
        }
    }
}
