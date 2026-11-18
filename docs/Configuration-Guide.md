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
*   **Doxis 4**: Archives documents, descriptors, OIS ACLs and versions into a Doxis DMS repository via the CSB REST API; binaries are uploaded, registered in place (`predefinedLocator`) or referenced (`spring.opencrawling.output.type=doxis`).

#### Elasticsearch output (`spring.opencrawling.output.type=elasticsearch`)

Writes chunks to Elasticsearch 8.x or 9.x with the official Elastic Java client (`co.elastic.clients:elasticsearch-java`, version managed by the Spring Boot BOM). The index is created on startup with a `dense_vector` mapping when it does not exist. Each chunk stores `security_allowed_read`, `security_denied_read` and normalized `security_read_keys`, so the MCP backend can pre-filter kNN candidates by ACL inside Elasticsearch; the access gate still re-checks every hit.

| Property (`spring.opencrawling.output.elasticsearch.*`) | Default | Description |
|---|---|---|
| `uris` | `http://localhost:9200` | One node URL or a comma-separated list. |
| `api-key` | *(empty)* | Base64 API key (Elastic Cloud / Serverless). Takes precedence over basic auth. |
| `username` / `password` | `elastic` / *(empty)* | Basic auth, used only when `password` is set and no API key is configured. |
| `index-name` | `enterprise_kb` | Target index. |
| `dimensions` | `1024` | `dense_vector` dimensions; must match the embedding model. |
| `similarity` | `cosine` | `cosine`, `dot_product`, `l2_norm` or `max_inner_product`. |
| `index-type` | `hnsw` | `hnsw`, `int8_hnsw` (scalar quantization, ~75% less vector memory), `int4_hnsw`, `bbq_hnsw`, `flat` or `int8_flat`. |
| `bulk-batch-size` | `500` | Most chunks per bulk request on the direct (non-Kafka) path. |
| `create-index` | `true` | Create the index with the expected mapping when it is missing. |
| `compatible-with` | `8` | REST API major sent as `compatible-with`. `8` works with 8.x and 9.x servers; set `9` on 9.x-only clusters to skip compatibility mode. |
| `ssl.trust-store-path` / `ssl.trust-store-password` / `ssl.trust-store-type` | *(empty)* / *(empty)* / `PKCS12` | Trust store holding the cluster CA for `https` URIs. |
| `ssl.ca-fingerprint` | *(empty)* | SHA-256 fingerprint of the cluster CA (Elasticsearch 8 auto-generated certificates); alternative to a trust store. |

Changing `dimensions`, `similarity` or `index-type` only affects newly created indexes; reindex or use a new `index-name` for an existing one.

---

## 📈 Horizon Scaling of Consumers

For high ingestion jobs, adjust Kafka topic partition numbers:
*   Configure the `opencrawling-chunks` and `opencrawling-embedded` topics with 4 to 8 partitions.
*   Run multiple instances of `oc-embedding-service` in the same consumer group. Kafka will distribute chunk embedding calculations evenly across instances.

---

## ♻️ Failed Messages: Retries and Dead-Letter Topics

A pipeline stage that cannot process a Kafka message (the ingestion consumer, the embedding service, or a store writer) retries it with exponential back-off. If it still fails, the message goes to `<topic>.DLT`: `opencrawling-documents.DLT`, `opencrawling-chunks.DLT` or `opencrawling-embedded.DLT`. A message is never dropped.

| Property | Default | Description |
|---|---|---|
| `opencrawling.kafka.retry.max-retries` | `6` | Retries before the message is dead-lettered. |
| `opencrawling.kafka.retry.initial-interval-ms` | `1000` | Wait before the first retry. |
| `opencrawling.kafka.retry.multiplier` | `2.0` | Factor applied to the wait after each retry. |
| `opencrawling.kafka.retry.max-interval-ms` | `30000` | Longest wait between two retries. |

