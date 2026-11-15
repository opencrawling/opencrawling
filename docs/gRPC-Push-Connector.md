# OpenCrawling gRPC Push Repository Connector

The **OpenCrawling gRPC Push Repository Connector** (`oc-grpc-push-connector`) provides a high-throughput, sub-millisecond, streaming binary ingestion gateway into the OpenCrawling pipeline. While traditional repository connectors operate on a pull/poll model (periodically crawling CMS, databases, or object stores), the gRPC Push Connector enables external producers—such as CDC pipelines, webhook listeners, web crawler bolts, or enterprise microservices—to push documents directly into OpenCrawling with native **Open Ingestion Standard (OIS)** semantics.

---

## 🌟 Key Capabilities

- **Streaming Binary RPCs**: Built on HTTP/2 and Protocol Buffers v3, supporting Unary (`PushDocument`), Client Streaming (`StreamDocuments`), Bi-Directional Streaming (`BiDirectionalStream`), and diagnostic (`Ping`).
- **Claim-Check Pattern Offloading**: Payloads exceeding a configurable threshold (`inlineBinaryThresholdKb`, default: 512 KB) are automatically offloaded to the configured claim-check storage (Apache Ozone, S3, or local storage), preserving memory and Kafka message limits. External producers can also supply a pre-uploaded `claim_check_uri` directly.
- **Full OIS Lifecycle Support**:
  - `UPSERT`: Full document content or metadata ingested into the RAG pipeline (Tika extraction, chunking, embedding, vector store publishing).
  - `DELETE`: Short-circuits extraction and embedding, issuing tombstones directly to downstream vector databases (pgvector, Qdrant, Milvus, OpenSearch, Vespa, Solr 10).
- **Security & Authentication**:
  - **Bearer Token Authentication**: Enforced via `GrpcSecurityInterceptor` while allowing diagnostic `Ping` probes for health checking.
  - **TLS & mTLS**: Shaded Netty TLS with optional mutual TLS client certificate verification (`clientAuth: REQUIRE`).
- **Distributed Observability**: W3C Trace Context (`traceparent`, `tracestate`) extraction via `OpenTelemetryServerInterceptor` for end-to-end distributed tracing across microservices.
- **Java 25 Concurrency**: Managed by a dedicated gRPC server manager utilizing **Virtual Threads** (`Executors.newVirtualThreadPerTaskExecutor()`).
- **Admin UI & Diagnostic Probes**: Configurable via the Admin UI with instant live `Ping` connectivity verification.

---

## 📐 Architecture & Flow

```mermaid
sequenceDiagram
    autonumber
    actor Producer as External Producer / CDC
    participant gRPC as OisPushServiceImpl (Port 9096)
    participant Sec as GrpcSecurityInterceptor
    participant Trace as OpenTelemetryInterceptor
    participant Disp as RuntimePushDocumentDispatcher
    participant Claim as Claim Check Store (Ozone/S3)
    participant Orch as JobOrchestrator (Kafka / Pipeline)

    Producer->>gRPC: PushDocument(OisDocumentPayload)
    gRPC->>Sec: Validate Bearer Token
    gRPC->>Trace: Extract W3C traceparent context
    alt Inline binary > inlineBinaryThresholdKb
        gRPC->>Claim: Offload raw_bytes to Claim Check Store
        Claim-->>gRPC: Return claim_check_uri
    end
    gRPC->>Disp: dispatch(jobId, RepositoryDocument)
    alt Action is DELETE
        Disp->>Orch: Publish Tombstone (Skip Tika/Embedding)
    else Action is UPSERT
        Disp->>Orch: publishDocument (RAG Pipeline)
    end
    Orch-->>Disp: Ingestion Ack / Status
    Disp-->>gRPC: Completed
    gRPC-->>Producer: PushResponse (INGESTED / REJECTED)
```

---

## 📜 Protobuf Contract (`ois_push_service.proto`)

