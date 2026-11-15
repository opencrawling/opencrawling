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
package org.opencrawling.cli.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.opencrawling.cli.util.AnsiColors;
import org.opencrawling.cli.util.TableFormatter;
import org.opencrawling.push.grpc.GrpcSecurityInterceptor;
import org.opencrawling.push.v1.DocumentAction;
import org.opencrawling.push.v1.IngestionAck;
import org.opencrawling.push.v1.IngestionStatus;
import org.opencrawling.push.v1.OisDocumentPayload;
import org.opencrawling.push.v1.OisPushServiceGrpc;
import org.opencrawling.push.v1.PingRequest;
import org.opencrawling.push.v1.PingResponse;
import org.opencrawling.push.v1.PushDocumentRequest;
import org.opencrawling.push.v1.PushDocumentResponse;
import org.opencrawling.push.v1.PushOptions;
import org.opencrawling.push.v1.SecurityConfig;
import org.opencrawling.push.v1.StreamSummaryResponse;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Picocli command group for streaming documents and tombstones to the gRPC Push Repository
 * Connector ({@code oc push}).
 */
@Command(
    name = "push",
    mixinStandardHelpOptions = true,
    description =
        "Stream documents and tombstones to the gRPC Push Repository Connector (ping, upsert, delete, stream)",
    subcommands = {
      PushCommand.PushPingCommand.class,
      PushCommand.PushUpsertCommand.class,
      PushCommand.PushDeleteCommand.class,
      PushCommand.PushStreamCommand.class
    })
public class PushCommand implements Runnable {

  @Override
  public void run() {
    System.out.println(
        AnsiColors.yellow("Please specify a subcommand: ping, upsert, delete, stream"));
  }

