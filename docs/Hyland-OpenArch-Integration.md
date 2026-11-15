# Hyland OpenArch Integration Guide

## Overview

OpenCrawling features native, bidirectional integration with **Hyland OpenArch**—a modern, cloud-native content and AI repository platform built on Spring Boot, MongoDB, and OpenSearch.

This integration delivers three core capabilities:
1. **Source Connector (`oc-openarch-repository-connector`)**: High-performance document and metadata harvesting from OpenArch using paged HXQL (`/api/query`) or folder hierarchy traversal, binary blob streaming (`/api/download/{id}`), incremental change tracking via `sys_modified`, and Java 25 Virtual Threads (`StructuredTaskScope`).
2. **Sink Connector & Parquet Vector Bridge (`oc-openarch-output-connector`)**: Ingestion into OpenArch via `/api/upload`, Zero-Trust ACL synchronization, and binary embeddings generation serialized to Apache Parquet files conforming strictly to OpenArch's Avro schema (`embedding.avsc`).
3. **Model Context Protocol (MCP) Vector Backend**: Exposes OpenArch full-text and semantic search directly to LLM agents via `openarch_search` and `openarch_fetch_document` tools in `McpVectorServer`, enforcing Zero-Trust security filtering.

---

## Architecture Diagram

```
+-----------------------------------------------------------------------------------+
|                                 OpenCrawling Core                                 |
|                                                                                   |
|  +--------------------------------+       +------------------------------------+  |
|  | OpenArchRepositoryConnector    |       | OpenArchOutputConnector            |  |
|  | - Paged HXQL (/api/query)      |       | - Document Ingestion (/api/upload) |  |
|  | - Folder traversal             |       | - Parquet Vector Writer            |  |
|  | - Binary streaming             |       |   (embedding.avsc)                 |  |
|  | - Java 25 Virtual Threads      |       | - Zero-Trust ACL Mapper            |  |
|  +---------------+----------------+       +-----------------+------------------+  |
|                  |                                          |                     |
|                  |          +--------------------+          |                     |
|                  +--------> | ClaimCheckStore    | <--------+                     |
|                             | (Ozone / S3)       |                                |
|                             +--------------------+                                |
+---------------------------------------+-------------------------------------------+
                                        |
                                        v
+-----------------------------------------------------------------------------------+
|                                Hyland OpenArch                                    |
|                                                                                   |
|  +--------------------+   +--------------------+   +--------------------------+   |
|  | REST API Engine    |   | OpenSearch Vector  |   | MongoDB Metadata Store   |   |
|  | (/api/query,       |   | & Text Search      |   | (Folders, SysFile, ACLs) |   |
|  |  /api/upload,      |   | (KNN embeddings,   |   |                          |   |
|  |  /api/download)    |   |  Parquet batches)  |   |                          |   |
|  +--------------------+   +--------------------+   +--------------------------+   |
+-----------------------------------------------------------------------------------+
```

---

## Source Connector (`OpenArchRepositoryConnector`)

### Capabilities
- **Crawl Modes**:
  - `QUERY`: Executes HXQL queries (e.g. `SELECT * FROM SysFile WHERE sys_title LIKE '%.pdf'`) with automatic cursor/offset pagination.
  - `FOLDER`: Traverses folder hierarchies recursively starting from a root folder UUID.
- **High-Throughput Concurrency**: Utilizes Java 25 `StructuredTaskScope.open()` to stream documents and offload binaries in parallel virtual threads.
- **Incremental Crawling**: Filters on `sys_modified > :lastTimestamp` to ingest only new or modified files.
- **Zero-Trust ACL Preservation**: Preserves OpenArch permission models and transforms them into OpenCrawling OIS security models.