The gRPC contract is located under `oc-grpc-push-connector/src/main/proto/opencrawling/push/v1/ois_push_service.proto`:

```protobuf
syntax = "proto3";

package opencrawling.push.v1;

option java_multiple_files = true;
option java_package = "org.opencrawling.push.v1";
option java_outer_classname = "OisPushServiceProto";

service OisPushService {
  rpc PushDocument (PushDocumentRequest) returns (PushResponse);
  rpc StreamDocuments (stream StreamDocumentRequest) returns (StreamSummaryResponse);
  rpc BiDirectionalStream (stream StreamDocumentRequest) returns (stream IngestionAck);
  rpc Ping (PingRequest) returns (PingResponse);
}
```

### Core Message Structures

- **`OisDocumentPayload`**:
  - `document_id` (string, required): Unique document identifier.
  - `action` (`UPSERT` | `DELETE`): OIS lifecycle operation.
  - `mime_type` (string): Document content MIME type.
  - `raw_bytes` (bytes) or `text_content` (string): Inline document payload.
  - `claim_check_uri` (string): Optional pre-uploaded payload URI.
  - `metadata` (map<string, string>): Arbitrary key-value metadata.
  - `security` (`SecurityConfig`): Fine-grained access control lists (`allow_sids`, `deny_sids`, `owner`, `tenant_id`).
- **`PushOptions`**:
  - `job_id` (string, required): Target ingestion job ID.
  - `sync_mode` (bool): Synchronous wait vs. asynchronous ingestion.
  - `extract_archive` (bool): Enable archive extraction (e.g., zip/tar).
  - `routing_tag` (string): Dynamic routing or partition tag.

---

## ⚙️ Configuration Properties

