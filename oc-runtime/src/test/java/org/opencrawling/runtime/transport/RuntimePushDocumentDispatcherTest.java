/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.runtime.transport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.push.grpc.GrpcPushProperties;
import org.opencrawling.push.v1.IngestionStatus;
import org.opencrawling.push.v1.OisDocumentPayload;
import org.opencrawling.push.v1.PushDocumentRequest;
import org.opencrawling.push.v1.PushDocumentResponse;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobController.JobDTO;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimePushDocumentDispatcherTest {

    private JobController jobController;
    private JobOrchestrator jobOrchestrator;
    private ClaimCheckStore claimCheckStore;
    private GrpcPushProperties properties;
    private RuntimePushDocumentDispatcher dispatcher;

    private JobDTO activeJob;

    @BeforeEach
    void setUp() {
        jobController = mock(JobController.class);
        jobOrchestrator = mock(JobOrchestrator.class);
        claimCheckStore = mock(ClaimCheckStore.class);
        properties = new GrpcPushProperties();
        dispatcher = new RuntimePushDocumentDispatcher(jobController, jobOrchestrator, claimCheckStore, properties);

        activeJob = new JobDTO(
                "job-1", "Test Job", "Grpc_Push_Connector", "PGVector_Output",
                null, "push://incoming", "RUNNING", "SCAN", 0L, null, "Ollama_Embedding_Default", null
        );
    }

    @Test
    void testDispatchRejectedWhenJobNotFound() {
        when(jobController.findJobById("unknown-job")).thenReturn(null);

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setJobId("unknown-job")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-1").setAction(org.opencrawling.push.v1.DocumentAction.UPSERT).build())
                .build();

        PushDocumentResponse response = dispatcher.dispatch(request);
        assertNotNull(response);
        assertEquals(IngestionStatus.REJECTED_JOB_NOT_FOUND, response.getStatus());
        assertTrue(response.getMessage().contains("Target job not found"));
    }

    @Test
    void testDispatchRejectedInMigrationMode() {
        when(jobController.findJobById("job-1")).thenReturn(activeJob);
        when(jobController.resolvePipelineMode(activeJob)).thenReturn(PipelineMode.MIGRATION);

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-1").setAction(org.opencrawling.push.v1.DocumentAction.UPSERT).build())
                .build();

        PushDocumentResponse response = dispatcher.dispatch(request);
        assertNotNull(response);
        assertEquals(IngestionStatus.REJECTED_INVALID_PAYLOAD, response.getStatus());
        assertTrue(response.getMessage().contains("migration mode"));
    }

    @Test
    void testDispatchAcceptedForUpsert() throws Exception {
        when(jobController.findJobById("job-1")).thenReturn(activeJob);
        when(jobController.resolvePipelineMode(activeJob)).thenReturn(PipelineMode.RAG);

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder()
                        .setId("contract-2026.pdf")
                        .setAction(org.opencrawling.push.v1.DocumentAction.UPSERT)
                        .setMimeType("application/pdf")
                        .setTextContent("Contract agreement details...")
                        .putMetadata("department", "Legal")
                        .build())
                .build();

        PushDocumentResponse response = dispatcher.dispatch(request);
        assertNotNull(response);
        assertEquals(IngestionStatus.ACCEPTED, response.getStatus());
        assertEquals("contract-2026.pdf", response.getDocumentId());

        ArgumentCaptor<RepositoryDocument> captor = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(jobOrchestrator).publishDocument(captor.capture(), eq("Ollama_Embedding_Default"), any(), any(), any(), any());

        RepositoryDocument published = captor.getValue();
        assertEquals("contract-2026.pdf", published.id());
        assertEquals(DocumentAction.UPSERT, published.action());
        assertEquals(java.util.List.of("Legal"), published.metadata().get("department"));
    }

    @Test
    void testDispatchAcceptedForDeleteTombstone() throws Exception {
        when(jobController.findJobById("job-1")).thenReturn(activeJob);
        when(jobController.resolvePipelineMode(activeJob)).thenReturn(PipelineMode.RAG);

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder()
                        .setId("contract-2026.pdf")
                        .setAction(org.opencrawling.push.v1.DocumentAction.DELETE)
                        .build())
                .build();

        PushDocumentResponse response = dispatcher.dispatch(request);
        assertEquals(IngestionStatus.ACCEPTED, response.getStatus());

        ArgumentCaptor<RepositoryDocument> captor = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(jobOrchestrator).publishDocument(captor.capture(), eq("Ollama_Embedding_Default"), any(), any(), any(), any());

        RepositoryDocument published = captor.getValue();
        assertEquals("contract-2026.pdf", published.id());
        assertEquals(DocumentAction.DELETE, published.action());
        assertNull(published.contentStream());
    }

    @Test
    void testDispatchReturnsInternalErrorWhenPublishFails() throws Exception {
        when(jobController.findJobById("job-1")).thenReturn(activeJob);
        when(jobController.resolvePipelineMode(activeJob)).thenReturn(PipelineMode.RAG);
        doThrow(new RuntimeException("Kafka producer unavailable")).when(jobOrchestrator).publishDocument(any(), any(), any(), any(), any(), any());

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder()
                        .setId("doc-fail")
                        .setAction(org.opencrawling.push.v1.DocumentAction.UPSERT)
                        .setTextContent("Content")
                        .build())
                .build();

        PushDocumentResponse response = dispatcher.dispatch(request);
        assertEquals(IngestionStatus.ERROR_INTERNAL, response.getStatus());
        assertTrue(response.getMessage().contains("Kafka producer unavailable"));
    }

    @Test
    void testUnaryAndStreamingPushGenerateAuditReports(@org.junit.jupiter.api.io.TempDir java.io.File tempDir) throws Exception {
        when(jobController.findJobById("job-1")).thenReturn(activeJob);
        when(jobController.resolvePipelineMode(activeJob)).thenReturn(PipelineMode.RAG);

        org.opencrawling.runtime.reporting.JobReportProperties reportProps = new org.opencrawling.runtime.reporting.JobReportProperties();
        reportProps.getStorage().setLocalDir(tempDir.getAbsolutePath());
        org.opencrawling.runtime.reporting.OperationalReportGenerator opGen =
                new org.opencrawling.runtime.reporting.OperationalReportGenerator(reportProps);
        org.opencrawling.runtime.reporting.ParquetJobReportGenerator parquetGen =
                new org.opencrawling.runtime.reporting.ParquetJobReportGenerator(reportProps);

        org.opencrawling.runtime.reporting.JobLedgerAccumulator accumulator =
                new org.opencrawling.runtime.reporting.JobLedgerAccumulator(event -> {
                    if (event instanceof org.opencrawling.core.event.JobExecutionCompletedEvent completed) {
                        opGen.onJobExecutionCompleted(completed);
                        parquetGen.onJobExecutionCompleted(completed);
                    }
                });
        org.opencrawling.runtime.reporting.JobReportService reportService =
                new org.opencrawling.runtime.reporting.JobReportService(reportProps, accumulator);

        RuntimePushDocumentDispatcher reportingDispatcher =
                new RuntimePushDocumentDispatcher(jobController, jobOrchestrator, claimCheckStore, properties, accumulator);

        // 1. Unary push
        PushDocumentRequest unaryReq = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder()
                        .setId("unary-doc-1")
                        .setAction(org.opencrawling.push.v1.DocumentAction.UPSERT)
                        .setTextContent("Unary pushed document content for reporting verification")
                        .build())
                .build();

        PushDocumentResponse unaryResp = reportingDispatcher.dispatch(unaryReq);
        assertEquals(IngestionStatus.ACCEPTED, unaryResp.getStatus());

        java.util.List<org.opencrawling.core.reporting.JobReportSummary> summaries = reportService.getAllSummaries();
        assertEquals(1, summaries.size(), "Unary push must generate an execution report summary");
        org.opencrawling.core.reporting.JobReportSummary unarySummary = summaries.get(0);
        assertEquals("job-1", unarySummary.jobId());
        assertEquals("Grpc_Push_Connector", unarySummary.repositoryConnector());
        assertEquals("PGVector_Output", unarySummary.outputConnector());
        assertEquals(1L, unarySummary.totalDocuments());
        assertEquals(1L, unarySummary.successfulDocuments());
        verify(jobController).recordPushExecution("job-1", 1L, true);

        // 2. Streaming push (2 documents aggregated into 1 stream execution report)
        String streamExecId = "exec-stream-test01";
        for (int i = 1; i <= 2; i++) {
            PushDocumentRequest streamReq = PushDocumentRequest.newBuilder()
                    .setJobId("job-1")
                    .setDocument(OisDocumentPayload.newBuilder()
                            .setId("stream-doc-" + i)
                            .setAction(org.opencrawling.push.v1.DocumentAction.UPSERT)
                            .setTextContent("Stream document #" + i)
                            .build())
                    .build();
            assertEquals(IngestionStatus.ACCEPTED, reportingDispatcher.dispatch(streamReq, streamExecId).getStatus());
        }
        reportingDispatcher.completeStreamExecution(streamExecId);

        org.opencrawling.core.reporting.JobReportSummary streamSummary = reportService.getSummary(streamExecId).orElseThrow();
        assertEquals(2L, streamSummary.totalDocuments());
        assertEquals(2L, streamSummary.successfulDocuments());
        assertEquals(2, reportService.getDocumentEntries(streamExecId, "ALL", null, 0, 10).total());

        // Verify DuckDB query over the generated Parquet report works
        org.opencrawling.runtime.reporting.JobReportService.QueryResult qr = reportService.executeDuckDbQuery(
                "SELECT status, count(*) AS doc_count FROM read_parquet('" + streamSummary.reportPathParquet() + "') GROUP BY status"
        );
        assertEquals(1, qr.rows().size());
        verify(jobController).recordPushExecution("job-1", 2L, true);
    }
}
