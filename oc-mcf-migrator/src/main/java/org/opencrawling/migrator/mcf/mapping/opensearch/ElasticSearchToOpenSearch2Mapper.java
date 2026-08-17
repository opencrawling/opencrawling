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
package org.opencrawling.migrator.mcf.mapping.opensearch;

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
 * Maps ManifoldCF's stock {@code org.apache.manifoldcf.agents.output.elasticsearch.ElasticSearchConnector}
 * (field names per its {@code ElasticSearchParam} enum) to OpenCrawling's {@code
 * org.opencrawling.opensearch2.OpenSearch2OutputConnector}.
 *
 * <p>Verified compatible before writing this: ManifoldCF's ES connector is plain, versionless REST
 * (Apache HttpClient, single-document Index/Delete API — {@code PUT <server>/<index>/_doc/<id>} —
 * no ES client library, no {@code _bulk} usage), so it has nothing that would break against
 * OpenSearch's REST surface, which kept that same path form for compatibility.
 *
 * <p><b>Known caveat, surfaced as a connector-level {@code RUNTIME_RISK} note below:</b>
 * OpenCrawling's admin-UI/{@code ConnectorRequest.configuration} keys this mapper writes
 * ({@code opensearch2Uris}, {@code opensearch2Username}, ...) are a separate, hand-maintained
 * naming scheme from the actual Spring {@code @Value} properties the real
 * {@code OpenSearch2StoreConfig}/{@code OpenSearch2OutputConnector} beans read
 * ({@code spring.opencrawling.output.opensearch2.*}) — confirmed in source, the two are not
 * reconciled anywhere in {@code oc-runtime}. On top of that, {@code JobController.startJob()}'s
 * dynamic connector resolution doesn't special-case OpenSearch at all (only Qdrant/Vespa), so a
 * job pointed at a migrated OpenSearch connector will silently fall back to the default output
 * bean rather than actually indexing into OpenSearch, until an operator manually configures the
 * Spring properties (or OpenCrawling closes this gap). Migrating the connector still has value —
 * the configuration is preserved and visible instead of silently discarded — but it will not work
 * out of the box the way the Vespa/filesystem migration does.
 */
public class ElasticSearchToOpenSearch2Mapper implements ConnectorMapper {

    public static final String MANIFOLDCF_CLASS = "org.apache.manifoldcf.agents.output.elasticsearch.ElasticSearchConnector";

    private static final String TARGET_CLASS = "org.opencrawling.opensearch2.OpenSearch2OutputConnector";

    // ManifoldCF source keys (org.apache.manifoldcf.agents.output.elasticsearch.ElasticSearchParam)
    private static final String SRC_SERVERLOCATION = "SERVERLOCATION";
    private static final String SRC_INDEXNAME = "INDEXNAME";
    private static final String SRC_USERNAME = "USERNAME";
    private static final String SRC_PASSWORD = "PASSWORD";
    private static final String SRC_SERVERKEYSTORE = "SERVERKEYSTORE";
    private static final String SRC_INDEXTYPE = "INDEXTYPE";
    private static final String SRC_USEINGESTATTACHMENT = "USEINGESTATTACHMENT";
    private static final String SRC_USEMAPPERATTACHMENTS = "USEMAPPERATTACHMENTS";
    private static final String SRC_PIPELINENAME = "PIPELINENAME";
    private static final String SRC_CONTENTATTRIBUTENAME = "CONTENTATTRIBUTENAME";
    private static final String SRC_URIATTRIBUTENAME = "URIATTRIBUTENAME";
    private static final String SRC_CREATEDDATEATTRIBUTENAME = "CREATEDDATEATTRIBUTENAME";
    private static final String SRC_MODIFIEDDATEATTRIBUTENAME = "MODIFIEDDATEATTRIBUTENAME";
    private static final String SRC_INDEXINGDATEATTRIBUTENAME = "INDEXINGDATEATTRIBUTENAME";
    private static final String SRC_MIMETYPEATTRIBUTENAME = "MIMETYPEATTRIBUTENAME";
    private static final String SRC_FIELDLIST = "FIELDLIST";
    private static final String SRC_SOCKET_TIMEOUT = "ELASTICSEARCH_SOCKET_TIMEOUT";
    private static final String SRC_CONNECTION_TIMEOUT = "ELASTICSEARCH_CONNECTION_TIMEOUT";

    // OpenCrawling target keys — the ConnectorRequest.configuration map keys the admin UI uses
    // (see class javadoc: these are NOT what the real Spring beans read at startup today).
    private static final String DST_URIS = "opensearch2Uris";
    private static final String DST_USERNAME = "opensearch2Username";
    private static final String DST_PASSWORD = "opensearch2Password";
    private static final String DST_INDEX_NAME = "opensearch2IndexName";
    private static final String DST_DIMENSIONS = "opensearch2Dimensions";

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

