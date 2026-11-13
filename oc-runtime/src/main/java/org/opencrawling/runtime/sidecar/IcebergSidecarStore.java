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
package org.opencrawling.runtime.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.opencrawling.core.ois.OisSidecar;
import org.opencrawling.iceberg.output.catalog.IcebergCatalogFactory;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.opencrawling.runtime.iceberg.IcebergDocumentMapper;
import org.opencrawling.runtime.iceberg.IcebergTableAccess;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * {@link SidecarStore} backed by Apache Iceberg Lakehouse Parquet tables.
 *
 * <p>Synthesizes canonical OIS sidecar envelopes directly from Iceberg metadata columns without reading binary objects,
 * applies Iceberg pushdown expressions for {@code source_type} and partition date prefixes, collapses snapshots to
 * the latest version per document, and caches rendered sidecar bytes per request so {@link #read} avoids redundant scans.
 */
public final class IcebergSidecarStore implements SidecarStore {

    static final int DEFAULT_MAX_SCAN_ROWS = 50_000;

    private final IcebergTableAccess.CatalogHandle catalogHandle;
    private final IcebergOutputProperties properties;
    private final String suffix;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, byte[]> keyCache = new LinkedHashMap<>();

    public IcebergSidecarStore(Catalog catalog, boolean ownsCatalog, IcebergOutputProperties properties, String suffix) {
        this.catalogHandle = new IcebergTableAccess.CatalogHandle(Objects.requireNonNull(catalog, "catalog"), ownsCatalog);
        this.properties = Objects.requireNonNull(properties, "properties");
        this.suffix = (suffix != null && !suffix.isBlank()) ? suffix : ".ois.json";
    }

    public static IcebergSidecarStore open(IcebergOutputProperties properties) {
        return open(properties, ".ois.json");
    }

    public static IcebergSidecarStore open(IcebergOutputProperties properties, String suffix) {
        IcebergOutputProperties safeProps = Objects.requireNonNull(properties, "properties");
        safeProps.setAutoCreateTable(false);
        Catalog catalog = IcebergCatalogFactory.createCatalog(safeProps);
        return new IcebergSidecarStore(catalog, true, safeProps, suffix);
    }

    @Override
    public Page list(String prefix, String startAfter, int maxKeys) {
        int limit = Math.max(1, maxKeys);
        String effectivePrefix = prefix != null ? prefix : "";
        IcebergDocumentMapper.SidecarPrefixHints hints = IcebergDocumentMapper.parseSidecarPrefix(effectivePrefix);
        Expression pushdown = buildPushdown(hints);

        TreeMap<String, byte[]> sorted = new TreeMap<>();
        try {
            List<TableIdentifier> tables = IcebergTableAccess.resolveExistingTables(
                    catalogHandle.catalog(), properties, null);
            for (TableIdentifier tableId : tables) {
                Table table = catalogHandle.catalog().loadTable(tableId);
                Expression tableFilter = applicableFilter(table, pushdown, hints);
                List<IcebergDocumentMapper.IcebergDocumentRecord> raw =
                        IcebergTableAccess.scanTable(table, null, null, tableFilter, false, DEFAULT_MAX_SCAN_ROWS);
                List<IcebergDocumentMapper.IcebergDocumentRecord> collapsed = IcebergTableAccess.latestByDocument(raw);
                // Tombstones carry source_type="generic" and a deletion timestamp, so re-verify matched document_ids
                // across the full table when a partition/source pushdown was applied.
                Map<String, IcebergDocumentMapper.IcebergDocumentRecord> latestAuthoritative = Map.of();
                if (tableFilter != Expressions.alwaysTrue() && !collapsed.isEmpty()) {
                    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
                    for (IcebergDocumentMapper.IcebergDocumentRecord rec : collapsed) {
                        if (IcebergDocumentMapper.blankToNull(rec.documentId()) != null) {
                            ids.add(rec.documentId());
                        }
                    }
                    latestAuthoritative = IcebergTableAccess.resolveLatestForDocIds(table, ids, DEFAULT_MAX_SCAN_ROWS);
                }
                for (IcebergDocumentMapper.IcebergDocumentRecord candidate : collapsed) {
                    IcebergDocumentMapper.IcebergDocumentRecord doc =
                            latestAuthoritative.getOrDefault(candidate.logicalKey(), candidate);
                    if (doc.isDelete()) {
                        continue;
                    }
                    String key = IcebergDocumentMapper.sidecarKey(doc, suffix);
                    if (!key.startsWith(effectivePrefix)) {
                        continue;
                    }
                    OisSidecar envelope = IcebergDocumentMapper.toOisSidecar(doc, properties);
                    sorted.put(key, mapper.writeValueAsBytes(envelope));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed listing OIS sidecars from Iceberg catalog.", e);
        }

        keyCache.clear();
        keyCache.putAll(sorted);

        List<String> keys = new ArrayList<>();
        Map<String, byte[]> tail = startAfter == null ? sorted : sorted.tailMap(startAfter, false);
        for (String key : tail.keySet()) {
            if (keys.size() == limit) {
                return new Page(keys, true);
            }
            keys.add(key);
        }
        return new Page(keys, false);
    }

    @Override
    public byte[] read(String key, int maxBytes) {
        if (key == null || key.isBlank()) {
            return null;
        }
        byte[] bytes = keyCache.get(key);
        if (bytes == null && keyCache.isEmpty()) {
            list("", null, DEFAULT_MAX_SCAN_ROWS);
            bytes = keyCache.get(key);
        }
        if (bytes == null) {
            return null;
        }
        return bytes.length > maxBytes ? Arrays.copyOf(bytes, maxBytes + 1) : bytes;
    }

    @Override
    public void close() {
        keyCache.clear();
        catalogHandle.close();
    }

    private static Expression buildPushdown(IcebergDocumentMapper.SidecarPrefixHints hints) {
        if (hints == null) {
            return Expressions.alwaysTrue();
        }
        Expression expr = Expressions.alwaysTrue();
        if (hints.sourceTypePrefix() != null && !hints.sourceTypePrefix().isBlank()) {
            expr = hints.exactSourceType()
                    ? IcebergTableAccess.and(expr, Expressions.equal("source_type", hints.sourceTypePrefix()))
                    : IcebergTableAccess.and(expr, Expressions.startsWith("source_type", hints.sourceTypePrefix()));
        }
        return expr;
    }

    private static Expression applicableFilter(
            Table table,
            Expression basePushdown,
            IcebergDocumentMapper.SidecarPrefixHints hints
    ) {
        Expression expr = Expressions.alwaysTrue();
        if (basePushdown != null && basePushdown != Expressions.alwaysTrue()
                && table.schema().findField("source_type") != null) {
            expr = IcebergTableAccess.and(expr, basePushdown);
        }
        if (hints != null && hints.yearMonth() != null) {
            Instant start = hints.yearMonth().atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = hints.yearMonth().plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            expr = IcebergTableAccess.and(expr, IcebergTableAccess.crawledAtBetween(table.schema(), start, end));
        }
        return expr;
    }
}
