# OpenCrawling - Apache SeaTunnel Output Connector

## Overview
`oc-seatunnel-output-connector` integrates **OpenCrawling** with **[Apache SeaTunnel](https://seatunnel.apache.org/) (v2.3.13)**. It enables high-throughput document crawling, text extraction, token chunking, and dense vector embeddings to fan out directly into Apache SeaTunnel pipelines powered by the **Zeta Engine**, Apache Flink, or Apache Spark.

Through SeaTunnel, OpenCrawling streams can be routed to over 100+ destination sinks simultaneously, including:
- **Vector Databases**: Milvus, Qdrant, Vespa
- **Data Lakehouses & Warehouses**: Apache Iceberg, ClickHouse, StarRocks, Snowflake, S3
- **Search Engines**: Elasticsearch, OpenSearch, Solr, Luxir
- **Message Brokers**: Apache Kafka, Apache Pulsar

## Architecture
1. **Decoupled Stream Fan-Out**: OpenCrawling emits standard Open Ingestion Standard (OIS) embedded chunks to the `opencrawling-embedded` topic on Apache Kafka.
2. **Automated Pipeline Orchestration**: `SeaTunnelOutputConnector` synthesizes SeaTunnel HOCON DAGs and submits/monitors them via the SeaTunnel Zeta **REST API v2** (`http://<master>:8080/submit-job`).
3. **Document Lifecycle & CDC**: Full tombstone purges (`DocumentAction.DELETE`) map to SeaTunnel's `RowKind.DELETE`, allowing downstream vector stores to purge removed documents without running expensive embeddings.
4. **Security ACL Mapping**: Document permissions (`security_allowed_read`, `security_denied_read`, `acl`) are carried in the SeaTunnel catalog schema.

## Configuration Properties (`application.yml`)
```yaml
spring:
  opencrawling:
    output:
      type: seatunnel
      seatunnel:
        rest-url: "http://localhost:8080"
        job-name: "opencrawling_ingestion_pipeline"
        job-mode: "STREAMING"
        checkpoint-interval-ms: 5000
        parallelism: 4
        kafka-bootstrap-servers: "localhost:9092"
        kafka-topic: "opencrawling-embedded"
        kafka-group-id: "seatunnel-ingestion-consumer"
        target-sinks: "clickhouse,milvus"
        dimensions: 1024
        auto-submit-job: true
```
