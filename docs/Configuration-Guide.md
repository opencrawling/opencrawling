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

## 📥 OIS Ingestion Endpoint

`POST /api/v1/ingest/ois/{jobId}` takes OIS documents from producers outside the runtime, such as the StormCrawler bolt, and runs them through the job's RAG pipeline.

*   **`OPENCRAWLING_INGEST_OIS_TOKEN`** (`opencrawling.ingest.ois.token`): the bearer token producers send as `Authorization: Bearer <token>`.
    *   *Type*: `string`
    *   *Default*: empty, which disables the endpoint
    *   The runtime that serves the endpoint writes the text of each UPSERT to the claim-check store, so that store must be one the ingestion consumer can read: the same process, a shared volume, or Ozone.

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
