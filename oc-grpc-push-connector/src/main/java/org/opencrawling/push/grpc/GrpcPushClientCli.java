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

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.opencrawling.push.v1.DocumentAction;
import org.opencrawling.push.v1.IngestionAck;
import org.opencrawling.push.v1.IngestionStatus;
import org.opencrawling.push.v1.OisDocumentPayload;
import org.opencrawling.push.v1.OisPushServiceGrpc;
import org.opencrawling.push.v1.PingRequest;
import org.opencrawling.push.v1.PingResponse;
import org.opencrawling.push.v1.PushDocumentRequest;
import org.opencrawling.push.v1.PushDocumentResponse;
import org.opencrawling.push.v1.SecurityConfig;
import org.opencrawling.push.v1.StreamSummaryResponse;

/**
 * Lightweight command-line gRPC client for probing and pushing documents to the OpenCrawling
 * {@link OisPushServiceGrpc} endpoint during integration tests and operational diagnostics.
 */
public final class GrpcPushClientCli {

  private GrpcPushClientCli() {}

  public static void main(String[] args) {
    int exitCode = execute(args);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  static int execute(String[] args) {
    String host = "127.0.0.1";
    int port = 9096;
    String token = "";
    String action = "ping";
    String jobId = "1";
    String docId = "";
    String content = "OpenCrawling gRPC Push test payload";
    String mimeType = "text/plain";
    List<String> aclAllow = new ArrayList<>();
    Map<String, String> metadata = new LinkedHashMap<>();

    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "--host" -> {
          if (i + 1 < args.length) {
            host = args[++i];
          }
        }
        case "--port" -> {
          if (i + 1 < args.length) {
            port = Integer.parseInt(args[++i]);
          }
        }
        case "--token" -> {
          if (i + 1 < args.length) {
            token = args[++i];
          }
        }
        case "--action" -> {
          if (i + 1 < args.length) {
            action = args[++i].toLowerCase();
          }
        }
        case "--job-id" -> {
          if (i + 1 < args.length) {
            jobId = args[++i];
          }
        }
        case "--doc-id" -> {
          if (i + 1 < args.length) {
            docId = args[++i];
          }
        }
        case "--content" -> {
          if (i + 1 < args.length) {
            content = args[++i];
          }
        }
        case "--mime-type" -> {
          if (i + 1 < args.length) {
            mimeType = args[++i];
          }
        }
        case "--acl-allow" -> {
          if (i + 1 < args.length) {
            for (String item : args[++i].split(",")) {
              if (!item.isBlank()) {
                aclAllow.add(item.trim());
              }
            }
          }
        }
        case "--metadata" -> {
          if (i + 1 < args.length) {
            String kv = args[++i];
            int eq = kv.indexOf('=');
            if (eq > 0) {
              metadata.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
          }
        }
        default -> {
          // Ignore unrecognized arguments
        }
      }
    }

    ManagedChannel channel =
        ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    try {
      OisPushServiceGrpc.OisPushServiceBlockingStub blockingStub =
          OisPushServiceGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS);
      OisPushServiceGrpc.OisPushServiceStub asyncStub =
          OisPushServiceGrpc.newStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS);

      if (token != null && !token.isBlank()) {
        Metadata headers = new Metadata();
        headers.put(GrpcSecurityInterceptor.AUTHORIZATION_METADATA_KEY, "Bearer " + token.trim());
        blockingStub =
            blockingStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        asyncStub =
            asyncStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
      }

