# Model Context Protocol (MCP) Integration

OpenCrawling features a built-in, secure **Model Context Protocol (MCP)** server (`McpVectorServer`) built with Spring AI. This allows LLMs (such as Claude Desktop, ChatGPT, cursor/IDE extensions, or custom autonomous agents) to query enterprise vector databases directly while authoritatively enforcing Zero-Trust security and Access Control Lists (ACLs).

---

## 🔒 Unified Zero-Trust Security & ACL Guard (`McpAclEvaluator`)

Standard vector databases lack identity-aware retrieval: any similarity query returns the nearest vectors regardless of caller permissions. In an enterprise system, this leads to context leaks (e.g. an LLM retrieving confidential payroll spreadsheets or HR files for unauthorized users).

OpenCrawling solves this with **server-side Zero-Trust ACL evaluation**: every backend hands its candidates to one `DocumentAccessGate`, which asks the configured authorization provider (the stored ACLs via `McpAclEvaluator` by default, or OpenFGA; see [Document Authorization](Document-Authorization)):
1. **Defense-in-Depth Filtering**: Search queries are pre-filtered at the database level where supported, and every hit is authoritatively re-checked by the gate before results are returned to the LLM. If the decision cannot be made, the request fails rather than returning anything.
2. **Explicit Deny Precedence**: If a document has an explicit `deny` access rule matching the user principal or any of their groups/roles, the document is immediately excluded.
3. **Identity Resolution**: Checks caller credentials against:
   - `userPrincipal` (exact username, email, or SID, case-insensitive)
   - `userRoles` (groups, departments, or role SIDs, case-insensitive)
   - a leading `user:` / `group:` prefix on either side is ignored when comparing
   - `public` (accessible to every caller)
4. **Multi-Schema Compatibility**: Seamlessly evaluates:
   - Structured Open Ingestion Standard (OIS) security (`metadata["security"]` containing `PermissionRule` objects or JSON maps)
   - Flattened array fields (`security_allowed_read`, `security_denied_read`)
   - Legacy ACL strings or arrays (`acl`, `acl_ss`) when no structured fields exist
   - A document with no ACL data at all is **denied**

---

## 🛠️ Canonical MCP Tools Surface

The MCP server exposes three standard tools regardless of which target vector database is configured:

| MCP Tool | Description | Parameters |
|---|---|---|
| `secureVectorSearch` | Performs semantic or hybrid vector similarity search across indexed chunks, applying caller identity checks. | `query` (string, required)<br>`userPrincipal` (string, required)<br>`userRoles` (string, comma-separated)<br>`maxResults` (int, default: 5)<br>`minScore` (double, default: 0.0)<br>`dimensions` (int, optional: 384, 768, 1024) |
| `getDocumentContent` | Retrieves full text content and metadata for a specific document chunk by URI. | `documentUri` (string, required)<br>`userPrincipal` (string, required)<br>`userRoles` (string, comma-separated)<br>`dimensions` (int, optional) |
| `listAccessibleSources` | Lists distinct documents and metadata accessible to the caller currently stored in the index. | `userPrincipal` (string, required)<br>`userRoles` (string, comma-separated)<br>`dimensions` (int, optional) |

> [!NOTE]
> For backward compatibility with existing Vespa deployments, `VespaMcpVectorServer` tool names (`vespaSecureVectorSearch`, `vespaGetDocumentContent`, `vespaListAccessibleSources`) remain active when `spring.opencrawling.output.type=vespa`.

---

## 🗄️ Supported Target Vector Databases

The MCP tool surface is powered by a pluggable strategy interface (`McpVectorSearchBackend`) with native implementations for 9 stores:

| Backend Type | Property `spring.opencrawling.output.type` | Search Mechanism | Query Embedding |
|---|---|---|---|
| **pgvector** | `pgvector` (default) | `PgVectorStore` with SQL cosine distance & metadata filters | Query embedded on-the-fly via configured `EmbeddingModel` |
| **Vespa** | `vespa` | Native Vespa YQL API (BM25 + vector hybrid fusion, query-level deny exclusion) | Ollama query embedding with automatic BM25 fallback |
| **Qdrant** | `qdrant` | `QdrantClient` (`searchAsync` + `scrollAsync` with payload filters) | Ollama / Spring AI `EmbeddingModel` |
| **Milvus** | `milvus` | `MilvusClientV2` (`SearchReq` with `FloatVec` + `QueryReq` for URIs) | Ollama / Spring AI `EmbeddingModel` |
| **OpenSearch 2.x** | `opensearch2` | `OpenSearchClient` (k-NN vector query + keyword term filters) | Ollama / Spring AI `EmbeddingModel` |
| **OpenSearch 3.x** | `opensearch3` | `OpenSearchClient` (k-NN vector query + keyword term filters) | Ollama / Spring AI `EmbeddingModel` |
| **Apache Solr** | `solr` | `SolrClient` (`{!knn f=embeddings topK=...}` dense vector query) | Ollama / Spring AI `EmbeddingModel` |
| **Luxir** | `luxir` | `LuxirClient` (`POST /collections/{collection}/_search` with KNN payload) | Ollama / Spring AI `EmbeddingModel` |
| **Hyland OpenArch** | `openarch` | `OpenArchClient` embedding query (federated content), document ACLs read from OpenArch | Spring AI `EmbeddingModel` |

