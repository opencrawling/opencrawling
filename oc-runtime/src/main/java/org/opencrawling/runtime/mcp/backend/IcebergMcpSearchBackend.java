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
package org.opencrawling.runtime.mcp.backend;

import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.opencrawling.iceberg.output.IcebergOutputConnector;
import org.opencrawling.iceberg.output.catalog.IcebergCatalogFactory;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.opencrawling.runtime.iceberg.IcebergDocumentMapper;
import org.opencrawling.runtime.iceberg.IcebergMcpProperties;
import org.opencrawling.runtime.iceberg.IcebergTableAccess;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * {@link McpVectorSearchBackend} for Apache Iceberg Lakehouse tables when {@code spring.opencrawling.output.type=iceberg}.
 *
 * <p>Implements Zero-Trust document inspection ({@link #getDocumentContent}) and accessible source discovery
 * ({@link #listAccessibleSources}) directly against Iceberg tables via Parquet pushdowns and
 * {@code DocumentAccessGate}, while failing fast with diagnostic guidance on {@link #secureVectorSearch}
 * because Iceberg tables do not store approximate nearest neighbor (ANN) vector indexes.
 */
@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "iceberg")
public class IcebergMcpSearchBackend extends GatedMcpSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(IcebergMcpSearchBackend.class);

    private final IcebergOutputProperties properties;
    private final Catalog sharedCatalog;
    private final IcebergOutputConnector outputConnector;
    private final int maxScan;

    @Autowired
    public IcebergMcpSearchBackend(
            IcebergOutputProperties properties,
            ObjectProvider<Catalog> catalogProvider,
            ObjectProvider<IcebergOutputConnector> outputConnectorProvider,
            ObjectProvider<IcebergMcpProperties> icebergMcpProperties) {
        this.properties = properties != null ? properties : new IcebergOutputProperties();
        this.sharedCatalog = catalogProvider != null ? catalogProvider.getIfAvailable() : null;
        this.outputConnector = outputConnectorProvider != null ? outputConnectorProvider.getIfAvailable() : null;
        IcebergMcpProperties mcpProps = icebergMcpProperties != null ? icebergMcpProperties.getIfAvailable() : null;
        this.maxScan = mcpProps != null ? mcpProps.effectiveMaxScan() : new IcebergMcpProperties().effectiveMaxScan();
        log.info("Initialized IcebergMcpSearchBackend for catalog '{}' (namespace '{}', table '{}').",
                this.properties.getCatalogName(), this.properties.getNamespace(), this.properties.getTableName());
    }

    /** Convenience constructor for unit tests using an explicit Iceberg {@link Catalog}. */
    public IcebergMcpSearchBackend(Catalog catalog, IcebergOutputProperties properties) {
        this(catalog, properties, new IcebergMcpProperties().effectiveMaxScan());
    }

    /** Convenience constructor for unit tests with explicit scan cap. */
    public IcebergMcpSearchBackend(Catalog catalog, IcebergOutputProperties properties, int maxScan) {
        this.sharedCatalog = Objects.requireNonNull(catalog, "catalog");
        this.properties = properties != null ? properties : new IcebergOutputProperties();
        this.outputConnector = null;
        this.maxScan = Math.max(1, maxScan);
    }

    @Override
    public String backendType() {
        return "iceberg";
    }

    @Override
    public List<McpVectorServer.DocumentSearchResult> secureVectorSearch(
            String query,
            String userPrincipal,
            List<String> userRoles,
            int maxResults,
            double minScore,
            Integer dimensions
    ) {
        throw new UnsupportedOperationException(
                "Apache Iceberg is an analytical Parquet Lakehouse storage format without an ANN vector index. "
                        + "Use getDocumentContent, listAccessibleSources, readOisSidecars, or the Iceberg Lakehouse MCP tools "
                        + "(listIcebergSnapshots, getIcebergDocumentAsOf, queryIcebergEntities, getIcebergIngestionStats) instead, "
                        + "or switch spring.opencrawling.output.type to a vector backend (pgvector, qdrant, milvus, vespa, opensearch, elasticsearch, solr).");
    }

    @Override
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        String target = IcebergDocumentMapper.blankToNull(documentUri);
        if (target == null) {
            throw new NoSuchElementException("Document not found or access denied.");
        }
        log.info("Iceberg getDocumentContent. URI/ID: '{}', Principal: '{}'", target, userPrincipal);

        List<IcebergDocumentMapper.IcebergDocumentRecord> allVersions = new ArrayList<>();
        try (IcebergTableAccess.CatalogHandle handle = openCatalog()) {
            List<TableIdentifier> tables = IcebergTableAccess.resolveExistingTables(handle.catalog(), properties, null);
            for (TableIdentifier tableId : tables) {
                Table table = handle.catalog().loadTable(tableId);
                Expression filter = buildUriOrIdFilter(table, target);
                List<IcebergDocumentMapper.IcebergDocumentRecord> scanned =
                        IcebergTableAccess.scanTable(table, null, null, filter, true, maxScan);
                for (IcebergDocumentMapper.IcebergDocumentRecord rec : scanned) {
                    if (target.equals(rec.documentUri()) || target.equals(rec.documentId())) {
                        allVersions.add(rec);
                    }
                }
            }
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed retrieving document '{}' from Iceberg: {}", target, e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details from Iceberg", e);
        }

        IcebergDocumentMapper.IcebergDocumentRecord latest = IcebergTableAccess.selectLatest(allVersions)
                .filter(rec -> !rec.isDelete())
                .orElseThrow(() -> new NoSuchElementException("Document not found or access denied."));

        return readable(userPrincipal, userRoles, List.of(latest), IcebergDocumentMapper::toResourceRef).stream()
                .findFirst()
                .map(doc -> {
                    String resolvedUri = IcebergDocumentMapper.blankToNull(doc.documentUri()) != null
                            ? doc.documentUri() : doc.documentId();
                    return new McpVectorServer.DocumentDetailsResult(
                            doc.id(),
                            resolvedUri,
                            doc.extractedText(),
                            IcebergDocumentMapper.toPublicMetadataMap(doc),
                            IcebergDocumentMapper.formatAclSummary(doc)
                    );
                })
                .orElseThrow(() -> new NoSuchElementException("Document not found or access denied."));
    }

    @Override
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Iceberg listAccessibleSources. Principal: '{}'", userPrincipal);

        List<IcebergDocumentMapper.IcebergDocumentRecord> activeLatest = new ArrayList<>();
        try (IcebergTableAccess.CatalogHandle handle = openCatalog()) {
            List<TableIdentifier> tables = IcebergTableAccess.resolveExistingTables(handle.catalog(), properties, null);
            for (TableIdentifier tableId : tables) {
                Table table = handle.catalog().loadTable(tableId);
                List<IcebergDocumentMapper.IcebergDocumentRecord> raw =
                        IcebergTableAccess.scanTable(table, null, null, Expressions.alwaysTrue(), false, maxScan);
                for (IcebergDocumentMapper.IcebergDocumentRecord doc : IcebergTableAccess.latestByDocument(raw)) {
                    if (!doc.isDelete()) {
                        activeLatest.add(doc);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed listing accessible sources from Iceberg: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources from Iceberg", e);
        }

        List<IcebergDocumentMapper.IcebergDocumentRecord> permitted =
                readable(userPrincipal, userRoles, activeLatest, IcebergDocumentMapper::toResourceRef);

        List<Map<String, Object>> summaries = new ArrayList<>(permitted.size());
        for (IcebergDocumentMapper.IcebergDocumentRecord doc : permitted) {
            Map<String, Object> summary = new LinkedHashMap<>();
            String resolvedUri = IcebergDocumentMapper.blankToNull(doc.documentUri()) != null
                    ? doc.documentUri() : doc.documentId();
            summary.put("id", doc.id());
            summary.put("documentId", doc.documentId());
            summary.put("uri", resolvedUri);
            summary.put("title", doc.title());
            summary.put("mimeType", doc.mimeType());
            summary.put("sourceType", doc.sourceType());
            summary.put("contentLength", doc.contentLength());
            summary.put("sha256Checksum", doc.sha256Checksum());
            summary.put("claimCheckUri", doc.claimCheckUri());
            summary.put("crawledAt", doc.crawledAt() != null ? doc.crawledAt().toString() : "");
            summary.put("tableName", doc.tableName());
            summary.put("acl", IcebergDocumentMapper.formatAclSummary(doc));
            summaries.add(summary);
        }
        return summaries;
    }

    private IcebergTableAccess.CatalogHandle openCatalog() {
        if (sharedCatalog != null) {
            return new IcebergTableAccess.CatalogHandle(sharedCatalog, false);
        }
        if (outputConnector != null && outputConnector.getCatalog() != null) {
            return new IcebergTableAccess.CatalogHandle(outputConnector.getCatalog(), false);
        }
        return new IcebergTableAccess.CatalogHandle(IcebergCatalogFactory.createCatalog(properties), true);
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
}
