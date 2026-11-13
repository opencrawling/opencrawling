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

import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.DateTimeUtil;
import org.opencrawling.iceberg.output.catalog.IcebergCatalogFactory;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.connector.OutputKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared read-only Apache Iceberg Lakehouse access helpers used by {@code IcebergSidecarStore},
 * {@code IcebergMcpSearchBackend}, and {@code IcebergMcpTools}.
 */
public final class IcebergTableAccess {

    private static final Logger log = LoggerFactory.getLogger(IcebergTableAccess.class);
    private static final Pattern SHORT_DURATION = Pattern.compile("^(\\d+)([smhdw])$", Pattern.CASE_INSENSITIVE);

    private IcebergTableAccess() {
    }

    /** Wraps a {@link Catalog} and closes it only when owned by the caller. */
    public record CatalogHandle(Catalog catalog, boolean owned) implements AutoCloseable {
        @Override
        public void close() {
            if (owned && catalog instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.debug("Ignoring exception while closing Iceberg catalog: {}", e.getMessage());
                }
            }
        }
    }

    /** Resolved Iceberg output connector target and its open catalog handle. */
    public record ResolvedTarget(String connectorName, IcebergOutputProperties properties,
                                 CatalogHandle handle) implements AutoCloseable {
        @Override
        public void close() {
            handle.close();
        }
    }

    /** Inclusive/exclusive UTC window parsed from a caller {@code timeWindow} specification. */
    public record TimeBounds(Instant startInclusive, Instant endInclusive) {
    }

    /**
     * Resolves an Iceberg connector target by name, checking registered Iceberg output connectors first
     * and falling back to active {@code IcebergOutputProperties} only for {@code null}/blank/{@code default}/{@code active}
     * (or the matching active fallback alias).
     */
    public static ResolvedTarget openTarget(
            String requestedConnector,
            Supplier<List<ConnectorDTO>> registeredConnectors,
            IcebergOutputProperties defaultProperties,
            Catalog sharedCatalog,
            boolean defaultFallbackAllowed
    ) {
        String name = IcebergDocumentMapper.blankToNull(requestedConnector);
        if (name != null && registeredConnectors != null) {
            List<ConnectorDTO> all = registeredConnectors.get();
            if (all != null) {
                for (ConnectorDTO dto : all) {
                    if (dto != null && dto.name() != null && dto.name().equalsIgnoreCase(name)) {
                        if (OutputKind.of(dto.className()) != OutputKind.ICEBERG) {
                            throw new IllegalArgumentException("Connector '" + name + "' is not an Apache Iceberg output connector.");
                        }
                        IcebergOutputProperties props = ConnectorFactory.icebergProperties(dto.configuration());
                        props.setAutoCreateTable(false);
                        Catalog catalog = IcebergCatalogFactory.createCatalog(props);
                        return new ResolvedTarget(dto.name(), props, new CatalogHandle(catalog, true));
                    }
                }
            }
        }

        boolean isDefaultAlias = name == null
                || "default".equalsIgnoreCase(name)
                || "active".equalsIgnoreCase(name)
                || (defaultProperties != null && name.equalsIgnoreCase(defaultProperties.getCatalogName()));

        if ((isDefaultAlias || defaultFallbackAllowed) && defaultProperties != null) {
            Catalog catalog = sharedCatalog != null ? sharedCatalog : IcebergCatalogFactory.createCatalog(defaultProperties);
            boolean owned = sharedCatalog == null;
            String effectiveName = name != null ? name : "default";
            return new ResolvedTarget(effectiveName, defaultProperties, new CatalogHandle(catalog, owned));
        }

        throw new NoSuchElementException("No Apache Iceberg Lakehouse connector named '" + name + "' is available.");
    }

    /** Resolves existing Iceberg table identifiers for the target connector without mutating/creating tables. */
    public static List<TableIdentifier> resolveExistingTables(
            Catalog catalog,
            IcebergOutputProperties properties,
            String tableOverride
    ) {
        String namespaceName = IcebergDocumentMapper.blankToNull(properties != null ? properties.getNamespace() : null);
        if (namespaceName == null) {
            namespaceName = "enterprise_data";
        }
        String defaultTable = IcebergDocumentMapper.blankToNull(properties != null ? properties.getTableName() : null);
        if (defaultTable == null) {
            defaultTable = "documents";
        }

        String override = IcebergDocumentMapper.blankToNull(tableOverride);
        if (override != null) {
            TableIdentifier explicit = override.contains(".")
                    ? TableIdentifier.parse(override)
                    : TableIdentifier.of(Namespace.of(namespaceName), override);
            return safeTableExists(catalog, explicit) ? List.of(explicit) : List.of();
        }

        Namespace namespace = Namespace.of(namespaceName);
        if (properties != null && properties.getTableStrategy() == IcebergOutputProperties.TableStrategy.PER_DOCUMENT_TYPE) {
            List<TableIdentifier> allInNamespace;
            try {
                allInNamespace = catalog.listTables(namespace);
            } catch (NoSuchNamespaceException e) {
                return List.of();
            }
            return allInNamespace != null ? allInNamespace : List.of();
        }

        TableIdentifier unified = TableIdentifier.of(namespace, defaultTable);
        return safeTableExists(catalog, unified) ? List.of(unified) : List.of();
    }

    /** Builds a read projection keeping all columns or omitting {@code extracted_text} for lightweight scans. */
    public static Schema projectionSchema(Table table, boolean includeExtractedText) {
        Schema schema = table.schema();
        if (includeExtractedText || schema.findField("extracted_text") == null) {
            return schema;
        }
        List<String> cols = new ArrayList<>(schema.columns().size());
        for (Types.NestedField field : schema.columns()) {
            if (!"extracted_text".equals(field.name())) {
                cols.add(field.name());
            }
        }
        return schema.select(cols);
    }

    /** Scans one Iceberg table with optional snapshot time-travel and Iceberg pushdown expression. */
    public static List<IcebergDocumentMapper.IcebergDocumentRecord> scanTable(
            Table table,
            Long snapshotId,
            Long asOfTimeMillis,
            Expression pushdownFilter,
            boolean includeExtractedText,
            int maxRows
    ) throws IOException {
        table.refresh();
        if (table.currentSnapshot() == null) {
            return List.of();
        }

        TableScan scan = table.newScan().caseSensitive(false);
        if (snapshotId != null) {
            if (table.snapshot(snapshotId) == null) {
                throw new NoSuchElementException(
                        "Snapshot " + snapshotId + " was not found in Iceberg table '" + table.name() + "'.");
            }
            scan = scan.useSnapshot(snapshotId);
        } else if (asOfTimeMillis != null) {
            scan = scan.asOfTime(asOfTimeMillis);
        }

        if (pushdownFilter != null && pushdownFilter != Expressions.alwaysTrue()) {
            scan = scan.filter(pushdownFilter);
        }

        Schema readSchema = projectionSchema(table, includeExtractedText);
        scan = scan.project(readSchema);

        List<IcebergDocumentMapper.IcebergDocumentRecord> results = new ArrayList<>();
        long scanOrder = 0L;

        try (CloseableIterable<FileScanTask> tasks = scan.planFiles()) {
            for (FileScanTask task : tasks) {
                if (results.size() >= maxRows) {
                    break;
                }
                if (task.file().format() == FileFormat.PARQUET && task.deletes().isEmpty()) {
                    long dataSeq = task.file().dataSequenceNumber() != null ? task.file().dataSequenceNumber() : 0L;
                    Parquet.ReadBuilder readerBuilder = Parquet.read(table.io().newInputFile(task.file().location()))
                            .project(readSchema)
                            .split(task.start(), task.length())
                            .caseSensitive(false)
                            .createReaderFunc(fileSchema -> GenericParquetReaders.buildReader(readSchema, fileSchema));
                    if (task.residual() != null && task.residual() != Expressions.alwaysTrue()) {
                        readerBuilder = readerBuilder.filter(task.residual()).filterRecords(true);
                    }
                    try (CloseableIterable<Record> records = readerBuilder.build()) {
                        for (Record record : records) {
                            results.add(IcebergDocumentMapper.fromRecord(record, readSchema, table.name(), dataSeq, scanOrder++));
                            if (results.size() >= maxRows) {
                                break;
                            }
                        }
                    }
                } else {
                    // Fallback to IcebergGenerics for non-Parquet formats or v2 delete-file tables.
                    return scanViaGenerics(table, snapshotId, asOfTimeMillis, pushdownFilter, readSchema, maxRows);
                }
            }
        }
        return results;
    }

    private static List<IcebergDocumentMapper.IcebergDocumentRecord> scanViaGenerics(
            Table table,
            Long snapshotId,
            Long asOfTimeMillis,
            Expression pushdownFilter,
            Schema readSchema,
            int maxRows
    ) throws IOException {
        IcebergGenerics.ScanBuilder builder = IcebergGenerics.read(table)
                .caseInsensitive()
                .project(readSchema);
        if (snapshotId != null) {
            builder = builder.useSnapshot(snapshotId);
        } else if (asOfTimeMillis != null) {
            builder = builder.asOfTime(asOfTimeMillis);
        }
        if (pushdownFilter != null && pushdownFilter != Expressions.alwaysTrue()) {
            builder = builder.where(pushdownFilter);
        }

        List<IcebergDocumentMapper.IcebergDocumentRecord> results = new ArrayList<>();
        long scanOrder = 0L;
        try (CloseableIterable<Record> records = builder.build()) {
            for (Record record : records) {
                results.add(IcebergDocumentMapper.fromRecord(record, readSchema, table.name(), 0L, scanOrder++));
                if (results.size() >= maxRows) {
                    break;
                }
            }
        }
        return results;
    }

    /** Collapses multiple Iceberg append snapshots per logical document to the newest record. */
    public static List<IcebergDocumentMapper.IcebergDocumentRecord> latestByDocument(
            List<IcebergDocumentMapper.IcebergDocumentRecord> records
    ) {
        Map<String, IcebergDocumentMapper.IcebergDocumentRecord> latest = new LinkedHashMap<>();
        for (IcebergDocumentMapper.IcebergDocumentRecord rec : records) {
            String key = rec.logicalKey();
            IcebergDocumentMapper.IcebergDocumentRecord existing = latest.get(key);
            if (existing == null || IcebergDocumentMapper.RECENCY_ORDER.compare(rec, existing) >= 0) {
                latest.put(key, rec);
            }
        }
        return new ArrayList<>(latest.values());
    }

    /** Picks the single newest record across candidate versions, or empty if none. */
    public static Optional<IcebergDocumentMapper.IcebergDocumentRecord> selectLatest(
            List<IcebergDocumentMapper.IcebergDocumentRecord> records
    ) {
        return records.stream().max(IcebergDocumentMapper.RECENCY_ORDER);
    }

    /**
     * Resolves the current authoritative latest row (including {@code DELETE} tombstones that lack metadata or
     * carry a newer timestamp) for each {@code document_id} in {@code docIds}.
     */
    public static Map<String, IcebergDocumentMapper.IcebergDocumentRecord> resolveLatestForDocIds(
            Table table,
            Set<String> docIds,
            int scanCap
    ) throws IOException {
        if (docIds == null || docIds.isEmpty() || table.schema().findField("document_id") == null) {
            return Map.of();
        }
        Map<String, IcebergDocumentMapper.IcebergDocumentRecord> latest = new LinkedHashMap<>();
        List<String> allIds = new ArrayList<>(docIds);
        int batchSize = 100;
        for (int start = 0; start < allIds.size(); start += batchSize) {
            List<String> batch = allIds.subList(start, Math.min(allIds.size(), start + batchSize));
            Expression expr = Expressions.in("document_id", batch);
            List<IcebergDocumentMapper.IcebergDocumentRecord> rows =
                    scanTable(table, null, null, expr, false, scanCap);
            for (IcebergDocumentMapper.IcebergDocumentRecord rec : latestByDocument(rows)) {
                latest.merge(rec.logicalKey(), rec, (a, b) ->
                        IcebergDocumentMapper.RECENCY_ORDER.compare(a, b) >= 0 ? a : b);
            }
        }
        return latest;
    }

    /** Combines two Iceberg expressions safely while ignoring {@code alwaysTrue()}. */
    public static Expression and(Expression left, Expression right) {
        if (left == null || left == Expressions.alwaysTrue()) {
            return right != null ? right : Expressions.alwaysTrue();
        }
        if (right == null || right == Expressions.alwaysTrue()) {
            return left;
        }
        return Expressions.and(left, right);
    }

    /** Converts an {@link Instant} into Iceberg microsecond timestamp literal units. */
    public static long toMicros(Instant instant) {
        return DateTimeUtil.microsFromInstant(Objects.requireNonNull(instant, "instant"));
    }

    /** Builds a {@code crawled_at} range pushdown predicate when a column is present. */
    public static Expression crawledAtBetween(Schema schema, Instant startInclusive, Instant endExclusive) {
        if (schema == null || schema.findField("crawled_at") == null) {
            return Expressions.alwaysTrue();
        }
        Expression expr = Expressions.alwaysTrue();
        if (startInclusive != null) {
            expr = and(expr, Expressions.greaterThanOrEqual("crawled_at", toMicros(startInclusive)));
        }
        if (endExclusive != null) {
            expr = and(expr, Expressions.lessThan("crawled_at", toMicros(endExclusive)));
        }
        return expr;
    }

    /** Checks whether a dynamic primitive string column exists on the table schema for pushdown. */
    public static boolean hasPrimitiveStringColumn(Schema schema, String columnName) {
        if (schema == null || columnName == null || columnName.isBlank()) {
            return false;
        }
        Types.NestedField field = schema.findField(columnName);
        return field != null && field.type().typeId() == Type.TypeID.STRING;
    }

    /** Parses an optional timestamp string (ISO-8601 instant or epoch millis). */
    public static Long parseTimestampMillis(String raw) {
        String trimmed = IcebergDocumentMapper.blankToNull(raw);
        if (trimmed == null) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            // Try ISO instant / date below.
        }
        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (Exception ignored) {
            // Try plain ISO local date (start of UTC day).
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid timestamp '" + raw + "'. Use ISO-8601 UTC (e.g. 2026-11-13T12:00:00Z) or epoch milliseconds.", e);
        }
    }

    /** Parses {@code timeWindow} such as {@code 24h}, {@code 7d}, {@code PT24H}, {@code P7D}, or {@code start..end}. */
    public static TimeBounds parseTimeWindow(String timeWindow, Clock clock) {
        String raw = IcebergDocumentMapper.blankToNull(timeWindow);
        if (raw == null) {
            return null;
        }
        Clock effectiveClock = clock != null ? clock : Clock.systemUTC();
        Instant now = effectiveClock.instant();

        String separator = raw.contains("..") ? "\\.\\." : raw.contains("/") && !raw.startsWith("P") ? "/" : null;
        if (separator != null) {
            String[] parts = raw.split(separator, 2);
            if (parts.length == 2) {
                Instant start = Instant.ofEpochMilli(parseTimestampMillis(parts[0]));
                Instant end = Instant.ofEpochMilli(parseTimestampMillis(parts[1]));
                if (end.isBefore(start)) {
                    throw new IllegalArgumentException("Invalid timeWindow: end precedes start.");
                }
                return new TimeBounds(start, end);
            }
        }

        Matcher shortMatch = SHORT_DURATION.matcher(raw);
        if (shortMatch.matches()) {
            long amount = Long.parseLong(shortMatch.group(1));
            if (amount <= 0) {
                throw new IllegalArgumentException("timeWindow duration must be positive: " + raw);
            }
            String unit = shortMatch.group(2).toLowerCase(Locale.ROOT);
            Duration d = switch (unit) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                case "w" -> Duration.ofDays(amount * 7);
                default -> throw new IllegalArgumentException("Unsupported timeWindow unit: " + unit);
            };
            return new TimeBounds(now.minus(d), now);
        }

        try {
            Duration d = Duration.parse(raw.toUpperCase(Locale.ROOT));
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("timeWindow duration must be positive: " + raw);
            }
            return new TimeBounds(now.minus(d), now);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignored) {
            // Try Period (e.g. P7D).
        }

        try {
            java.time.Period period = java.time.Period.parse(raw.toUpperCase(Locale.ROOT));
            Instant start = now.atZone(ZoneOffset.UTC).minus(period).toInstant();
            if (!start.isBefore(now)) {
                throw new IllegalArgumentException("timeWindow period must be positive: " + raw);
            }
            return new TimeBounds(start, now);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid timeWindow '" + timeWindow + "'. Examples: '24h', '7d', 'PT12H', 'P30D', or '<isoStart>..<isoEnd>'.", e);
        }
    }

    private static boolean safeTableExists(Catalog catalog, TableIdentifier id) {
        try {
            return catalog.tableExists(id);
        } catch (Exception e) {
            log.debug("Iceberg tableExists check failed for {}: {}", id, e.getMessage());
            return false;
        }
    }
}
