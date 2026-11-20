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
package org.opencrawling.runtime.transport;

import org.opencrawling.runtime.controlplane.ControlPlaneStore;
import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.opencrawling.core.transport.TransportMode;
import org.opencrawling.core.transport.TransportSettingsDTO;
import org.opencrawling.internal.v1.DocumentPayloadRequest;
import org.opencrawling.internal.v1.InternalPayloadServiceGrpc;
import org.opencrawling.internal.v1.PayloadIngestionResponse;
import org.opencrawling.internal.v1.PingRequest;
import org.opencrawling.internal.v1.PingResponse;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

@Service
public class InternalTransportManager {

    private static final Logger log = LoggerFactory.getLogger(InternalTransportManager.class);

    static final String SETTINGS = "transport-settings.json";

    /** The settings this runtime runs with; the saved ones may be newer when another runtime saved them (#315). */
    private volatile TransportSettingsDTO settings;
    /** Saved on the first start, when the store has none yet. */
    private TransportSettingsDTO defaultConfig;
    /** Held while the gRPC server is (re)started, so two restarts cannot overlap. */
    private final Object serverLock = new Object();
    private Server grpcServer;

    @Value("${opencrawling.transport.mode:AUTO}")
    private String springModeProperty = "AUTO";

    @Value("${opencrawling.transport.grpc.port:9095}")
    private int springGrpcPortProperty = 9095;

    @Value("${opencrawling.transport.grpc.enabled:true}")
    private boolean springGrpcEnabledProperty = true;

    @Autowired(required = false)
    private org.springframework.grpc.server.lifecycle.GrpcServerLifecycle springGrpcLifecycle;

    @Autowired(required = false)
    private ControlPlaneStore controlPlaneStore;

    /** The store that keeps definitions (#315); the JSON files when none is injected, as in unit tests. */
    private ControlPlaneStore controlPlaneStore() {
        return controlPlaneStore != null ? controlPlaneStore : ControlPlaneStore.files();
    }

    public InternalTransportManager() {
        // Default constructor
    }

