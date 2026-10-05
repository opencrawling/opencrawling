# OpenCrawling - Apache Ozone Output Connector

`oc-ozone-output-connector` is an OpenCrawling output connector dedicated **exclusively** to **Migration Mode** (`opencrawling.pipeline.mode=migration`).

## Overview

Unlike AI/vector output connectors designed for semantic search and RAG indexing, the Apache Ozone Output Connector is built specifically for high-throughput, bit-for-bit enterprise content migration and Zero-Trust data liberation into [Apache Ozone](https://ozone.apache.org/) object storage.

### Key Capabilities

- **Strict Migration Mode Enforcement**: Rejects RAG mode executions fail-fast (`IllegalStateException`), avoiding unnecessary Tika extraction, chunking, and embedding generation.
- **Bit-for-Bit Content Parity**: Streams pristine content binaries directly from Claim Check storage into Ozone Volumes and Buckets.
- **Dual Transport Strategies**: Supports both **NATIVE RPC** (`ofs://` via direct OzoneManager connections) and **S3 Gateway** (`s3://` via AWS S3 SDK).
- **OIS JSON Metadata Sidecars**: Produces companion `<key>.ois.json` metadata envelopes capturing source lineage, content hashes (SHA-256), and fine-grained Zero-Trust security rules (`inheritanceEnabled`, `permissions` lists).
- **Full OIS Lifecycle Management**: Supports `UPSERT` actions and `DELETE` tombstone purges or archiving.

## Configuration

Add the following properties to `application.yml`:

```yaml
spring:
  opencrawling:
    output:
      type: ozone
      ozone:
        client-type: NATIVE # Options: NATIVE (ofs/RPC) or S3G (S3 Gateway HTTP)
        volume: opencrawling
        bucket: migration
        om-host: localhost
        om-port: 9862
        s3-endpoint: http://localhost:9878
        access-key: any
        secret-key: any
        auto-create-bucket: true
        key-strategy: HIERARCHICAL # Options: HIERARCHICAL or FLAT
        sidecar-suffix: .ois.json
        tombstone-action: DELETE_KEY # Options: DELETE_KEY or ARCHIVE_TOMBSTONE

opencrawling:
  pipeline:
    mode: migration # Mandatory for Ozone output connector
```
