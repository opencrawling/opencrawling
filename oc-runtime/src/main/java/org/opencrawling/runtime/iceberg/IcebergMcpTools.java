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
package org.opencrawling.runtime.iceberg;

import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.opencrawling.core.security.Caller;
import org.opencrawling.iceberg.output.IcebergOutputConnector;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.opencrawling.iceberg.output.schema.IcebergTableManager;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.api.PersistenceHelper;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.security.CallerResolver;
import org.opencrawling.runtime.security.VerifiedCallerRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * MCP tools for querying, auditing, and time-traveling across Apache Iceberg Lakehouse tables.
 *
 * <p>Activated whenever {@code opencrawling.mcp.server.enabled=true} and either
 * {@code opencrawling.mcp.iceberg.enabled=true}, {@code opencrawling.iceberg.mcp.enabled=true}, or
 * {@code spring.opencrawling.output.type=iceberg}. Every document, entity match, and statistical slice is
 * filtered through the runtime's Zero-Trust {@link DocumentAccessGate} and {@link CallerResolver}.
 */
@Component
@ConditionalOnExpression("(${opencrawling.mcp.iceberg.enabled:false} or ${opencrawling.iceberg.mcp.enabled:false} or '${spring.opencrawling.output.type:pgvector}' == 'iceberg') and ${opencrawling.mcp.server.enabled:true}")
public class IcebergMcpTools {

    private static final Logger log = LoggerFactory.getLogger(IcebergMcpTools.class);

    static final String IDENTITY_NOTE =
            " Ignored when the server verifies callers by bearer token: the token's identity is used instead.";

    private final IcebergMcpProperties mcpProperties;
    private final IcebergOutputProperties defaultOutputProperties;
    private final Supplier<List<ConnectorDTO>> registeredConnectors;
    private final ObjectProvider<Catalog> catalogProvider;
    private final ObjectProvider<IcebergOutputConnector> outputConnectorProvider;
    private final Catalog directCatalog;
    private final Clock clock;

    private CallerResolver callerResolver = CallerResolver.asserted();
    private DocumentAccessGate accessGate = DocumentAccessGate.metadataDefault();

    @Autowired
    public IcebergMcpTools(
            IcebergMcpProperties mcpProperties,
            IcebergOutputProperties defaultOutputProperties,
            ObjectProvider<Catalog> catalogProvider,
            ObjectProvider<IcebergOutputConnector> outputConnectorProvider,
            ObjectProvider<CallerResolver> callerResolverProvider,
            ObjectProvider<DocumentAccessGate> accessGateProvider) {
        this.mcpProperties = mcpProperties != null ? mcpProperties : new IcebergMcpProperties();
        this.defaultOutputProperties = defaultOutputProperties != null ? defaultOutputProperties : new IcebergOutputProperties();
        this.registeredConnectors = () -> PersistenceHelper.loadList("connectors.json", ConnectorDTO.class, List.of());
        this.catalogProvider = catalogProvider;
        this.outputConnectorProvider = outputConnectorProvider;
        this.directCatalog = null;
        this.clock = Clock.systemUTC();
        if (callerResolverProvider != null && callerResolverProvider.getIfAvailable() != null) {
            this.callerResolver = callerResolverProvider.getIfAvailable();
        }
        if (accessGateProvider != null && accessGateProvider.getIfAvailable() != null) {
            this.accessGate = accessGateProvider.getIfAvailable();
        }
    }

    /** Programmatic / unit-test constructor. */
    public IcebergMcpTools(
            Catalog catalog,
            IcebergOutputProperties outputProperties,
            IcebergMcpProperties mcpProperties,
            Supplier<List<ConnectorDTO>> registeredConnectors,
            DocumentAccessGate accessGate,
            CallerResolver callerResolver,
            Clock clock) {
        this.directCatalog = catalog;
        this.defaultOutputProperties = outputProperties != null ? outputProperties : new IcebergOutputProperties();
        this.mcpProperties = mcpProperties != null ? mcpProperties : new IcebergMcpProperties();
        this.registeredConnectors = registeredConnectors != null ? registeredConnectors : List::of;
        this.catalogProvider = null;
        this.outputConnectorProvider = null;
        this.clock = clock != null ? clock : Clock.systemUTC();
        if (accessGate != null) {
            this.accessGate = accessGate;
        }
        if (callerResolver != null) {
            this.callerResolver = callerResolver;
        }
    }

