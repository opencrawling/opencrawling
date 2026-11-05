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
*   **Stop:** `POST /api/jobs/{id}/stop` ends the crawl, at the latest before its next document, and the job shows `Finished` with stage `Stopped`. It returns `202` while the run winds down; a job with no run here (for example a `Running` status left over from a restart) is simply marked `Finished`.
*   **Runs in progress:** `GET /api/jobs/runs`, and `GET /api/jobs/{id}/run` for one job (`404` when it is not running).
*   **Outcome:** a run that fails shows `Error`; before, a failed run was also shown as `Finished`.

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

