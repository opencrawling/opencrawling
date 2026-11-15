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
package org.opencrawling.push.grpc;

import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.push.v1.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OisPushServiceInProcessTest {

    private static final String TEST_TOKEN = "secret-token-123";

    private Server inProcessServer;
    private ManagedChannel channel;
    private GrpcPushProperties properties;
    private PushDocumentDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        String serverName = InProcessServerBuilder.generateName();

        properties = new GrpcPushProperties();
        properties.setToken(TEST_TOKEN);

        dispatcher = request -> {
            String jobId = request.getJobId();
            if ("non-existent-job".equals(jobId)) {
                return PushDocumentResponse.newBuilder()
                        .setDocumentId(request.getDocument().getId())
                        .setStatus(IngestionStatus.REJECTED_JOB_NOT_FOUND)
                        .setMessage("Job does not exist")
                        .setProcessedAt(System.currentTimeMillis())
                        .build();
            }
            if ("failing-doc".equals(request.getDocument().getId())) {
                return PushDocumentResponse.newBuilder()
                        .setDocumentId(request.getDocument().getId())
                        .setStatus(IngestionStatus.REJECTED_INVALID_PAYLOAD)
                        .setMessage("Corrupted payload")
                        .setProcessedAt(System.currentTimeMillis())
                        .build();
            }
            return PushDocumentResponse.newBuilder()
                    .setDocumentId(request.getDocument().getId())
                    .setStatus(IngestionStatus.ACCEPTED)
                    .setMessage("Document accepted")
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
        };

        OisPushServiceImpl pushService = new OisPushServiceImpl(dispatcher);
        GrpcSecurityInterceptor securityInterceptor = new GrpcSecurityInterceptor(properties);
        OpenTelemetryServerInterceptor otelInterceptor = new OpenTelemetryServerInterceptor();

        inProcessServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(otelInterceptor)
                .intercept(securityInterceptor)
                .addService(pushService)
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
        if (inProcessServer != null) {
            inProcessServer.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private OisPushServiceGrpc.OisPushServiceBlockingStub getBlockingStub(String token) {
        OisPushServiceGrpc.OisPushServiceBlockingStub stub = OisPushServiceGrpc.newBlockingStub(channel);
        if (token != null) {
            Metadata headers = new Metadata();
            headers.put(GrpcSecurityInterceptor.AUTHORIZATION_METADATA_KEY, "Bearer " + token);
            stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        }
        return stub;
    }

    private OisPushServiceGrpc.OisPushServiceStub getAsyncStub(String token) {
        OisPushServiceGrpc.OisPushServiceStub stub = OisPushServiceGrpc.newStub(channel);
        if (token != null) {
            Metadata headers = new Metadata();
            headers.put(GrpcSecurityInterceptor.AUTHORIZATION_METADATA_KEY, "Bearer " + token);
            stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        }
        return stub;
    }

    @Test
    void testPingDiagnosticWithoutAuth() {
        OisPushServiceGrpc.OisPushServiceBlockingStub stub = OisPushServiceGrpc.newBlockingStub(channel);
        PingResponse ping = stub.ping(PingRequest.newBuilder().setClientId("test-client").build());
        assertNotNull(ping);
        assertEquals("UP", ping.getStatus());
        assertEquals("1.0.0", ping.getVersion());
        assertTrue(ping.getServerTime() > 0);
    }

    @Test
    void testUnaryPushWithoutTokenIsRejectedUnauthenticated() {
        OisPushServiceGrpc.OisPushServiceBlockingStub stub = getBlockingStub(null);
        PushDocumentRequest req = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-1").setAction(DocumentAction.UPSERT).build())
                .build();

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () -> stub.pushDocument(req));
        assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
        assertTrue(ex.getMessage().contains("Missing or invalid Bearer authorization token"));
    }

    @Test
    void testUnaryPushWithInvalidTokenIsRejectedUnauthenticated() {
        OisPushServiceGrpc.OisPushServiceBlockingStub stub = getBlockingStub("wrong-token");
        PushDocumentRequest req = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-1").setAction(DocumentAction.UPSERT).build())
                .build();

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () -> stub.pushDocument(req));
        assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
        assertTrue(ex.getMessage().contains("Invalid Bearer authorization token"));
    }

    @Test
    void testUnaryPushSuccessWithValidToken() {
        OisPushServiceGrpc.OisPushServiceBlockingStub stub = getBlockingStub(TEST_TOKEN);
        PushDocumentRequest req = PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder()
                        .setId("doc-1")
                        .setAction(DocumentAction.UPSERT)
                        .setTextContent("Sample text")
                        .build())
                .build();

        PushDocumentResponse resp = stub.pushDocument(req);
        assertNotNull(resp);
        assertEquals("doc-1", resp.getDocumentId());
        assertEquals(IngestionStatus.ACCEPTED, resp.getStatus());
        assertEquals("Document accepted", resp.getMessage());
    }

    @Test
    void testUnaryPushJobNotFound() {
        OisPushServiceGrpc.OisPushServiceBlockingStub stub = getBlockingStub(TEST_TOKEN);
        PushDocumentRequest req = PushDocumentRequest.newBuilder()
                .setJobId("non-existent-job")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-1").setAction(DocumentAction.UPSERT).build())
                .build();

        PushDocumentResponse resp = stub.pushDocument(req);
        assertEquals(IngestionStatus.REJECTED_JOB_NOT_FOUND, resp.getStatus());
    }

    @Test
    void testClientStreamingPush() throws Exception {
        OisPushServiceGrpc.OisPushServiceStub asyncStub = getAsyncStub(TEST_TOKEN);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<StreamSummaryResponse> summaryRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        StreamObserver<PushDocumentRequest> requestObserver = asyncStub.streamDocuments(new StreamObserver<>() {
            @Override
            public void onNext(StreamSummaryResponse value) {
                summaryRef.set(value);
            }

            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        // Send 3 accepted documents and 1 failing document
        requestObserver.onNext(PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-1").setAction(DocumentAction.UPSERT).build())
                .build());
        requestObserver.onNext(PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-2").setAction(DocumentAction.UPSERT).build())
                .build());
        requestObserver.onNext(PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("failing-doc").setAction(DocumentAction.UPSERT).build())
                .build());
        requestObserver.onNext(PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-3").setAction(DocumentAction.DELETE).build())
                .build());

        requestObserver.onCompleted();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNull(errorRef.get());

        StreamSummaryResponse summary = summaryRef.get();
        assertNotNull(summary);
        assertEquals(4, summary.getTotalReceived());
        assertEquals(3, summary.getTotalAccepted());
        assertEquals(1, summary.getTotalRejected());
        assertEquals(1, summary.getFailuresCount());
        assertEquals("failing-doc", summary.getFailures(0).getDocumentId());
    }

    @Test
    void testBiDirectionalStreamingPush() throws Exception {
        OisPushServiceGrpc.OisPushServiceStub asyncStub = getAsyncStub(TEST_TOKEN);

        CountDownLatch latch = new CountDownLatch(3);
        List<IngestionAck> receivedAcks = new ArrayList<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        StreamObserver<PushDocumentRequest> requestObserver = asyncStub.biDirectionalStream(new StreamObserver<>() {
            @Override
            public void onNext(IngestionAck ack) {
                synchronized (receivedAcks) {
                    receivedAcks.add(ack);
                }
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
            }

            @Override
            public void onCompleted() {}
        });

        requestObserver.onNext(PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-bidi-1").setAction(DocumentAction.UPSERT).build())
                .build());
        requestObserver.onNext(PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("failing-doc").setAction(DocumentAction.UPSERT).build())
                .build());
        requestObserver.onNext(PushDocumentRequest.newBuilder()
                .setJobId("job-1")
                .setDocument(OisDocumentPayload.newBuilder().setId("doc-bidi-2").setAction(DocumentAction.UPSERT).build())
                .build());

        requestObserver.onCompleted();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNull(errorRef.get());

        assertEquals(3, receivedAcks.size());
        assertEquals("doc-bidi-1", receivedAcks.get(0).getDocumentId());
        assertEquals(IngestionStatus.ACCEPTED, receivedAcks.get(0).getStatus());

        assertEquals("failing-doc", receivedAcks.get(1).getDocumentId());
        assertEquals(IngestionStatus.REJECTED_INVALID_PAYLOAD, receivedAcks.get(1).getStatus());

        assertEquals("doc-bidi-2", receivedAcks.get(2).getDocumentId());
        assertEquals(IngestionStatus.ACCEPTED, receivedAcks.get(2).getStatus());
    }
}
