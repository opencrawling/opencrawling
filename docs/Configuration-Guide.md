# Configuration Guide

OpenCrawling is configured using standard Spring Boot property profiles. The main configuration parameters specify repository connections, message brokers, caching, and target vector databases.

---

## 🛠️ Dev Profile Properties (`application-dev.yml`)

The runtime behavior is controlled under `oc-runtime` via YAML configurations. Here is a typical deployment setup:

```yaml
spring:
  profiles:
    active: dev
  
  datasource:
    url: jdbc:postgresql://localhost:5432/opencrawling
    username: opencrawling
    password: croom-secure-password
    driver-class-name: org.postgresql.Driver
    
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: opencrawling-group
      auto-offset-reset: earliest
      
  redis:
    host: localhost
    port: 6379

# OpenCrawling Custom Settings
spring:
  opencrawling:
    # Trigger a scan path immediately on application boot
    crawl-on-startup: false
    scan-path: /tmp/opencrawling-mount
    
    # Configure default transformation embedding models
    embedding:
      engine: ollama # Options: ollama, openai
      model-name: mxbai-embed-large
      dimensions: 1024
      url: http://localhost:11434
```

---

## 🔌 Core Connector Types

OpenCrawling uses a Service Provider Interface (SPI) structure. To run a job, you map a **Repository Ingestion Source** to a **Vector Output Destination**.

### 1. Repository Connectors (Sources)
*   **Filesystem Connector**: Scans local or mounted directories recursively.
*   **S3 Connector**: Pulls document objects from AWS S3 or MinIO buckets.
*   **SharePoint Connector**: Utilizes Microsoft Graph delta queries to scan directories incrementally while extracting Active Directory SIDs.
*   **gRPC Push Connector**: High-throughput streaming gRPC push ingestion gateway (Port 9096) supporting unary, streaming, and bi-directional OIS document ingestion with automatic claim-check offload. See [gRPC Push Connector](gRPC-Push-Connector).

### 2. Output Connectors (Destinations)
*   **PgVector Store**: Connects to PostgreSQL using `pgvector` extension.
*   **Elasticsearch / OpenSearch**: Leverages KNN indexing vector engines.
*   **Qdrant / Milvus**: Connects to standard cloud-native vector indexes.

---

## 📈 Horizon Scaling of Consumers

For high ingestion jobs, adjust Kafka topic partition numbers:
*   Configure the `opencrawling-chunks` and `opencrawling-embedded` topics with 4 to 8 partitions.
*   Run multiple instances of `oc-embedding-service` in the same consumer group. Kafka will distribute chunk embedding calculations evenly across instances.

---

## 🏃 Job Runs

Jobs started through the job API (`POST /api/jobs/{id}/start`, the admin UI, the CLI and the SDK) run in the runtime that received the request:

*   **One run per job:** starting a job that is already running returns `409`.
*   **`OPENCRAWLING_JOBS_MAX_CONCURRENT_RUNS`** (`opencrawling.jobs.max-concurrent-runs`): how many jobs may run at the same time; a start beyond it returns `429`.
    *   *Type*: `int`
    *   *Default*: `0` (no cap)
