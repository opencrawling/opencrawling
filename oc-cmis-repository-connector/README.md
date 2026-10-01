# OpenCrawling - OASIS CMIS Repository Connector

The `oc-cmis-repository-connector` module enables OpenCrawling to crawl, ingest, and synchronize content and metadata from any **OASIS CMIS (Content Management Interoperability Services) 1.0 / 1.1** compliant enterprise repository (including Alfresco Content Services, IBM FileNet, OpenText Documentum, Nuxeo, SAP DMS, and Apache Chemistry InMemory Server).

> [!NOTE]
> This connector implements a modern, high-performance, lightweight HTTP/JSON client for OASIS CMIS 1.1 Browser Binding (`/browser`) natively on **Java 25**, completely avoiding obsolete Apache Chemistry OpenCMIS dependencies.

---

## Features

- **Modern HTTP/JSON CMIS 1.1 Browser Binding**: Lightweight, native HTTP client without legacy CXF or XML stack dependencies.
- **Hierarchical Tree Traversal (`crawl-mode: folder`)**: Traverses folder trees recursively, fetching subfolders and document children with configurable folder exclusions.
- **CMISQL Query Ingestion (`crawl-mode: query`)**: Executes relational CMISQL queries with streaming pagination (e.g. `SELECT * FROM cmis:document WHERE cmis:lastModificationDate >= TIMESTAMP '2026-01-01T00:00:00.000Z'`).
- **Versioning Policy Ingestion**: Supports `latest_major` (default, only major released versions like 1.0, 2.0), `latest` (latest version including minor revisions), or `all` (historical version tree).
- **Binary Content Streaming & Claim-Check**: Streams document binaries (`getContentStream`) directly into OpenCrawling's Claim-Check storage offloading large payloads from message brokers.
- **Aspects & Secondary Object Types**: Ingests secondary object types (`cmis:secondaryObjectTypeIds`) and prefixes all custom metadata attributes with `cmis_prop_*`.
- **Zero-Trust ACL & Permission Mapping**: Extracts Access Control Lists (ACLs) and Access Control Entries (ACEs), mapping CMIS permissions (`cmis:read`, `cmis:write`, `cmis:all`) directly to Open Ingestion Standard (OIS) permission rules.
- **Incremental Synchronization & Tombstone Deletion**: Reads CMIS Change Log events (`session.getContentChanges()`) and produces OIS `action: "DELETE"` tombstones for deleted documents to eliminate stale vector embeddings.
- **Java 25 Virtual Threads & Concurrency**: Leverages `StructuredTaskScope.open()` and `ObservabilityTask` for concurrent folder navigation and document enrichment.

---

## Configuration Properties

| Property key | Environment Variable | Default | Description |
|---|---|---|---|
| `endpoint-url` | `CMIS_ENDPOINT_URL` | `http://localhost:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser` | CMIS 1.1 Browser Binding service document URL |
| `binding-type` | `CMIS_BINDING_TYPE` | `browser` | Protocol binding: `browser` (JSON) or `atompub` (XML) |
| `repository-id` | `CMIS_REPOSITORY_ID` | `""` | Repository ID (leave empty for auto-discovery) |
| `username` | `CMIS_USERNAME` | `admin` | HTTP Basic Auth username |
| `password` | `CMIS_PASSWORD` | `admin` | HTTP Basic Auth password |
| `crawl-mode` | `CMIS_CRAWL_MODE` | `folder` | Crawl mode: `folder` (tree traversal) or `query` (CMISQL) |
| `root-folder-path` | `CMIS_ROOT_FOLDER_PATH` | `/` | Root folder path when `crawl-mode: folder` |
| `root-folder-id` | `CMIS_ROOT_FOLDER_ID` | `""` | Optional root folder ID override |
| `include-subfolders` | `CMIS_INCLUDE_SUBFOLDERS` | `true` | Recursively traverse subfolders |
| `excluded-folder-paths` | `CMIS_EXCLUDED_FOLDER_PATHS` | `/Sites/trash,/System` | Comma-separated list of paths to exclude from crawling |
| `cmis-query` | `CMIS_CMIS_QUERY` | `SELECT * FROM cmis:document` | CMISQL query when `crawl-mode: query` |
| `versions-mode` | `CMIS_VERSIONS_MODE` | `latest_major` | Versioning filter: `latest_major`, `latest`, `all` |
| `include-content-stream`| `CMIS_INCLUDE_CONTENT_STREAM`| `true` | Fetch and ingest binary document content |
| `max-content-size-bytes`| `CMIS_MAX_CONTENT_SIZE_BYTES`| `52428800` (50MB) | Maximum file size for binary downloads |
| `include-acls` | `CMIS_INCLUDE_ACLS` | `true` | Extract ACLs and map to OIS security rules |
| `include-secondary-types`| `CMIS_INCLUDE_SECONDARY_TYPES`| `true` | Extract secondary object type properties and aspects |
| `change-log-enabled` | `CMIS_CHANGE_LOG_ENABLED` | `false` | Enable incremental change log delta processing |
| `change-log-token` | `CMIS_CHANGE_LOG_TOKEN` | `""` | Initial change log token for incremental synchronization |
| `batch-size` | `CMIS_BATCH_SIZE` | `100` | Pagination page size for folder children and query results |
| `timeout-seconds` | `CMIS_TIMEOUT_SECONDS` | `30` | HTTP request timeout in seconds |

