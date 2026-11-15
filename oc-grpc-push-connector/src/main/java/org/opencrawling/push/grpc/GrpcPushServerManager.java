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

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of the standalone or embedded gRPC Push listener.
 * Employs Java 25 Virtual Threads for unbounded non-blocking streaming concurrency,
 * enforces transport security (TLS/mTLS), and provides gRPC server reflection for grpcurl / Postman.
 */
public class GrpcPushServerManager {

    private static final Logger log = LoggerFactory.getLogger(GrpcPushServerManager.class);

    private final GrpcPushProperties properties;
    private final OisPushServiceImpl oisPushService;
    private final GrpcSecurityInterceptor securityInterceptor;
    private final OpenTelemetryServerInterceptor otelInterceptor;

    private Server server;

    public GrpcPushServerManager(
            GrpcPushProperties properties,
            OisPushServiceImpl oisPushService,
            GrpcSecurityInterceptor securityInterceptor,
            OpenTelemetryServerInterceptor otelInterceptor) {
        this.properties = properties;
        this.oisPushService = oisPushService;
        this.securityInterceptor = securityInterceptor;
        this.otelInterceptor = otelInterceptor;
    }

    @PostConstruct
    public synchronized void start() {
        if (properties == null || !properties.isEnabled()) {
            log.info("gRPC Push Connector is disabled via configuration.");
            return;
        }

        if (server != null && !server.isShutdown()) {
            return;
        }

        int port = properties.getPort();
        int maxMsgBytes = properties.getMaxMessageSizeMb() > 0
                ? properties.getMaxMessageSizeMb() * 1024 * 1024
                : 64 * 1024 * 1024;

        try {
            ServerBuilder<?> builder = ServerBuilder.forPort(port)
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .maxInboundMessageSize(maxMsgBytes)
                    .intercept(otelInterceptor)
                    .intercept(securityInterceptor)
                    .addService(oisPushService);

            if (properties.getReflection() != null && properties.getReflection().isEnabled()) {
                builder.addService(ProtoReflectionService.newInstance());
                log.info("gRPC Server Reflection enabled for OisPushService on port {}", port);
            }

            configureTls(builder);

            this.server = builder.build().start();
            log.info("gRPC Push Repository Connector listener successfully started on port {} (Virtual Threads enabled, maxMessageSize: {} MB)",
                    server.getPort(), properties.getMaxMessageSizeMb());

        } catch (Exception e) {
            log.error("Failed to start gRPC Push Connector listener on port {}: {}", port, e.getMessage(), e);
            throw new RuntimeException("Could not start gRPC push listener on port " + port, e);
        }
    }

    private void configureTls(ServerBuilder<?> builder) {
        GrpcPushProperties.TlsProperties tls = properties.getTls();
        if (tls == null || !tls.isEnabled()) {
            return;
        }

        String certChainPath = tls.getCertChainPath();
        String privateKeyPath = tls.getPrivateKeyPath();
        if (certChainPath == null || certChainPath.isBlank() || privateKeyPath == null || privateKeyPath.isBlank()) {
            log.warn("TLS enabled for gRPC Push Connector, but certChainPath or privateKeyPath is empty. Falling back to plaintext.");
            return;
        }

        File certChain = new File(certChainPath);
        File privateKey = new File(privateKeyPath);
        if (!certChain.exists() || !privateKey.exists()) {
            log.warn("TLS enabled for gRPC Push Connector, but certificate files do not exist ({} / {}). Falling back to plaintext.",
                    certChain.getAbsolutePath(), privateKey.getAbsolutePath());
            return;
        }

        try {
            if (tls.getClientAuth() != null && tls.getClientAuth() != GrpcPushProperties.ClientAuth.NONE
                    && tls.getTrustCertPath() != null && !tls.getTrustCertPath().isBlank()) {
                File trustCert = new File(tls.getTrustCertPath());
                if (trustCert.exists()) {
                    io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder sslBuilder =
                            io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forServer(certChain, privateKey)
                                    .trustManager(trustCert);

                    if (tls.getClientAuth() == GrpcPushProperties.ClientAuth.REQUIRE) {
                        sslBuilder.clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.REQUIRE);
                    } else if (tls.getClientAuth() == GrpcPushProperties.ClientAuth.OPTIONAL) {
                        sslBuilder.clientAuth(io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth.OPTIONAL);
                    }

                    if (builder instanceof io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder nettyBuilder) {
                        nettyBuilder.sslContext(sslBuilder.build());
                        log.info("gRPC Push Connector mTLS enabled with client auth {} and truststore {}",
                                tls.getClientAuth(), trustCert.getAbsolutePath());
                        return;
                    }
                }
            }

            builder.useTransportSecurity(certChain, privateKey);
            log.info("gRPC Push Connector TLS encryption enabled using cert: {}", certChain.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to configure TLS for gRPC Push Connector: {}", e.getMessage(), e);
            throw new RuntimeException("TLS configuration failure", e);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (server != null && !server.isShutdown()) {
            log.info("Shutting down gRPC Push Connector listener...");
            try {
                server.shutdown().awaitTermination(3, TimeUnit.SECONDS);
                log.info("gRPC Push Connector listener stopped successfully.");
            } catch (Exception e) {
                log.warn("Forcing gRPC Push Connector listener shutdownNow: {}", e.getMessage());
                server.shutdownNow();
            } finally {
                server = null;
            }
        }
    }

    public synchronized boolean isRunning() {
        return server != null && !server.isShutdown() && !server.isTerminated();
    }

    public synchronized int getPort() {
        if (server != null) {
            return server.getPort();
        }
        return properties != null ? properties.getPort() : 9096;
    }
}