- **Defaults:** about a minute of retries (1, 2, 4, 8, 16 and 30 seconds). Each retry polls the message again, so keep a single wait below the consumer's `max.poll.interval.ms` (5 minutes by default). The same properties apply to `oc-runtime` and `oc-embedding-service`.
- **While a message is retried, its partition waits.** Messages are keyed by document id, so the order within a document is kept; other documents on that partition are delayed until the retries end.
- **No retries for messages that can never succeed:** a payload that cannot be deserialized goes to the dead-letter topic at once, with its original bytes.
- **Diagnostics:** each dead letter carries Spring Kafka's headers: `kafka_dlt-original-topic`, `-partition` and `-offset`, and `kafka_dlt-exception-fqcn` and `-message`. Once the cause is fixed, a dead letter can be republished to its original topic: writes are idempotent (chunks are stored under their own ids).
- **Content of dead-lettered documents is kept:** a message on `opencrawling-documents.DLT` carries only the URI of its claim-check content. The claim garbage collector keeps every claim such a dead letter references, whatever its age, so a replay can read it again.
  - **How long:** as long as the dead letter exists, so set the retention of `opencrawling-documents.DLT` (Kafka's default is 7 days) to the window you need for recovery.
  - **Release:** a replay that succeeds deletes the claim, like any other ingestion.
  - **Fail safe:** before each sweep the collector reads the dead-letter topic to its end. If it cannot, or the read is interrupted (for example by retention deleting what it is reading), the sweep is skipped, never run without that list. `spring.opencrawling.claim-check.lifecycle.dead-letter-read-timeout-ms` (default `30000`) bounds the read.
  - **Ozone:** the S3 gateway and the native client reach the same objects, so a claim is kept under both of its names (`s3://claims/x` is `ofs://s3v/claims/x`). If your cluster's S3 gateway serves another volume (`ozone.s3g.volume.name`), set `spring.opencrawling.claim-check.ozone.s3-gateway-volume` to it (default `s3v`).
  - **Not covered:** claims of messages that have not been dead-lettered yet, still waiting or being retried, are only protected by the TTL (`spring.opencrawling.claim-check.lifecycle.ttl-hours`, default 24). Keep it longer than the longest time a document message can wait in the pipeline.
- **Not retried:** an ingestion message whose claim-check content no longer exists or that has no claim-check content (metadata-only documents) is skipped, since a retry cannot bring content back. A governance hook that cannot decide still means the document is not embedded (see [Pre-Embedding Governance](#️-pre-embedding-governance)).

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

## 🗄️ Control-Plane Store

Where the runtime keeps its definitions (jobs, connectors, system settings and transport settings) and its job runs ([#315](https://github.com/opencrawling/opencrawling/issues/315)).

*   **`OPENCRAWLING_CONTROL_PLANE_STORE`** (`opencrawling.control-plane.store`): `file` or `jdbc`.
    *   *Type*: `string`
    *   *Default*: `file`
    *   **`file`:** JSON files in `data/` (`jobs.json`, `connectors.json`, `settings.json`, `transport-settings.json`), relative to the working directory. A container without a volume on `data/` loses them when it is recreated.
    *   **`jdbc`:** tables in the runtime's datasource (`spring.datasource.*`): `oc_job`, `oc_connector` and `oc_setting`, one row per definition, with a `version` that grows whenever the definition changes. The `docker-compose-decoupled` stacks use it with their PostgreSQL.
    *   **Several runtimes on one database (`jdbc`):** every runtime reads the definitions another one saved, at once. Every change, and every `If-Match` check of the Platform API, runs under one lock in the database (the `oc_lock` row), so two runtimes cannot overwrite each other's changes: an edit made from an older version gets `412`. The connectors' ETags are the same in every runtime, because the key behind them (`api-v1-etag-key.json`) is kept in the store too. Each runtime caches what it read while the store's revision is unchanged, so a read costs one small query.
    *   **Runs (`jdbc`):** every job run is a row of `oc_run`, with the runtime that runs it and its state, and every change of its state a row of `oc_run_step` (its history, served at `GET /api/v1/job-runs/{executionId}/history`). The limit of one run per job and the caps on runs at the same time (`opencrawling.jobs.max-concurrent-runs`, `max-concurrent-runs-per-caller`) count the runs of every runtime. A run is listed, stopped, paused and resumed from any runtime; its own runtime applies a stop, pause or resume asked elsewhere at its next heartbeat. A run whose runtime stops renewing its heartbeat (it crashed, or was shut down mid-run) is marked `INTERRUPTED`, its job's status becomes `Error` with stage `Interrupted`, and the job can be started again. With `file`, runs are kept in the runtime's memory.
    *   **Pause:** a paused run holds before its next document, in its runtime, until it is resumed or stopped. A pause does not survive the runtime: if it restarts, the paused run is interrupted, and the job starts again from the beginning. A connector that holds a session or cursor open may lose it during a long pause.
    *   **Idempotency keys:** the Platform API's idempotency keys are kept apart, in the runtime's memory by default. With several runtimes on one database, set `opencrawling.api.idempotency.store=jdbc` to keep them in this database too, or `redis` (see [Platform API Idempotency Keys](#-platform-api-idempotency-keys)).
    *   **Schema:** Flyway creates and upgrades the tables at startup (`db/migration`: `V1__control_plane.sql`, `V2__control_plane_lock.sql`, `V3__control_plane_runs.sql`, `V4__control_plane_idempotency.sql`). A database that already holds other tables, such as the vector store's, is baselined first (`spring.flyway.baseline-on-migrate`), so nothing else in it is touched.
    *   **Moving from `file`:** on its first start with `jdbc`, the runtime imports the existing files in `data/` into the tables and leaves the files unchanged. After that the tables are the source; later edits of the files are ignored.
    *   **Errors:** a definition that cannot be saved fails the request instead of being logged and lost. An unknown value stops the runtime at startup.
*   **`OPENCRAWLING_CONTROL_PLANE_REFRESH_INTERVAL`** (`opencrawling.control-plane.refresh-interval`): how often a runtime checks for system or transport settings that another runtime saved, and applies them (Tika settings and the default chunk size; the gRPC server is restarted with new transport settings). The runtime that saves them applies them at once.
    *   *Type*: `duration`
    *   *Default*: `10s`
*   **`OPENCRAWLING_CONTROL_PLANE_RUN_HEARTBEAT_INTERVAL`** (`opencrawling.control-plane.run-heartbeat-interval`): how often a runtime renews the heartbeat of its runs and picks up a stop, pause or resume asked in another runtime.
    *   *Type*: `duration`
    *   *Default*: `5s`
*   **`OPENCRAWLING_CONTROL_PLANE_RUN_HEARTBEAT_TIMEOUT`** (`opencrawling.control-plane.run-heartbeat-timeout`): `jdbc` only. A run whose heartbeat is older than this is marked `INTERRUPTED` by any runtime. Keep it well above the interval: a runtime that is only slow stops its own run when it finds it interrupted.
    *   *Type*: `duration`
    *   *Default*: `60s`
*   **`OPENCRAWLING_CONTROL_PLANE_RUNTIME_ID`** (`opencrawling.control-plane.runtime-id`): the name this runtime's runs carry. By default the host name and a random suffix, new at every start. Set to a stable name, a restarted runtime interrupts the runs it left at once instead of after the heartbeat timeout; it must then be unique: two runtimes with one name would interrupt each other's runs when they start.
    *   *Type*: `string`
*   **`OPENCRAWLING_CONTROL_PLANE_RUN_RETENTION`** (`opencrawling.control-plane.run-retention`): how long what a finished run leaves is kept: its row and history, its report files in the local report directory (`spring.opencrawling.reporting.storage.local-dir`: summaries, manifests, Parquet and Avro ledgers, by when they were written), and the completed execution the runtime keeps in memory. `0` keeps everything. Reports in S3 storage are left to the bucket's lifecycle rules. It replaces `spring.opencrawling.reporting.retention.retention-days`, which was never applied.
    *   *Type*: `duration`
    *   *Default*: `90d`
*   **`OPENCRAWLING_CONTROL_PLANE_RUN_RETENTION_INTERVAL`** (`opencrawling.control-plane.run-retention-interval`): how often the retention runs (first one minute after startup).
    *   *Type*: `duration`
    *   *Default*: `6h`

## 🧭 Platform API Idempotency Keys

`POST` requests to the Platform API (`/api/v1`) may carry an `Idempotency-Key`, so a retry does not repeat the operation (see [Platform API](Platform-API.md#idempotency-keys)). These settings decide where the keys are kept:

*   **`OPENCRAWLING_API_IDEMPOTENCY_STORE`** (`opencrawling.api.idempotency.store`): `memory`, `redis` or `jdbc`.
    *   *Type*: `string`
    *   *Default*: `memory`
    *   **`memory`:** keys live in the runtime that received the request. They are lost on a restart and not shared between runtime instances.
    *   **`redis`:** keys are shared by every runtime instance and survive a restart. This mode uses the runtime's Redis connection (`spring.data.redis.*`). The Docker Compose stacks use it.
    *   **Redis keys:** each key is stored under `oc:api:idempotency:<sha-256>`, so callers, paths and keys never appear in Redis.
    *   **`jdbc`:** keys are shared by every runtime on the same database and survive a restart, without Redis. They are rows of `oc_idempotency_key` in the runtime's datasource (`spring.datasource.*`), the database of the `jdbc` control-plane store, created by Flyway (`V4__control_plane_idempotency.sql`). Each row's id is the SHA-256 of the caller, path and key, so none of them is stored. Expired rows are ignored at once and deleted every few minutes by whichever runtime handles a request with a key.
    *   **Store outage:** while Redis (`redis`) or the database (`jdbc`) cannot be reached, a `POST` with an `Idempotency-Key` is refused with `503` (`idempotency-unavailable`) and is not run. Requests without a key are not affected.
    *   **Startup errors:** an unknown value, `redis` with no Redis connection configured, or `jdbc` with no datasource, stops the runtime at startup.
*   **`OPENCRAWLING_API_IDEMPOTENCY_RETENTION`** (`opencrawling.api.idempotency.retention`): how long a successful answer is replayed.
    *   *Type*: `duration`
    *   *Default*: `PT24H`
*   **`OPENCRAWLING_API_IDEMPOTENCY_IN_PROGRESS_TIMEOUT`** (`opencrawling.api.idempotency.in-progress-timeout`): `redis` and `jdbc`. How long the marker of a running request lives without renewal. The runtime renews it while the request runs, so a long request keeps its key; a retry in that time gets `409`. If a runtime dies mid-request, the key is free again after this time instead of after the whole retention.
    *   *Type*: `duration`
    *   *Default*: `PT10M`
*   **`OPENCRAWLING_API_IDEMPOTENCY_MAX_ENTRIES`** (`opencrawling.api.idempotency.max-entries`): `memory` only. The most keys kept.
    *   *Type*: `int`
    *   *Default*: `10000`
    *   Beyond it, the oldest finished key is dropped. A key whose request is still running is never dropped; when only those are left, a new key is refused with `503`.
*   **`OPENCRAWLING_API_IDEMPOTENCY_MAX_ENTRIES_PER_CALLER`** (`opencrawling.api.idempotency.max-entries-per-caller`): `memory` only. The most keys one caller may hold, so no caller can push out the others' keys.
    *   *Type*: `int`
    *   *Default*: `1000`
*   **`OPENCRAWLING_API_IDEMPOTENCY_MAX_STORED_BYTES`** (`opencrawling.api.idempotency.max-stored-bytes`): the largest answer kept for replay.
    *   *Type*: `int`
    *   *Default*: `262144`
    *   For a larger answer only its status is kept, and a retry answers `409 idempotency-answer-unavailable` instead of running the request again.
*   **`OPENCRAWLING_API_REQUIRE_IF_MATCH`** (`opencrawling.api.require-if-match`): require `If-Match` on every change of a job, connector or settings document; a change without it answers `428`. Turn it on when several people or tools manage the same runtime.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_API_MAX_REQUEST_BYTES`** (`opencrawling.api.max-request-bytes`): the largest request body the Platform API takes; a larger one answers `413`.
    *   *Type*: `long`
    *   *Default*: `1048576`
    *   Uploads (multipart) have their own limits, `spring.servlet.multipart.*`.

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
*   **`OPENCRAWLING_SECURITY_IDENTITY_PROTECT_API`** (`opencrawling.security.identity.protect-api`): in `jwt` mode, also require a token on `/api/**` (OIS ingest keeps its own token). The admin UI then signs users in, which needs `admin-ui.client-id` below.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_SECURITY_IDENTITY_ADMIN_UI_CLIENT_ID`** (`opencrawling.security.identity.admin-ui.client-id`): the identity provider's public client for the admin UI. When set (in `jwt` mode), the UI signs users in with the OpenID Connect authorization code flow with PKCE and sends their access token. The UI reads it from the public `GET /api/auth/config`.
    *   *Default*: empty (the UI does not sign in)
*   **`OPENCRAWLING_SECURITY_IDENTITY_ADMIN_UI_SCOPE`** (`opencrawling.security.identity.admin-ui.scope`): the scopes the UI asks for. Add your API's scope where the provider needs one to issue a token for it (Microsoft Entra ID).
    *   *Default*: `openid profile`
*   **`OPENCRAWLING_SECURITY_IDENTITY_ADMIN_UI_ISSUER`** (`opencrawling.security.identity.admin-ui.issuer`): the issuer as the browser reaches it, if that differs from `issuer-uri`.
    *   *Default*: empty (`spring.security.oauth2.resourceserver.jwt.issuer-uri`)
*   **`OPENCRAWLING_SECURITY_IDENTITY_JWT_PRINCIPAL_CLAIMS`**, **`..._GROUP_CLAIMS`**, **`..._TENANT_CLAIM`**: which claims give the principal (default `preferred_username,sub`, first non-blank wins), the groups (default `realm_access.roles,groups`) and the tenant (default none).

See [Model Context Protocol, Caller identity](Model-Context-Protocol#-caller-identity) for an example and the claim rules, and [Security and Identity Management](Security-and-Identity-Management) for setting up Keycloak, Microsoft Entra ID or Okta.

---

## 🛂 Management Authorization

Who may do what with jobs, connectors, settings, reports and traces (#187). Off by default: with it off, any caller that reaches the API may do everything, as before.

*   **`OPENCRAWLING_SECURITY_MANAGEMENT_ENABLED`** (`opencrawling.security.management.enabled`): turns it on. It needs `opencrawling.security.identity.mode=jwt` with `protect-api=true`, because every decision is made on the verified caller of the request. The runtime refuses to start otherwise. A request without a valid token may do nothing.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_SECURITY_MANAGEMENT_ADMIN_GROUPS`** (`opencrawling.security.management.admin-groups`): members (from the token's group claims) may do everything.
    *   *Default*: `opencrawling-admin`
*   **`OPENCRAWLING_SECURITY_MANAGEMENT_JOB_CREATOR_GROUPS`** (`opencrawling.security.management.job-creator-groups`): members may also create jobs, and own the jobs they create.
    *   *Default*: empty (only admins create jobs)

Group names match case-insensitively. Each job carries an `access` block (in `jobs.json` and in the job API):

```json
"access": {
  "owner": "hr.lead",
  "tenantId": "acme",
  "allowedUsers": ["payroll.clerk"],
  "allowedGroups": ["hr-team"],
  "viewerUsers": [],
  "viewerGroups": ["auditors"]
}
```

| Caller | See the job, its runs, reports, traces and errors | Start, stop, pause | Edit, delete, change `access` |
|---|---|---|---|
| Admin | yes | yes | yes |
| `owner` | yes | yes | yes, except `owner` and `tenantId` |
| `allowedUsers`, `allowedGroups` | yes | yes | no |
| `viewerUsers`, `viewerGroups` | yes | no | no |
| Anyone else | no (`404`) | no | no |

*   **New jobs:** the creator becomes the owner and the job belongs to the creator's tenant (`tenant-claim`), whatever the request says. An admin may name another owner or tenant.
*   **Tenants:** a job with a `tenantId` is invisible to callers of other tenants, whatever its lists say.
*   **Jobs created before authorization was on** have no `access` block. Only admins see them, until an admin assigns them by saving the job with an `access` block.
*   **Job lists are filtered:** `GET /api/jobs`, `GET /api/jobs/runs` and `GET /api/reports` return only what the caller may see. A job the caller may not see answers `404`, never `403`, so its existence is not revealed.
*   **Reports and traces of other jobs:** for a job not in the job list (a dynamic job started by an agent, or a job deleted since), admins and the user it ran for may read its reports. The traces and errors of a dynamic job are visible to the user it was started for while its status is remembered.
*   **Admin-only routes:** connectors (except listing them, which job creators may do, without their configuration), `/api/system/settings/**`, transport settings, `/api/system/logs`, `/api/mcf-migration/**`, `/api/vespa/**`, `/api/observability/metrics` and `/api/reports/query`. Job creators may also use the narrativization copilot. The MCP tool `queryThroughputMetrics` is admin-only too.
*   **Unchanged:** discovery, dynamic jobs, sidecars, BPM events and OIS ingest keep their own caller checks.

The admin UI shows each user only what they may use: admin-only pages are hidden, and job actions follow `GET /api/jobs/permissions`. The runtime enforces the same rules either way.

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
*   **`opencrawling.jobs.dynamic.min-chunk-size`**, **`max-chunk-size`**: the chunk sizes, in tokens, a job may ask for with its own `chunking` (defaults `100`, `2048`; never above `8192`). Keep `max-chunk-size` within the embedding model's input. A job that sets no chunk size uses the global chunk size from the admin UI's Settings page (`POST /api/system/settings`, `chunkSize`), `800` tokens unless an administrator changed it. The global value is stamped on each document when it is published, so it also reaches decoupled ingestion consumers, and it applies to documents published after the change. The Settings page's chunk overlap is not used: the `TokenTextSplitter` does not overlap chunks. See [Model Context Protocol, Dynamic jobs](Model-Context-Protocol#-dynamic-jobs).
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

## 🗂️ OIS Sidecars

Lets a verified caller, typically an agent, read the OIS metadata sidecars (`<key>.ois.json` on Apache Ozone or AWS S3, or columnar OIS rows in Apache Iceberg via `IcebergSidecarStore`), without downloading the binaries (REST `/api/sidecars/**`, MCP tools `listSidecarStores` and `readOisSidecars`). Each sidecar is filtered by the runtime's authorization gate, as search results are. See [Model Context Protocol, OIS sidecars](Model-Context-Protocol#%EF%B8%8F-ois-sidecars).

*   **`OPENCRAWLING_SIDECARS_ENABLED`** (`opencrawling.sidecars.enabled`): whether the endpoints and tools exist.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_SIDECARS_CONNECTORS`** (`opencrawling.sidecars.connectors`): the registered Ozone, S3, or Apache Iceberg output connectors (names in `connectors.json`) whose sidecars may be read, comma-separated.
    *   *Type*: list
    *   *Default*: empty (none)
*   **`OPENCRAWLING_SIDECARS_REQUIRED_SCOPE`** (`opencrawling.sidecars.required-scope`): the OAuth2 scope the caller's token must carry. Blank: not checked.
    *   *Type*: `string`
    *   *Default*: `oc:sidecars:read`
*   **`opencrawling.sidecars.allow-asserted-identity`**: also accept the `userPrincipal`/`userRoles` arguments without a token. Development and tests only. Default `false`.
*   **`opencrawling.sidecars.default-limit`**, **`max-limit`**: sidecars per answer when a request does not say, and the most it may ask for (defaults `50`, `200`; never above `1000`). An answer also stops at 8 MB of sidecars.
*   **`opencrawling.sidecars.max-scan`**: keys one request lists before it stops and returns a cursor (default `5000`; never above `50000`).
*   **`opencrawling.sidecars.max-sidecar-bytes`**: larger sidecars are skipped (default `1048576`; never above 16 MB).
*   **`OPENCRAWLING_SIDECARS_CURSOR_SECRET`** (`opencrawling.sidecars.cursor-secret`), **`opencrawling.sidecars.cursor-ttl`**: paging cursors are encrypted with this secret (at least 32 characters) and expire after the TTL (default `1h`). Blank: a random key per instance, so a cursor only works on the instance that gave it, until it restarts.

---

## 🧊 Apache Iceberg MCP Tools

Exposes time-travel, snapshot provenance, entity queries, and Zero-Trust partition statistics over Apache Iceberg tables (`IcebergMcpTools` and `IcebergMcpSearchBackend`).

*   **`OPENCRAWLING_MCP_ICEBERG_ENABLED`** (`opencrawling.mcp.iceberg.enabled`, alias `opencrawling.iceberg.mcp.enabled`): explicitly enables `IcebergMcpTools`. Also auto-enabled whenever `opencrawling.mcp.server.enabled=true` and `spring.opencrawling.output.type=iceberg`.
    *   *Type*: `boolean`
    *   *Default*: `false`
*   **`OPENCRAWLING_MCP_ICEBERG_DEFAULT_MAX_RESULTS`** (`opencrawling.mcp.iceberg.default-max-results`): default `maxResults` when omitted in `queryIcebergEntities`.
    *   *Type*: `integer`
    *   *Default*: `25`
*   **`OPENCRAWLING_MCP_ICEBERG_MAX_RESULTS`** (`opencrawling.mcp.iceberg.max-results`): upper bound on results returned per entity query (hard ceiling `500`).
    *   *Type*: `integer`
    *   *Default*: `200`
*   **`OPENCRAWLING_MCP_ICEBERG_MAX_SCAN`** (`opencrawling.mcp.iceberg.max-scan`): maximum Iceberg rows scanned per tool invocation (hard ceiling `100000`).
    *   *Type*: `integer`
    *   *Default*: `20000`
*   **`OPENCRAWLING_MCP_ICEBERG_MAX_SNAPSHOTS`** (`opencrawling.mcp.iceberg.max-snapshots`): maximum snapshots returned by `listIcebergSnapshots` (hard ceiling `500`).
    *   *Type*: `integer`
    *   *Default*: `100`
*   **`OPENCRAWLING_MCP_ICEBERG_REQUIRE_VERIFIED_CALLER`** (`opencrawling.mcp.iceberg.require-verified-caller`): requires an authenticated token-verified caller even when global identity mode is `none`.
    *   *Type*: `boolean`
    *   *Default*: `false`

---

## 🛡️ Pre-Embedding Governance

Checks every document before it is chunked and embedded. Hooks run in the ingestion consumer, in the order listed, and each one approves the document, approves a sanitized version (for example with personal data masked), or rejects it. A sanitized version is what the next hook sees, and the first rejection ends the evaluation. It applies to every document that reaches the ingestion consumer: admin jobs, dynamic jobs, BPM events, OIS and gRPC push.

*   **Permissions never change.** A hook cannot change a document's permissions: the `security` and `acl` metadata keys, every key starting with `security_` or `acl_`, and `documentId`, `uri` and `lastModified` always keep the values the source gave them.
*   **It fails closed.** When a hook fails or cannot be reached, the document is not embedded, and an error is recorded in the job's trace. The earlier version, if any, stays, and the claim-check object is kept, so a later crawl can ingest the document again.
*   **Rejections remove the earlier version by default.** A rejected document is not embedded. With `on-rejected-update: purge` (the default), the chunks of its earlier version are removed too, so the index stops serving text that was not approved again. In `openfga` authorization mode the document's tuples are removed as well (the runtime publishes a governance-rejection DELETE on the documents topic for the sync consumer). With `keep`, the last approved version stays searchable and its tuples are left as they are.
*   **Only the pipeline is governed.** A RAG job on a vector store (pgvector, Qdrant, Milvus, Vespa, Solr, OpenSearch, Elasticsearch, Luxir, SeaTunnel) writes through the pipeline only, so every document passes the gate. A RAG job whose output still takes documents directly (Hyland CIC, OpenArch) would extract and embed them itself and never pass the gate: with governance on, such jobs are refused, the job API answers `409`, and the job's stage says why. Migration-mode jobs embed nothing, so they still run.
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