---

## Example `application.yml`

```yaml
spring:
  opencrawling:
    connector:
      cmis:
        endpoint-url: "http://localhost:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser"
        binding-type: "browser"
        repository-id: ""
        username: "admin"
        password: "adminPassword"
        crawl-mode: "folder"
        root-folder-path: "/"
        include-subfolders: true
        excluded-folder-paths:
          - "/Sites/trash"
          - "/System"
        versions-mode: "latest_major"
        include-content-stream: true
        max-content-size-bytes: 52428800
        include-acls: true
        include-secondary-types: true
        batch-size: 100
        timeout-seconds: 30
```

---

## Admin UI & CLI Integration

### Admin UI (`oc-admin-ui`)
Under **Connectors -> Repository Connectors**, select **OASIS CMIS Repository (1.0 / 1.1)**:
- Configure endpoint URL, credentials, and crawl mode (`Folder Tree Traversal` or `CMISQL Query`).
- Click **Check Connection** to verify endpoint availability and inspect repository info.

### CLI (`oc-cli`)
```bash
# Check CMIS connection
oc connector check --name CMIS_Repository --type repository

# Run a CMIS crawl job
oc job create --name "CMIS Docs Crawl" --repository CMIS_Repository --output PGVector_Output --transform Ollama_Embedding_Default
```

---

## Docker Compose & Integration Testing

A pre-configured Docker Compose environment is provided at `oc-cmis-repository-connector/docker/docker-compose-decoupled-with-cmis.yml`:

```bash
docker compose -f oc-cmis-repository-connector/docker/docker-compose-decoupled-with-cmis.yml up -d
```

### 1. Standalone Connector Tests
Runs unit and integration tests against isolated embedded mock HTTP servers:
```bash
./scripts/test-cmis-connector.sh
```

### 2. Full Decoupled Pipeline Integration Test
Executes an end-to-end integration test validating the entire distributed pipeline with Alfresco Content Services acting as the CMIS 1.1 provider:
```bash
./scripts/test-cmis-decoupled.sh
```
This script:
1. Boots the complete decoupled architecture (Alfresco Community, pgvector, Redis, Ollama, Kafka, microservices).
2. Probes the OASIS CMIS 1.1 Browser Binding endpoint (`/alfresco/api/-default-/public/cmis/versions/1.1/browser`).
3. Provisions test documents with rich metadata and text streams into the repository.
4. Executes `oc-crawler` targeting the repository via the CMIS Browser Binding.
5. Verifies claim-checked content resolution, tokenization, semantic chunking, and embedding generation.
6. Asserts dense 1024-dimensional vector persistence and metadata matching in PostgreSQL pgvector.
7. Validates OpenCrawling MCP Server endpoint readiness.
8. Validates Open Ingestion Standard (OIS) deletion tombstones (`action: "DELETE"`).
9. Cleans up test artifacts and tears down Docker Compose containers.

