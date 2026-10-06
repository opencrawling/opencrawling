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

Additional properties:

| Property | Default | Description |
|---|---|---|
| `spring.opencrawling.output.ozone.allow-in-memory-fallback` | `false` | Testing only. When `false`, an unreachable OzoneManager makes the connector fail instead of silently writing to an in-memory store. |
| `spring.opencrawling.output.ozone.consumer-group` | `opencrawling-ozone-migration-group` | Dedicated Kafka consumer group of the decoupled `OzoneMigrationWriterConsumer` (must differ from the RAG ingestion group, otherwise partitions are split and messages are lost). |
| `spring.opencrawling.output.ozone.consumer-concurrency` | `3` | Parallel writer threads of `OzoneMigrationWriterConsumer` (one Kafka consumer each). |
| `spring.opencrawling.kafka.topic.partitions` | `3` | Partitions of the `opencrawling-documents` topic, created at startup by `KafkaAdmin`. Set the same value on every service. |
| `spring.opencrawling.crawler.concurrency` | `4` | Parallel claim-check upload + Kafka publish lanes of the standalone crawler, for repository connectors that opt in (see [Crawler side](#crawler-side)). `1` = sequential. |
| `spring.opencrawling.output.ozone.multipart-threshold` | `256MB` | S3G writer: binaries of at least this size use a parallel multipart upload (see [Large objects](#large-objects-s3-multipart)). `0` disables multipart. |
| `spring.opencrawling.output.ozone.multipart-part-size` | `16MB` | S3G writer: part size (min `5MB`). |
| `spring.opencrawling.output.ozone.multipart-concurrency` | `4` | S3G writer: parallel part uploads per object. |
| `spring.opencrawling.claim-check.ozone.multipart-threshold` / `-part-size` / `-concurrency` | `256MB` / `16MB` / `4` | Same settings for the crawler's S3 claim-check uploads. |

### Claim-check transport

The writer reads each binary from the claim-check store written by the crawler. With `spring.opencrawling.claim-check.store=ozone`, the transport is chosen by `spring.opencrawling.claim-check.ozone.client-type`:

| Value | Transport | Settings |
|---|---|---|
| `NATIVE` (default) | Ozone RPC to the OM + datanodes (`ofs://<volume>/<bucket>/<key>`), via `OzoneRpcClaimCheckStrategy` in this module | `om-host`, `om-port`, `volume`, `bucket`, `auto-create-bucket` |
| `S3` | S3 Gateway HTTP (`s3://<bucket>/<key>`), with multipart uploads for large objects | `s3-endpoint`, `bucket`, `access-key`, `secret-key`, `multipart-*` |

- `NATIVE` needs network access from every service to the OM and all datanodes; `S3` only needs the gateway endpoint.
- The real `NATIVE` client is registered by this module (`OzoneRpcClaimCheckStrategyFactory`); `oc-runtime` includes it. Applications built on `oc-core` alone fall back to an in-process store that is not shared across services, and log a warning when it is selected.
- A missing claim-check object fails with `NoSuchFileException` (no empty content), so the Kafka message is retried instead of migrating a 0-byte binary.

## Performance Tuning

The decoupled writer migrates documents in parallel: each consumer thread owns a subset of the topic partitions.

- **Effective parallelism = `min(consumer-concurrency, topic.partitions)`**. Extra threads stay idle (`partitions assigned: []`). Scaling out with several writer replicas shares the same partitions.
- **Ordering is preserved per document**: messages are keyed by document id, so all `UPSERT`/`DELETE` events of a document land on the same partition and are processed in order. Within a document, the write order is always binary → sidecar → index.
- **Choose the partition count before the first migration.** `KafkaAdmin` can increase but never decrease partitions, and increasing them while a backlog exists remaps keys to new partitions, which can reorder pending events of the same document.
- The S3 Gateway client checks/creates the bucket once per process instead of once per document; the native client caches its connection.

Measured with the e2e load mode (400 documents, 131 MB, 4 KB–1 MB files, single-datanode Ozone on Docker Desktop, so absolute numbers are a lower bound):

| Transport | Threads (= partitions) | Throughput | Speed-up |
|---|---|---|---|
| S3G | 1 | 19.1 docs/s, 6.3 MB/s | — |
| S3G | 3 | 33.3 docs/s, 11.0 MB/s | 1.75× |
| S3G | 6 | 38.4 docs/s, 12.6 MB/s | 2.0× |
| NATIVE | 1 | 25.2 docs/s, 8.3 MB/s | — |
| NATIVE | 3 | 42.2 docs/s, 13.9 MB/s | 1.67× |
| NATIVE | 6 | 51.1 docs/s, 16.8 MB/s | 2.0× |

Beyond 3 threads the single datanode becomes the bottleneck; a multi-datanode cluster scales further. `NATIVE` is consistently ~30% faster than `S3G`, which adds an HTTP gateway hop.

### Crawler side

The standalone crawler (decoupled deployment) uploads each binary to the claim-check store and publishes its reference to Kafka. It can process documents in parallel lanes (`spring.opencrawling.crawler.concurrency`, default `4`, on virtual threads):

- **Ordering is preserved per document**: a document always maps to the same lane (hash of its id), so its `UPSERT`/`DELETE` events are published in order.
- **Opt-in per repository connector** via `RepositoryConnector.supportsConcurrentProcessing()` (default `false`). With lanes the scan runs ahead of processing and buffers documents, so a connector may opt in only if a buffered document holds no open connection, cursor or large payload until its content is read. Currently only the **filesystem** connector opts in (files are opened lazily on first read). All other connectors (CMIS, Alfresco, JDBC, …) keep sequential processing, where each document is processed before the next one is emitted.
- Processing is always sequential when the job writes directly to an `OutputConnector` (Admin UI/API jobs, embedded runtime), because output connectors are not required to be thread-safe. Narrativization and embeddings are unaffected: embeddings are computed downstream by the Kafka consumers.
- `1` restores fully sequential processing.

Measured with the same load (filesystem connector, claim check on the source Ozone S3 Gateway, upload + publish):

| Crawler lanes | Throughput | Speed-up |
|---|---|---|
| 1 | 30.8 docs/s, 10.1 MB/s | — |
| 4 | 67.3 docs/s, 22.2 MB/s | 2.2× |
| 8 | 88.9 docs/s, 29.3 MB/s | 2.9× |

With 4+ lanes the crawler publishes faster than a 3-thread writer drains, so end-to-end throughput is bounded by the writer settings above.

### Large objects (S3 multipart)

Both S3 uploads, the crawler's claim check and the `S3G` writer, use a shared uploader (`org.opencrawling.core.s3.S3MultipartUploader`):

- Objects below `multipart-threshold` use a single PUT; larger ones are split into `multipart-part-size` parts uploaded `multipart-concurrency` at a time. If a part fails, the remaining parts are cancelled and the upload is aborted (no orphan parts).
- Streams of unknown length (the crawler always passes `-1`) are buffered in memory up to 8 MB and spooled to a temp file above that, so memory stays bounded for any file size, also with parallel crawler lanes.
- Every request body is repeatable (bytes or a file slice re-opened on demand), as required by the AWS SDK's checksum computation and retries.
- The `NATIVE` writer is not affected: it already streams blocks directly to the datanodes.

Measured on the single-datanode Docker setup (256 MB file, S3G):

| Mode | Claim-check upload (crawler) | Target upload (writer) |
|---|---|---|
| Single PUT | 1.6 s (164 MB/s) | 2.2 s (117 MB/s) |
| Multipart, 16 MB parts × 4 | 2.3 s (109 MB/s) | 3.1 s (82 MB/s) |

On one datanode and one disk, parallel parts compete for the same disk and each part adds a commit, so multipart is ~30% slower. It is therefore enabled only for very large objects (default threshold `256MB`), where it bounds each API call's duration (the claim-check client has a 30 s call timeout) and retries a single part instead of the whole object. On a multi-datanode cluster over a real network, measure with the e2e large-file mode (see [Testing](#testing)) before lowering the threshold.

## Pipeline Mode Selection

The mode is resolved per job: `JobDTO.pipelineMode` (if set) overrides the global `opencrawling.pipeline.mode` (default `rag`).
Starting a job whose output is the Ozone connector in `rag` mode is rejected with HTTP `409`.

- **CLI**: `opencrawling job start <jobId> --mode migration`, `opencrawling job create -f job.json --mode migration`; `job list` shows a `MODE` column.
- **Admin UI**: the Job form has a *Pipeline Mode* radio group (RAG / Migration); selecting an Ozone output switches it to Migration automatically. Jobs display a `Migration` / `RAG` badge.
- **Offline validation**: `opencrawling schema validate <envelope.json>` checks migration envelopes (`contentRef`, `checksumSha256`, `security`, …).

## Object Layout

For each `UPSERT` the connector writes:

| Object | Content |
|---|---|
| `<key>` | Original binary, streamed bit-for-bit (SHA-256 computed while streaming). |
| `<key>.ois.json` | OIS sidecar: `id`, `action`, `metadata`, `security` (`inheritanceEnabled`, `permissions[]`), `contentRef` (`key`, `volume`, `bucket`, `claimCheckUri`, `filename`, `mimeType`, `contentLength`, `checksumSha256`). |
| `.opencrawling/index/<sha256(docId)>` | Internal index mapping the document id to its key, so metadata-less tombstones remove the right objects. |

`HIERARCHICAL` keys derive from `relativePath`/`file_path` (or the `file:` URI), sanitized against `..` traversal; `FLAT` keys use the sanitized document id.
On `DELETE`, `DELETE_KEY` removes binary, sidecar and index entry; `ARCHIVE_TOMBSTONE` additionally writes `.tombstones/<key>.ois.json`.

## Testing

- Unit: `mvn -pl oc-ozone-output-connector test`
- End-to-end (two Ozone clusters + Kafka): `scripts/test-ozone-migration-decoupled.sh`

The e2e script accepts these environment variables:

| Variable | Default | Description |
|---|---|---|
| `OUTPUT_OZONE_CLIENT_TYPE` | `NATIVE` | Writer transport into the target cluster: `NATIVE` or `S3G`. |
| `CLAIM_CHECK_OZONE_CLIENT_TYPE` | `NATIVE` | Claim-check transport on the source cluster: `NATIVE` or `S3` (see [Claim-check transport](#claim-check-transport)). |
| `OC_KAFKA_PARTITIONS` | `3` | Partitions of the documents topic. |
| `OZONE_WRITER_CONCURRENCY` | `3` | Writer consumer threads. |
| `OC_CRAWLER_CONCURRENCY` | `4` | Crawler lanes (claim-check upload + Kafka publish). |
| `LARGE_FILE_MB` | `24` | Step 5b: crawls one random file of this size and checks that the target object and the sidecar `checksumSha256` match the source SHA-256. With the S3 transports it also checks that the crawler (`CLAIM_CHECK_OZONE_CLIENT_TYPE=S3`) and the writer (`S3G`) used a multipart upload. `0` skips the step. |
| `OC_MULTIPART_THRESHOLD` / `OC_MULTIPART_PART_SIZE` / `OC_MULTIPART_CONCURRENCY` | `8MB` / `5MB` / `4` | Multipart settings for the S3 transports (crawler and writer). The script lowers them (compose defaults: `256MB` / `16MB` / `4`) so Step 5b exercises multipart; `OC_MULTIPART_THRESHOLD=0` disables multipart (single-PUT baseline). |
| `LOAD_DOCS` | `0` | When `> 0`, runs an extra throughput step: generates N files, crawls them with the writer stopped, then times the writer draining the backlog. Prints a `CRAWLER_THROUGHPUT` and a (writer) `THROUGHPUT` line. |

Example: `OC_KAFKA_PARTITIONS=6 OZONE_WRITER_CONCURRENCY=6 LOAD_DOCS=400 scripts/test-ozone-migration-decoupled.sh`

S3 transports end to end: `OUTPUT_OZONE_CLIENT_TYPE=S3G CLAIM_CHECK_OZONE_CLIENT_TYPE=S3 scripts/test-ozone-migration-decoupled.sh`

Large-file comparison (S3 transports, with `OUTPUT_OZONE_CLIENT_TYPE=S3G CLAIM_CHECK_OZONE_CLIENT_TYPE=S3`): `LARGE_FILE_MB=256 OC_MULTIPART_THRESHOLD=0 scripts/test-ozone-migration-decoupled.sh` (single PUT) vs `LARGE_FILE_MB=256 OC_MULTIPART_THRESHOLD=16MB OC_MULTIPART_PART_SIZE=16MB scripts/test-ozone-migration-decoupled.sh` (multipart); compare the `LARGE_CLAIM_CHECK_UPLOAD` / `LARGE_FILE_MIGRATION` lines.