---

## 🚀 Running the MCP Server

### 1. In Single-Process Mode
Run the Spring Boot runtime application with your desired target vector database:

```bash
# Example for pgvector:
mvn spring-boot:run -pl oc-runtime -Dspring-boot.run.arguments="--spring.opencrawling.output.type=pgvector"

# Example for Qdrant:
mvn spring-boot:run -pl oc-runtime -Dspring-boot.run.arguments="--spring.opencrawling.output.type=qdrant --spring.opencrawling.output.qdrant.host=localhost"

# Example for Milvus:
mvn spring-boot:run -pl oc-runtime -Dspring-boot.run.arguments="--spring.opencrawling.output.type=milvus --spring.opencrawling.output.milvus.uri=http://localhost:19530"

# Example for OpenSearch 3.x:
mvn spring-boot:run -pl oc-runtime -Dspring-boot.run.arguments="--spring.opencrawling.output.type=opensearch3 --spring.opencrawling.output.opensearch3.uris=http://localhost:9200"
```

The MCP server speaks **Streamable HTTP** at `http://localhost:8080/mcp` (the default, `SPRING_AI_MCP_SERVER_PROTOCOL=STREAMABLE`). Clients that only support the older HTTP+SSE transport can use `SPRING_AI_MCP_SERVER_PROTOCOL=SSE`, which serves `/sse` (event stream) and `/mcp/message` (client messages) instead.

### 2. In Decoupled Docker Compose Stacks
Each decoupled vector database stack (`oc-*-output-connector/docker/docker-compose-decoupled-with-*.yml`) ships with an `oc-mcp-server` service container already configured with access to Ollama (`SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434`) for query-time embeddings.

To start a decoupled stack:
```bash
# Example: Milvus stack
docker compose -f oc-milvus-output-connector/docker/docker-compose-decoupled-with-milvus.yml up -d

# Example: Qdrant stack
docker compose -f oc-qdrant-output-connector/docker/docker-compose-decoupled-with-qdrant.yml up -d

# Example: OpenSearch stack
docker compose -f oc-opensearch3-output-connector/docker/docker-compose-decoupled-with-opensearch.yml up -d

# Example: Vespa stack
docker compose -f oc-vespa-output-connector/docker/docker-compose-decoupled-with-vespa.yml up -d
```

---

## 🧪 Running the Decoupled Integration Test Suite

OpenCrawling includes an end-to-end integration test specifically for validating the entire MCP server capabilities in a decoupled multi-container deployment:

```bash
./scripts/test-mcp-decoupled.sh
```

This dedicated test suite automatically validates:
1. **MCP Transport & Protocol Handshake**: JSON-RPC 2.0 `initialize` negotiation, session ID creation (`Mcp-Session-Id`), `notifications/initialized`, and `ping`.
2. **Dynamic Tool Discovery (`tools/list`)**: Verifies advertised tools and schemas for both Vector Knowledge (`secureVectorSearch`, `getDocumentContent`, `listAccessibleSources`) and AIOps Observability (`fetchJobTraces`, `getErrorLogs`, `queryThroughputMetrics`).
3. **Live AIOps Tool Invocations (`tools/call`)**: Validates execution of observability tools with simulated and real trace breakdowns.
4. **Decoupled OIS Multi-ACL Ingestion Pipeline**: Ingests multi-ACL documents (public, role-restricted, explicit deny) through Kafka into pgvector.
5. **Zero-Trust Security & Explicit Deny Precedence**: Verifies that LLM tool callers only receive documents permitted by their principal and roles, and that explicit deny rules authoritatively supersede group allowances.
6. **Identity-Aware Document Retrieval & Discovery**: Validates `getDocumentContent` access gates and `listAccessibleSources` identity scoping.
7. **Multi-Dimension Index Resolution**: Exercises dimension-aware vector querying (384, 768, 1024).
8. **OIS Tombstone DELETE Lifecycle**: Validates that deleted documents are purged from MCP search results.
9. **REST API Fallback Parity**: Confirms consistency across `/api/observability/*` and MCP tools.
10. **In-Process SPI & Unit Test Parity**: Runs JUnit assertions covering the `McpVectorSearchBackend` SPI and `McpAclEvaluator`.

---

## 💻 Claude Desktop Configuration

To connect Claude Desktop to your OpenCrawling Secure MCP Server, bridge its Streamable HTTP endpoint with [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) by adding the following entry to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "opencrawling-secure-knowledge": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote@0.15.1",
        "http://localhost:8080/mcp",
        "--allow-http"
      ]
    }
  }
}
```

`--allow-http` is only needed for a plain-HTTP server other than `localhost`; behind TLS, use the `https://` URL and drop it.

Once connected, Claude will show the tool icon with `secureVectorSearch`, `getDocumentContent`, and `listAccessibleSources`. When querying, Claude passes the user's principal identity and roles as tool arguments; the server enforces the document permissions for that identity.

> [!IMPORTANT]
> The principal and roles in the tool arguments are asserted by the client, not verified by the server. Expose the MCP endpoint only to trusted clients (for example behind an authenticating gateway) until token-based caller identity (#187) is in place.