    @Autowired(required = false)
    public void setCallerResolver(CallerResolver callerResolver) {
        if (callerResolver != null) {
            this.callerResolver = callerResolver;
        }
    }

    @Autowired(required = false)
    public void setAccessGate(DocumentAccessGate accessGate) {
        if (accessGate != null) {
            this.accessGate = accessGate;
        }
    }

    // =========================================================================
    // Response DTOs
    // =========================================================================

    public record SnapshotSummary(
            long snapshotId,
            Long parentSnapshotId,
            String committedAt,
            String operation,
            Long addedRecords,
            Long deletedRecords,
            Long totalRecords,
            Long addedFiles,
            Long totalDataFiles,
            Map<String, String> summary
    ) {}

    public record IcebergSnapshotsResponse(
            String connector,
            String namespace,
            String tableName,
            Long currentSnapshotId,
            int totalSnapshots,
            boolean truncated,
            List<SnapshotSummary> snapshots
    ) {}

    public record IcebergDocumentAsOfResult(
            String connector,
            String tableName,
            Long snapshotId,
            String snapshotCommittedAt,
            String id,
            String documentId,
            String documentUri,
            String title,
            String mimeType,
            String sourceType,
            long contentLength,
            String sha256Checksum,
            String claimCheckUri,
            String action,
            String crawledAt,
            String extractedText,
            Map<String, String> extractedEntities,
            Map<String, String> customMetadata,
            String securityOwner
    ) {}

    public record EntityDocumentMatch(
            String tableName,
            String documentId,
            String documentUri,
            String title,
            String mimeType,
            String sourceType,
            String crawledAt,
            String matchedEntityKey,
            String matchedEntityValue,
            Map<String, String> extractedEntities,
            Map<String, String> customMetadata
    ) {}

    public record IcebergEntityQueryResponse(
            String connector,
            List<String> tablesQueried,
            String entityKey,
            String entityValue,
            String timeWindow,
            int matchedCount,
            boolean truncated,
            List<EntityDocumentMatch> matches
    ) {}

    public record PartitionStats(
            String tableName,
            String sourceType,
            String yearMonthPartition,
            long activeDocuments,
            long upsertRecords,
            long deleteTombstones,
            long contentBytes
    ) {}

    public record IcebergIngestionStatsResponse(
            String connector,
            List<String> tablesEvaluated,
            Long evaluatedSnapshotId,
            String partitionStrategy,
            long totalVisibleRecords,
            long activeDocumentCount,
            long upsertRecordCount,
            long deleteTombstoneCount,
            long totalContentBytes,
            boolean truncated,
            List<PartitionStats> partitions,
            Map<String, Long> byMimeType,
            Map<String, Long> bySourceType
    ) {}

    // =========================================================================
    // MCP Tools
    // =========================================================================

