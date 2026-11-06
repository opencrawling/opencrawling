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
| `secureVectorSearch` | Performs semantic or hybrid vector similarity search across indexed chunks, applying caller identity checks. | `query` (string, required)<br>`userPrincipal` (string)<br>`userRoles` (string, comma-separated)<br>`maxResults` (int, default: 5)<br>`minScore` (double, default: 0.0)<br>`dimensions` (int, optional: 384, 768, 1024) |
| `getDocumentContent` | Retrieves full text content and metadata for a specific document chunk by URI. | `documentUri` (string, required)<br>`userPrincipal` (string)<br>`userRoles` (string, comma-separated)<br>`dimensions` (int, optional) |
| `listAccessibleSources` | Lists distinct documents and metadata accessible to the caller currently stored in the index. | `userPrincipal` (string)<br>`userRoles` (string, comma-separated)<br>`dimensions` (int, optional) |

`userPrincipal` and `userRoles` are the caller's identity only in the default `none` identity mode. In `jwt` mode they are ignored and the identity comes from the request's bearer token (see [Caller identity](#-caller-identity) below).

> [!NOTE]
> For backward compatibility with existing Vespa deployments, `VespaMcpVectorServer` tool names (`vespaSecureVectorSearch`, `vespaGetDocumentContent`, `vespaListAccessibleSources`) remain active when `spring.opencrawling.output.type=vespa`.

---

## 🔭 Repository discovery

With `opencrawling.discovery.enabled=true`, three more tools let an agent look at a registered repository before anything is ingested. They read no content and ingest nothing.

| MCP Tool | Description | Parameters |
|---|---|---|
| `listRepositoryConnectors` | Lists the registered repository connectors that support discovery: names and descriptions only, never their configuration. | `userPrincipal`, `userRoles` |
| `exploreRepositoryTree` | Lists folders and documents breadth-first from a folder (the connector's configured root by default). | `connector` (required)<br>`start` (folder id or path)<br>`depth`<br>`maxNodes`<br>`includeDocuments` (default `true`)<br>`userPrincipal`, `userRoles` |
| `queryRepositoryDryRun` | Previews a crawl of a folder or a read-only query: how many matching documents the caller may read, and samples. | `connector` (required)<br>`scope` (folder id or path, or a query such as a CMIS `SELECT`)<br>`sampleSize`<br>`maxScan`<br>`userPrincipal`, `userRoles` |

The same operations are available over REST: `GET /api/discovery/connectors`, `GET /api/discovery/{connector}/tree` and `POST /api/discovery/{connector}/dry-run` (body `{"scope", "sampleSize", "maxScan"}`).

- **Which connectors:** the repository connectors registered in `connectors.json` (the ones the job API runs) whose implementation supports discovery. Today those are CMIS and Alfresco.
- **Who the caller is:** the caller must be verified by token (`opencrawling.security.identity.mode=jwt`; for REST also `protect-api`), because discovery reads live repositories with the connector's own credentials. `opencrawling.discovery.allow-asserted-identity=true` also accepts the `userPrincipal`/`userRoles` arguments, for development and tests only.
- **What the caller sees:** each item is decided with the metadata rules (the same rules as `metadata` mode, including the `user:`/`group:` separation) on the ACL the *source repository* reports for it now, whatever `opencrawling.security.authorization.mode` is. OpenFGA only holds permissions for documents that have been indexed, so it would hide every folder and every document not yet ingested. As a result, group memberships kept only in OpenFGA are not taken into account here: discovery may show a caller less than it may read, never more.
  - A folder the caller cannot read is not opened, so nothing below it is named.
  - A start folder the caller cannot read, one outside the connector's configured root, and one that does not exist all give the same answer.
  - An item whose ACL the repository does not return is never shown.
  - Results never include ACLs.
- **Counts:** a folder dry run checks every document, so its count is the caller's (`EXACT`, or `LOWER_BOUND` when it stopped at `maxScan` or the timeout). A query returns no ACLs, so its matches cannot be checked one by one: only its samples are checked, and no count is returned unless `opencrawling.discovery.reveal-hidden-counts=true` (then the total match count, as `ESTIMATE`). The same setting adds `hiddenCount`, the number of items hidden from the caller.
- **Bounds:** every request is held within the configured `max-depth`, `max-nodes`, `sample-size`, `max-scan` and `timeout`, and results say when and why they were truncated.

## 🚚 Dynamic jobs

With `opencrawling.jobs.dynamic.enabled=true`, an agent can ingest a folder it found through discovery, without an administrator creating a job for it. The agent names a connector and a start folder; it never supplies endpoints, hosts, credentials or connector settings.

| MCP Tool | Description | Parameters |
|---|---|---|
| `triggerDynamicJob` | Starts a job that crawls a folder of a registered connector. Returns `jobId`, `executionId` and `statusUrl`. | `connector` (required)<br>`start` (folder id or path; the connector's configured root by default)<br>`name`<br>`validateOnly` (check, start nothing)<br>`idempotencyKey`<br>`correlationId` |
| `getDynamicJobStatus` | The job's state (`RUNNING`, `COMPLETED`, `FAILED`, `STOPPED`) and, once it has ended, how many documents it handled. | `jobId` (required) |
| `listDynamicJobs` | The caller's dynamic jobs that are still remembered, newest first. | |
| `stopDynamicJob` | Stops a running job before its next document. | `jobId` (required) |
| `ingestDocument` | Ingests one document again, as a one-document job. Returns `jobId` like `triggerDynamicJob`. | `connector` (required)<br>`document` (its repository id, as `exploreRepositoryTree` returns it; required)<br>`idempotencyKey`<br>`correlationId` |
| `removeDeletedDocument` | Removes a document from the index because it was deleted in its repository. | `connector` (required)<br>`document` (required)<br>`correlationId` |

The same operations are available over REST: `POST /api/jobs/dynamic` (body `{"connector", "start", "name", "validateOnly"}`, headers `Idempotency-Key` and `X-Correlation-Id`; answers `202` with a `Location`), `GET /api/jobs/dynamic`, `GET /api/jobs/dynamic/{jobId}`, `POST /api/jobs/dynamic/{jobId}/stop`, and for one document `POST /api/jobs/dynamic/documents` (body `{"connector", "document", "action"}` with `action` `UPSERT`, the default, or `DELETE`).

- **Who the caller is:** always verified by token (`opencrawling.security.identity.mode=jwt`; for REST also `protect-api`). There is no asserted-identity option. The token must name a user and carry the scope in `opencrawling.jobs.dynamic.required-scope` (default `oc:jobs:write`, read from the `scope` or `scp` claim).
- **Which connectors:** only the registered connectors listed in `opencrawling.jobs.dynamic.connectors` (empty by default, so none) that support discovery and crawl a folder tree. A connector set up to crawl a query or a change log is refused. A connector that is not listed reads exactly like one that does not exist.
- **Which folder:** the start folder must be inside the connector's configured root, not excluded, and readable by the caller under the same source-ACL rules as discovery. Otherwise the answer is the same as for a folder that does not exist. The crawl then takes everything below that folder that the connector's own rules take, including documents the caller cannot read: every document keeps its source permissions, so search still shows each user only what they may read.
- **Where documents go:** a dynamic job never writes to an output connector directly. Every document goes to the documents topic (`opencrawling-documents`), so it passes through ingestion and the OpenFGA permission sync like any other. Dynamic jobs always run in `RAG` mode.
- **Who sees a job:** the user it was started for (whichever agent acted for them, in the same tenant) and members of `opencrawling.jobs.dynamic.admin-groups`. To anyone else it does not exist (`404`).
- **Limits:** `max-running-per-user` (default `2`) dynamic jobs per user at once, plus the runtime's `opencrawling.jobs.max-concurrent-runs` and `max-concurrent-runs-per-caller`. Over a limit, the answer is `429`.
- **Idempotency:** repeating a request with the same `Idempotency-Key` returns the job the first one started (`"replayed": true`). The same key with a different request is refused (`409`). Keys belong to the user.
- **One document:** the token needs `opencrawling.jobs.dynamic.document-scope` (default `oc:jobs:run`) instead of `required-scope`, so a workflow service can refresh documents without being able to crawl folders. Either scope lets a caller follow and stop its own jobs. The document is named by its repository id, never a path.
  - **Ingest (`UPSERT`):** the document must be one the connector's folder crawl would take (inside the configured root, not in an excluded folder, matching the version and type filters) and one the caller may read by its source ACL. It is fetched as the crawl would emit it, under the same id and with its source permissions, and runs as a one-document job (`kind` `DOCUMENT`). A document that is hidden, out of scope or missing gets the same `400`.
  - **Remove (`DELETE`):** a tombstone is published only when the repository reports that it no longer has the document, so a live document can never be removed from the index this way. Asking to remove a document that still exists and that the caller may read gets `409`. Every other request gets the same `202`, whether or not anything was removed, so a caller learns nothing about documents it cannot read.
  - Only CMIS and Alfresco connectors that crawl a folder tree support this.
- **Human approval (optional):** with `opencrawling.jobs.dynamic.require-approval=true`, an agent's folder job or document ingest is checked as usual, so something the caller cannot see is still refused at once. It is then filed as `PENDING_APPROVAL` instead of starting.
  - **Approve:** a member of `admin-groups` approves it with `POST /api/jobs/dynamic/{jobId}/approve`. That must be someone other than the user who asked. The folder or document is checked again under the requester's identity, since access may have changed while it waited, and the job starts under the same id. If the check fails, the job ends `FAILED` with the reason in its `note`.
  - **Reject:** `POST /api/jobs/dynamic/{jobId}/reject` with an optional `{"reason"}` ends it `REJECTED`.
  - **Cancel:** the owner can cancel a pending job with `stopDynamicJob` (`STOPPED`).
  - **Expiry:** an unanswered job expires after `approval-timeout` (default `24h`, state `EXPIRED`).
  - **Limit:** each user may have `max-pending-per-user` (default `10`) jobs waiting.
  - **What is never held:** removals of deleted documents (they can only remove documents the repository no longer has), and BPM events (an administrator's route already authorizes them).
  - **Approval is REST only:** there is no MCP tool for it, so an agent cannot approve jobs.
- **Status is kept in memory** for `retention` (default `24h`) after a job ends, up to `max-retained` jobs, and is lost on restart. The run's ledger entry (with `trigger_source` `mcp` or `dynamic-api`, who started it and on whose behalf) is the lasting record.

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

Once connected, Claude will show the tool icon with `secureVectorSearch`, `getDocumentContent`, and `listAccessibleSources`. In the default `none` identity mode, Claude passes the user's principal identity and roles as tool arguments and the server enforces the document permissions for that identity.

> [!IMPORTANT]
> In `none` mode the principal and roles in the tool arguments are asserted by the client, not verified by the server. Either expose the MCP endpoint only to trusted clients (for example behind an authenticating gateway), or switch to `jwt` mode.

When the server runs in `jwt` mode, give `mcp-remote` a bearer token for the user. The whole header value goes in an environment variable, with no space after the colon, because some clients (Claude Desktop on Windows, Cursor) split arguments on spaces:

```json
{
  "mcpServers": {
    "opencrawling-secure-knowledge": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote@0.15.1",
        "https://opencrawling.example.com/mcp",
        "--header",
        "Authorization:${AUTH_HEADER}"
      ],
      "env": { "AUTH_HEADER": "Bearer <access token>" }
    }
  }
}
```

Access tokens expire, so this suits testing; an agent or gateway that obtains tokens itself is the production setup.

---

## 🪪 Caller identity

`opencrawling.security.identity.mode` decides who an MCP tool call is made for:

| Mode | MCP endpoint | Identity used by the tools |
|---|---|---|
| `none` (default) | open | the `userPrincipal` / `userRoles` arguments, as sent by the client |
| `jwt` | needs a valid bearer token (`401` otherwise) | the token's claims; the arguments are ignored |

In `jwt` mode the runtime is an OAuth2 resource server. It validates each token's signature, expiry and (when configured) audience against your identity provider (Keycloak, Microsoft Entra ID, Okta, or any OpenID Connect provider):

```yaml
opencrawling:
  security:
    identity:
      mode: jwt
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://idp.example.com/realms/corp   # or jwk-set-uri
          audiences: opencrawling                           # recommended: reject tokens issued for other apps
```

How the token maps to the caller (all configurable under `opencrawling.security.identity.jwt`):

- **Principal:** the first non-blank claim of `principal-claims` (default `preferred_username`, then `sub`).
- **Groups:** the values of every claim in `group-claims` (default `realm_access.roles` and `groups`). A dotted name is a nested claim. Group values only ever become groups and the principal only ever the principal, so a `user:x` grant cannot be reached through a role named `x` (see [Document Authorization](Document-Authorization)).
- **Tenant:** `tenant-claim`, empty by default.
- **Delegation:** a token-exchange (RFC 8693) token keeps the user as the principal; its `act.sub` is recorded as the acting agent.

If Keycloak's group mapper sends full group paths (`/finance`), either turn off "Full group path" or write the ACL grants with the full path.

The rest of the runtime is unchanged: the REST API and the admin UI stay open unless `opencrawling.security.identity.protect-api=true`, which requires a token on `/api/**` too. The OIS ingest endpoint keeps its own token either way. The admin UI cannot send tokens yet, so leave `protect-api` off where the UI is used.
