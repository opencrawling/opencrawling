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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

import org.opencrawling.internal.v1.DocumentPayloadRequest;
import org.opencrawling.internal.v1.InternalPayloadServiceGrpc;
import org.opencrawling.internal.v1.PayloadIngestionResponse;
import org.opencrawling.internal.v1.PingRequest;
import org.opencrawling.internal.v1.PingResponse;

import io.grpc.stub.StreamObserver;

@GrpcService
public class OpenCrawlingGrpcService extends InternalPayloadServiceGrpc.InternalPayloadServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(OpenCrawlingGrpcService.class);

    @Override
    public void pingTransport(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        responseObserver.onNext(new PingResponse(System.currentTimeMillis(), true));
        responseObserver.onCompleted();
    }

    @Override
    public void sendDocumentPayload(DocumentPayloadRequest request, StreamObserver<PayloadIngestionResponse> responseObserver) {
        log.info("Spring Boot @GrpcService received DocumentPayload for task: {}, docId: {}", 
                request.getTaskId(), request.getDocumentId());
        responseObserver.onNext(new PayloadIngestionResponse(
            request.getDocumentId(),
            PayloadIngestionResponse.Status.SUCCESS,
            ""
        ));
        responseObserver.onCompleted();
    }
}