    @McpTool(description = "List historical commit snapshots of an Apache Iceberg Lakehouse table (newest first), including snapshot IDs, commit timestamps, parent IDs, operations (append, overwrite, delete), and record metrics.")
    public IcebergSnapshotsResponse listIcebergSnapshots(
            @McpToolParam(description = "Name of the registered Iceberg output connector (or 'default' for active configuration)", required = false) String connector,
            @McpToolParam(description = "Optional specific table name (useful with PER_DOCUMENT_TYPE strategy)", required = false) String tableName,
            @McpToolParam(description = "Maximum snapshot entries to return", required = false) Integer maxSnapshots,
            @McpToolParam(description = "The caller's user principal." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated groups/roles of the caller." + IDENTITY_NOTE, required = false) String userRoles
    ) {
        resolveCaller(userPrincipal, userRoles);
        int cap = clampSnapshots(maxSnapshots);

        try (IcebergTableAccess.ResolvedTarget target = openTarget(connector)) {
            List<TableIdentifier> tables = IcebergTableAccess.resolveExistingTables(
                    target.handle().catalog(), target.properties(), tableName);
            if (tables.isEmpty()) {
                return new IcebergSnapshotsResponse(
                        target.connectorName(),
                        target.properties().getNamespace(),
                        firstNonBlank(tableName, target.properties().getTableName()),
                        null,
                        0,
                        false,
                        List.of()
                );
            }

            TableIdentifier chosen = tables.getFirst();
            Table table = target.handle().catalog().loadTable(chosen);
            table.refresh();

            List<Snapshot> all = new ArrayList<>();
            for (Snapshot s : table.snapshots()) {
                all.add(s);
            }
            all.sort(Comparator.comparingLong(Snapshot::timestampMillis).reversed()
                    .thenComparing(Comparator.comparingLong(Snapshot::snapshotId).reversed()));

            boolean truncated = all.size() > cap;
            List<SnapshotSummary> summaries = new ArrayList<>(Math.min(all.size(), cap));
            for (int i = 0; i < Math.min(all.size(), cap); i++) {
                Snapshot s = all.get(i);
                Map<String, String> sum = s.summary() != null ? s.summary() : Map.of();
                summaries.add(new SnapshotSummary(
                        s.snapshotId(),
                        s.parentId(),
                        Instant.ofEpochMilli(s.timestampMillis()).toString(),
                        s.operation(),
                        parseLongOrNull(sum.get("added-records")),
                        parseLongOrNull(sum.get("deleted-records")),
                        parseLongOrNull(sum.get("total-records")),
                        parseLongOrNull(sum.get("added-data-files")),
                        parseLongOrNull(sum.get("total-data-files")),
                        sum
                ));
            }

            Long currentId = table.currentSnapshot() != null ? table.currentSnapshot().snapshotId() : null;
            return new IcebergSnapshotsResponse(
                    target.connectorName(),
                    chosen.namespace().toString(),
                    chosen.name(),
                    currentId,
                    all.size(),
                    truncated,
                    summaries
            );
        }
    }

    @McpTool(description = "Time-travel retrieve a specific document from an Apache Iceberg table at an explicit snapshot ID or historical UTC timestamp (asOfTimestamp), enforcing Zero-Trust read ACLs as recorded at that snapshot.")
    public IcebergDocumentAsOfResult getIcebergDocumentAsOf(
            @McpToolParam(description = "Name of the registered Iceberg output connector (or 'default' for active configuration)", required = false) String connector,
            @McpToolParam(description = "The document URI or canonical document ID to retrieve", required = true) String documentUri,
            @McpToolParam(description = "Target Iceberg snapshot ID for point-in-time retrieval (from listIcebergSnapshots)", required = false) Long snapshotId,
            @McpToolParam(description = "Historical UTC ISO-8601 timestamp (e.g. '2026-11-13T10:15:00Z') or epoch millis for asOfTime time-travel", required = false) String asOfTimestamp,
            @McpToolParam(description = "Optional specific table name", required = false) String tableName,
            @McpToolParam(description = "The caller's user principal." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated groups/roles of the caller." + IDENTITY_NOTE, required = false) String userRoles
    ) {
        Caller caller = resolveCaller(userPrincipal, userRoles);
        String targetDoc = IcebergDocumentMapper.blankToNull(documentUri);
        if (targetDoc == null) {
            throw new IllegalArgumentException("documentUri must not be blank.");
        }
        if (snapshotId != null && IcebergDocumentMapper.blankToNull(asOfTimestamp) != null) {
            throw new IllegalArgumentException("Specify either snapshotId or asOfTimestamp, not both.");
        }
        Long asOfMillis = IcebergTableAccess.parseTimestampMillis(asOfTimestamp);

        int scanCap = mcpProperties.effectiveMaxScan();
        List<IcebergDocumentMapper.IcebergDocumentRecord> matches = new ArrayList<>();
        String effectiveConnector;
        Snapshot resolvedSnapshot = null;

        try (IcebergTableAccess.ResolvedTarget target = openTarget(connector)) {
            effectiveConnector = target.connectorName();
            List<TableIdentifier> tables = IcebergTableAccess.resolveExistingTables(
                    target.handle().catalog(), target.properties(), tableName);
            for (TableIdentifier tableId : tables) {
                Table table = target.handle().catalog().loadTable(tableId);
                table.refresh();
                Snapshot targetSnapshot = resolveTargetSnapshot(table, snapshotId, asOfMillis);
                if (resolvedSnapshot == null) {
                    resolvedSnapshot = targetSnapshot;
                }
                Expression filter = buildUriOrIdFilter(table, targetDoc);
                List<IcebergDocumentMapper.IcebergDocumentRecord> rows =
                        IcebergTableAccess.scanTable(table, snapshotId, asOfMillis, filter, true, scanCap);
                for (IcebergDocumentMapper.IcebergDocumentRecord rec : rows) {
                    if (targetDoc.equals(rec.documentUri()) || targetDoc.equals(rec.documentId())) {
                        matches.add(rec);
                    }
                }
            }
        } catch (NoSuchElementException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed time-travel lookup in Iceberg: " + e.getMessage(), e);
        }

        IcebergDocumentMapper.IcebergDocumentRecord latest = IcebergTableAccess.selectLatest(matches)
                .filter(rec -> !rec.isDelete())
                .orElseThrow(() -> new NoSuchElementException(
                        "Document not found, deleted, or access denied as of the requested snapshot."));

        List<IcebergDocumentMapper.IcebergDocumentRecord> authorized =
                accessGate.filter(caller, List.of(latest), IcebergDocumentMapper::toResourceRef);
        if (authorized.isEmpty()) {
            throw new NoSuchElementException(
                    "Document not found, deleted, or access denied as of the requested snapshot.");
        }

        IcebergDocumentMapper.IcebergDocumentRecord doc = authorized.getFirst();
        Long snapIdOut = resolvedSnapshot != null ? resolvedSnapshot.snapshotId() : snapshotId;
        String snapTsOut = resolvedSnapshot != null ? Instant.ofEpochMilli(resolvedSnapshot.timestampMillis()).toString() : null;

        return new IcebergDocumentAsOfResult(
                effectiveConnector,
                doc.tableName(),
                snapIdOut,
                snapTsOut,
                doc.id(),
                doc.documentId(),
                doc.documentUri(),
                doc.title(),
                doc.mimeType(),
                doc.sourceType(),
                doc.contentLength(),
                doc.sha256Checksum(),
                doc.claimCheckUri(),
                doc.action(),
                doc.crawledAt() != null ? doc.crawledAt().toString() : null,
                doc.extractedText(),
                doc.extractedEntities(),
                doc.customMetadata(),
                doc.securityOwner()
        );
    }

    @McpTool(description = "Query structured extracted entities stored in Apache Iceberg tables (such as invoice numbers, vendors, contract IDs, or evolved columns) using Iceberg pushdowns, optional time windows, and Zero-Trust ACL filtering.")
    public IcebergEntityQueryResponse queryIcebergEntities(
            @McpToolParam(description = "Name of the registered Iceberg output connector (or 'default' for active configuration)", required = false) String connector,
            @McpToolParam(description = "Entity key name to match (e.g. 'contract_number', 'vendor', 'invoice_id')", required = true) String entityKey,
            @McpToolParam(description = "Optional exact entity value to filter by; when omitted matches any document carrying the entity key", required = false) String entityValue,
            @McpToolParam(description = "Optional relative or explicit UTC time window (e.g. '24h', '7d', 'PT12H', 'P30D', or '<isoStart>..<isoEnd>')", required = false) String timeWindow,
            @McpToolParam(description = "Maximum number of authorized matches to return", required = false) Integer maxResults,
            @McpToolParam(description = "Optional specific table name", required = false) String tableName,
            @McpToolParam(description = "The caller's user principal." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated groups/roles of the caller." + IDENTITY_NOTE, required = false) String userRoles
    ) {
        Caller caller = resolveCaller(userPrincipal, userRoles);
        String cleanKey = normalizeEntityKey(entityKey);
        if (cleanKey == null) {
            throw new IllegalArgumentException("entityKey must not be blank.");
        }
        String sanitizedCol = IcebergTableManager.sanitizeIdentifier(cleanKey);
        String expectedValue = IcebergDocumentMapper.blankToNull(entityValue);
        IcebergTableAccess.TimeBounds bounds = IcebergTableAccess.parseTimeWindow(timeWindow, clock);
        int limit = mcpProperties.effectiveLimit(maxResults);
        int scanCap = mcpProperties.effectiveMaxScan();

        String effectiveConnector;
        List<String> tablesQueried = new ArrayList<>();
        List<EntityCandidate> candidates = new ArrayList<>();

        try (IcebergTableAccess.ResolvedTarget target = openTarget(connector)) {
            effectiveConnector = target.connectorName();
            List<TableIdentifier> tables = IcebergTableAccess.resolveExistingTables(
                    target.handle().catalog(), target.properties(), tableName);

            for (TableIdentifier tableId : tables) {
                Table table = target.handle().catalog().loadTable(tableId);
                table.refresh();
                tablesQueried.add(table.name());
                if (table.currentSnapshot() == null) {
                    continue;
                }

                Expression filter = buildEntityScanPushdown(table, sanitizedCol, expectedValue, bounds);
                List<IcebergDocumentMapper.IcebergDocumentRecord> raw =
                        IcebergTableAccess.scanTable(table, null, null, filter, false, scanCap);

                List<IcebergDocumentMapper.IcebergDocumentRecord> matched = new ArrayList<>();
                Set<String> matchedDocIds = new LinkedHashSet<>();
                for (IcebergDocumentMapper.IcebergDocumentRecord rec : raw) {
                    if (!rec.isDelete() && matchesEntity(rec, cleanKey, sanitizedCol, expectedValue) != null) {
                        matched.add(rec);
                        if (IcebergDocumentMapper.blankToNull(rec.documentId()) != null) {
                            matchedDocIds.add(rec.documentId());
                        }
                    }
                }

                // Check for any newer version or DELETE tombstone on the matched document_ids so deleted/updated docs never resurrect.
                Map<String, IcebergDocumentMapper.IcebergDocumentRecord> latestByDocId =
                        IcebergTableAccess.resolveLatestForDocIds(table, matchedDocIds, scanCap);

                Map<String, IcebergDocumentMapper.IcebergDocumentRecord> collapsed = new LinkedHashMap<>();
                for (IcebergDocumentMapper.IcebergDocumentRecord rec : IcebergTableAccess.latestByDocument(matched)) {
                    IcebergDocumentMapper.IcebergDocumentRecord authoritative =
                            latestByDocId.getOrDefault(rec.logicalKey(), rec);
                    if (authoritative.isDelete()) {
                        continue;
                    }
                    String matchedVal = matchesEntity(authoritative, cleanKey, sanitizedCol, expectedValue);
                    if (matchedVal != null) {
                        collapsed.put(authoritative.logicalKey(), authoritative);
                    }
                }

                for (IcebergDocumentMapper.IcebergDocumentRecord doc : collapsed.values()) {
                    candidates.add(new EntityCandidate(doc, matchesEntity(doc, cleanKey, sanitizedCol, expectedValue)));
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed querying Iceberg entities: " + e.getMessage(), e);
        }

        List<EntityCandidate> permitted = new ArrayList<>(
                accessGate.filter(caller, candidates, c -> IcebergDocumentMapper.toResourceRef(c.doc())));
        permitted.sort(Comparator.comparing((EntityCandidate c) -> c.doc().crawledAt(),
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());

        boolean truncated = permitted.size() > limit;
        List<EntityDocumentMatch> out = new ArrayList<>(Math.min(limit, permitted.size()));
        for (int i = 0; i < Math.min(limit, permitted.size()); i++) {
            EntityCandidate candidate = permitted.get(i);
            IcebergDocumentMapper.IcebergDocumentRecord doc = candidate.doc();
            out.add(new EntityDocumentMatch(
                    doc.tableName(),
                    doc.documentId(),
                    doc.documentUri(),
                    doc.title(),
                    doc.mimeType(),
                    doc.sourceType(),
                    doc.crawledAt() != null ? doc.crawledAt().toString() : null,
                    cleanKey,
                    candidate.value(),
                    doc.extractedEntities(),
                    doc.customMetadata()
            ));
        }

        return new IcebergEntityQueryResponse(
                effectiveConnector,
                tablesQueried,
                cleanKey,
                expectedValue,
                timeWindow,
                out.size(),
                truncated,
                out
        );
    }

    @McpTool(description = "Summarize Apache Iceberg ingestion statistics: partition-level active document counts, UPSERT vs DELETE tombstone counts, total content bytes, and MIME/source breakdowns.")
    public IcebergIngestionStatsResponse getIcebergIngestionStats(
            @McpToolParam(description = "Name of the registered Iceberg output connector (or 'default' for active configuration)", required = false) String connector,
            @McpToolParam(description = "Optional Iceberg snapshot ID to compute historical ingestion statistics", required = false) Long snapshotId,
            @McpToolParam(description = "Optional specific table name", required = false) String tableName,
            @McpToolParam(description = "The caller's user principal." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated groups/roles of the caller." + IDENTITY_NOTE, required = false) String userRoles
    ) {
        Caller caller = resolveCaller(userPrincipal, userRoles);
        int scanCap = mcpProperties.effectiveMaxScan();

        String effectiveConnector;
        String partitionStrategy;
        List<String> tablesEvaluated = new ArrayList<>();
        Long resolvedSnapshotId = snapshotId;
        boolean truncated = false;

        Map<PartitionKey, MutablePartitionStats> partitionAgg = new TreeMap<>();
        Map<String, Long> byMimeType = new TreeMap<>();
        Map<String, Long> bySourceType = new TreeMap<>();
        long totalVisibleRecords = 0L;
        long totalActiveDocs = 0L;
        long totalUpsertRecords = 0L;
        long totalDeleteTombstones = 0L;
        long totalContentBytes = 0L;

        try (IcebergTableAccess.ResolvedTarget target = openTarget(connector)) {
            effectiveConnector = target.connectorName();
            partitionStrategy = target.properties().getPartitionBy() != null
                    ? target.properties().getPartitionBy().name()
                    : IcebergOutputProperties.PartitionStrategy.YEAR_MONTH.name();

            List<TableIdentifier> tables = IcebergTableAccess.resolveExistingTables(
                    target.handle().catalog(), target.properties(), tableName);

            for (TableIdentifier tableId : tables) {
                Table table = target.handle().catalog().loadTable(tableId);
                table.refresh();
                tablesEvaluated.add(table.name());
                if (table.currentSnapshot() == null) {
                    continue;
                }
                if (resolvedSnapshotId == null) {
                    resolvedSnapshotId = table.currentSnapshot().snapshotId();
                }

                List<IcebergDocumentMapper.IcebergDocumentRecord> allRows =
                        IcebergTableAccess.scanTable(table, snapshotId, null, Expressions.alwaysTrue(), false, scanCap);
                if (allRows.size() >= scanCap) {
                    truncated = true;
                }

                // Collapse to the latest state per logical document FIRST so revoked ACLs or tombstones in newer
                // snapshots never allow stale historical snapshots to leak through.
                Map<String, IcebergDocumentMapper.IcebergDocumentRecord> latestUpsertByKey = new LinkedHashMap<>();
                for (IcebergDocumentMapper.IcebergDocumentRecord rec : allRows) {
                    if (!rec.isDelete()) {
                        latestUpsertByKey.merge(rec.logicalKey(), rec, (a, b) ->
                                IcebergDocumentMapper.RECENCY_ORDER.compare(a, b) >= 0 ? a : b);
                    }
                }

                List<IcebergDocumentMapper.IcebergDocumentRecord> latestActive = new ArrayList<>();
                List<IcebergDocumentMapper.IcebergDocumentRecord> deletedPriorUpserts = new ArrayList<>();
                List<IcebergDocumentMapper.IcebergDocumentRecord> standaloneDeletesWithAcl = new ArrayList<>();
                Set<String> visibleKeys = new LinkedHashSet<>();

                for (IcebergDocumentMapper.IcebergDocumentRecord latestDoc : IcebergTableAccess.latestByDocument(allRows)) {
                    if (!latestDoc.isDelete()) {
                        latestActive.add(latestDoc);
                    } else {
                        IcebergDocumentMapper.IcebergDocumentRecord priorUpsert = latestUpsertByKey.get(latestDoc.logicalKey());
                        if (priorUpsert != null) {
                            deletedPriorUpserts.add(priorUpsert);
                        } else if (!latestDoc.deniedPrincipals().isEmpty()
                                || (!latestDoc.allowedPrincipals().isEmpty()
                                && !List.of("public").equals(latestDoc.allowedPrincipals()))) {
                            standaloneDeletesWithAcl.add(latestDoc);
                        } else {
                            // Standalone createTombstone (empty or default 'public' placeholder with no prior
                            // restricted UPSERT in history): include aggregate delete count without leaking metadata.
                            visibleKeys.add(latestDoc.logicalKey());
                        }
                    }
                }

                List<IcebergDocumentMapper.IcebergDocumentRecord> allowedActive =
                        accessGate.filter(caller, latestActive, IcebergDocumentMapper::toResourceRef);
                for (IcebergDocumentMapper.IcebergDocumentRecord doc : allowedActive) {
                    visibleKeys.add(doc.logicalKey());
                }
                if (!deletedPriorUpserts.isEmpty()) {
                    for (IcebergDocumentMapper.IcebergDocumentRecord doc :
                            accessGate.filter(caller, deletedPriorUpserts, IcebergDocumentMapper::toResourceRef)) {
                        visibleKeys.add(doc.logicalKey());
                    }
                }
                if (!standaloneDeletesWithAcl.isEmpty()) {
                    for (IcebergDocumentMapper.IcebergDocumentRecord doc :
                            accessGate.filter(caller, standaloneDeletesWithAcl, IcebergDocumentMapper::toResourceRef)) {
                        visibleKeys.add(doc.logicalKey());
                    }
                }

                for (IcebergDocumentMapper.IcebergDocumentRecord rec : allRows) {
                    if (!visibleKeys.contains(rec.logicalKey())) {
                        continue;
                    }
                    totalVisibleRecords++;
                    PartitionKey pk = new PartitionKey(table.name(), rec.sourceType(), rec.yearMonthPartition());
                    MutablePartitionStats agg = partitionAgg.computeIfAbsent(pk, ignored -> new MutablePartitionStats());
                    if (rec.isDelete()) {
                        agg.deletes++;
                        totalDeleteTombstones++;
                    } else {
                        agg.upserts++;
                        totalUpsertRecords++;
                    }
                }

                for (IcebergDocumentMapper.IcebergDocumentRecord latestDoc : allowedActive) {
                    totalActiveDocs++;
                    totalContentBytes += latestDoc.contentLength();
                    PartitionKey pk = new PartitionKey(table.name(), latestDoc.sourceType(), latestDoc.yearMonthPartition());
                    MutablePartitionStats agg = partitionAgg.computeIfAbsent(pk, ignored -> new MutablePartitionStats());
                    agg.activeDocs++;
                    agg.contentBytes += latestDoc.contentLength();
                    byMimeType.merge(latestDoc.mimeType(), 1L, Long::sum);
                    bySourceType.merge(latestDoc.sourceType(), 1L, Long::sum);
                }
            }
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed collecting Iceberg ingestion stats: " + e.getMessage(), e);
        }

        List<PartitionStats> partitionList = new ArrayList<>(partitionAgg.size());
        partitionAgg.forEach((pk, agg) -> partitionList.add(new PartitionStats(
                pk.tableName(), pk.sourceType(), pk.yearMonth(),
                agg.activeDocs, agg.upserts, agg.deletes, agg.contentBytes
        )));

        return new IcebergIngestionStatsResponse(
                effectiveConnector,
                tablesEvaluated,
                resolvedSnapshotId,
                partitionStrategy,
                totalVisibleRecords,
                totalActiveDocs,
                totalUpsertRecords,
                totalDeleteTombstones,
                totalContentBytes,
                truncated,
                partitionList,
                Collections.unmodifiableMap(byMimeType),
                Collections.unmodifiableMap(bySourceType)
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private record EntityCandidate(IcebergDocumentMapper.IcebergDocumentRecord doc, String value) {}

    private record PartitionKey(String tableName, String sourceType, String yearMonth)
            implements Comparable<PartitionKey> {
        @Override
        public int compareTo(PartitionKey o) {
            int c = this.tableName.compareTo(o.tableName);
            if (c != 0) return c;
            c = this.yearMonth.compareTo(o.yearMonth);
            if (c != 0) return c;
            return this.sourceType.compareTo(o.sourceType);
        }
    }

    private static final class MutablePartitionStats {
        long activeDocs;
        long upserts;
        long deletes;
        long contentBytes;
    }

    private Caller resolveCaller(String userPrincipal, String userRoles) {
        if (mcpProperties.isRequireVerifiedCaller()) {
            return callerResolver.verifiedCaller()
                    .orElseThrow(VerifiedCallerRequiredException::new);
        }
        return callerResolver.resolve(userPrincipal, userRoles);
    }

    private IcebergTableAccess.ResolvedTarget openTarget(String connector) {
        Catalog activeCatalog = directCatalog;
        if (activeCatalog == null && catalogProvider != null) {
            activeCatalog = catalogProvider.getIfAvailable();
        }
        if (activeCatalog == null && outputConnectorProvider != null) {
            IcebergOutputConnector outConn = outputConnectorProvider.getIfAvailable();
            if (outConn != null) {
                activeCatalog = outConn.getCatalog();
            }
        }
        return IcebergTableAccess.openTarget(
                connector,
                registeredConnectors,
                defaultOutputProperties,
                activeCatalog,
                false
        );
    }

    private static Snapshot resolveTargetSnapshot(Table table, Long snapshotId, Long asOfMillis) {
        if (snapshotId != null) {
            Snapshot s = table.snapshot(snapshotId);
            if (s == null) {
                throw new NoSuchElementException(
                        "Snapshot " + snapshotId + " was not found in Iceberg table '" + table.name() + "'.");
            }
            return s;
        }
        if (asOfMillis != null) {
            Snapshot match = null;
            for (Snapshot s : table.snapshots()) {
                if (s.timestampMillis() <= asOfMillis
                        && (match == null || s.timestampMillis() > match.timestampMillis())) {
                    match = s;
                }
            }
            if (match == null) {
                throw new IllegalArgumentException(
                        "No Iceberg snapshot exists in table '" + table.name() + "' at or before "
                                + Instant.ofEpochMilli(asOfMillis));
            }
            return match;
        }
        return table.currentSnapshot();
    }

    private static Expression buildUriOrIdFilter(Table table, String target) {
        boolean hasUri = table.schema().findField("document_uri") != null;
        boolean hasId = table.schema().findField("document_id") != null;
        if (hasUri && hasId) {
            return Expressions.or(Expressions.equal("document_uri", target), Expressions.equal("document_id", target));
        }
        if (hasUri) {
            return Expressions.equal("document_uri", target);
        }
        if (hasId) {
            return Expressions.equal("document_id", target);
        }
        return Expressions.alwaysTrue();
    }

    private static Expression buildEntityScanPushdown(
            Table table,
            String sanitizedCol,
            String expectedValue,
            IcebergTableAccess.TimeBounds bounds
    ) {
        Expression expr = Expressions.alwaysTrue();
        if (table.schema().findField("action") != null) {
            expr = IcebergTableAccess.and(expr, Expressions.notEqual("action", "DELETE"));
        }
        if (bounds != null) {
            expr = IcebergTableAccess.and(expr,
                    IcebergTableAccess.crawledAtBetween(table.schema(), bounds.startInclusive(), bounds.endInclusive()));
        }
        if (IcebergTableAccess.hasPrimitiveStringColumn(table.schema(), sanitizedCol)) {
            expr = expectedValue != null
                    ? IcebergTableAccess.and(expr, Expressions.equal(sanitizedCol, expectedValue))
                    : IcebergTableAccess.and(expr, Expressions.notNull(sanitizedCol));
        }
        return expr;
    }

    private static String matchesEntity(
            IcebergDocumentMapper.IcebergDocumentRecord doc,
            String cleanKey,
            String sanitizedCol,
            String expectedValue
    ) {
        String found = null;
        for (Map.Entry<String, String> entry : doc.extractedEntities().entrySet()) {
            String k = entry.getKey();
            if (k != null && (k.equalsIgnoreCase(cleanKey)
                    || IcebergTableManager.sanitizeIdentifier(k).equals(sanitizedCol))) {
                found = entry.getValue();
                break;
            }
        }
        if (found == null || found.isBlank()) {
            return null;
        }
        if (expectedValue != null && !expectedValue.equals(found.trim())) {
            return null;
        }
        return found;
    }

    private static String normalizeEntityKey(String entityKey) {
        String trimmed = IcebergDocumentMapper.blankToNull(entityKey);
        if (trimmed == null) {
            return null;
        }
        return trimmed.replaceFirst("^(?i)(entity\\.|extracted\\.)", "");
    }

    private int clampSnapshots(Integer requested) {
        int hard = mcpProperties.effectiveMaxSnapshots();
        int value = requested != null ? requested : hard;
        return Math.max(1, Math.min(value, hard));
    }

    private static Long parseLongOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String primary, String fallback) {
        String p = IcebergDocumentMapper.blankToNull(primary);
        return p != null ? p : fallback;
    }
}