### Configuration Properties
| Property | Type | Default | Description |
|---|---|---|---|
| `endpointUrl` | `String` | `http://localhost:8080` | OpenArch base REST API endpoint URL |
| `repositoryId` | `String` | `default` | Target repository identifier |
| `username` | `String` | `Administrator` | API authentication username |
| `password` | `String` | `Administrator` | API authentication password |
| `crawlMode` | `String` | `QUERY` | Crawl mode: `QUERY` or `FOLDER` |
| `hxqlQuery` | `String` | `SELECT * FROM SysFile` | Query used when `crawlMode=QUERY` |
| `rootFolderId` | `String` | `00000000-0000-0000-0000-000000000000` | Folder UUID when `crawlMode=FOLDER` |
| `includeSubfolders` | `boolean` | `true` | Recursively traverse child folders |
| `batchSize` | `int` | `100` | Pagination page size |
| `includeAcls` | `boolean` | `true` | Extract and map document permissions |
| `includeContentStream` | `boolean` | `true` | Stream binary content via `/api/download/{id}` |
| `incremental` | `boolean` | `false` | Enable incremental crawl filtering |

---

## Output Connector & Vector Bridge (`OpenArchOutputConnector`)

### Parquet Vector Bridging
The connector generates vector embeddings in Apache Parquet format adhering strictly to OpenArch's Avro schema (`embedding.avsc`):
- `id` (string): Document / chunk identifier.
- `type` (string): Embedding model identifier (e.g. `mxbai-embed-large`).
- `vector` (array<double>): Embedding vector coordinates.
- `text` (string): Extracted chunk text.
- `blobref` (nullable string): Claim-Check URI or binary blob reference.
- `location` (nullable record): Nested provenance records (`text`, `position`, `timestamp`, `spreadsheet`).

Batches of embeddings are written to Parquet and bridged into OpenArch's vector index when the chunk count reaches `parquetBatchThreshold`.

### Zero-Trust ACL Mapping
Maps incoming OIS security configurations to OpenArch ACL records:
- Maps `READ`, `WRITE`, `ADMIN` permissions to OpenArch permission arrays.
- Preserves user, group, and role identity types.
- Supports tenant and domain isolation.

### Configuration Properties
| Property | Type | Default | Description |
|---|---|---|---|
| `endpointUrl` | `String` | `http://localhost:8080` | OpenArch REST API URL |
| `repositoryId` | `String` | `default` | Target repository identifier |
| `username` | `String` | `Administrator` | OpenArch username |
| `password` | `String` | `Administrator` | OpenArch password |
| `rootFolderId` | `String` | `00000000-0000-0000-0000-000000000000` | Target folder for ingested documents |
| `parquetBatchThreshold` | `int` | `50` | Chunks per Parquet file flush |
| `embeddingsType` | `String` | `mxbai-embed-large` | Embeddings model type |
| `dimensions` | `int` | `1024` | Vector dimensionality |
| `upsert` | `boolean` | `true` | Overwrite existing records |
| `syncAcls` | `boolean` | `true` | Synchronize Zero-Trust ACLs |
| `uploadBinaries` | `boolean` | `true` | Stream binary payload to `/api/upload` |

---

## MCP Server Integration

OpenCrawling runtime registers OpenArch as a first-class search backend in `McpVectorServer`:

```json
{
  "name": "openarch_search",
  "description": "Execute full-text and semantic queries against Hyland OpenArch with Zero-Trust security filtering",
  "parameters": {
    "query": "string (required)",
    "limit": "integer (optional, default 10)"
  }
}
```

```json
{
  "name": "openarch_fetch_document",
  "description": "Retrieve document metadata and text content from Hyland OpenArch by object ID",
  "parameters": {
    "documentId": "string (required)"
  }
}
```

---

## Admin UI & CLI Verification

### Admin UI
Navigate to **Connectors** in the OpenCrawling Admin UI:
1. Select **Source** -> `Hyland OpenArch Federated Repository` to configure harvesting jobs.
2. Select **Output** -> `Hyland OpenArch Federated Ingestion & Parquet Vector Bridge` to configure indexing pipelines.
3. Click **Test OpenArch Connectivity** to verify live communication with the OpenArch instance.

### CLI
Verify connector reachability and test connections directly from `oc-cli`:

```bash
# Test connection using positional argument
oc connector test OpenArch_Repository

# Test connection using --name option
oc connector check --name OpenArch_Output --type output

# Override endpoint or credentials during test
oc connector test OpenArch_Repository --config endpointUrl=http://openarch.internal:8080
```