Configure the connector in your `application.yml` or via environment variables:

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `opencrawling.connector.grpc-push.port` | `OPENCRAWLING_GRPC_PUSH_PORT` | `9096` | Port for the gRPC push server listener |
| `opencrawling.connector.grpc-push.max-message-size-mb` | `OPENCRAWLING_GRPC_PUSH_MAX_MESSAGE_MB` | `64` | Maximum incoming gRPC frame size in megabytes |
| `opencrawling.connector.grpc-push.inline-binary-threshold-kb` | `OPENCRAWLING_GRPC_PUSH_INLINE_THRESHOLD_KB` | `512` | Payloads exceeding this size are offloaded to claim-check storage |
| `opencrawling.connector.grpc-push.token` | `OPENCRAWLING_GRPC_PUSH_TOKEN` | *empty* | Bearer token for authentication. If blank, authentication is disabled |
| `opencrawling.connector.grpc-push.reflection` | `OPENCRAWLING_GRPC_PUSH_REFLECTION` | `true` | Enables gRPC Server Reflection (`ProtoReflectionService`) |
| `opencrawling.connector.grpc-push.tls.enabled` | `OPENCRAWLING_GRPC_PUSH_TLS_ENABLED` | `false` | Enable TLS encryption |
| `opencrawling.connector.grpc-push.tls.cert-chain-path` | `OPENCRAWLING_GRPC_PUSH_TLS_CERT` | *empty* | Path to server X.509 certificate chain (PEM) |
| `opencrawling.connector.grpc-push.tls.private-key-path` | `OPENCRAWLING_GRPC_PUSH_TLS_KEY` | *empty* | Path to server private key (PKCS#8 PEM) |
| `opencrawling.connector.grpc-push.tls.trust-cert-path` | `OPENCRAWLING_GRPC_PUSH_TLS_TRUST` | *empty* | Path to CA trust certificate collection for client authentication |
| `opencrawling.connector.grpc-push.tls.client-auth` | `OPENCRAWLING_GRPC_PUSH_CLIENT_AUTH` | `NONE` | Client authentication mode (`NONE`, `OPTIONAL`, `REQUIRE`) |

### Example `application.yml`

```yaml
opencrawling:
  connector:
    grpc-push:
      port: 9096
      max-message-size-mb: 64
      inline-binary-threshold-kb: 512
      token: "secret-bearer-token-here"
      reflection: true
      tls:
        enabled: false
        client-auth: NONE
```

---

## 🛠️ Usage Examples

### 1. Diagnostic Ping with `grpcurl`

Test health and connectivity without authentication:

```bash
grpcurl -plaintext 127.0.0.1:9096 opencrawling.push.v1.OisPushService/Ping
```

*Output:*
```json
{
  "status": "UP",
  "version": "1.0.4-SNAPSHOT",
  "systemLoad": 0.12
}
```

### 2. Ingest a Document with `grpcurl`

Push a sample document with metadata and security ACLs:

```bash
grpcurl -plaintext \
  -H "authorization: Bearer secret-bearer-token-here" \
  -d '{
    "document": {
      "document_id": "doc-push-001",
      "action": "UPSERT",
      "mime_type": "text/markdown",
      "text_content": "# Enterprise Architecture\n\nHigh-performance gRPC push pipeline documentation.",
      "metadata": {
        "source": "ci-cd-pipeline",
        "author": "devops-team"
      },
      "security": {
        "allow_sids": ["ROLE_ENGINEERING", "ROLE_ADMIN"],
        "owner": "alice",
        "tenant_id": "tenant-alpha"
      }
    },
    "options": {
      "job_id": "1",
      "sync_mode": true
    }
  }' \
  127.0.0.1:9096 opencrawling.push.v1.OisPushService/PushDocument
```

*Output:*
```json
{
  "documentId": "doc-push-001",
  "status": "INGESTED",
  "message": "Document accepted for ingestion"
}
```

### 3. Delete / Tombstone Document

Issue an immediate tombstone deletion short-circuiting downstream models:

```bash
grpcurl -plaintext \
  -H "authorization: Bearer secret-bearer-token-here" \
  -d '{
    "document": {
      "document_id": "doc-push-001",
      "action": "DELETE"
    },
    "options": {
      "job_id": "1"
    }
  }' \
  127.0.0.1:9096 opencrawling.push.v1.OisPushService/PushDocument
```

---

## ☕ Java 25 Client Producer Snippet

```java
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.opencrawling.push.v1.*;

public class GrpcPushClientSample {
    public static void main(String[] args) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 9096)
                .usePlaintext()
                .build();

        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer secret-bearer-token-here"
        );

        OisPushServiceGrpc.OisPushServiceBlockingStub stub =
                OisPushServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        PushDocumentRequest request = PushDocumentRequest.newBuilder()
                .setDocument(OisDocumentPayload.newBuilder()
                        .setDocumentId("https://kb.example.com/notes/architecture.md")
                        .setAction(DocumentAction.UPSERT)
                        .setMimeType("text/plain")
                        .setTextContent("Sample document ingested via gRPC push connector.")
                        .putMetadata("env", "production")
                        .setSecurity(SecurityConfig.newBuilder()
                                .addAllowSids("GROUP_DEVELOPERS")
                                .setOwner("admin")
                                .build())
                        .build())
                .setOptions(PushOptions.newBuilder()
                        .setJobId("1")
                        .setSyncMode(true)
                        .build())
                .build();

        PushResponse response = stub.pushDocument(request);
        System.out.println("Ingestion status: " + response.getStatus());

        channel.shutdown();
    }
}
```

---

## 🧪 Testing & Verification

Run the automated integration test script:

```bash
./scripts/test-grpc-push-connector.sh
```

This script:
1. Runs all unit and in-process JUnit tests (`GrpcPushConnectorTest`, `OisPushServiceInProcessTest`, `RuntimePushDocumentDispatcherTest`).
2. Checks whether the backend is running and issues a `POST /api/connectors/check` probe.
3. If `grpcurl` is installed, sends a live `Ping` RPC over the network.
