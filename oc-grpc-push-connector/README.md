# OpenCrawling - gRPC Push Repository Connector

The `oc-grpc-push-connector` module provides an enterprise-grade, high-performance streaming gRPC push ingestion gateway for OpenCrawling, implementing the **Open Ingestion Standard (OIS)**.

## Features

- **Push Ingestion RPCs**: High-throughput unary (`PushDocument`), client streaming (`StreamDocuments`), and bi-directional streaming (`BiDirectionalStream`) interfaces.
- **Diagnostic Health Probes**: `Ping` RPC for live zero-dependency connectivity checks and health monitoring.
- **Claim Check Pattern Offload**: Automatic offloading to Apache Ozone / S3 / local claim-check storage when payloads exceed `inlineBinaryThresholdKb` (default: 512 KB), with support for external `claim_check_uri`.
- **Tombstone DELETE Short-Circuiting**: Direct routing of document deletion requests to downstream vector search engines, bypassing text extraction and chunking.
- **Enterprise Security**: Bearer token authentication via `GrpcSecurityInterceptor` and TLS / mTLS mutual certificate authentication.
- **Distributed Tracing**: Extraction of W3C `traceparent` and `tracestate` context via `OpenTelemetryServerInterceptor`.
- **Java 25 Concurrency**: Virtual thread per task executor for lightweight, concurrent gRPC request dispatching.
- **SPI Architecture**: Pluggable `RepositoryConnector` service provider implementation.

## Configuration Properties

| Property key | Environment Variable | Default | Description |
|---|---|---|---|
| `opencrawling.connector.grpc-push.port` | `OPENCRAWLING_GRPC_PUSH_PORT` | `9096` | Port for the gRPC push server listener |
| `opencrawling.connector.grpc-push.max-message-size-mb` | `OPENCRAWLING_GRPC_PUSH_MAX_MESSAGE_MB` | `64` | Maximum gRPC message size in megabytes |
| `opencrawling.connector.grpc-push.inline-binary-threshold-kb` | `OPENCRAWLING_GRPC_PUSH_INLINE_THRESHOLD_KB` | `512` | Inline binary threshold before claim-check offload |
| `opencrawling.connector.grpc-push.token` | `OPENCRAWLING_GRPC_PUSH_TOKEN` | *empty* | Bearer token for authentication |
| `opencrawling.connector.grpc-push.reflection` | `OPENCRAWLING_GRPC_PUSH_REFLECTION` | `true` | Enable gRPC server reflection |
| `opencrawling.connector.grpc-push.tls.enabled` | `OPENCRAWLING_GRPC_PUSH_TLS_ENABLED` | `false` | Enable TLS encryption |
| `opencrawling.connector.grpc-push.tls.client-auth` | `OPENCRAWLING_GRPC_PUSH_CLIENT_AUTH` | `NONE` | Client certificate authentication (`NONE`, `OPTIONAL`, `REQUIRE`) |

## Documentation

For full architecture details, message schemas, and client examples, see [gRPC Push Connector Documentation](../docs/gRPC-Push-Connector.md).
