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

import io.grpc.stub.StreamObserver;
import org.opencrawling.push.v1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * gRPC service implementation for the Open Ingestion Standard (OIS) push ingestion endpoint.
 * Supports unary, client-streaming, and bi-directional streaming RPCs.
 */
public class OisPushServiceImpl extends OisPushServiceGrpc.OisPushServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(OisPushServiceImpl.class);

    private final PushDocumentDispatcher dispatcher;

    public OisPushServiceImpl(PushDocumentDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void pushDocument(PushDocumentRequest request, StreamObserver<PushDocumentResponse> responseObserver) {
        try {
            PushDocumentResponse response = dispatcher.dispatch(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Unhandled error processing unary push document: {}", e.getMessage(), e);
            String docId = (request != null && request.getDocument() != null) ? request.getDocument().getId() : "";
            PushDocumentResponse errorResp = PushDocumentResponse.newBuilder()
                    .setDocumentId(docId)
                    .setStatus(IngestionStatus.ERROR_INTERNAL)
                    .setMessage("Internal error: " + e.getMessage())
                    .setProcessedAt(System.currentTimeMillis())
                    .build();
            responseObserver.onNext(errorResp);
            responseObserver.onCompleted();
        }
    }

    @Override
    public StreamObserver<PushDocumentRequest> streamDocuments(StreamObserver<StreamSummaryResponse> responseObserver) {
        long startTime = System.currentTimeMillis();
        String streamExecutionId = "exec-stream-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AtomicLong totalReceived = new AtomicLong(0);
        AtomicLong totalAccepted = new AtomicLong(0);
        AtomicLong totalRejected = new AtomicLong(0);
        List<PushFailure> failures = new CopyOnWriteArrayList<>();

        return new StreamObserver<>() {
            @Override
            public void onNext(PushDocumentRequest request) {
                totalReceived.incrementAndGet();
                try {
                    PushDocumentResponse response = dispatcher.dispatch(request, streamExecutionId);
                    if (response.getStatus() == IngestionStatus.ACCEPTED || response.getStatus() == IngestionStatus.QUEUED) {
                        totalAccepted.incrementAndGet();
                    } else {
                        totalRejected.incrementAndGet();
                        failures.add(PushFailure.newBuilder()
                                .setDocumentId(response.getDocumentId())
                                .setErrorCode(response.getStatus().name())
                                .setReason(response.getMessage())
                                .build());
                    }
                } catch (Exception e) {
                    totalRejected.incrementAndGet();
                    String docId = (request != null && request.getDocument() != null) ? request.getDocument().getId() : "";
                    failures.add(PushFailure.newBuilder()
                            .setDocumentId(docId)
                            .setErrorCode(IngestionStatus.ERROR_INTERNAL.name())
                            .setReason(e.getMessage() != null ? e.getMessage() : "Unknown exception")
                            .build());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Error encountered during client-streaming ingestion: {}", t.getMessage());
                dispatcher.completeStreamExecution(streamExecutionId);
            }

            @Override
            public void onCompleted() {
                dispatcher.completeStreamExecution(streamExecutionId);
                long duration = System.currentTimeMillis() - startTime;
                StreamSummaryResponse summary = StreamSummaryResponse.newBuilder()
                        .setTotalReceived(totalReceived.get())
                        .setTotalAccepted(totalAccepted.get())
                        .setTotalRejected(totalRejected.get())
                        .setDurationMs(duration)
                        .addAllFailures(failures)
                        .build();

                responseObserver.onNext(summary);
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public StreamObserver<PushDocumentRequest> biDirectionalStream(StreamObserver<IngestionAck> responseObserver) {
        String streamExecutionId = "exec-stream-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return new StreamObserver<>() {
            @Override
            public void onNext(PushDocumentRequest request) {
                try {
                    PushDocumentResponse response = dispatcher.dispatch(request, streamExecutionId);
                    boolean ok = response.getStatus() == IngestionStatus.ACCEPTED || response.getStatus() == IngestionStatus.QUEUED;
                    IngestionAck ack = IngestionAck.newBuilder()
                            .setDocumentId(response.getDocumentId())
                            .setStatus(response.getStatus())
                            .setErrorMessage(ok ? "" : response.getMessage())
                            .setAckTimestamp(System.currentTimeMillis())
                            .build();

                    synchronized (responseObserver) {
                        responseObserver.onNext(ack);
                    }
                } catch (Exception e) {
                    String docId = (request != null && request.getDocument() != null) ? request.getDocument().getId() : "";
                    IngestionAck errorAck = IngestionAck.newBuilder()
                            .setDocumentId(docId)
                            .setStatus(IngestionStatus.ERROR_INTERNAL)
                            .setErrorMessage("Internal error: " + e.getMessage())
                            .setAckTimestamp(System.currentTimeMillis())
                            .build();

                    synchronized (responseObserver) {
                        responseObserver.onNext(errorAck);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Error encountered during bi-directional streaming ingestion: {}", t.getMessage());
                dispatcher.completeStreamExecution(streamExecutionId);
            }

            @Override
            public void onCompleted() {
                dispatcher.completeStreamExecution(streamExecutionId);
                synchronized (responseObserver) {
                    responseObserver.onCompleted();
                }
            }
        };
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        PingResponse response = PingResponse.newBuilder()
                .setStatus("UP")
                .setServerTime(System.currentTimeMillis())
                .setVersion("1.0.0")
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
