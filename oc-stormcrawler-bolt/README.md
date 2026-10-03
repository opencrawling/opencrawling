# OpenCrawling - Apache StormCrawler Bolt

The `oc-stormcrawler-bolt` module provides an Apache Storm Bolt library (`org.opencrawling:oc-stormcrawler-bolt`) that intercepts parsed web documents and URL lifecycle status streams from **Apache StormCrawler** topologies.

It normalizes crawled records into **Open Ingestion Standard (OIS)** payloads:
- **`action: "UPSERT"`**: For newly discovered and updated web pages emitted by `ParserBolt`.
- **`action: "DELETE"`**: For removed pages, deleted URLs, and HTTP 404/410 status events emitted via the StormCrawler status stream (`StatusStreamName` / `DELETION_STREAM_NAME`).

## Architecture & Stream Mapping

```mermaid
graph LR
    subgraph StormCrawler Topology
        ParserBolt[ParserBolt] -->|Default Stream: url, content, metadata, text| OCBolt[OpenCrawlingBolt]
        OCBolt -->|status stream: url, metadata, FETCHED| StatusUpdater[Status Updater]
        StatusStream[Status Stream / 404 / Deletions] -->|StatusStreamName / DELETION_STREAM_NAME| OCBolt
    end

    subgraph OpenCrawling Pipeline
        OCBolt -->|action: UPSERT| OIS_Queue[OIS Ingestion Queue]
        OCBolt -->|action: DELETE| OIS_Tombstone[OIS Deletion Tombstone]
    end
```

## Features
- **Dual-Stream Processing**: Seamlessly ingests parsed content tuples and handles status deletion signals.
- **OIS Compliance**: Maps crawled web pages into standard OIS `UPSERT` documents and deletion `tombstones`.
- **Content Deduplication**: Calculates document content hashes (`SHA-256` or `MD5`) for deduplication and incremental crawl detection.
- **Flexible Dispatching**:
  - `REST`: Dispatches OIS JSON payloads via HTTP POST to the OpenCrawling ingestion runtime (`/api/v1/ingest/ois`).
  - `MEMORY`: In-memory dispatcher for embedded topologies and test suites.

## Indexer Behavior

`OpenCrawlingBolt` extends StormCrawler's `AbstractIndexerBolt` and takes the place of the indexer in a topology:

- It puts the parser's `text` field in the OIS `content.text`; the `content` field (the raw fetched bytes) is only used for `metadata.contentHash`.
- After a successful dispatch it emits `(url, metadata, FETCHED)` on the `status` stream and acks the tuple; if the dispatch fails, the tuple fails and no status is emitted. Subscribe the status updater to the bolt's `status` stream, as for any StormCrawler indexer: Storm rejects a topology that subscribes to a stream the bolt does not declare.
- The OIS `id` is the fetched URL, for UPSERT and DELETE alike.

It reads these StormCrawler indexer settings:

| Setting | Effect |
|---|---|
| `indexer.md.mapping` | Metadata keys copied to the OIS `metadata`, with optional renaming (`parse.title=title`); the first value of each key is used. Unmapped keys are not sent. |
| `indexer.canonical.name` | Metadata key holding the canonical URL, sent as `metadata.canonical.url` when it is on the same registered domain as the fetched URL; otherwise the fetched URL is sent. |
| `indexer.md.filter` | Only documents whose metadata match are dispatched. Pages marked `robots.noIndex` are never dispatched. Both kinds are still reported as `FETCHED`. |

`indexer.text.fieldname`, `indexer.url.fieldname`, `indexer.text.maxlength`, `indexer.md.docid` and `indexer.ignore.empty.fields` have no effect: OIS has fixed field names, `content.text` is sent in full, and the OIS `id` is always the fetched URL.

## Topology Configuration (`crawler-conf.yaml`)

```yaml
config:
  topology.workers: 2
  topology.message.timeout.secs: 30
  
  # OpenCrawling Bolt Configuration
  opencrawling.target.endpoint: "http://localhost:8080/api/v1/ingest/ois"
  opencrawling.transport.mode: "REST" # Options: REST, MEMORY
  opencrawling.emit.deletions: true
  opencrawling.status.stream.id: "status"
  opencrawling.hash.algorithm: "SHA-256"
  opencrawling.instance.id: "stormcrawler-cluster-01"

  # StormCrawler indexer settings read by the bolt
  indexer.md.mapping:
  - parse.title=title
  - parse.description=description
  indexer.canonical.name: "canonical"
```
