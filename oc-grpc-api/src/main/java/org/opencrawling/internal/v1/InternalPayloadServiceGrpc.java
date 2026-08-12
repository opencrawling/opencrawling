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
package org.opencrawling.internal.v1;

import io.grpc.BindableService;
import io.grpc.Channel;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.AbstractBlockingStub;
import io.grpc.stub.AbstractAsyncStub;
import io.grpc.stub.StreamObserver;

public final class InternalPayloadServiceGrpc {

    private InternalPayloadServiceGrpc() {}

    public static final String SERVICE_NAME = "opencrawling.internal.v1.InternalPayloadService";

    public abstract static class InternalPayloadServiceImplBase implements BindableService {

        public void sendDocumentPayload(DocumentPayloadRequest request, StreamObserver<PayloadIngestionResponse> responseObserver) {
            responseObserver.onNext(new PayloadIngestionResponse(
                request.getDocumentId(),
                PayloadIngestionResponse.Status.SUCCESS,
                ""
            ));
            responseObserver.onCompleted();
        }

        public void pingTransport(PingRequest request, StreamObserver<PingResponse> responseObserver) {
            responseObserver.onNext(new PingResponse(System.currentTimeMillis(), true));
            responseObserver.onCompleted();
        }

        @Override
        public ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder(SERVICE_NAME)
                    .addMethod(
                            io.grpc.MethodDescriptor.<PingRequest, PingResponse>newBuilder()
                                    .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                                    .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "PingTransport"))
                                    .setRequestMarshaller(new JsonMarshaller<>(PingRequest.class))
                                    .setResponseMarshaller(new JsonMarshaller<>(PingResponse.class))
                                    .build(),
                            io.grpc.stub.ServerCalls.asyncUnaryCall((request, responseObserver) -> pingTransport(request, responseObserver))
                    )
                    .addMethod(
                            io.grpc.MethodDescriptor.<DocumentPayloadRequest, PayloadIngestionResponse>newBuilder()
                                    .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                                    .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "SendDocumentPayload"))
                                    .setRequestMarshaller(new JsonMarshaller<>(DocumentPayloadRequest.class))
                                    .setResponseMarshaller(new JsonMarshaller<>(PayloadIngestionResponse.class))
                                    .build(),
                            io.grpc.stub.ServerCalls.asyncUnaryCall((request, responseObserver) -> sendDocumentPayload(request, responseObserver))
                    )
                    .build();
        }
    }

    public static InternalPayloadServiceBlockingStub newBlockingStub(Channel channel) {
        return new InternalPayloadServiceBlockingStub(channel);
    }

    public static final class InternalPayloadServiceBlockingStub extends AbstractBlockingStub<InternalPayloadServiceBlockingStub> {
        
        private InternalPayloadServiceBlockingStub(Channel channel) {
            super(channel, io.grpc.CallOptions.DEFAULT);
        }

        private InternalPayloadServiceBlockingStub(Channel channel, io.grpc.CallOptions callOptions) {
            super(channel, callOptions);
        }

        @Override
        protected InternalPayloadServiceBlockingStub build(Channel channel, io.grpc.CallOptions callOptions) {
            return new InternalPayloadServiceBlockingStub(channel, callOptions);
        }

        public PingResponse pingTransport(PingRequest request) {
            return io.grpc.stub.ClientCalls.blockingUnaryCall(
                    getChannel(),
                    io.grpc.MethodDescriptor.<PingRequest, PingResponse>newBuilder()
                            .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                            .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "PingTransport"))
                            .setRequestMarshaller(new JsonMarshaller<>(PingRequest.class))
                            .setResponseMarshaller(new JsonMarshaller<>(PingResponse.class))
                            .build(),
                    getCallOptions(),
                    request
            );
        }

        public PayloadIngestionResponse sendDocumentPayload(DocumentPayloadRequest request) {
            return io.grpc.stub.ClientCalls.blockingUnaryCall(
                    getChannel(),
                    io.grpc.MethodDescriptor.<DocumentPayloadRequest, PayloadIngestionResponse>newBuilder()
                            .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                            .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(SERVICE_NAME, "SendDocumentPayload"))
                            .setRequestMarshaller(new JsonMarshaller<>(DocumentPayloadRequest.class))
                            .setResponseMarshaller(new JsonMarshaller<>(PayloadIngestionResponse.class))
                            .build(),
                    getCallOptions(),
                    request
            );
        }
    }

    private static class JsonMarshaller<T> implements io.grpc.MethodDescriptor.Marshaller<T> {
        private final Class<T> clazz;

        public JsonMarshaller(Class<T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public java.io.InputStream stream(T value) {
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(baos);
                oos.writeObject(value);
                oos.flush();
                return new java.io.ByteArrayInputStream(baos.toByteArray());
            } catch (Exception e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public T parse(java.io.InputStream stream) {
            try {
                java.io.ObjectInputStream ois = new java.io.ObjectInputStream(stream);
                return (T) ois.readObject();
            } catch (Exception e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