      return switch (action) {
        case "ping" -> runPing(blockingStub);
        case "upsert" ->
            runPush(
                blockingStub,
                buildRequest(
                    jobId, docId, DocumentAction.UPSERT, content, mimeType, aclAllow, metadata));
        case "delete" ->
            runPush(
                blockingStub,
                buildRequest(
                    jobId, docId, DocumentAction.DELETE, "", mimeType, aclAllow, metadata));
        case "stream" ->
            runClientStream(asyncStub, jobId, docId, content, mimeType, aclAllow, metadata);
        case "bidi" ->
            runBidiStream(asyncStub, jobId, docId, content, mimeType, aclAllow, metadata);
        default -> {
          System.err.println("Unsupported --action: " + action);
          yield 2;
        }
      };
    } catch (StatusRuntimeException e) {
      System.out.printf(
          "{\"accepted\":false,\"grpcStatus\":\"%s\",\"description\":\"%s\"}%n",
          e.getStatus().getCode(),
          escapeJson(e.getStatus().getDescription() != null ? e.getStatus().getDescription() : ""));
      return 1;
    } finally {
      channel.shutdown();
      try {
        channel.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        channel.shutdownNow();
      }
    }
  }

  private static int runPing(OisPushServiceGrpc.OisPushServiceBlockingStub stub) {
    PingResponse response =
        stub.ping(PingRequest.newBuilder().setClientId("oc-grpc-push-cli").build());
    System.out.printf(
        "{\"status\":\"%s\",\"version\":\"%s\",\"serverTime\":%d}%n",
        escapeJson(response.getStatus()),
        escapeJson(response.getVersion()),
        response.getServerTime());
    return "UP".equalsIgnoreCase(response.getStatus()) ? 0 : 1;
  }

  private static int runPush(
      OisPushServiceGrpc.OisPushServiceBlockingStub stub, PushDocumentRequest request) {
    PushDocumentResponse response = stub.pushDocument(request);
    boolean accepted =
        response.getStatus() == IngestionStatus.ACCEPTED
            || response.getStatus() == IngestionStatus.QUEUED;
    System.out.printf(
        "{\"accepted\":%b,\"documentId\":\"%s\",\"status\":\"%s\",\"message\":\"%s\"}%n",
        accepted,
        escapeJson(response.getDocumentId()),
        response.getStatus().name(),
        escapeJson(response.getMessage()));
    return accepted ? 0 : 1;
  }

  private static int runClientStream(
      OisPushServiceGrpc.OisPushServiceStub asyncStub,
      String jobId,
      String docIdsCsv,
      String content,
      String mimeType,
      List<String> aclAllow,
      Map<String, String> metadata) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean allAccepted = new AtomicBoolean(false);
    AtomicReference<Throwable> streamError = new AtomicReference<>();

    StreamObserver<StreamSummaryResponse> responseObserver =
        new StreamObserver<>() {
          @Override
          public void onNext(StreamSummaryResponse summary) {
            boolean ok =
                summary.getTotalAccepted() > 0 && summary.getTotalRejected() == 0;
            allAccepted.set(ok);
            System.out.printf(
                "{\"accepted\":%b,\"totalReceived\":%d,\"totalAccepted\":%d,\"totalRejected\":%d}%n",
                ok,
                summary.getTotalReceived(),
                summary.getTotalAccepted(),
                summary.getTotalRejected());
          }

          @Override
          public void onError(Throwable t) {
            streamError.set(t);
            latch.countDown();
          }

          @Override
          public void onCompleted() {
            latch.countDown();
          }
        };

    StreamObserver<PushDocumentRequest> requestObserver =
        asyncStub.streamDocuments(responseObserver);
    for (String id : docIdsCsv.split(",")) {
      String trimmed = id.trim();
      if (!trimmed.isEmpty()) {
        requestObserver.onNext(
            buildRequest(
                jobId, trimmed, DocumentAction.UPSERT, content, mimeType, aclAllow, metadata));
      }
    }
    requestObserver.onCompleted();

    return awaitLatch(latch, streamError, allAccepted);
  }

  private static int runBidiStream(
      OisPushServiceGrpc.OisPushServiceStub asyncStub,
      String jobId,
      String docIdsCsv,
      String content,
      String mimeType,
      List<String> aclAllow,
      Map<String, String> metadata) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean allAccepted = new AtomicBoolean(true);
    AtomicReference<Throwable> streamError = new AtomicReference<>();

    StreamObserver<IngestionAck> responseObserver =
        new StreamObserver<>() {
          @Override
          public void onNext(IngestionAck ack) {
            boolean ok =
                ack.getStatus() == IngestionStatus.ACCEPTED
                    || ack.getStatus() == IngestionStatus.QUEUED;
            if (!ok) {
              allAccepted.set(false);
            }
            System.out.printf(
                "{\"accepted\":%b,\"documentId\":\"%s\",\"status\":\"%s\",\"errorMessage\":\"%s\"}%n",
                ok,
                escapeJson(ack.getDocumentId()),
                ack.getStatus().name(),
                escapeJson(ack.getErrorMessage()));
          }

          @Override
          public void onError(Throwable t) {
            streamError.set(t);
            allAccepted.set(false);
            latch.countDown();
          }

          @Override
          public void onCompleted() {
            latch.countDown();
          }
        };

    StreamObserver<PushDocumentRequest> requestObserver =
        asyncStub.biDirectionalStream(responseObserver);
    for (String id : docIdsCsv.split(",")) {
      String trimmed = id.trim();
      if (!trimmed.isEmpty()) {
        requestObserver.onNext(
            buildRequest(
                jobId, trimmed, DocumentAction.UPSERT, content, mimeType, aclAllow, metadata));
      }
    }
    requestObserver.onCompleted();

    return awaitLatch(latch, streamError, allAccepted);
  }

  private static int awaitLatch(
      CountDownLatch latch,
      AtomicReference<Throwable> streamError,
      AtomicBoolean allAccepted) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        System.err.println("Timed out waiting for gRPC stream completion");
        return 1;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 1;
    }

    if (streamError.get() != null) {
      if (streamError.get() instanceof StatusRuntimeException sre) {
        System.out.printf(
            "{\"accepted\":false,\"grpcStatus\":\"%s\",\"description\":\"%s\"}%n",
            sre.getStatus().getCode(),
            escapeJson(
                sre.getStatus().getDescription() != null
                    ? sre.getStatus().getDescription()
                    : ""));
      } else {
        System.err.println("Stream error: " + streamError.get().getMessage());
      }
      return 1;
    }
    return allAccepted.get() ? 0 : 1;
  }

  private static PushDocumentRequest buildRequest(
      String jobId,
      String docId,
      DocumentAction actionType,
      String content,
      String mimeType,
      List<String> aclAllow,
      Map<String, String> metadata) {
    OisDocumentPayload.Builder docBuilder =
        OisDocumentPayload.newBuilder()
            .setId(docId)
            .setAction(actionType)
            .setMimeType(mimeType)
            .putAllMetadata(metadata);

    if (actionType == DocumentAction.UPSERT && content != null) {
      docBuilder.setRawBytes(ByteString.copyFrom(content, StandardCharsets.UTF_8));
    }
    if (!aclAllow.isEmpty()) {
      docBuilder.setSecurity(SecurityConfig.newBuilder().addAllAllowSids(aclAllow).build());
    }
    return PushDocumentRequest.newBuilder().setJobId(jobId).setDocument(docBuilder.build()).build();
  }

  private static String escapeJson(String input) {
    if (input == null) {
      return "";
    }
    return input.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
