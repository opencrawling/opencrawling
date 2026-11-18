# OpenCrawling - Vespa Output Connector

[![Vespa](https://img.shields.io/badge/Vespa-8-4E9BFA.svg?style=flat&logo=vespa&logoColor=white)](https://vespa.ai/)
[![Java Version](https://img.shields.io/badge/Java-25-orange.svg?style=flat&logo=openjdk&logoColor=white)](https://jdk.java.net/25/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1+-green.svg?style=flat&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat)](../../LICENSE)

This module provides the `OutputConnector` implementation for **[Vespa](https://vespa.ai/)**, Yahoo's open-source big data serving engine combining ANN vector search with BM25 lexical ranking. Crawled document chunks and embeddings are fed into a Vespa document type with the same ACL pre-filtering model used by the pgvector, Milvus, and Qdrant connectors.

## Feature Overview

1. **Document/v1 feeding via the official client**: `VespaOutputConnector.send()` and `VespaStoreWriterConsumer` both feed chunks through `com.yahoo.vespa:vespa-feed-client`, which handles HTTP/2 connection pooling, dynamic throttling, and retries on `429`/`503` internally, so no custom backoff loop is needed.
2. **Dynamic multi-dimension routing**: `VespaDocumentMapper.resolveDocumentType()` inspects the actual length of each chunk's embedding and routes it to a dimension-specific document type (`opencrawling_chunk_384`/`768`/`1024`), mirroring `VectorStoreWriterConsumer`'s pgvector routing. A single running instance can therefore feed `all-minilm`, `nomic-embed-text`, and `mxbai-embed-large` side by side with no config change, restart, or redeploy - switching embedding models per job just works. Any other dimension falls back to the configurable default document type.
3. **ACL-aware indexing**: each document's fields carry `security_allowed_read`, `security_denied_read`, and `security_inheritance`, derived from the document's `SecurityConfig`/`PermissionRule` list, the same OIS security model used across every OpenCrawling output connector.
4. **Two write paths, one mapper**: `VespaOutputConnector.send()` (direct `OutputConnector` SPI path) and `VespaStoreWriterConsumer` (the Kafka `opencrawling-embedded` writer used by the decoupled pipeline) both delegate to `VespaDocumentMapper`, so the ACL/field-mapping and dimension-routing logic exists exactly once.
5. **Metadata as one JSON field**: unlike Qdrant/Milvus payloads, Vespa schemas are strongly typed, feeding an undeclared field is rejected outright. Arbitrary document metadata is therefore folded into a single `metadata_json` summary field instead of dynamic top-level fields.
6. **Operator-managed schema**: the connector only writes documents; it does not deploy the Vespa application package. `vespa-app/schemas/` (the default `opencrawling_chunk` plus the three dimension-specific document types) and `vespa-app/services.xml` are provided as the application package to deploy once (see Testing & Execution below).
7. **Admin UI, wired end-to-end**: the `oc-admin-ui` Connector Configuration screen's Output tab includes a dedicated Vespa form (endpoint, namespace, default document type, dimensions, timeout, mTLS toggle), and `JobController` resolves a real `VespaOutputConnector` instance from those settings per job - the same real (not cosmetic) dynamic-resolution path Qdrant uses.
8. **Live Model Insights panel, with optional UI-triggered schema deploy**: the same Output tab renders a "Vespa Model Insights" panel (`VespaInsightsService`/`VespaInsightsController`, `/api/vespa/*`) showing live health, per-document-type chunk counts, and a BM25/semantic/hybrid query tester, all read straight from this Vespa instance - manually refreshed, never polled. The panel can also deploy the bundled `vespa-app/` package with one click, or forward an operator-supplied `.zip`/`.tar.gz` package unmodified; both are additive to, and never replace, an operator's own CI/CD or `vespa` CLI deploy (see Known Limitations).
9. **Secure MCP integration**: `VespaMcpVectorServer` exposes `vespaSecureVectorSearch`, `vespaGetDocumentContent`, and `vespaListAccessibleSources` over the same MCP server used by `McpVectorServer` (see [Model Context Protocol](../docs/Model-Context-Protocol.md)). The search tool defaults to Vespa's `hybrid` rank profile, fusing BM25 keyword scoring with vector similarity in a single query, and excludes explicit `security_denied_read` entries inside the Vespa query itself, on top of the same authoritative per-hit ACL re-check `McpVectorServer` performs.

## Configuration Parameters

All properties are bound via `VespaOutputProperties` under the `spring.opencrawling.output.vespa` prefix.

| Parameter | Spring Property Key | Default Value | Description |
| :--- | :--- | :--- | :--- |
| **Endpoint** | `spring.opencrawling.output.vespa.endpoint` | `http://localhost:8080` | Vespa container/search endpoint |
| **Namespace** | `spring.opencrawling.output.vespa.namespace` | `opencrawling` | Document ID namespace |
| **Document Type** | `spring.opencrawling.output.vespa.document-type` | `opencrawling_chunk` | Default document type used only when an embedding's length isn't 384, 768, or 1024 (those route automatically) |
| **Dimensions** | `spring.opencrawling.output.vespa.dimensions` | `1024` | Kept for configuration compatibility. Since #333 there is no fallback vector: without an embedding model a document fails and nothing is fed |
| **Timeout Seconds** | `spring.opencrawling.output.vespa.timeout-seconds` | `30` | Startup connectivity check timeout |
| **TLS Enabled** | `spring.opencrawling.output.vespa.tls-enabled` | `false` | Enable mTLS client certificate auth (Vespa Cloud) |
| **TLS Certificate** | `spring.opencrawling.output.vespa.tls-certificate` | *(none)* | Path to client certificate PEM file |
| **TLS Private Key** | `spring.opencrawling.output.vespa.tls-private-key` | *(none)* | Path to client private key PEM file |
| **TLS CA Certificates** | `spring.opencrawling.output.vespa.tls-ca-certificates` | *(none)* | Path to CA certificates PEM file, overrides the JVM default truststore |
| **Content Cluster** | `spring.opencrawling.output.vespa.content-cluster` | `opencrawling_content` | The content cluster deletions address (Vespa's document API requires it for a delete by selection); the bundled `vespa-app/services.xml` names it `opencrawling_content` |

To select this connector, set `spring.opencrawling.output.type=vespa`.

## Field Mapping

Every fed document carries these fields (see `VespaFields`), plus a `metadata_json` blob of any other document metadata. The document type each chunk lands in (`opencrawling_chunk_384`/`768`/`1024`, or the configured default) is resolved per chunk from the embedding's actual length - see `VespaDocumentMapper.resolveDocumentType()`:

```json
{
  "fields": {
    "chunk_id": "doc-123_a1b2c3",
    "text": "...chunk text...",
    "uri": "file:///data/doc-123.pdf",
    "document_id": "/data/doc-123.pdf",
    "chunk_index": 0,
    "total_chunks": 12,
    "acl": "acl-123",
    "lastModified": "2026-07-23T08:00:00Z",
    "security_inheritance": true,
    "security_allowed_read": ["user1", "hr-group"],
    "security_denied_read": ["external-user"],
    "metadata_json": "{\"title\":[\"Doc Title\"],\"mimeType\":[\"application/pdf\"]}",
    "embedding": { "values": [0.0123, -0.045] }
  }
}
```

## Upgrading: Deploy the Schemas First

Vespa rejects any field its deployed schema does not declare, and the schemas in `vespa-app/schemas/` have gained three:

| Field | Type | Used for |
|---|---|---|
| `document_id` | `string`, `attribute: fast-search`, `rank: filter` | Deleting every chunk of a document (a delete by selection on `document_id`): a direct write may carry the claim-check URI and the tombstone the source's, so the URI is not a stable key. Chunks without it are deleted by URI. |
| `chunk_index` | `int`, `attribute` | Ordering a document's chunks in `getDocumentContent`. |
| `total_chunks` | `int`, `attribute` | Telling whether a document is complete. |

Before upgrading the runtime, the writer or the crawler:

1. Deploy the updated application package to every Vespa cluster OpenCrawling writes to: all four schemas (`opencrawling_chunk`, `opencrawling_chunk_384`, `opencrawling_chunk_768`, `opencrawling_chunk_1024`). Use the Admin UI's bundled deploy (`POST /api/vespa/deploy/bundled`), the `vespa-deploy` container of the Compose stacks, or your own deployment.
2. If you deploy your own application package, add the three fields to every document type OpenCrawling writes, with the definitions above (see `vespa-app/schemas/opencrawling_chunk.sd`).
3. Then upgrade OpenCrawling and re-crawl, so existing chunks get the new fields.

Adding fields is a live schema change in Vespa: no restart and no data migration.

If OpenCrawling is upgraded first:

- **The pipeline writer** keeps ingesting. It feeds chunks without the three fields and logs a warning once. Documents are then not ordered or complete in `getDocumentContent`, and deletions find chunks by URI.
- **Per-job outputs that are written directly** fail every write and say why. Their chunks carry the claim-check URI, so without `document_id` a later deletion could not find them.

## Admin UI: Model Insights & Secure MCP

### Model Insights Panel

Backed by `VespaInsightsService` / `VespaInsightsController`, mounted under `/api/vespa`:

| Endpoint | Method | Purpose |
| :--- | :--- | :--- |
| `/api/vespa/health` | GET | Proxies `{endpoint}/state/v1/health` |
| `/api/vespa/document-counts` | GET | Per-document-type chunk counts (`opencrawling_chunk`, `_384`, `_768`, `_1024`) |
| `/api/vespa/query` | POST | Runs a BM25 (`default`), vector (`semantic`), or fused (`hybrid`) query; degrades to BM25 with an explanatory message if no embedding model is configured |
| `/api/vespa/deploy/bundled` | POST | Deploys the classpath-packaged `vespa-app/` (schema + `services.xml`) |
| `/api/vespa/deploy/custom` | POST (multipart) | Forwards an operator-supplied `.zip`/`.tar.gz` package to Vespa unmodified |

All five back the "Vespa Model Insights" panel in `oc-admin-ui`'s Output tab. The panel's "Config Server Endpoint" field (default `http://localhost:19071`) is UI state only, not a `VespaOutputProperties` field - it's unrelated to the document/search `endpoint` above and only matters for the two deploy endpoints.

### Secure MCP Tools

`VespaMcpVectorServer` activates only when `spring.opencrawling.output.type=vespa`, alongside the pgvector-backed `McpVectorServer` (see [docs/Model-Context-Protocol.md](../docs/Model-Context-Protocol.md) for the SSE endpoint and client setup):

| Tool | Equivalent to | Notable difference |
| :--- | :--- | :--- |
| `vespaSecureVectorSearch` | `secureVectorSearch` | Defaults to `hybrid` rank profile; excludes `security_denied_read` matches inside the Vespa query itself |
| `vespaGetDocumentContent` | `getDocumentContent` | Same ACL semantics, backed by a direct URI lookup against Vespa |
| `vespaListAccessibleSources` | `listAccessibleSources` | Same ACL semantics, deduplicated by chunk |

Every hit is re-checked in Java against `security_allowed_read`/`security_denied_read`/`acl` regardless of what the Vespa-side query already excluded - the query-level filtering is an optimization, not the security boundary.

## Testing & Execution

### 1. Run Unit Tests
```bash
mvn test -pl oc-vespa-output-connector
```

### 2. Run Integration Tests
Requires a local Docker daemon (drives a plain `vespaengine/vespa` container directly, there is no official Testcontainers Vespa module):
```bash
mvn verify -pl oc-vespa-output-connector
```

### 3. Standalone Vespa via Docker Compose
```bash
docker compose -f oc-vespa-output-connector/docker/docker-compose.yml up -d
```
This starts `vespaengine/vespa` and a one-shot container that deploys `vespa-app/` (schema + `services.xml`) via the config server's deploy REST API once Vespa reports healthy. Wait for `http://localhost:8080/state/v1/health` to report `"up"` before ingesting; deployment typically takes 30-60 seconds after the container is healthy.

### 4. Full Decoupled Pipeline with Vespa
```bash
docker compose -f oc-vespa-output-connector/docker/docker-compose-decoupled-with-vespa.yml up -d --build
```
See the root [README.md](../README.md) ("Decoupled Vespa-Based Deployment") for details.

### 5. Standalone Connector Smoke Test
For a faster, connector-only check that doesn't boot the full decoupled pipeline (deploys the schema, feeds ACL-tagged chunks at two different embedding dimensions, and verifies both ACL-filtered search and dynamic routing), spins up a temporary Vespa container automatically if one isn't already running at `localhost:8080`:
```bash
./scripts/test-vespa-connector.sh
```

### 6. Verify the Model Insights Endpoints and Secure MCP Tools
The connector-only smoke test above never starts `oc-runtime`, so it can't exercise `/api/vespa/*` or the MCP tools - the full decoupled pipeline test does:
```bash
./scripts/test-vespa-decoupled.sh
```
Once the pipeline has fed a real document, this also asserts the Model Insights health, document-count, and BM25 query endpoints through `oc-runtime` itself, not just raw Vespa. To verify the MCP tools (`vespaSecureVectorSearch`, `vespaGetDocumentContent`, `vespaListAccessibleSources`), connect an MCP client such as [MCP Inspector](https://github.com/modelcontextprotocol/inspector) (`npx @modelcontextprotocol/inspector`) or Claude Desktop to the SSE endpoint described in [docs/Model-Context-Protocol.md](../docs/Model-Context-Protocol.md) - there's no automated coverage for the MCP protocol itself, since nothing in this repo drives real MCP JSON-RPC/SSE traffic from a shell script.

## Known Limitations

- Dynamic routing only covers the three embedding dimensions already used across OpenCrawling (`all-minilm` 384, `nomic-embed-text` 768, `mxbai-embed-large` 1024). A model producing any other dimension falls back to the configurable default document type (`opencrawling_chunk`), whose tensor dimension is fixed at schema-deploy time; there is no runtime cross-check of that fallback against the live schema, so a custom-dimension model still requires editing `opencrawling_chunk.sd` and redeploying.
- Schema/application package deployment is a one-time operator step, not something the connector automates on every startup (unlike Qdrant/Milvus's collection auto-provisioning), since Vespa application package deploys are a heavier, versioned operation typically owned by CI/CD or the `vespa` CLI rather than a per-instance side effect. All four document types (`opencrawling_chunk` plus the three dimension-specific ones) must be deployed together for dynamic routing to work. The Model Insights panel's one-click deploy (see above) is an optional convenience on top of this, not a replacement for it.
- The Secure MCP tools' query-side ACL push-down only excludes `security_denied_read` matches; it does not also push down the allow-list (public/legacy `acl` fallback), since that would require expressing "array is empty" in YQL. The authoritative check is always the Java-side re-check in `VespaMcpVectorServer`, so this only affects candidate-set efficiency, never correctness.
