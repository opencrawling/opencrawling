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

import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.iceberg.output.IcebergOutputConnector;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.security.CallerResolver;
import org.opencrawling.runtime.security.IdentityProperties;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.opencrawling.runtime.security.VerifiedCallerRequiredException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link IcebergMcpTools} against real Iceberg snapshots committed by {@link IcebergOutputConnector},
 * covering snapshot history, point-in-time retrieval with snapshot ACL shifts, entity pushdown queries,
 * partition-level statistics, PER_DOCUMENT_TYPE tables, and verified JWT caller identity modes.
 */
class IcebergMcpToolsTest {

    private InMemoryCatalog catalog;
    private IcebergOutputProperties outputProperties;
    private IcebergMcpProperties mcpProperties;
    private IcebergOutputConnector connector;
    private Clock fixedClock;
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new InMemoryCatalog();
        catalog.initialize("iceberg_mcp_test", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "inmemory://lakehouse"));

        outputProperties = new IcebergOutputProperties();
        outputProperties.setCatalogType("in-memory");
        outputProperties.setNamespace("enterprise_data");
        outputProperties.setTableName("documents");
        outputProperties.setAutoCreateTable(true);
        outputProperties.setAutoSchemaEvolution(true);
        outputProperties.setPartitionBy(IcebergOutputProperties.PartitionStrategy.YEAR_MONTH);

        mcpProperties = new IcebergMcpProperties();
        mcpProperties.setEnabled(true);

        now = Instant.parse("2026-11-13T12:00:00Z");
        fixedClock = Clock.fixed(now, java.time.ZoneOffset.UTC);

        connector = new IcebergOutputConnector(outputProperties, catalog, null);
        connector.connect();
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        connector.disconnect();
    }

    private IcebergMcpTools tools(CallerResolver resolver) {
        return new IcebergMcpTools(
                catalog,
                outputProperties,
                mcpProperties,
                List::of,
                DocumentAccessGate.metadataDefault(),
                resolver,
                fixedClock
        );
    }

    private IcebergMcpTools defaultTools() {
        return tools(CallerResolver.asserted());
    }

    @Test
    void listSnapshotsAndTimeTravelRespectsHistoricalAndCurrentAclsAndDeletes() throws Exception {
        // Snapshot 1: contract readable by both finance and legal teams
        ingest(doc("doc-contract-1", "alfresco://contracts/msa-v1.pdf", "MSA v1",
                "alfresco", "application/pdf", "Initial draft shared with legal and finance.",
                now.minus(2, ChronoUnit.HOURS),
                Map.of("entity.contract_number", List.of("CTR-100"), "entity.vendor", List.of("Acme Corp")),
                List.of(
                        new PermissionRule("finance", "group", "Finance", "read"),
                        new PermissionRule("legal", "group", "Legal", "read")
                )));

        Table table = catalog.loadTable(TableIdentifier.of("enterprise_data", "documents"));
        long snap1Id = table.currentSnapshot().snapshotId();
        long snap1Time = table.currentSnapshot().timestampMillis();

        // Ensure snapshot timestamps are strictly ordered for asOfTimestamp resolution
        Thread.sleep(5);

        // Snapshot 2: updated content AND tightened ACL removing 'legal' (finance-only, explicitly denies eve)
        ingest(doc("doc-contract-1", "alfresco://contracts/msa-v1.pdf", "MSA v2 Confidential",
                "alfresco", "application/pdf", "Signed pricing annex visible strictly to finance.",
                now.minus(1, ChronoUnit.HOURS),
                Map.of("entity.contract_number", List.of("CTR-100"), "entity.vendor", List.of("Acme Corp")),
                List.of(
                        new PermissionRule("finance", "group", "Finance", "read"),
                        new PermissionRule("eve", "user", "Eve", "deny")
                )));

        table.refresh();
        long snap2Id = table.currentSnapshot().snapshotId();
        long snap2Time = table.currentSnapshot().timestampMillis();
        assertThat(snap2Time).isGreaterThan(snap1Time);

        Thread.sleep(5);

        // Snapshot 3: document deleted via tombstone
        connector.send(RepositoryDocument.createTombstone("doc-contract-1", "alfresco://contracts/msa-v1.pdf")).block();

        IcebergMcpTools tools = defaultTools();

        // Verify listIcebergSnapshots newest-first order and clamping
        IcebergMcpTools.IcebergSnapshotsResponse allSnaps = tools.listIcebergSnapshots(null, null, null, "alice", "finance");
        assertThat(allSnaps.tableName()).isEqualTo("documents");
        assertThat(allSnaps.totalSnapshots()).isEqualTo(3);
        assertThat(allSnaps.truncated()).isFalse();
        assertThat(allSnaps.snapshots()).hasSize(3);
        assertThat(allSnaps.snapshots().get(0).snapshotId()).isEqualTo(allSnaps.currentSnapshotId());
        assertThat(allSnaps.snapshots().get(2).snapshotId()).isEqualTo(snap1Id);

        IcebergMcpTools.IcebergSnapshotsResponse cappedSnaps = tools.listIcebergSnapshots(null, null, 2, "alice", "finance");
        assertThat(cappedSnaps.truncated()).isTrue();
        assertThat(cappedSnaps.snapshots()).hasSize(2);

        // Time-travel to Snapshot 1: legal user CAN access v1, unauthorized user cannot
        IcebergMcpTools.IcebergDocumentAsOfResult v1Legal =
                tools.getIcebergDocumentAsOf(null, "alfresco://contracts/msa-v1.pdf", snap1Id, null, null, "linda", "legal");
        assertThat(v1Legal.title()).isEqualTo("MSA v1");
        assertThat(v1Legal.extractedText()).contains("Initial draft");
        assertThat(v1Legal.snapshotId()).isEqualTo(snap1Id);

        assertThatThrownBy(() -> tools.getIcebergDocumentAsOf(
                null, "alfresco://contracts/msa-v1.pdf", snap1Id, null, null, "mallory", "marketing"))
                .isInstanceOf(NoSuchElementException.class);

        // Time-travel to Snapshot 2 by explicit ID: legal user is NOW denied, finance user gets v2
        assertThatThrownBy(() -> tools.getIcebergDocumentAsOf(
                null, "alfresco://contracts/msa-v1.pdf", snap2Id, null, null, "linda", "legal"))
                .isInstanceOf(NoSuchElementException.class);

        IcebergMcpTools.IcebergDocumentAsOfResult v2Finance =
                tools.getIcebergDocumentAsOf(null, "alfresco://contracts/msa-v1.pdf", snap2Id, null, null, "bob", "finance");
        assertThat(v2Finance.title()).isEqualTo("MSA v2 Confidential");
        assertThat(v2Finance.extractedText()).contains("Signed pricing annex");

        // Time-travel via asOfTimestamp strictly between snap1 and snap2 resolves Snapshot 1
        String betweenTs = Instant.ofEpochMilli(snap1Time).toString();
        IcebergMcpTools.IcebergDocumentAsOfResult asOfSnap1 =
                tools.getIcebergDocumentAsOf(null, "doc-contract-1", null, betweenTs, null, "linda", "legal");
        assertThat(asOfSnap1.snapshotId()).isEqualTo(snap1Id);
        assertThat(asOfSnap1.title()).isEqualTo("MSA v1");

        // Current snapshot (after DELETE tombstone): both legal and finance receive NoSuchElementException
        assertThatThrownBy(() -> tools.getIcebergDocumentAsOf(
                null, "alfresco://contracts/msa-v1.pdf", null, null, null, "bob", "finance"))
                .isInstanceOf(NoSuchElementException.class);

        // Validation guards
        assertThatThrownBy(() -> tools.getIcebergDocumentAsOf(
                null, "doc-contract-1", snap1Id, betweenTs, null, "bob", "finance"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.getIcebergDocumentAsOf(
                null, "doc-contract-1", 999999999L, null, null, "bob", "finance"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void queryIcebergEntitiesSupportsEvolvedColumnsMapKeysExplicitDenyTombstonesAndTimeWindow() {
        // 1) Evolved entity columns (contract_number, vendor), accessible to finance
        ingest(doc("doc-e1", "alfresco://inv/inv1.pdf", "Invoice 1",
                "alfresco", "application/pdf", "Acme invoice",
                now.minus(3, ChronoUnit.HOURS),
                Map.of("entity.contract_number", List.of("CTR-2026-01"), "entity.vendor", List.of("Acme")),
                List.of(new PermissionRule("finance", "group", "Finance", "read"))));

        // 2) Non-evolved custom entity key (audit_tag -> stored only in extracted_entities map), explicitly denies bob
        ingest(doc("doc-e2", "alfresco://inv/inv2.pdf", "Audit Report",
                "alfresco", "application/pdf", "Internal audit findings",
                now.minus(2, ChronoUnit.HOURS),
                Map.of("entity.contract_number", List.of("CTR-2026-02"), "entity.audit_tag", List.of("AUDIT-99")),
                List.of(
                        new PermissionRule("finance", "group", "Finance", "read"),
                        new PermissionRule("bob", "user", "Bob", "deny")
                )));

        // 3) Doc updated in newer snapshot so OldVendor no longer matches
        ingest(doc("doc-e3", "alfresco://inv/inv3.pdf", "Vendor Migration v1",
                "alfresco", "text/plain", "Initial vendor",
                now.minus(4, ChronoUnit.HOURS),
                Map.of("entity.vendor", List.of("OldVendor")),
                List.of(new PermissionRule("finance", "group", "Finance", "read"))));
        ingest(doc("doc-e3", "alfresco://inv/inv3.pdf", "Vendor Migration v2",
                "alfresco", "text/plain", "Migrated vendor",
                now.minus(1, ChronoUnit.HOURS),
                Map.of("entity.vendor", List.of("NewVendor")),
                List.of(new PermissionRule("finance", "group", "Finance", "read"))));

        // 4) Doc matching contract CTR-DELETED, later removed by DELETE tombstone
        ingest(doc("doc-e4", "alfresco://inv/inv4.pdf", "Obsolete Contract",
                "alfresco", "application/pdf", "Should be deleted",
                now.minus(2, ChronoUnit.HOURS),
                Map.of("entity.contract_number", List.of("CTR-DELETED")),
                List.of(new PermissionRule("finance", "group", "Finance", "read"))));
        connector.send(RepositoryDocument.createTombstone("doc-e4", "alfresco://inv/inv4.pdf")).block();

        // 5) Old doc outside 24h window
        ingest(doc("doc-old", "alfresco://inv/inv-old.pdf", "Archive Contract",
                "alfresco", "application/pdf", "Old archive",
                now.minus(5, ChronoUnit.DAYS),
                Map.of("entity.contract_number", List.of("CTR-ARCHIVE")),
                List.of(new PermissionRule("finance", "group", "Finance", "read"))));

        IcebergMcpTools tools = defaultTools();

        // Bob has finance role, but explicit DENY hides doc-e2; tombstone hides doc-e4; 24h omits doc-old
        IcebergMcpTools.IcebergEntityQueryResponse bobContracts =
                tools.queryIcebergEntities(null, "entity.contract_number", null, "24h", 10, null, "bob", "finance");
        assertThat(bobContracts.matches())
                .extracting(IcebergMcpTools.EntityDocumentMatch::documentId)
                .containsExactly("doc-e1");

        // Alice (no deny) sees both doc-e1 and doc-e2 when timeWindow=10d
        IcebergMcpTools.IcebergEntityQueryResponse aliceAll =
                tools.queryIcebergEntities(null, "contract_number", null, "10d", 10, null, "alice", "finance");
        assertThat(aliceAll.matches())
                .extracting(IcebergMcpTools.EntityDocumentMatch::documentId)
                .containsExactly("doc-e2", "doc-e1", "doc-old");

        // Exact value pushdown on non-evolved map column 'audit_tag' enforces explicit deny for bob, allows alice
        IcebergMcpTools.IcebergEntityQueryResponse auditAlice =
                tools.queryIcebergEntities(null, "audit_tag", "AUDIT-99", null, 10, null, "alice", "finance");
        assertThat(auditAlice.matches())
                .extracting(IcebergMcpTools.EntityDocumentMatch::documentId)
                .containsExactly("doc-e2");

        IcebergMcpTools.IcebergEntityQueryResponse auditBob =
                tools.queryIcebergEntities(null, "audit_tag", "AUDIT-99", null, 10, null, "bob", "finance");
        assertThat(auditBob.matches()).isEmpty();

        // Deleted doc-e4 never resurrects
        assertThat(tools.queryIcebergEntities(null, "contract_number", "CTR-DELETED", null, 10, null, "alice", "finance")
                .matches()).isEmpty();

        // Overwritten OldVendor does not falsely match doc-e3
        assertThat(tools.queryIcebergEntities(null, "vendor", "OldVendor", null, 10, null, "alice", "finance")
                .matches()).isEmpty();
        assertThat(tools.queryIcebergEntities(null, "vendor", "NewVendor", null, 10, null, "alice", "finance")
                .matches())
                .extracting(IcebergMcpTools.EntityDocumentMatch::documentId)
                .containsExactly("doc-e3");
    }

    @Test
    void getIngestionStatsFiltersConfidentialRevokedDocsAndCountsPartitionsAndTombstones() {
        Instant octTs = Instant.parse("2026-10-15T10:00:00Z");
        Instant novTs = Instant.parse("2026-11-10T10:00:00Z");

        // Oct Alfresco PDF visible to Alice
        ingest(doc("stat-1", "alfresco://1", "Q4 Budget", "alfresco", "application/pdf", "Budget 2026",
                octTs, Map.of(), List.of(new PermissionRule("finance", "group", "Finance", "read"))));

        // Nov SharePoint TXT visible to Alice, later deleted
        ingest(doc("stat-2", "sp://2", "Temp Notes", "sharepoint", "text/plain", "Temporary text",
                novTs, Map.of(), List.of(new PermissionRule("alice", "user", "Alice", "read"))));
        connector.send(RepositoryDocument.createTombstone("stat-2", "sp://2")).block();

        // Confidential doc: Snap 1 allowed Alice, Snap 2 revoked Alice -> must NOT leak via stale Snap 1!
        ingest(doc("stat-revoked", "alfresco://revoked", "M&A Plan", "alfresco", "application/pdf", "Draft shared",
                novTs, Map.of(), List.of(new PermissionRule("finance", "group", "Finance", "read"))));
        ingest(doc("stat-revoked", "alfresco://revoked", "M&A Final", "alfresco", "application/pdf", "Executives only",
                novTs.plusSeconds(60), Map.of(), List.of(new PermissionRule("executives", "group", "Exec", "read"))));

        // Strictly executive doc never visible to Alice
        ingest(doc("stat-exec", "alfresco://secret", "Board Deck", "alfresco", "application/pdf", "Confidential board data",
                novTs, Map.of(), List.of(new PermissionRule("executives", "group", "Exec", "read"))));

        // Standalone tombstone (no prior UPSERT in history)
        connector.send(RepositoryDocument.createTombstone("stat-standalone-del", "alfresco://deleted-legacy")).block();

        IcebergMcpTools tools = defaultTools();
        IcebergMcpTools.IcebergIngestionStatsResponse aliceStats =
                tools.getIcebergIngestionStats(null, null, null, "alice", "finance");

        assertThat(aliceStats.activeDocumentCount()).isEqualTo(1);
        assertThat(aliceStats.upsertRecordCount()).isEqualTo(2); // stat-1 + prior readable stat-2
        assertThat(aliceStats.deleteTombstoneCount()).isEqualTo(2); // stat-2 tombstone + standalone tombstone
        assertThat(aliceStats.totalContentBytes()).isEqualTo("Budget 2026".getBytes(StandardCharsets.UTF_8).length);
        assertThat(aliceStats.byMimeType()).containsExactlyInAnyOrderEntriesOf(Map.of("application/pdf", 1L));
        assertThat(aliceStats.bySourceType()).containsExactlyInAnyOrderEntriesOf(Map.of("alfresco", 1L));

        IcebergMcpTools.IcebergIngestionStatsResponse execStats =
                tools.getIcebergIngestionStats(null, null, null, "ceo", "executives");
        assertThat(execStats.activeDocumentCount()).isEqualTo(2); // stat-revoked (latest) + stat-exec
    }

    @Test
    void perDocumentTypeTableStrategyAggregatesAcrossTypeTables() throws Exception {
        IcebergOutputProperties perTypeProps = new IcebergOutputProperties();
        perTypeProps.setCatalogType("in-memory");
        perTypeProps.setNamespace("per_type_ns");
        perTypeProps.setTableName("docs");
        perTypeProps.setTableStrategy(IcebergOutputProperties.TableStrategy.PER_DOCUMENT_TYPE);
        perTypeProps.setAutoCreateTable(true);
        perTypeProps.setAutoSchemaEvolution(true);

        InMemoryCatalog perTypeCatalog = new InMemoryCatalog();
        perTypeCatalog.initialize("per_type", Map.of(CatalogProperties.WAREHOUSE_LOCATION, "inmemory://pertype"));
        IcebergOutputConnector perTypeConnector = new IcebergOutputConnector(perTypeProps, perTypeCatalog, null);
        perTypeConnector.connect();
        try {
            perTypeConnector.send(doc("p1", "alfresco://p1", "Alfresco Policy", "alfresco", "application/pdf", "Policy",
                    now, Map.of("document_type", List.of("alfresco"), "entity.vendor", List.of("SharedVendor")),
                    List.of(new PermissionRule("public", "group", "public", "read")))).block();
            perTypeConnector.send(doc("p2", "sp://p2", "SharePoint Policy", "sharepoint", "text/plain", "SP Policy",
                    now, Map.of("document_type", List.of("sharepoint"), "entity.vendor", List.of("SharedVendor")),
                    List.of(new PermissionRule("public", "group", "public", "read")))).block();

            IcebergMcpTools multiTableTools = new IcebergMcpTools(
                    perTypeCatalog, perTypeProps, mcpProperties, List::of,
                    DocumentAccessGate.metadataDefault(), CallerResolver.asserted(), fixedClock);

            IcebergMcpTools.IcebergEntityQueryResponse allMatches =
                    multiTableTools.queryIcebergEntities(null, "vendor", "SharedVendor", null, 10, null, "guest", "");
            assertThat(allMatches.tablesQueried()).hasSize(2);
            assertThat(allMatches.matches())
                    .extracting(IcebergMcpTools.EntityDocumentMatch::documentId)
                    .containsExactlyInAnyOrder("p1", "p2");

            IcebergMcpTools.IcebergIngestionStatsResponse specificTableStats =
                    multiTableTools.getIcebergIngestionStats(null, null, "alfresco", "guest", "");
            assertThat(specificTableStats.tablesEvaluated()).hasSize(1);
            assertThat(specificTableStats.activeDocumentCount()).isEqualTo(1);
        } finally {
            perTypeConnector.disconnect();
        }
    }

    @Test
    void jwtIdentityModeEnforcesBearerTokenAndRejectsMissingAuth() {
        ingest(doc("jwt-doc", "alfresco://jwt/1", "JWT Secured Doc", "alfresco", "text/plain", "Finance only",
                now, Map.of("entity.contract_number", List.of("CTR-JWT")),
                List.of(new PermissionRule("finance", "group", "Finance", "read"))));

        CallerResolver verifiedResolver = CallerResolver.verified(new JwtCallerMapper(new IdentityProperties().getJwt()));
        IcebergMcpTools jwtTools = tools(verifiedResolver);

        // Missing token fails closed
        assertThatThrownBy(() -> jwtTools.listIcebergSnapshots(null, null, null, "spoofed-admin", "finance"))
                .isInstanceOf(VerifiedCallerRequiredException.class);
        assertThatThrownBy(() -> jwtTools.queryIcebergEntities(null, "contract_number", null, null, 10, null, "alice", "finance"))
                .isInstanceOf(VerifiedCallerRequiredException.class);

        // Token carrying hr role (not finance) cannot spoof userRoles='finance'
        authenticate("hr-user", List.of("hr"));
        assertThat(jwtTools.queryIcebergEntities(null, "contract_number", "CTR-JWT", null, 10, null, "alice", "finance")
                .matches()).isEmpty();

        // Token carrying finance role succeeds even if caller args claim something else
        authenticate("real-auditor", List.of("finance"));
        assertThat(jwtTools.queryIcebergEntities(null, "contract_number", "CTR-JWT", null, 10, null, "anonymous", "")
                .matches())
                .extracting(IcebergMcpTools.EntityDocumentMatch::documentId)
                .containsExactly("jwt-doc");

        // requireVerifiedCaller=true blocks even with asserted resolver if configured
        SecurityContextHolder.clearContext();
        mcpProperties.setRequireVerifiedCaller(true);
        assertThatThrownBy(() -> jwtTools.getIcebergIngestionStats(null, null, null, "alice", "finance"))
                .isInstanceOf(VerifiedCallerRequiredException.class);
    }

    private void ingest(RepositoryDocument document) {
        connector.send(document).block();
    }

    private static RepositoryDocument doc(
            String id,
            String uri,
            String title,
            String sourceType,
            String mimeType,
            String text,
            Instant crawledAt,
            Map<String, List<String>> extraMeta,
            List<PermissionRule> rules
    ) {
        Map<String, List<String>> meta = new HashMap<>(extraMeta);
        meta.put("title", List.of(title));
        meta.put("source_type", List.of(sourceType));
        meta.put("mimeType", List.of(mimeType));
        meta.put("extracted_text", List.of(text));
        List<String> acls = new ArrayList<>();
        for (PermissionRule r : rules) {
            if ("read".equalsIgnoreCase(r.access())) {
                acls.add(r.identity());
            }
        }
        return new RepositoryDocument(
                id,
                uri,
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                meta,
                acls.isEmpty() ? null : String.join(",", acls),
                new SecurityConfig(true, rules),
                crawledAt,
                DocumentAction.UPSERT
        );
    }

    private static void authenticate(String principal, List<String> groups) {
        Jwt jwt = new Jwt(
                "mock-jwt-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("preferred_username", principal, "groups", groups)
        );
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
