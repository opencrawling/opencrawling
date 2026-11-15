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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Server interceptor validating Bearer token authentication against configured ingestion tokens.
 */
public class GrpcSecurityInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcSecurityInterceptor.class);

    public static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    public static final Context.Key<String> CLIENT_TOKEN_CONTEXT_KEY =
            Context.key("grpcClientToken");

    private final GrpcPushProperties properties;

    public GrpcSecurityInterceptor(GrpcPushProperties properties) {
        this.properties = properties;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String methodName = call.getMethodDescriptor().getFullMethodName();

        // Allow diagnostic health check / ping probe without strict auth requirement
        if (methodName != null && methodName.endsWith("/Ping")) {
            return next.startCall(call, headers);
        }

        String configuredToken = properties != null ? properties.getToken() : null;
        if (configuredToken == null || configuredToken.isBlank()) {
            // Authentication not enforced globally
            return next.startCall(call, headers);
        }

        String authHeader = headers.get(AUTHORIZATION_METADATA_KEY);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("gRPC Push call rejected: missing or invalid Bearer token for {}", methodName);
            call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid Bearer authorization token"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        String presentedToken = authHeader.substring(7).trim();
        if (!constantTimeEquals(configuredToken.trim(), presentedToken)) {
            log.warn("gRPC Push call rejected: token mismatch for {}", methodName);
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid Bearer authorization token"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        Context context = Context.current().withValue(CLIENT_TOKEN_CONTEXT_KEY, presentedToken);
        return Contexts.interceptCall(context, call, headers, next);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