  static ManagedChannel openChannel(String host, int port, boolean tls, String caCertPath)
      throws Exception {
    if (!tls) {
      return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }
    NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port);
    if (caCertPath != null && !caCertPath.isBlank()) {
      File caFile = new File(caCertPath);
      builder.sslContext(GrpcSslContexts.forClient().trustManager(caFile).build());
    } else {
      builder.useTransportSecurity();
    }
    return builder.build();
  }

  static String resolveToken(String flagToken) {
    if (flagToken != null && !flagToken.isBlank()) {
      return flagToken.trim();
    }
    String env = System.getenv("OPENCRAWLING_GRPC_PUSH_TOKEN");
    return env != null ? env.trim() : "";
  }

  static OisPushServiceGrpc.OisPushServiceBlockingStub decorateBlockingStub(
      ManagedChannel channel, String token) {
    OisPushServiceGrpc.OisPushServiceBlockingStub stub =
        OisPushServiceGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS);
    String resolved = resolveToken(token);
    if (!resolved.isEmpty()) {
      Metadata headers = new Metadata();
      headers.put(GrpcSecurityInterceptor.AUTHORIZATION_METADATA_KEY, "Bearer " + resolved);
      stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    return stub;
  }

  static OisPushServiceGrpc.OisPushServiceStub decorateAsyncStub(
      ManagedChannel channel, String token) {
    OisPushServiceGrpc.OisPushServiceStub stub =
        OisPushServiceGrpc.newStub(channel).withDeadlineAfter(60, TimeUnit.SECONDS);
    String resolved = resolveToken(token);
    if (!resolved.isEmpty()) {
      Metadata headers = new Metadata();
      headers.put(GrpcSecurityInterceptor.AUTHORIZATION_METADATA_KEY, "Bearer " + resolved);
      stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }
    return stub;
  }

  static void closeChannel(ManagedChannel channel) {
    if (channel != null) {
      channel.shutdown();
      try {
        channel.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        channel.shutdownNow();
      }
    }
  }

  static List<String> parseCsv(String csv) {
    List<String> list = new ArrayList<>();
    if (csv != null && !csv.isBlank()) {
      for (String part : csv.split(",")) {
        if (!part.isBlank()) {
          list.add(part.trim());
        }
      }
    }
    return list;
  }

  static Map<String, String> parseMetadata(List<String> entries) {
    Map<String, String> map = new LinkedHashMap<>();
    if (entries != null) {
      for (String kv : entries) {
        int eq = kv.indexOf('=');
        if (eq > 0) {
          map.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
        }
      }
    }
    return map;
  }

  static String detectMimeType(Path file, String explicitMimeType) {
    if (explicitMimeType != null && !explicitMimeType.isBlank()) {
      return explicitMimeType.trim();
    }
    try {
      String probed = Files.probeContentType(file);
      if (probed != null && !probed.isBlank()) {
        return probed;
      }
    } catch (IOException ignored) {
      // Fallback to extension inspection
    }
    String name = file.getFileName().toString().toLowerCase();
    if (name.endsWith(".md")) {
      return "text/markdown";
    }
    if (name.endsWith(".html") || name.endsWith(".htm")) {
      return "text/html";
    }
    if (name.endsWith(".pdf")) {
      return "application/pdf";
    }
    if (name.endsWith(".json")) {
      return "application/json";
    }
    return "text/plain";
  }

  @Command(
      name = "ping",
      mixinStandardHelpOptions = true,
      description = "Probe the gRPC Push Repository Connector listener (OisPushService/Ping)")
  public static class PushPingCommand implements Callable<Integer> {

    @Option(
        names = {"--host"},
        description = "gRPC Push server hostname or IP",
        defaultValue = "127.0.0.1")
    private String host;

    @Option(
        names = {"--port"},
        description = "gRPC Push server port",
        defaultValue = "9096")
    private int port;

    @Option(
        names = {"--tls"},
        description = "Use TLS transport security")
    private boolean tls;

    @Option(
        names = {"--ca-cert"},
        description = "Trusted CA certificate path for TLS")
    private String caCert;

    @Option(
        names = {"--format"},
        description = "Output format: table, json",
        defaultValue = "table")
    private String format;

    @Override
    public Integer call() {
      ManagedChannel channel = null;
      try {
        long startNs = System.nanoTime();
        channel = openChannel(host, port, tls, caCert);
        OisPushServiceGrpc.OisPushServiceBlockingStub stub =
            decorateBlockingStub(channel, null);
        PingResponse resp =
            stub.ping(PingRequest.newBuilder().setClientId("oc-cli").build());
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        boolean up = "UP".equalsIgnoreCase(resp.getStatus());
        if ("json".equalsIgnoreCase(format)) {
          ObjectMapper mapper = new ObjectMapper();
          Map<String, Object> out = new LinkedHashMap<>();
          out.put("status", resp.getStatus());
          out.put("version", resp.getVersion());
          out.put("serverTime", resp.getServerTime());
          out.put("latencyMs", latencyMs);
          System.out.println(mapper.writeValueAsString(out));
        } else {
          TableFormatter table =
              new TableFormatter()
                  .setHeaders("ENDPOINT", "STATUS", "VERSION", "SERVER TIME", "LATENCY (MS)");
          table.addRow(
              host + ":" + port,
              up ? AnsiColors.green(resp.getStatus()) : AnsiColors.red(resp.getStatus()),
              resp.getVersion(),
              String.valueOf(resp.getServerTime()),
              String.valueOf(latencyMs));
          System.out.println(table.render());
        }
        return up ? 0 : 1;
      } catch (StatusRuntimeException e) {
        System.err.println(
            AnsiColors.red(
                "✖ gRPC Ping failed ["
                    + e.getStatus().getCode()
                    + "]: "
                    + (e.getStatus().getDescription() != null
                        ? e.getStatus().getDescription()
                        : e.getMessage())));
        return 1;
      } catch (Exception e) {
        System.err.println(AnsiColors.red("✖ Error probing gRPC Push server: " + e.getMessage()));
        return 1;
      } finally {
        closeChannel(channel);
      }
    }
  }

  @Command(
      name = "upsert",
      aliases = {"document"},
      mixinStandardHelpOptions = true,
      description =
          "Push an UPSERT document payload to the gRPC Push Repository Connector (OisPushService/PushDocument)")
  public static class PushUpsertCommand implements Callable<Integer> {

    @Option(
        names = {"--job-id"},
        description = "Target OpenCrawling job ID",
        defaultValue = "1")
    private String jobId;

    @Option(
        names = {"--id", "--doc-id"},
        required = true,
        description = "Unique document identifier or URI")
    private String docId;

    @Option(
        names = {"--text", "--content"},
        description = "Inline text content to index")
    private String textContent;

    @Option(
        names = {"--file"},
        description = "Path to a local file to push as binary payload")
    private String filePath;

    @Option(
        names = {"--claim-check-uri"},
        description = "Pre-uploaded Claim-Check URI (e.g. ozone://... or s3://...)")
    private String claimCheckUri;

    @Option(
        names = {"--mime-type"},
        description = "Document MIME type (default: text/plain or auto-detected from --file)")
    private String mimeType;

    @Option(
        names = {"--acl-allow", "--allow-sids"},
        description = "Comma-separated list of allowed security identities/SIDs")
    private String aclAllow;

    @Option(
        names = {"--acl-deny", "--deny-sids"},
        description = "Comma-separated list of denied security identities/SIDs")
    private String aclDeny;

    @Option(
        names = {"--owner"},
        description = "Document owner identity")
    private String owner;

    @Option(
        names = {"--tenant-id"},
        description = "Multi-tenant isolation identifier")
    private String tenantId;

    @Option(
        names = {"--metadata"},
        description = "Document metadata key=value pair (repeatable)")
    private List<String> metadataEntries;

    @Option(
        names = {"--bypass-tika"},
        description = "Bypass Apache Tika text extraction for pre-extracted text")
    private boolean bypassTika;

    @Option(
        names = {"--host"},
        description = "gRPC Push server hostname or IP",
        defaultValue = "127.0.0.1")
    private String host;

    @Option(
        names = {"--port"},
        description = "gRPC Push server port",
        defaultValue = "9096")
    private int port;

    @Option(
        names = {"--token"},
        description =
            "Bearer authentication token (defaults to OPENCRAWLING_GRPC_PUSH_TOKEN env var)")
    private String token;

    @Option(
        names = {"--tls"},
        description = "Use TLS transport security")
    private boolean tls;

    @Option(
        names = {"--ca-cert"},
        description = "Trusted CA certificate path for TLS")
    private String caCert;

    @Option(
        names = {"--format"},
        description = "Output format: table, json",
        defaultValue = "table")
    private String format;

    @Override
    public Integer call() {
      ManagedChannel channel = null;
      try {
        OisDocumentPayload.Builder docBuilder =
            OisDocumentPayload.newBuilder()
                .setId(docId)
                .setAction(DocumentAction.UPSERT)
                .setTimestamp(System.currentTimeMillis())
                .putAllMetadata(parseMetadata(metadataEntries));

        if (claimCheckUri != null && !claimCheckUri.isBlank()) {
          docBuilder.setClaimCheckUri(claimCheckUri.trim());
          docBuilder.setMimeType(
              mimeType != null && !mimeType.isBlank()
                  ? mimeType.trim()
                  : "application/octet-stream");
        } else if (filePath != null && !filePath.isBlank()) {
          Path path = Path.of(filePath);
          if (!Files.exists(path)) {
            System.err.println(AnsiColors.red("✖ File not found: " + filePath));
            return 1;
          }
          byte[] bytes = Files.readAllBytes(path);
          docBuilder.setRawBytes(ByteString.copyFrom(bytes));
          docBuilder.setMimeType(detectMimeType(path, mimeType));
        } else {
          String body = textContent != null ? textContent : "";
          docBuilder.setTextContent(body);
          docBuilder.setMimeType(
              mimeType != null && !mimeType.isBlank() ? mimeType.trim() : "text/plain");
        }

        List<String> allows = parseCsv(aclAllow);
        List<String> denies = parseCsv(aclDeny);
        if (!allows.isEmpty()
            || !denies.isEmpty()
            || (owner != null && !owner.isBlank())
            || (tenantId != null && !tenantId.isBlank())) {
          SecurityConfig.Builder secBuilder =
              SecurityConfig.newBuilder().addAllAllowSids(allows).addAllDenySids(denies);
          if (owner != null && !owner.isBlank()) {
            secBuilder.setOwner(owner.trim());
          }
          if (tenantId != null && !tenantId.isBlank()) {
            secBuilder.setTenantId(tenantId.trim());
          }
          docBuilder.setSecurity(secBuilder.build());
        }

        PushDocumentRequest.Builder reqBuilder =
            PushDocumentRequest.newBuilder().setJobId(jobId).setDocument(docBuilder.build());
        if (bypassTika) {
          reqBuilder.setOptions(PushOptions.newBuilder().setBypassTikaExtraction(true).build());
        }

        channel = openChannel(host, port, tls, caCert);
        OisPushServiceGrpc.OisPushServiceBlockingStub stub = decorateBlockingStub(channel, token);
        PushDocumentResponse resp = stub.pushDocument(reqBuilder.build());

        boolean accepted =
            resp.getStatus() == IngestionStatus.ACCEPTED
                || resp.getStatus() == IngestionStatus.QUEUED;
        renderPushResponse(resp.getDocumentId(), resp.getStatus().name(), resp.getMessage(), accepted, format);
        return accepted ? 0 : 1;
      } catch (StatusRuntimeException e) {
        return handleGrpcError(e, format);
      } catch (Exception e) {
        System.err.println(AnsiColors.red("✖ Error pushing document: " + e.getMessage()));
        return 1;
      } finally {
        closeChannel(channel);
      }
    }
  }

  @Command(
      name = "delete",
      mixinStandardHelpOptions = true,
      description =
          "Push a DELETE tombstone to the gRPC Push Repository Connector (OisPushService/PushDocument)")
  public static class PushDeleteCommand implements Callable<Integer> {

    @Option(
        names = {"--job-id"},
        description = "Target OpenCrawling job ID",
        defaultValue = "1")
    private String jobId;

    @Option(
        names = {"--id", "--doc-id"},
        required = true,
        description = "Unique document identifier or URI to delete")
    private String docId;

    @Option(
        names = {"--host"},
        description = "gRPC Push server hostname or IP",
        defaultValue = "127.0.0.1")
    private String host;

    @Option(
        names = {"--port"},
        description = "gRPC Push server port",
        defaultValue = "9096")
    private int port;

    @Option(
        names = {"--token"},
        description =
            "Bearer authentication token (defaults to OPENCRAWLING_GRPC_PUSH_TOKEN env var)")
    private String token;

    @Option(
        names = {"--tls"},
        description = "Use TLS transport security")
    private boolean tls;

    @Option(
        names = {"--ca-cert"},
        description = "Trusted CA certificate path for TLS")
    private String caCert;

    @Option(
        names = {"--format"},
        description = "Output format: table, json",
        defaultValue = "table")
    private String format;

    @Override
    public Integer call() {
      ManagedChannel channel = null;
      try {
        OisDocumentPayload payload =
            OisDocumentPayload.newBuilder()
                .setId(docId)
                .setAction(DocumentAction.DELETE)
                .setTimestamp(System.currentTimeMillis())
                .build();

        PushDocumentRequest request =
            PushDocumentRequest.newBuilder().setJobId(jobId).setDocument(payload).build();

        channel = openChannel(host, port, tls, caCert);
        OisPushServiceGrpc.OisPushServiceBlockingStub stub = decorateBlockingStub(channel, token);
        PushDocumentResponse resp = stub.pushDocument(request);

        boolean accepted =
            resp.getStatus() == IngestionStatus.ACCEPTED
                || resp.getStatus() == IngestionStatus.QUEUED;
        renderPushResponse(resp.getDocumentId(), resp.getStatus().name(), resp.getMessage(), accepted, format);
        return accepted ? 0 : 1;
      } catch (StatusRuntimeException e) {
        return handleGrpcError(e, format);
      } catch (Exception e) {
        System.err.println(AnsiColors.red("✖ Error pushing delete tombstone: " + e.getMessage()));
        return 1;
      } finally {
        closeChannel(channel);
      }
    }
  }

  @Command(
      name = "stream",
      mixinStandardHelpOptions = true,
      description =
          "Stream multiple documents over HTTP/2 (StreamDocuments or BiDirectionalStream)")
  public static class PushStreamCommand implements Callable<Integer> {

    @Option(
        names = {"--job-id"},
        description = "Target OpenCrawling job ID",
        defaultValue = "1")
    private String jobId;

    @Option(
        names = {"--dir"},
        description = "Directory of local files to stream")
    private String directoryPath;

    @Option(
        names = {"--doc-ids"},
        description = "Comma-separated document IDs to stream with inline --text")
    private String docIdsCsv;

    @Option(
        names = {"--text", "--content"},
        description = "Inline text content when using --doc-ids",
        defaultValue = "OpenCrawling gRPC Push streamed payload")
    private String textContent;

    @Option(
        names = {"--mime-type"},
        description = "Override MIME type for streamed documents")
    private String mimeType;

    @Option(
        names = {"--bidi"},
        description = "Use BiDirectionalStream for real-time per-document acknowledgments")
    private boolean bidi;

    @Option(
        names = {"--acl-allow", "--allow-sids"},
        description = "Comma-separated list of allowed security identities/SIDs")
    private String aclAllow;

    @Option(
        names = {"--acl-deny", "--deny-sids"},
        description = "Comma-separated list of denied security identities/SIDs")
    private String aclDeny;

    @Option(
        names = {"--metadata"},
        description = "Document metadata key=value pair (repeatable)")
    private List<String> metadataEntries;

    @Option(
        names = {"--host"},
        description = "gRPC Push server hostname or IP",
        defaultValue = "127.0.0.1")
    private String host;

    @Option(
        names = {"--port"},
        description = "gRPC Push server port",
        defaultValue = "9096")
    private int port;

    @Option(
        names = {"--token"},
        description =
            "Bearer authentication token (defaults to OPENCRAWLING_GRPC_PUSH_TOKEN env var)")
    private String token;

    @Option(
        names = {"--tls"},
        description = "Use TLS transport security")
    private boolean tls;

    @Option(
        names = {"--ca-cert"},
        description = "Trusted CA certificate path for TLS")
    private String caCert;

    @Option(
        names = {"--format"},
        description = "Output format: table, json",
        defaultValue = "table")
    private String format;

    @Override
    public Integer call() {
      List<PushDocumentRequest> requests = new ArrayList<>();
      Map<String, String> metadata = parseMetadata(metadataEntries);
      List<String> allows = parseCsv(aclAllow);
      List<String> denies = parseCsv(aclDeny);

      try {
        if (directoryPath != null && !directoryPath.isBlank()) {
          Path dir = Path.of(directoryPath);
          if (!Files.isDirectory(dir)) {
            System.err.println(AnsiColors.red("✖ Directory not found: " + directoryPath));
            return 1;
          }
          try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
              OisDocumentPayload.Builder doc =
                  OisDocumentPayload.newBuilder()
                      .setId(file.toUri().toString())
                      .setAction(DocumentAction.UPSERT)
                      .setMimeType(detectMimeType(file, mimeType))
                      .setRawBytes(ByteString.copyFrom(Files.readAllBytes(file)))
                      .setTimestamp(System.currentTimeMillis())
                      .putAllMetadata(metadata);
              if (!allows.isEmpty() || !denies.isEmpty()) {
                doc.setSecurity(
                    SecurityConfig.newBuilder()
                        .addAllAllowSids(allows)
                        .addAllDenySids(denies)
                        .build());
              }
              requests.add(
                  PushDocumentRequest.newBuilder().setJobId(jobId).setDocument(doc.build()).build());
            }
          }
        } else if (docIdsCsv != null && !docIdsCsv.isBlank()) {
          for (String id : parseCsv(docIdsCsv)) {
            OisDocumentPayload.Builder doc =
                OisDocumentPayload.newBuilder()
                    .setId(id)
                    .setAction(DocumentAction.UPSERT)
                    .setMimeType(
                        mimeType != null && !mimeType.isBlank() ? mimeType.trim() : "text/plain")
                    .setTextContent(textContent)
                    .setTimestamp(System.currentTimeMillis())
                    .putAllMetadata(metadata);
            if (!allows.isEmpty() || !denies.isEmpty()) {
              doc.setSecurity(
                  SecurityConfig.newBuilder()
                      .addAllAllowSids(allows)
                      .addAllDenySids(denies)
                      .build());
            }
            requests.add(
                PushDocumentRequest.newBuilder().setJobId(jobId).setDocument(doc.build()).build());
          }
        } else {
          System.err.println(
              AnsiColors.red("✖ Please specify either --dir <path> or --doc-ids <id1,id2,...>"));
          return 2;
        }
      } catch (Exception e) {
        System.err.println(
            AnsiColors.red("✖ Error preparing stream payloads: " + e.getMessage()));
        return 1;
      }

      if (requests.isEmpty()) {
        System.err.println(AnsiColors.yellow("⚠ No documents found to stream."));
        return 0;
      }

      ManagedChannel channel = null;
      try {
        channel = openChannel(host, port, tls, caCert);
        OisPushServiceGrpc.OisPushServiceStub asyncStub = decorateAsyncStub(channel, token);
        if (bidi) {
          return executeBidiStream(asyncStub, requests, format);
        }
        return executeClientStream(asyncStub, requests, format);
      } catch (StatusRuntimeException e) {
        return handleGrpcError(e, format);
      } catch (Exception e) {
        System.err.println(AnsiColors.red("✖ Error streaming documents: " + e.getMessage()));
        return 1;
      } finally {
        closeChannel(channel);
      }
    }

    private int executeClientStream(
        OisPushServiceGrpc.OisPushServiceStub asyncStub,
        List<PushDocumentRequest> requests,
        String format)
        throws Exception {
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<StreamSummaryResponse> summaryRef = new AtomicReference<>();
      AtomicReference<Throwable> errorRef = new AtomicReference<>();

      StreamObserver<PushDocumentRequest> reqObserver =
          asyncStub.streamDocuments(
              new StreamObserver<>() {
                @Override
                public void onNext(StreamSummaryResponse summary) {
                  summaryRef.set(summary);
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

      for (PushDocumentRequest req : requests) {
        reqObserver.onNext(req);
      }
      reqObserver.onCompleted();

      if (!latch.await(60, TimeUnit.SECONDS)) {
        System.err.println(AnsiColors.red("✖ Timed out waiting for StreamDocuments response"));
        return 1;
      }
      if (errorRef.get() != null) {
        if (errorRef.get() instanceof StatusRuntimeException sre) {
          return handleGrpcError(sre, format);
        }
        System.err.println(AnsiColors.red("✖ Stream error: " + errorRef.get().getMessage()));
        return 1;
      }

      StreamSummaryResponse summary = summaryRef.get();
      boolean ok =
          summary != null && summary.getTotalAccepted() > 0 && summary.getTotalRejected() == 0;
      if ("json".equalsIgnoreCase(format) && summary != null) {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", ok);
        out.put("totalReceived", summary.getTotalReceived());
        out.put("totalAccepted", summary.getTotalAccepted());
        out.put("totalRejected", summary.getTotalRejected());
        out.put("durationMs", summary.getDurationMs());
        System.out.println(mapper.writeValueAsString(out));
      } else if (summary != null) {
        TableFormatter table =
            new TableFormatter()
                .setHeaders("TOTAL RECEIVED", "ACCEPTED", "REJECTED", "DURATION (MS)");
        table.addRow(
            String.valueOf(summary.getTotalReceived()),
            AnsiColors.green(String.valueOf(summary.getTotalAccepted())),
            summary.getTotalRejected() > 0
                ? AnsiColors.red(String.valueOf(summary.getTotalRejected()))
                : "0",
            String.valueOf(summary.getDurationMs()));
        System.out.println(table.render());
      }
      return ok ? 0 : 1;
    }

    private int executeBidiStream(
        OisPushServiceGrpc.OisPushServiceStub asyncStub,
        List<PushDocumentRequest> requests,
        String format)
        throws Exception {
      CountDownLatch latch = new CountDownLatch(1);
      AtomicBoolean allAccepted = new AtomicBoolean(true);
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      List<IngestionAck> acks = new ArrayList<>();

      StreamObserver<PushDocumentRequest> reqObserver =
          asyncStub.biDirectionalStream(
              new StreamObserver<>() {
                @Override
                public void onNext(IngestionAck ack) {
                  synchronized (acks) {
                    acks.add(ack);
                  }
                  boolean ok =
                      ack.getStatus() == IngestionStatus.ACCEPTED
                          || ack.getStatus() == IngestionStatus.QUEUED;
                  if (!ok) {
                    allAccepted.set(false);
                  }
                }

                @Override
                public void onError(Throwable t) {
                  errorRef.set(t);
                  allAccepted.set(false);
                  latch.countDown();
                }

                @Override
                public void onCompleted() {
                  latch.countDown();
                }
              });

      for (PushDocumentRequest req : requests) {
        reqObserver.onNext(req);
      }
      reqObserver.onCompleted();

      if (!latch.await(60, TimeUnit.SECONDS)) {
        System.err.println(AnsiColors.red("✖ Timed out waiting for BiDirectionalStream response"));
        return 1;
      }
      if (errorRef.get() != null) {
        if (errorRef.get() instanceof StatusRuntimeException sre) {
          return handleGrpcError(sre, format);
        }
        System.err.println(AnsiColors.red("✖ Stream error: " + errorRef.get().getMessage()));
        return 1;
      }

      if ("json".equalsIgnoreCase(format)) {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", allAccepted.get());
        out.put("count", acks.size());
        System.out.println(mapper.writeValueAsString(out));
      } else {
        TableFormatter table =
            new TableFormatter().setHeaders("DOCUMENT ID", "STATUS", "ERROR MESSAGE");
        for (IngestionAck ack : acks) {
          boolean ok =
              ack.getStatus() == IngestionStatus.ACCEPTED
                  || ack.getStatus() == IngestionStatus.QUEUED;
          table.addRow(
              ack.getDocumentId(),
              ok
                  ? AnsiColors.green(ack.getStatus().name())
                  : AnsiColors.red(ack.getStatus().name()),
              ack.getErrorMessage());
        }
        System.out.println(table.render());
      }
      return allAccepted.get() ? 0 : 1;
    }
  }

  private static void renderPushResponse(
      String docId, String status, String message, boolean accepted, String format)
      throws Exception {
    if ("json".equalsIgnoreCase(format)) {
      ObjectMapper mapper = new ObjectMapper();
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("accepted", accepted);
      out.put("documentId", docId);
      out.put("status", status);
      out.put("message", message);
      System.out.println(mapper.writeValueAsString(out));
    } else if (accepted) {
      System.out.println(
          AnsiColors.green("✔ Document accepted: ")
              + AnsiColors.bold(docId)
              + " ["
              + status
              + "] "
              + (message != null ? message : ""));
    } else {
      System.err.println(
          AnsiColors.red("✖ Document rejected: ")
              + docId
              + " ["
              + status
              + "] "
              + (message != null ? message : ""));
    }
  }

  private static int handleGrpcError(StatusRuntimeException e, String format) {
    String code = e.getStatus().getCode().name();
    String desc =
        e.getStatus().getDescription() != null ? e.getStatus().getDescription() : e.getMessage();
    if ("json".equalsIgnoreCase(format)) {
      System.out.printf(
          "{\"accepted\":false,\"grpcStatus\":\"%s\",\"description\":\"%s\"}%n",
          code, desc != null ? desc.replace("\"", "\\\"") : "");
    } else {
      System.err.println(AnsiColors.red("✖ gRPC Push rejected [" + code + "]: " + desc));
    }
    return 1;
  }
}