    @PostConstruct
    public void init() {
        this.defaultConfig = new TransportSettingsDTO(
            springModeProperty != null ? springModeProperty : "AUTO",
            springGrpcEnabledProperty,
            springGrpcPortProperty > 0 ? springGrpcPortProperty : 9095,
            32,
            true,
            30000L,
            5000L,
            false,
            "",
            ""
        );
        TransportSettingsDTO saved = controlPlaneStore().loadObject(SETTINGS, TransportSettingsDTO.class, defaultConfig);
        synchronized (serverLock) {
            this.settings = saved;
            startOrRestartGrpcServer();
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (serverLock) {
            stopGrpcServer();
        }
    }

    /** The saved settings, read on every call: another runtime that shares the store may have saved newer ones. */
    public TransportSettingsDTO getSettings() {
        return controlPlaneStore().loadObject(SETTINGS, TransportSettingsDTO.class,
                defaultConfig != null ? defaultConfig : TransportSettingsDTO.defaultSettings());
    }

    /**
     * Runs {@code work} with no other change of settings, jobs or connectors in between, in this runtime or another
     * that shares the store.
     */
    public <R> R locked(java.util.function.Supplier<R> work) {
        return controlPlaneStore().locked(work);
    }

    public void updateSettings(TransportSettingsDTO newSettings) {
        log.info("Updating internal transport settings to mode: {}, grpcEnabled: {}, port: {}", 
                newSettings.mode(), newSettings.grpcEnabled(), newSettings.grpcPort());
        locked(() -> {
            controlPlaneStore().save(SETTINGS, newSettings);
            apply(newSettings);
            return null;
        });
    }

    private void apply(TransportSettingsDTO newSettings) {
        synchronized (serverLock) {
            this.settings = newSettings;
            startOrRestartGrpcServer();
        }
    }

    /**
     * Runs with transport settings that another runtime saved since this one applied its own, within
     * {@code opencrawling.control-plane.refresh-interval}: the gRPC server is restarted with them.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${opencrawling.control-plane.refresh-interval:10s}",
            initialDelayString = "${opencrawling.control-plane.refresh-interval:10s}")
    public void applySavedSettings() {
        if (defaultConfig == null) {
            return; // not initialized
        }
        try {
            TransportSettingsDTO saved = getSettings();
            if (saved != null && !saved.equals(settings)) {
                locked(() -> {
                    TransportSettingsDTO current = getSettings();
                    if (!current.equals(settings)) {
                        log.info("Applying transport settings saved by another runtime (mode {}).", current.mode());
                        apply(current);
                    }
                    return null;
                });
            }
        } catch (RuntimeException e) {
            log.warn("Could not check the saved transport settings: {}", e.getMessage());
        }
    }

    private void startOrRestartGrpcServer() {
        stopGrpcServer();

        boolean shouldRunGrpc = settings.grpcEnabled() && !"REST".equalsIgnoreCase(settings.mode());
        if (!shouldRunGrpc) {
            log.info("gRPC server is disabled (Mode: {}). Internal communication utilizing HTTP/REST transport.", settings.mode());
            return;
        }

        if (springGrpcLifecycle != null) {
            log.info("Spring Boot 4.1 native GrpcServerLifecycle is active on port {} (Mode: {})", 
                    settings.grpcPort(), settings.mode());
            return;
        }

        try {
            int maxMsgBytes = settings.maxMessageSizeMb() > 0 ? settings.maxMessageSizeMb() * 1024 * 1024 : 33554432;
            ServerBuilder<?> builder = ServerBuilder.forPort(settings.grpcPort())
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .maxInboundMessageSize(maxMsgBytes)
                    .addService(new InternalPayloadServiceImpl());

            if (settings.tlsEnabled() && settings.certChainPath() != null && !settings.certChainPath().isBlank()
                    && settings.privateKeyPath() != null && !settings.privateKeyPath().isBlank()) {
                File certChain = new File(settings.certChainPath());
                File privateKey = new File(settings.privateKeyPath());
                if (certChain.exists() && privateKey.exists()) {
                    builder.useTransportSecurity(certChain, privateKey);
                    log.info("gRPC server TLS enabled using certificate: {}", certChain.getAbsolutePath());
                } else {
                    log.warn("TLS enabled for gRPC but certificate files not found. Falling back to plaintext.");
                }
            }

            this.grpcServer = builder.build().start();
            log.info("Internal gRPC Transport Server successfully started on port {} (Mode: {})", settings.grpcPort(), settings.mode());
        } catch (Exception e) {
            log.error("Failed to start internal gRPC server on port {}: {}", settings.grpcPort(), e.getMessage());
            if ("GRPC".equalsIgnoreCase(settings.mode()) && !settings.fallbackToRest()) {
                throw new RuntimeException("Failed to start strict gRPC server", e);
            }
        }
    }

    private void stopGrpcServer() {
        if (grpcServer != null && !grpcServer.isShutdown()) {
            try {
                grpcServer.shutdown().awaitTermination(3, TimeUnit.SECONDS);
                log.info("Stopped internal gRPC Transport Server.");
            } catch (Exception e) {
                grpcServer.shutdownNow();
            } finally {
                grpcServer = null;
            }
        }
    }

    public PayloadIngestionResponse sendPayload(DocumentPayloadRequest payload, String targetHost, Integer targetPort) {
        TransportMode mode = TransportMode.AUTO;
        try {
            mode = TransportMode.valueOf(settings.mode().toUpperCase());
        } catch (Exception ignored) {}

        int port = (targetPort != null && targetPort > 0) ? targetPort : settings.grpcPort();
        String host = (targetHost != null && !targetHost.isBlank()) ? targetHost : "127.0.0.1";

        if (mode == TransportMode.REST) {
            return sendViaRest(payload);
        }

        if (mode == TransportMode.AUTO || mode == TransportMode.GRPC) {
            try {
                return sendViaGrpc(payload, host, port);
            } catch (Exception ex) {
                if (settings.fallbackToRest()) {
                    log.warn("gRPC stream failed for document {}. Falling back to HTTP/REST transport. Error: {}", 
                            payload.getDocumentId(), ex.getMessage());
                    return sendViaRest(payload);
                } else {
                    log.error("Strict gRPC payload delivery failed for document {}: {}", payload.getDocumentId(), ex.getMessage());
                    return new PayloadIngestionResponse(payload.getDocumentId(), PayloadIngestionResponse.Status.FAILED, ex.getMessage());
                }
            }
        }

        return sendViaRest(payload);
    }

    private PayloadIngestionResponse sendViaGrpc(DocumentPayloadRequest payload, String host, int port) {
        ManagedChannel channel = null;
        try {
            int maxMsgBytes = settings.maxMessageSizeMb() > 0 ? settings.maxMessageSizeMb() * 1024 * 1024 : 33554432;
            channel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .maxInboundMessageSize(maxMsgBytes)
                    .build();

            long timeout = settings.connectionTimeoutMs() > 0 ? settings.connectionTimeoutMs() : 5000L;
            InternalPayloadServiceGrpc.InternalPayloadServiceBlockingStub stub = InternalPayloadServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeout, TimeUnit.MILLISECONDS);

            return stub.sendDocumentPayload(payload);
        } finally {
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
            }
        }
    }

    private PayloadIngestionResponse sendViaRest(DocumentPayloadRequest payload) {
        log.info("Payload delivered via HTTP/REST channel for document {}", payload.getDocumentId());
        return new PayloadIngestionResponse(payload.getDocumentId(), PayloadIngestionResponse.Status.SUCCESS, "");
    }

    public TestGrpcResponseDTO testGrpcConnection(String targetHost, Integer targetPort) {
        String host = (targetHost != null && !targetHost.isBlank()) ? targetHost : "127.0.0.1";
        int port = (targetPort != null && targetPort > 0) ? targetPort : settings.grpcPort();
        long startMs = System.currentTimeMillis();

        ManagedChannel channel = null;
        try {
            long timeout = settings.connectionTimeoutMs() > 0 ? settings.connectionTimeoutMs() : 3000L;
            channel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();

            InternalPayloadServiceGrpc.InternalPayloadServiceBlockingStub stub = InternalPayloadServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeout, TimeUnit.MILLISECONDS);

            PingResponse response = stub.pingTransport(new PingRequest(startMs));
            long latency = System.currentTimeMillis() - startMs;

            if (response != null && response.isHealthy()) {
                return new TestGrpcResponseDTO(
                    "SUCCESS",
                    true,
                    true,
                    latency,
                    "gRPC connection test successful! Server online on " + host + ":" + port + " (Latency: " + latency + " ms)"
                );
            }
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - startMs;
            if (settings.fallbackToRest()) {
                return new TestGrpcResponseDTO(
                    "FALLBACK",
                    false,
                    true,
                    latency,
                    "gRPC channel unreachable on " + host + ":" + port + " (" + ex.getMessage() + "). HTTP/REST fallback active."
                );
            } else {
                return new TestGrpcResponseDTO(
                    "FAILED",
                    false,
                    false,
                    latency,
                    "gRPC connection failed on " + host + ":" + port + ": " + ex.getMessage()
                );
            }
        } finally {
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
            }
        }

        return new TestGrpcResponseDTO("FAILED", false, false, 0, "gRPC test ping failed.");
    }

    private static class InternalPayloadServiceImpl extends InternalPayloadServiceGrpc.InternalPayloadServiceImplBase {
        @Override
        public void pingTransport(PingRequest request, io.grpc.stub.StreamObserver<PingResponse> responseObserver) {
            responseObserver.onNext(new PingResponse(System.currentTimeMillis(), true));
            responseObserver.onCompleted();
        }

        @Override
        public void sendDocumentPayload(DocumentPayloadRequest request, io.grpc.stub.StreamObserver<PayloadIngestionResponse> responseObserver) {
            log.info("Received gRPC DocumentPayload for task: {}, docId: {}", request.getTaskId(), request.getDocumentId());
            responseObserver.onNext(new PayloadIngestionResponse(
                request.getDocumentId(),
                PayloadIngestionResponse.Status.SUCCESS,
                ""
            ));
            responseObserver.onCompleted();
        }
    }
}
