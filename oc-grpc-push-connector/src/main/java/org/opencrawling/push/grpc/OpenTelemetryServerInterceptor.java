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
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server interceptor extracting W3C traceparent and tracestate headers from gRPC metadata,
 * attaching the OpenTelemetry context to execution threads and propagating trace context downstream.
 */
public class OpenTelemetryServerInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryServerInterceptor.class);

    public static final Metadata.Key<String> TRACEPARENT_KEY =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> TRACESTATE_KEY =
            Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);

    public static final io.grpc.Context.Key<String> TRACEPARENT_CONTEXT_KEY =
            io.grpc.Context.key("traceparent");

    private static final TextMapGetter<Metadata> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Metadata carrier) {
            return carrier != null ? carrier.keys() : java.util.Collections.emptyList();
        }

        @Override
        public String get(Metadata carrier, String key) {
            if (carrier == null || key == null) return null;
            Metadata.Key<String> metaKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
            return carrier.get(metaKey);
        }
    };

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String traceparent = headers.get(TRACEPARENT_KEY);
        if (traceparent != null && !traceparent.isBlank()) {
            log.debug("Extracted W3C traceparent from gRPC metadata: {}", traceparent);
        }

        io.opentelemetry.context.Context otelContext;
        try {
            otelContext = GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .extract(io.opentelemetry.context.Context.current(), headers, GETTER);
        } catch (Exception e) {
            log.debug("Could not extract OTel context from metadata: {}", e.getMessage());
            otelContext = io.opentelemetry.context.Context.current();
        }

        io.grpc.Context grpcContext = io.grpc.Context.current();
        if (traceparent != null && !traceparent.isBlank()) {
            grpcContext = grpcContext.withValue(TRACEPARENT_CONTEXT_KEY, traceparent);
        }

        // Run with both OTel Context attached and gRPC Context
        try (io.opentelemetry.context.Scope scope = otelContext.makeCurrent()) {
            return Contexts.interceptCall(grpcContext, call, headers, next);
        }
    }
}
