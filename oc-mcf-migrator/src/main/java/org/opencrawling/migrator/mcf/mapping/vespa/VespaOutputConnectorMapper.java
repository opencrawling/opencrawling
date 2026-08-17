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
package org.opencrawling.migrator.mcf.mapping.vespa;

import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapper;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mapping.FieldNote;
import org.opencrawling.migrator.mcf.mapping.FieldNoteKind;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.sdk.models.ConnectorRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps ManifoldCF's {@code org.apache.manifoldcf.agents.output.vespa.VespaOutputConnector} (field
 * names per its {@code VespaOutputConfig}) to OpenCrawling's own {@code
 * org.opencrawling.vespa.VespaOutputConnector}. Target keys are the exact {@code
 * ConnectorRequest.configuration} map keys OpenCrawling's {@code JobController.startJob()} reads
 * (verified in source) when it dynamically resolves an output connector whose class name contains
 * "Vespa" — {@code vespaEndpoint}, {@code vespaNamespace}, {@code vespaDocumentType}, {@code
 * vespaDimensions}, {@code vespaTimeoutSeconds}, {@code vespaTlsEnabled} plus the three TLS
 * material keys — which differ from the Java field names in {@code VespaOutputProperties} (
 * {@code endpoint}, {@code namespace}, {@code documentType}, ...). Getting these wrong means the
 * migrated connector silently fails to resolve at job-start.
 *
 * <p>Most ManifoldCF fields have no target at all: the two connectors solve genuinely different
 * problems (ManifoldCF's does inline embedding calls, source shadow-enrichment, Basic/Bearer auth;
 * OpenCrawling's is mTLS-or-none and expects embedding to happen in its own pipeline stage).
 * Everything dropped is logged with a specific reason rather than silently discarded.
 */
public class VespaOutputConnectorMapper implements ConnectorMapper {

    public static final String MANIFOLDCF_CLASS = "org.apache.manifoldcf.agents.output.vespa.VespaOutputConnector";

    private static final String TARGET_CLASS = "org.opencrawling.vespa.VespaOutputConnector";

    // ManifoldCF source keys (org.apache.manifoldcf.agents.output.vespa.VespaOutputConfig)
    private static final String SRC_ENDPOINT = "vespaEndpoint";
    private static final String SRC_HEALTH_PATH = "vespaHealthPath";
    private static final String SRC_NAMESPACE = "vespaNamespace";
    private static final String SRC_DOC_TYPE = "vespaDocType";
    private static final String SRC_AUTH_MODE = "vespaAuthMode";
    private static final String SRC_USERNAME = "vespaUsername";
    private static final String SRC_PASSWORD = "vespaPassword";
    private static final String SRC_BEARER_TOKEN = "vespaBearerToken";
    private static final String SRC_ID_STRATEGY = "vespaIdStrategy";
    private static final String SRC_TIMEOUT_MS = "vespaTimeoutMs";
    private static final String SRC_MAX_BINARY_BYTES = "vespaMaxBinaryBytes";
    private static final String SRC_TEXT_FIELD_CANDIDATES = "vespaTextFieldCandidates";
    private static final String SRC_STORE_BINARY = "vespaStoreBinary";
    private static final String SRC_SOURCE_SYSTEM = "sourceSystem";
    private static final String SRC_SOURCE_INSTANCE = "sourceInstance";
    private static final String SRC_REPOSITORY_ID = "repositoryId";
    private static final String SRC_TENANT_ID = "tenantId";
    private static final String SRC_SOURCE_CONNECTION_NAME = "sourceConnectionName";
    private static final String SRC_LANGUAGE_OVERRIDE = "vespaLanguageOverride";
    private static final String SRC_FIX_RTL_TEXT = "vespaFixRtlText";
    private static final String SRC_EMBEDDING_ENDPOINT = "vespaEmbeddingEndpoint";
    private static final String SRC_EMBEDDING_TIMEOUT_MS = "vespaEmbeddingTimeoutMs";
    private static final String SRC_EMBEDDING_MAX_CHARS = "vespaEmbeddingMaxChars";
    private static final List<String> SRC_SHADOW_KEYS = List.of(
        "shadowMode", "shadowAlfrescoUrl", "shadowAlfrescoUser", "shadowAlfrescoPwd",
        "shadowSolrUrl", "shadowSolrCore", "shadowEsUrl", "shadowEsIndex", "shadowFallback",
        "fsShadowEnabled", "fsShadowFilesFolder", "fsShadowMetadataFolder");

    // OpenCrawling target keys — the ConnectorRequest.configuration map keys JobController reads.
    private static final String DST_ENDPOINT = "vespaEndpoint";
    private static final String DST_NAMESPACE = "vespaNamespace";
    private static final String DST_DOCUMENT_TYPE = "vespaDocumentType";
    private static final String DST_DIMENSIONS = "vespaDimensions";
    private static final String DST_TIMEOUT_SECONDS = "vespaTimeoutSeconds";
    private static final String DST_TLS_ENABLED = "vespaTlsEnabled";

    private static final String DEFAULT_TIMEOUT_SECONDS = "30";

    @Override
    public boolean supports(String manifoldClassName) {
        return MANIFOLDCF_CLASS.equals(manifoldClassName);
    }

    @Override
    public String manifoldClassName() {
        return MANIFOLDCF_CLASS;
    }

    @Override
    public String targetType() {
        return "output";
    }

    @Override
    public ConnectorMappingResult map(McfConnection source, MigrationOptions options) {
        Map<String, String> src = source.configuration();
        Map<String, String> target = new LinkedHashMap<>();
        List<FieldNote> notes = new ArrayList<>();

        // Direct / renamed / converted fields.
        if (src.containsKey(SRC_ENDPOINT)) {
            target.put(DST_ENDPOINT, src.get(SRC_ENDPOINT));
        } else {
            notes.add(new FieldNote(DST_ENDPOINT, FieldNoteKind.DEFAULTED,
                "'" + SRC_ENDPOINT + "' was missing from this ManifoldCF connection's configuration; the migrated "
                    + "connector has no endpoint set and will fail to resolve at job-start until one is configured"));
        }
        if (src.containsKey(SRC_NAMESPACE)) {
            target.put(DST_NAMESPACE, src.get(SRC_NAMESPACE));
        } else {
            notes.add(new FieldNote(DST_NAMESPACE, FieldNoteKind.DEFAULTED,
                "'" + SRC_NAMESPACE + "' was missing from this ManifoldCF connection's configuration; the migrated "
                    + "connector has no namespace set and will fail to resolve at job-start until one is configured"));
        }
        if (src.containsKey(SRC_DOC_TYPE)) {
            target.put(DST_DOCUMENT_TYPE, src.get(SRC_DOC_TYPE));
            notes.add(new FieldNote(SRC_DOC_TYPE, FieldNoteKind.CONVERTED,
                "renamed to '" + DST_DOCUMENT_TYPE + "'"));
        }
        target.put(DST_TIMEOUT_SECONDS, convertTimeoutMsToSeconds(src.get(SRC_TIMEOUT_MS), notes));

        // Target-only fields with no ManifoldCF source at all.
        int dimensions = options.defaultEmbeddingDimensions();
        target.put(DST_DIMENSIONS, Integer.toString(dimensions));
        notes.add(new FieldNote(DST_DIMENSIONS, FieldNoteKind.DEFAULTED,
            "no equivalent field in ManifoldCF's Vespa connector (dimensions aren't declared statically there); "
                + "defaulted to " + dimensions + " for this deployment's embedder. "
                + "OpenCrawling's own connector default (1024) would be wrong here — override with "
                + "--default-embedding-dimensions if this embedder changes."));
        target.put(DST_TLS_ENABLED, "false");
        notes.add(new FieldNote(DST_TLS_ENABLED, FieldNoteKind.DEFAULTED,
            "no TLS material migrates automatically; configure vespaTlsEnabled/Certificate/PrivateKey/"
                + "CaCertificates manually if the target Vespa endpoint requires mTLS"));

        // Dropped: no target equivalent at all.
        dropIfPresent(src, SRC_HEALTH_PATH, notes,
            "hardcoded to /state/v1/health in OpenCrawling, no override");
        dropIfPresent(src, SRC_AUTH_MODE, notes,
            "target Vespa connector supports mTLS-or-none only, no Basic/Bearer auth mode");
        dropIfPresent(src, SRC_USERNAME, notes,
            "target Vespa connector supports mTLS-or-none only, no Basic auth");
        dropIfPresent(src, SRC_PASSWORD, notes,
            "target Vespa connector supports mTLS-or-none only, no Basic auth (value not carried into this report)");
        dropIfPresent(src, SRC_BEARER_TOKEN, notes,
            "target Vespa connector supports mTLS-or-none only, no Bearer auth (value not carried into this report)");
        dropIfPresent(src, SRC_ID_STRATEGY, notes,
            "OpenCrawling derives its own document id scheme");
        dropIfPresent(src, SRC_MAX_BINARY_BYTES, notes,
            "no equivalent; target has no binary-storage size cap");
        dropIfPresent(src, SRC_TEXT_FIELD_CANDIDATES, notes,
            "no equivalent; target uses a single fixed 'text' field, not a candidate list");
        dropIfPresent(src, SRC_STORE_BINARY, notes,
            "no equivalent; target has no binary-storage toggle");
        dropIfPresent(src, SRC_SOURCE_SYSTEM, notes,
            "no tenant/provenance-tagging concept in OpenCrawling");
        dropIfPresent(src, SRC_SOURCE_INSTANCE, notes,
            "no tenant/provenance-tagging concept in OpenCrawling");
        dropIfPresent(src, SRC_REPOSITORY_ID, notes,
            "no tenant/provenance-tagging concept in OpenCrawling");
        dropIfPresent(src, SRC_TENANT_ID, notes,
            "no tenant/provenance-tagging concept in OpenCrawling");
        dropIfPresent(src, SRC_SOURCE_CONNECTION_NAME, notes,
            "no tenant/provenance-tagging concept in OpenCrawling");
        dropIfPresent(src, SRC_LANGUAGE_OVERRIDE, notes, "no equivalent in OpenCrawling's Vespa connector");
        dropIfPresent(src, SRC_FIX_RTL_TEXT, notes, "no equivalent in OpenCrawling's Vespa connector");
        dropIfPresent(src, SRC_EMBEDDING_ENDPOINT, notes,
            "embedding is handled by OpenCrawling's own transformation-stage pipeline, not by the output connector");
        dropIfPresent(src, SRC_EMBEDDING_TIMEOUT_MS, notes,
            "embedding is handled by OpenCrawling's own transformation-stage pipeline, not by the output connector");
        dropIfPresent(src, SRC_EMBEDDING_MAX_CHARS, notes,
            "embedding is handled by OpenCrawling's own transformation-stage pipeline, not by the output connector");

        boolean anyShadowKeyPresent = SRC_SHADOW_KEYS.stream().anyMatch(src::containsKey);
        if (anyShadowKeyPresent) {
            notes.add(new FieldNote("shadow*/fsShadow*", FieldNoteKind.DROPPED,
                "no shadow/enrichment concept in OpenCrawling (this ManifoldCF connection had shadow-mode "
                    + "fields configured)"));
        }

        ConnectorRequest target_ = ConnectorRequest.builder()
            .name(source.name())
            .description(source.description() != null ? source.description() : source.name())
            .type(targetType())
            .className(TARGET_CLASS)
            .maxConnections(source.maxConnections())
            .configuration(target)
            .build();

        return ConnectorMappingResult.supported(target_, notes);
    }

    private static String convertTimeoutMsToSeconds(String timeoutMs, List<FieldNote> notes) {
        if (timeoutMs != null) {
            try {
                long seconds = Math.round(Long.parseLong(timeoutMs) / 1000.0);
                notes.add(new FieldNote(SRC_TIMEOUT_MS, FieldNoteKind.CONVERTED,
                    "converted from milliseconds (" + timeoutMs + ") to seconds (" + seconds + ")"));
                return Long.toString(seconds);
            } catch (NumberFormatException ignored) {
                // fall through to default below
            }
        }
        notes.add(new FieldNote(DST_TIMEOUT_SECONDS, FieldNoteKind.DEFAULTED,
            "'" + SRC_TIMEOUT_MS + "' was missing or non-numeric; defaulted to " + DEFAULT_TIMEOUT_SECONDS + "s"));
        return DEFAULT_TIMEOUT_SECONDS;
    }

    private static void dropIfPresent(Map<String, String> src, String key, List<FieldNote> notes, String reason) {
        if (src.containsKey(key)) {
            notes.add(new FieldNote(key, FieldNoteKind.DROPPED, reason));
        }
    }
}