*   **`OPENCRAWLING_JOBS_MAX_CONCURRENT_RUNS_PER_CALLER`** (`opencrawling.jobs.max-concurrent-runs-per-caller`): how many runs one verified caller may hold at the same time; a start beyond it returns `429`.
    *   *Type*: `int`
    *   *Default*: `0` (no cap)
    *   **Who counts as a caller:** a run has a caller only when its start request carried a validated token, which needs `jwt` mode with `protect-api` (see [Caller Identity](#-caller-identity)). The caller is the token's principal, or the acting agent for a delegated token, so an agent is capped across all the users it acts for. The same name in different tenants counts as different callers.
    *   **Runs without a caller:** they count only towards `max-concurrent-runs`. Set that too if anonymous starts must be limited.
*   **Stop:** `POST /api/jobs/{id}/stop` ends the crawl, at the latest before its next document, and the job shows `Finished` with stage `Stopped`. It returns `202` while the run winds down; a job with no run here (for example a `Running` status left over from a restart) is simply marked `Finished`.
*   **Runs in progress:** `GET /api/jobs/runs`, and `GET /api/jobs/{id}/run` for one job (`404` when it is not running). Each run shows its `caller` (empty when it has none).
*   **Outcome:** a run that fails shows `Error`; before, a failed run was also shown as `Finished`.
*   **Provenance:** each run's ledger records who started it and how (`trigger_source`, `triggered_by`, `on_behalf_of`, ...). The caller is recorded only when the start request carried a validated token, which needs `jwt` mode with `protect-api` (see [Caller Identity](#-caller-identity)); an optional `X-Correlation-Id` header is recorded as `correlation_id`. See [Run Provenance](Lakehouse-Audit-Reporting-and-Ledger.md#run-provenance-who-started-the-execution).

Runs are kept in memory: they are not shared between runtime instances and do not survive a restart.

---

## 📥 OIS Ingestion Endpoint

`POST /api/v1/ingest/ois/{jobId}` takes OIS documents from producers outside the runtime, such as the StormCrawler bolt, and runs them through the job's RAG pipeline.

*   **`OPENCRAWLING_INGEST_OIS_TOKEN`** (`opencrawling.ingest.ois.token`): the bearer token producers send as `Authorization: Bearer <token>`.
    *   *Type*: `string`
    *   *Default*: empty, which disables the endpoint
    *   The runtime that serves the endpoint writes the text of each UPSERT to the claim-check store, so that store must be one the ingestion consumer can read: the same process, a shared volume, or Ozone.

---

## 🪪 Caller Identity

Who an MCP tool call is made for:

*   **`OPENCRAWLING_SECURITY_IDENTITY_MODE`** (`opencrawling.security.identity.mode`): `none` (the identity in the tool arguments `userPrincipal`/`userRoles`, unverified) or `jwt` (the MCP endpoints need a valid bearer token, and its identity is used).
    *   *Type*: `string`
    *   *Default*: `none`
*   **`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI`** (or **`..._JWKSETURI`**) and **`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES`**: the token issuer and the accepted audiences, required and recommended respectively in `jwt` mode.
*   **`OPENCRAWLING_SECURITY_IDENTITY_PROTECT_API`** (`opencrawling.security.identity.protect-api`): in `jwt` mode, also require a token on `/api/**` (OIS ingest keeps its own token). The admin UI cannot send tokens yet.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_SECURITY_IDENTITY_JWT_PRINCIPAL_CLAIMS`**, **`..._GROUP_CLAIMS`**, **`..._TENANT_CLAIM`**: which claims give the principal (default `preferred_username,sub`, first non-blank wins), the groups (default `realm_access.roles,groups`) and the tenant (default none).

See [Model Context Protocol, Caller identity](Model-Context-Protocol#-caller-identity) for an example and the claim rules.

---

## 🔐 Document Authorization

Who may read a retrieved document is decided by one provider for every store, selected with:

*   **`OPENCRAWLING_SECURITY_AUTHORIZATION_MODE`** (`opencrawling.security.authorization.mode`): `metadata` (default: the ACLs stored with each document), `shadow` (stored ACLs decide, OpenFGA is compared), `intersection` (both must allow) or `openfga`.
    *   *Type*: `string`
    *   *Default*: `metadata`
*   **`OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_API_URL`**, **`..._API_TOKEN`**, **`..._STORE_ID`**, **`..._MODEL_ID`**, **`..._AUTO_PROVISION`**: the OpenFGA connection, used only in the OpenFGA modes.

See [Document Authorization](Document-Authorization) for the full property list, the rollout path, backfill and metrics.

---

## 🔭 Repository Discovery

Lets agents and API clients list a registered repository's tree and preview a crawl before ingesting (REST `/api/discovery/**`, MCP tools `listRepositoryConnectors`, `exploreRepositoryTree`, `queryRepositoryDryRun`). Results show only what the caller may read by the source repository's ACL; see [Model Context Protocol, Repository discovery](Model-Context-Protocol#-repository-discovery).

*   **`OPENCRAWLING_DISCOVERY_ENABLED`** (`opencrawling.discovery.enabled`): whether the endpoints and tools exist.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_DISCOVERY_ALLOW_ASSERTED_IDENTITY`** (`opencrawling.discovery.allow-asserted-identity`): also accept an identity the client asserts (`userPrincipal`/`userRoles`) instead of requiring a token (`identity.mode=jwt`). Development and tests only.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_DISCOVERY_REVEAL_HIDDEN_COUNTS`** (`opencrawling.discovery.reveal-hidden-counts`): include how many items were hidden from the caller, and a query dry run's total match count.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`opencrawling.discovery.max-depth`**, **`max-nodes`**, **`sample-size`**, **`max-scan`**, **`timeout`**: what a request gets by default and the most it may ask for (defaults `3`, `200`, `10`, `10000`, `30s`; hard caps `10`, `1000`, `50`, `100000`, `2m`).

---

## 🚚 Dynamic Jobs

Lets a verified caller, typically an agent, ingest a folder of a registered connector, or one document of it, without an administrator creating a job (REST `/api/jobs/dynamic/**`, MCP tools `triggerDynamicJob`, `getDynamicJobStatus`, `listDynamicJobs`, `stopDynamicJob`, `ingestDocument`, `removeDeletedDocument`). Needs `opencrawling.security.identity.mode=jwt`, plus `protect-api` for REST. See [Model Context Protocol, Dynamic jobs](Model-Context-Protocol#-dynamic-jobs).

*   **`OPENCRAWLING_JOBS_DYNAMIC_ENABLED`** (`opencrawling.jobs.dynamic.enabled`): whether the endpoints and tools exist.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_JOBS_DYNAMIC_CONNECTORS`** (`opencrawling.jobs.dynamic.connectors`): the registered repository connectors (names in `connectors.json`) dynamic jobs may use, comma-separated. Each must support discovery and crawl a folder tree (CMIS, Alfresco).
    *   *Type*: list
    *   *Default*: empty (none)
*   **`OPENCRAWLING_JOBS_DYNAMIC_REQUIRED_SCOPE`** (`opencrawling.jobs.dynamic.required-scope`): the OAuth2 scope the caller's token must carry (`scope` or `scp` claim). Blank: not checked.
    *   *Type*: `string`
    *   *Default*: `oc:jobs:write`
*   **`OPENCRAWLING_JOBS_DYNAMIC_DOCUMENT_SCOPE`** (`opencrawling.jobs.dynamic.document-scope`): the OAuth2 scope for ingesting or removing one document (`POST /api/jobs/dynamic/documents`, MCP `ingestDocument`, `removeDeletedDocument`). Either scope lets a caller follow and stop its own jobs. Blank: not checked.
    *   *Type*: `string`
    *   *Default*: `oc:jobs:run`
*   **`OPENCRAWLING_JOBS_DYNAMIC_ADMIN_GROUPS`** (`opencrawling.jobs.dynamic.admin-groups`): groups whose members may read and stop every dynamic job, not only their own.
    *   *Type*: list
    *   *Default*: empty
*   **`opencrawling.jobs.dynamic.max-running-per-user`**: dynamic jobs one user may have running at once (`0`: no cap of its own). Default `2`. The runtime's `max-concurrent-runs` and `max-concurrent-runs-per-caller` apply as well.
*   **`OPENCRAWLING_JOBS_DYNAMIC_REQUIRE_APPROVAL`** (`opencrawling.jobs.dynamic.require-approval`): whether an agent's folder job or document ingest waits for a member of `admin-groups`, other than the requester, to approve it (`POST /api/jobs/dynamic/{jobId}/approve` or `/reject`). Removals of deleted documents and BPM events are never held.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`opencrawling.jobs.dynamic.approval-timeout`**, **`max-pending-per-user`**: how long a job may wait for approval before it expires, and how many jobs one user may have waiting (defaults `24h`, `10`).
*   **`opencrawling.jobs.dynamic.retention`**, **`max-retained`**: how long a finished job's status and its `Idempotency-Key` are kept, and how many finished jobs at most (defaults `24h`, `1000`). Kept in memory, so lost on restart.
*   **`opencrawling.jobs.dynamic.min-chunk-size`**, **`max-chunk-size`**: the chunk sizes, in tokens, a job may ask for with its own `chunking` (defaults `100`, `2048`; never above `8192`). Keep `max-chunk-size` within the embedding model's input. A job that sets no chunking uses the ingestion consumer's default (`800` tokens). See [Model Context Protocol, Dynamic jobs](Model-Context-Protocol#-dynamic-jobs).
*   **`opencrawling.jobs.dynamic.transformation-connector`**: the embedding (transformation) connector dynamic jobs use. Default `Ollama_Embedding_Default`.

---

## 🔔 BPM Document Events

Lets a BPM engine (Flowable, Alfresco Process Services) have one document ingested again, or removed from the index, as soon as a process reaches a transition. The engine POSTs an event to `/api/events/bpm/document-transition`, and a route that an administrator configured decides what happens. Ingestion is asynchronous and usually takes seconds, not milliseconds.

**The event body (JSON):**

```json
{
  "eventId": "contract-4711-signed",
  "engine": "flowable",
  "processDefinitionKey": "contract-approval",
  "processInstanceId": "4711",
  "transition": "contractSigned",
  "documentId": "a1b2c3d4-0000-4000-8000-123456789abc"
}
```

*   `eventId` (required) is the sender's unique id for the event: 1 to 200 letters, digits, `.`, `_`, `:` or `-`.
    *   A repeat from the same sender within `dedup-retention` gets the first answer again, marked `"duplicate": true`, and nothing is done twice.
    *   An event that failed is not remembered, so the sender can retry it.
*   `processDefinitionKey` and `transition` (required) are matched against the routes. `activityId` is read when there is no `transition`.
*   `documentId` (required) is the document's id in the route's repository (for Alfresco the node id, for CMIS the object id), never a path.
*   `engine` is matched by routes that name one, ignoring case. `processInstanceId` is logged.

**Routes:** the first route whose `engine` (when set), `process` and `transition` (each `*` for any) match is used. The route, not the event, names the connector and the action:
*   `UPSERT` (the default) ingests the document again. It must be one the connector's folder crawl would take: inside the configured root, not in an excluded folder, and matching the type filters. It runs as a one-document job.
*   `DELETE` removes the document from the index only when the repository reports that it no longer has it. A document that still exists is not removed (`409`).

Some rules apply whichever route matches:
*   **The sender's own access is not checked.** An engine's service account rarely appears in document ACLs. The document keeps its source permissions in the index, so search still shows each user only what they may read.
*   **Which connectors a route can name:** it must be registered in `connectors.json` and support single documents (CMIS, Alfresco). It does not have to be listed in `opencrawling.jobs.dynamic.connectors`.
*   **The answers:**
    *   An event no route matches gets `200` with `"status": "IGNORED"`.
    *   A handled event gets `202` with `"status": "ACCEPTED"`. For an `UPSERT`, the answer includes the `jobId` an admin can follow.

**Authentication**, checked in this order:
1.  **HMAC signature:** three headers.
    *   `X-OpenCrawling-Source`: the source's name.
    *   `X-OpenCrawling-Timestamp`: seconds since the epoch.
    *   `X-OpenCrawling-Signature: sha256=<hex>`: the HMAC-SHA256, keyed with the source's `hmac-secret`, of the timestamp, a `.`, and the exact request body.

    A timestamp further than `max-clock-skew` from the runtime's clock is refused, so a captured request cannot be replayed later.
2.  **Static token over Basic auth:** the username is the source's name and the password is its `token`. This suits engines that can only send a fixed credential. Event-id deduplication is the only replay protection, so prefer HMAC or a bearer token where the engine can send one.
3.  **Bearer token** with the scope `required-scope` (default `oc:events:write`). This needs `opencrawling.security.identity.mode=jwt` and `protect-api`.

Every refused credential gets the same `401`. With `protect-api`, requests to the event endpoint that carry a signature or Basic credentials skip the JWT check, because the endpoint checks them itself. Every other `/api/**` request still needs a token.

**Settings:**
*   **`OPENCRAWLING_EVENTS_BPM_ENABLED`** (`opencrawling.events.bpm.enabled`): whether the endpoint exists.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_EVENTS_BPM_REQUIRED_SCOPE`** (`opencrawling.events.bpm.required-scope`): the scope a bearer token must carry. Blank: bearer tokens are not accepted.
    *   *Type*: `string`
    *   *Default*: `oc:events:write`
*   **`opencrawling.events.bpm.sources[n].name`**, **`hmac-secret`**, **`token`**: senders with a shared secret.
    *   Each source needs at least one secret of 32 characters or more, or the runtime does not start.
    *   Pass secrets through environment variables, never in a committed file.
*   **`opencrawling.events.bpm.routes[n].name`**, **`engine`**, **`process`**, **`transition`**, **`connector`**, **`action`**: the routes.
*   **Limits and retention:**
    *   `opencrawling.events.bpm.max-clock-skew`: default `5m`.
    *   `dedup-retention`: default `24h`.
    *   `max-remembered-events`: default `10000`.
    *   `max-running`: default `10` jobs per sender at once.

```yaml
opencrawling:
  events:
    bpm:
      enabled: true
      sources:
        - name: aps
          token: ${OPENCRAWLING_BPM_APS_TOKEN}
      routes:
        - name: contract-signed
          process: contract-approval
          transition: contractSigned
          connector: Alfresco_Main
          action: UPSERT
```

**Flowable:** an HTTP task (`flowable:type="http"`) can send the event. Its `requestHeaders` and `requestBody` fields take expressions.
*   **Basic auth:** put the `Authorization` header in `requestHeaders`.
*   **HMAC:** compute the signature with a bean of your own, for example `${ocSigner.sign(execution)}`, using the algorithm above.

```xml
<serviceTask id="notifyOpenCrawling" flowable:type="http">
  <extensionElements>
    <flowable:field name="requestMethod"><flowable:string>POST</flowable:string></flowable:field>
    <flowable:field name="requestUrl"><flowable:string>https://opencrawling.example.com/api/events/bpm/document-transition</flowable:string></flowable:field>
    <flowable:field name="requestHeaders">
      <flowable:expression>Content-Type: application/json
Authorization: ${ocEventsAuthorization}</flowable:expression>
    </flowable:field>
    <flowable:field name="requestBody">
      <flowable:expression>{"eventId":"${execution.processInstanceId}-contractSigned","engine":"flowable","processDefinitionKey":"contract-approval","processInstanceId":"${execution.processInstanceId}","transition":"contractSigned","documentId":"${contractNodeId}"}</flowable:expression>
    </flowable:field>
  </extensionElements>
</serviceTask>
```

**Alfresco Process Services:** add a REST call task, in three steps:
1.  As an administrator, define an endpoint for OpenCrawling with Basic authentication. The username is the source's name and the password is its token.
2.  Map a JSON request body with `${variable}` placeholders for the process variables that hold the event id and the document id.
3.  Check the placeholders against your APS version: they resolve process variables.

---

## 🛡️ Pre-Embedding Governance

Checks every document before it is chunked and embedded. Hooks run in the ingestion consumer, in the order listed, and each one approves the document, approves a sanitized version (for example with personal data masked), or rejects it. A sanitized version is what the next hook sees, and the first rejection ends the evaluation. It applies to every document that reaches the ingestion consumer: admin jobs, dynamic jobs, BPM events, OIS and gRPC push.

*   **Permissions never change.** A hook cannot change a document's permissions: the `security` and `acl` metadata keys, every key starting with `security_` or `acl_`, and `documentId`, `uri` and `lastModified` always keep the values the source gave them.
*   **It fails closed.** When a hook fails or cannot be reached, the document is not embedded, and an error is recorded in the job's trace. The earlier version, if any, stays, and the claim-check object is kept, so a later crawl can ingest the document again.
*   **Rejections remove the earlier version by default.** A rejected document is not embedded. With `on-rejected-update: purge` (the default), the chunks of its earlier version are removed too, so the index stops serving text that was not approved again. With `keep`, the last approved version stays searchable.
*   **Only the decoupled pipeline is governed.** In RAG mode, a job that has an output connector also hands each document straight to that connector, which extracts and embeds it itself and never passes the gate. With governance on, such jobs are refused: the job API answers `409` and the job's stage says why. Run RAG jobs on the decoupled pipeline, where the crawler has no output connector. Migration-mode jobs embed nothing, so they still run.
*   **Outcomes:** each document gets a `Governance` span in the job's trace, with the outcome (`APPROVED`, `SANITIZED`, `REJECTED` or `UNAVAILABLE`), the hook that rejected it, and the hooks' audit facts, such as `pii-masking.masked.email=2`. The content found is never recorded.

*   **`OPENCRAWLING_GOVERNANCE_ENABLED`** (`opencrawling.governance.enabled`): whether the gate is on.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_GOVERNANCE_HOOKS`** (`opencrawling.governance.hooks`): the hooks to run, in order: `pii-masking`, `http`, or the `name()` of a `PreEmbeddingGovernanceHook` bean (`oc-core`, `org.opencrawling.core.governance`). Required when the gate is on. A hook that is unknown or cannot be built stops startup.
    *   *Type*: list
    *   *Default*: empty
*   **`OPENCRAWLING_GOVERNANCE_ON_REJECTED_UPDATE`** (`opencrawling.governance.on-rejected-update`): `purge` or `keep`.
    *   *Type*: `string`
    *   *Default*: `purge`
*   **`opencrawling.governance.pii.types`**: what the `pii-masking` hook masks: `email`, `payment-card` (13 to 19 digits that pass the Luhn check) and `iban` (check digits verified). Each item found is replaced with `[REDACTED:<TYPE>]`. Default: all three. The hook finds well-formed data only; it is no substitute for a dedicated governance service.
*   **`opencrawling.governance.pii.mask-metadata`**: whether metadata values are masked too. Default `true`.
*   **`OPENCRAWLING_GOVERNANCE_HTTP_URL`** (`opencrawling.governance.http.url`), **`OPENCRAWLING_GOVERNANCE_HTTP_SECRET`** (`opencrawling.governance.http.secret`): the governance service the `http` hook asks, and the shared secret it signs requests with (at least 32 characters). Use `https`.
*   **`opencrawling.governance.http.connect-timeout`**, **`timeout`**, **`max-attempts`**, **`initial-backoff`**, **`max-backoff`**: defaults `5s`, `30s`, `3`, `500ms`, `5s`. Network errors, timeouts, `429` and `5xx` are retried, with the pause doubling up to `max-backoff`. Any other status is not retried. One document can hold the consumer for at most about `max-attempts × timeout` plus the pauses.

**The `http` hook's contract.** For each document, the hook sends `POST <url>` with `Content-Type: application/json`:

```json
{
  "documentId": "workspace://SpacesStore/1f0c...",
  "uri": "file:///claims/1f0c...",
  "text": "the extracted text",
  "metadata": {"title": ["Contract 42"], "mimeType": ["application/pdf"]},
  "security": {"inheritanceEnabled": false, "permissions": [{"identity": "finance", "identityType": "GROUP", "displayName": "Finance", "access": "read"}]}
}
```

It signs the request the way BPM events are signed:
*   `X-OpenCrawling-Timestamp` holds the seconds since the epoch.
*   `X-OpenCrawling-Signature` holds `sha256=` followed by the lowercase hex HMAC-SHA256 of `timestamp + "." + body`, keyed with the secret.

The service should check the signature and refuse old timestamps. It answers `200` with one of:

```json
{"decision": "APPROVE", "audit": {"policy": "p-7"}}
{"decision": "SANITIZE", "text": "the text to embed instead", "metadata": {"title": ["Contract 42"]}}
{"decision": "REJECT", "reason": "unmasked credentials", "audit": {"policy": "p-9"}}
```

*   For `SANITIZE`, `metadata` is optional: when it is left out, the metadata stays as it was.
*   `reason` and the `audit` values appear in traces and logs, so they must not repeat what was found.
*   An answer that is not one of these counts as no decision, and so does one larger than 32 MB.

---

## 📊 AIOps & Observability Configurations

OpenCrawling integrates end-to-end distributed tracing and metrics that are enabled by default. They can be disabled or fine-tuned using the following environment variables:

### 1. Enable/Disable Telemetry
*   **`OPENCRAWLING_OBSERVABILITY_ENABLED`**: Toggles tracing features completely (including Kafka header observations and OTel span creation).
    *   *Type*: `boolean`
    *   *Default*: `true`
    *   *To Disable*: Set `OPENCRAWLING_OBSERVABILITY_ENABLED=false`
*   **`OPENCRAWLING_OBSERVABILITY_SAMPLING_PROBABILITY`**: Configures trace sampling rate to reduce telemetry overhead.
    *   *Type*: `float` (range `0.0` to `1.0`)
    *   *Default*: `1.0` (captures 100% of traces)
    *   *Example (10% sampling)*: Set `OPENCRAWLING_OBSERVABILITY_SAMPLING_PROBABILITY=0.1`

### 2. OTel Exporter Settings
*   **`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`**: Configures the HTTP endpoint of the OpenTelemetry Collector.
    *   *Type*: `string`
    *   *Default*: `http://localhost:4318/v1/traces`

---

## 📊 Lakehouse Columnar Audit Reporting & Ledger Configuration

OpenCrawling provides automated, post-job execution audit reports and document-level ledgers. Reports are stored in columnar Apache Parquet with Zstandard (ZSTD) compression, along with CSV compliance manifests, JSON operational summaries, and optional Avro containers. Hive-style directory partitioning (`mode=.../year=.../month=.../day=...`) enables fast analytical querying using the embedded DuckDB engine.

### 1. Spring Boot Properties (`application.yml`)

```yaml
opencrawling:
  reporting:
    enabled: true                     # Enable audit reporting and ledger generation
    storage:
      type: LOCAL                     # LOCAL or S3
      local-dir: target/reports       # Local disk folder (Docker: /data/reports)
      s3:
        endpoint: http://localhost:9878
        bucket: opencrawling-audit-reports
        region: us-east-1
    formats:
      parquet:
        enabled: true                 # Generate Apache Parquet columnar file
        compression: ZSTD             # Codec: ZSTD, SNAPPY, GZIP, UNCOMPRESSED
        row-group-size-bytes: 134217728 # 128 MB row group size
        page-size-bytes: 1048576       # 1 MB page size
      avro: false                     # Generate optional binary Avro file
      csv: true                       # Generate RFC 4180 CSV compliance manifest
      json: true                      # Generate operational JSON summary
```

### 2. Environment Variables

| Environment Variable | Spring Property | Default | Description |
|---|---|---|---|
| `SPRING_OPENCRAWLING_REPORTING_ENABLED` | `opencrawling.reporting.enabled` | `true` | Master toggle for audit reporting and ledger tracking |
| `SPRING_OPENCRAWLING_REPORTING_STORAGE_TYPE` | `opencrawling.reporting.storage.type` | `LOCAL` | Storage backend (`LOCAL` or `S3`) |
| `SPRING_OPENCRAWLING_REPORTING_LOCAL_DIR` | `opencrawling.reporting.storage.local-dir` | `target/reports` | Local disk folder for reports (Docker: `/data/reports`) |
| `SPRING_OPENCRAWLING_REPORTING_S3_ENDPOINT` | `opencrawling.reporting.storage.s3.endpoint` | `http://localhost:9878` | S3 or Apache Ozone S3 Gateway endpoint |
| `SPRING_OPENCRAWLING_REPORTING_S3_BUCKET` | `opencrawling.reporting.storage.s3.bucket` | `opencrawling-audit-reports` | S3 bucket name for reports |
| `SPRING_OPENCRAWLING_REPORTING_S3_REGION` | `opencrawling.reporting.storage.s3.region` | `us-east-1` | S3 signing region |
| `SPRING_OPENCRAWLING_REPORTING_FORMATS_PARQUET_ENABLED` | `opencrawling.reporting.formats.parquet.enabled` | `true` | Generate columnar Apache Parquet files |
| `SPRING_OPENCRAWLING_REPORTING_FORMATS_PARQUET_COMPRESSION` | `opencrawling.reporting.formats.parquet.compression` | `ZSTD` | Compression codec (`ZSTD`, `SNAPPY`, `GZIP`, `UNCOMPRESSED`) |
| `SPRING_OPENCRAWLING_REPORTING_FORMATS_PARQUET_ROW_GROUP_SIZE_BYTES` | `opencrawling.reporting.formats.parquet.row-group-size-bytes` | `134217728` | Parquet row group buffer size in bytes (128 MB) |
| `SPRING_OPENCRAWLING_REPORTING_FORMATS_PARQUET_PAGE_SIZE_BYTES` | `opencrawling.reporting.formats.parquet.page-size-bytes` | `1048576` | Parquet data page size in bytes (1 MB) |
| `SPRING_OPENCRAWLING_REPORTING_FORMATS_AVRO` | `opencrawling.reporting.formats.avro` | `false` | Generate binary Avro schema container files |
| `SPRING_OPENCRAWLING_REPORTING_FORMATS_CSV` | `opencrawling.reporting.formats.csv` | `true` | Generate CSV compliance audit manifests |
| `SPRING_OPENCRAWLING_REPORTING_FORMATS_JSON` | `opencrawling.reporting.formats.json` | `true` | Generate high-level operational JSON summaries |

