/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.runtime.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.reporting.DocumentLedgerEntry;
import org.opencrawling.googledrive.GoogleDriveCrawlState;
import org.opencrawling.googledrive.GoogleDriveDocumentBuilder;
import org.opencrawling.googledrive.GoogleDriveProperties;
import org.opencrawling.googledrive.GoogleDriveSecurityMapper;
import org.opencrawling.runtime.controlplane.JdbcControlPlaneStore;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.reporting.JobLedgerAccumulator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies:
 * <ol>
 *   <li>Google Drive Changes API page token and item state are persisted in the shared JDBC {@code ControlPlaneStore}
 *       so two distinct runtime replicas see each other's updates without any local state file (#339, #395).</li>
 *   <li>Oversized Google Drive documents flagged as {@code TOO_LARGE} are recorded as {@code SKIPPED}
 *       ({@code errorCode = "TOO_LARGE"}) in {@link JobLedgerAccumulator} rather than {@code SUCCESS} (#339, #397).</li>
 * </ol>
 */
class ControlPlaneGoogleDriveStateStoreTest {

    @Test
    void twoRuntimeReplicasShareChangesApiPageTokenViaJdbcControlPlaneStore(@TempDir Path tempDir) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:gdrive-cp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        // Two independent runtime instances backed by the same database, with empty separate local dirs
        JdbcControlPlaneStore replicaA = new JdbcControlPlaneStore(jdbc, tx, tempDir.resolve("replica-a"));
        JdbcControlPlaneStore replicaB = new JdbcControlPlaneStore(jdbc, tx, tempDir.resolve("replica-b"));

        ControlPlaneGoogleDriveStateStore storeA = new ControlPlaneGoogleDriveStateStore(replicaA);
        ControlPlaneGoogleDriveStateStore storeB = new ControlPlaneGoogleDriveStateStore(replicaB);

        String scopeKey = "v1|https://www.googleapis.com/drive/v3|drive=0AO_TEAM|roots=folder-1";

        assertThat(storeA.load(scopeKey).startPageToken()).isNull();

        // Replica A completes initial scan and saves Changes API startPageToken + known file
        GoogleDriveCrawlState.FileEntry fileEntry = new GoogleDriveCrawlState.FileEntry(
                "googledrive://drives/0AO_TEAM/files/f1", "0AO_TEAM", "folder-1", "Guide.html",
                "/Procedures/Guide.html", "v:12", "acl-fp-1", "r1", "INDEXED");
        storeA.save(scopeKey, new GoogleDriveCrawlState.State(
                "page-token-42",
                "2026-11-21T10:00:00Z",
                Map.of("folder-1", new GoogleDriveCrawlState.FolderEntry(null, "Procedures", "0AO_TEAM")),
                Map.of("f1", fileEntry)
        ));

        // Replica B runs next scheduled tick -> sees Replica A's exact startPageToken and file state
        GoogleDriveCrawlState.State loadedByB = storeB.load(scopeKey);
        assertThat(loadedByB.startPageToken()).isEqualTo("page-token-42");
        assertThat(loadedByB.lastFullScanAt()).isEqualTo("2026-11-21T10:00:00Z");
        assertThat(loadedByB.files()).containsKey("f1");
        assertThat(loadedByB.files().get("f1").versionKey()).isEqualTo("v:12");

        // Replica B advances page token -> Replica A sees the updated revision
        storeB.save(scopeKey, new GoogleDriveCrawlState.State(
                "page-token-99", loadedByB.lastFullScanAt(), loadedByB.folders(), loadedByB.files()));
        assertThat(storeA.load(scopeKey).startPageToken()).isEqualTo("page-token-99");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jobOrchestratorRecordsTooLargeGoogleDriveDocumentAsSkippedInLedger() throws Exception {
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        ClaimCheckStore claimCheck = mock(ClaimCheckStore.class);
        TelemetryTraceStore traceStore = new TelemetryTraceStore();
        JobLedgerAccumulator ledger = new JobLedgerAccumulator(mock(ApplicationEventPublisher.class));

        JobOrchestrator orchestrator = new JobOrchestrator(kafka, claimCheck, traceStore);
        var field = JobOrchestrator.class.getDeclaredField("ledgerAccumulator");
        field.setAccessible(true);
        field.set(orchestrator, ledger);

        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of("maxFileSizeBytes", "1024"));
        var fileJson = new ObjectMapper().readTree("""
                {"id":"huge-1","name":"Archive.zip","mimeType":"application/zip","size":"99999999",
                 "webViewLink":"https://drive.google.com/file/d/huge-1/view"}
                """);
        RepositoryDocument tooLarge = GoogleDriveDocumentBuilder.buildTooLarge(
                fileJson,
                new GoogleDriveDocumentBuilder.FileContext("0AO_TEAM", "Support", List.of("Manuals"), "/Manuals", "/Manuals/Archive.zip", null),
                null,
                props,
                99_999_999L,
                "File size 99999999 bytes exceeds max-file-size-bytes (1024 bytes)",
                GoogleDriveSecurityMapper.publicAccess()
        );

        RepositoryConnector stubRepo = new RepositoryConnector() {
            @Override
            public String getName() {
                return "GoogleDriveRepositoryConnector";
            }

            @Override
            public void connect() {
            }

            @Override
            public void disconnect() {
            }

            @Override
            public Flux<RepositoryDocument> scan(String basePath) {
                return Flux.just(tooLarge);
            }
        };

        boolean ok = orchestrator.runJob(stubRepo, null, "default", null, "job-gdrive", null, PipelineMode.RAG, "exec-too-large");
        assertThat(ok).isTrue();
        verify(kafka, never()).send(anyString(), anyString(), any());

        List<DocumentLedgerEntry> entries = ledger.getEntries("exec-too-large");
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().status()).isEqualTo("SKIPPED");
        assertThat(entries.getFirst().errorCode()).isEqualTo("TOO_LARGE");
        assertThat(entries.getFirst().errorMessage()).contains("exceeds max-file-size-bytes");
        assertThat(ledger.getCompletedEvent("exec-too-large").skippedDocuments()).isEqualTo(1L);
        assertThat(ledger.getCompletedEvent("exec-too-large").successfulDocuments()).isZero();
    }
}