        if (src.containsKey(SRC_SERVERLOCATION)) {
            target.put(DST_URIS, src.get(SRC_SERVERLOCATION));
        } else {
            notes.add(new FieldNote(DST_URIS, FieldNoteKind.DEFAULTED,
                "'" + SRC_SERVERLOCATION + "' was missing from this ManifoldCF connection's configuration; the "
                    + "migrated connector has no server location set and will fail to resolve at job-start until "
                    + "one is configured"));
        }
        if (src.containsKey(SRC_INDEXNAME)) {
            target.put(DST_INDEX_NAME, src.get(SRC_INDEXNAME));
            notes.add(new FieldNote(SRC_INDEXNAME, FieldNoteKind.CONVERTED, "renamed to '" + DST_INDEX_NAME + "'"));
        }
        if (src.containsKey(SRC_USERNAME)) {
            target.put(DST_USERNAME, src.get(SRC_USERNAME));
            notes.add(new FieldNote(SRC_USERNAME, FieldNoteKind.CONVERTED, "renamed to '" + DST_USERNAME + "'"));
        }
        if (src.containsKey(SRC_PASSWORD)) {
            target.put(DST_PASSWORD, src.get(SRC_PASSWORD));
            notes.add(new FieldNote(SRC_PASSWORD, FieldNoteKind.CONVERTED,
                "renamed to '" + DST_PASSWORD + "' (value carried forward, not shown here)"));
        }

        int dimensions = options.defaultEmbeddingDimensions();
        target.put(DST_DIMENSIONS, Integer.toString(dimensions));
        notes.add(new FieldNote(DST_DIMENSIONS, FieldNoteKind.DEFAULTED,
            "no equivalent field in ManifoldCF's Elasticsearch connector; defaulted to " + dimensions
                + " for this deployment's embedder — override with --default-embedding-dimensions if it changes"));

        dropIfPresent(src, SRC_SERVERKEYSTORE, notes, "no client-TLS-keystore equivalent in the target connector");
        dropIfPresent(src, SRC_INDEXTYPE, notes,
            "target's document schema is fixed (see OpenSearch2Constants), not configurable per connection");
        dropIfPresent(src, SRC_USEINGESTATTACHMENT, notes,
            "no ES ingest-pipeline attachment concept; OpenCrawling extracts text via its own Tika pipeline stage");
        dropIfPresent(src, SRC_USEMAPPERATTACHMENTS, notes,
            "no legacy mapper-attachments equivalent; OpenCrawling extracts text via its own Tika pipeline stage");
        dropIfPresent(src, SRC_PIPELINENAME, notes, "no ingest-pipeline concept in the target connector");
        dropIfPresent(src, SRC_CONTENTATTRIBUTENAME, notes, "target uses a fixed field name ('text'), not configurable");
        dropIfPresent(src, SRC_URIATTRIBUTENAME, notes, "target uses a fixed field name ('uri'), not configurable");
        dropIfPresent(src, SRC_CREATEDDATEATTRIBUTENAME, notes, "no equivalent fixed field exists on the target schema");
        dropIfPresent(src, SRC_MODIFIEDDATEATTRIBUTENAME, notes, "target uses a fixed field name ('lastModified'), not configurable");
        dropIfPresent(src, SRC_INDEXINGDATEATTRIBUTENAME, notes, "no equivalent fixed field exists on the target schema");
        dropIfPresent(src, SRC_MIMETYPEATTRIBUTENAME, notes, "no equivalent fixed field exists on the target schema");
        dropIfPresent(src, SRC_FIELDLIST, notes, "already unused/dead in ManifoldCF's own connector; no target either way");
        dropIfPresent(src, SRC_SOCKET_TIMEOUT, notes, "no per-connection timeout override exposed by the target connector's config map");
        dropIfPresent(src, SRC_CONNECTION_TIMEOUT, notes, "no per-connection timeout override exposed by the target connector's config map");

        notes.add(new FieldNote("configuration", FieldNoteKind.RUNTIME_RISK,
            "the '" + DST_URIS + "'/'" + DST_USERNAME + "'/etc. keys written here are what OpenCrawling's admin UI "
                + "displays, but the real OpenSearch2StoreConfig/OpenSearch2OutputConnector Spring beans read a "
                + "separate property scheme (spring.opencrawling.output.opensearch2.*) that isn't populated from "
                + "this connector's configuration at all — manual application.yml/environment configuration on the "
                + "OpenCrawling side is required before this output actually indexes anything"));

        ConnectorRequest targetRequest = ConnectorRequest.builder()
            .name(source.name())
            .description(source.description() != null ? source.description() : source.name())
            .type(targetType())
            .className(TARGET_CLASS)
            .maxConnections(source.maxConnections())
            .configuration(target)
            .build();

        return ConnectorMappingResult.supported(targetRequest, notes);
    }

    private static void dropIfPresent(Map<String, String> src, String key, List<FieldNote> notes, String reason) {
        if (src.containsKey(key)) {
            notes.add(new FieldNote(key, FieldNoteKind.DROPPED, reason));
        }
    }
}
