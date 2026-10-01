# OpenCrawling - Doxis Output Connector

This module provides the `OutputConnector` implementation for **[Doxis AI.dp](https://www.doxis.com/en/)** (SER Group's Intelligent Content Automation / document processing platform). OpenCrawling archives crawled documents into a Doxis **Dataset v3** dataset through the Doxis AI.dp REST API. Each row holds the original file, the OIS descriptors and the OIS security model. Doxis then OCRs and indexes the archived documents.

The REST contract follows the published Doxis AI.dp OpenAPI specification (`https://dochorizon.klippa.com/api/open-api.yaml`, Swagger UI at `https://dochorizon.klippa.com/api/swagger`).

## Feature Overview

1. **API-key authentication**: every request carries the `x-api-key` header. The connection check calls `GET /api/services/auth/v1/info` and reports the organization and project the key belongs to.
2. **Dataset auto-provisioning**: on startup, `DoxisDatasetInitializer` resolves the target dataset. It uses the configured `dataset-id` first, then a dataset whose name matches `dataset-name`, and otherwise creates the dataset (`POST /datasets/v3/datasets`). It adds any column the connector writes that the dataset lacks (`POST /datasets/{id}/columns`).
3. **Original binary archiving**: `DoxisOutputConnector.send()` writes one row per document, with the source file inlined as a base64 *document-input* object in the `document` cell (`POST /datasets/{id}/rows?on_error=abort`).
4. **OIS descriptor mapping**: title, URI, source system, MIME type, content length, last-modified and ingestion timestamps are stored in typed columns. All remaining source metadata is stored as JSON in `metadata_json`.
5. **OIS security mapping**: the legacy `acl` string and the `SecurityConfig` permissions are stored per row:
   - `read`/`write` identities go to `security_allowed_read`.
   - `deny` identities go to `security_denied_read`.
   - `security_inheritance` holds the inheritance flag.
   - `security_json` holds the full OIS security JSON.
6. **Conflict resolution**: Dataset v3 document cells are write-once, so an UPSERT of an already archived document is resolved by `conflict-resolution`:
   - `REPLACE` (default) inserts a fresh row first, then deletes the previous rows. A failed upload never removes the archived copy.
   - `UPDATE_METADATA` keeps the archived content and only patches the metadata and security cells (`PATCH /datasets/{id}/rows/{rowId}`).
7. **OIS deletion tombstones**: `action: "DELETE"` looks up every row of the document (the `document_id` filter on `POST /rows/views/detailed/search`) and deletes each one (`DELETE /rows/{rowId}`). A row that is already gone (404) counts as deleted, so tombstones are idempotent.
8. **Decoupled Kafka writer**: when `opencrawling.consumer.writer.enabled=true`, `DoxisStoreWriterConsumer` listens to `opencrawling-embedded` and stores each embedded chunk as a `record_type=chunk` row, keyed by chunk id and grouped by `document_id`, with the chunk text in `chunk_text`.
9. **Resilience**: connection failures and HTTP `429`/`502`/`503`/`504` are retried with exponential backoff (`max-retries`). Platform errors surface the Doxis error envelope (`code`, `message`, `request_id`).

## Configuration Parameters

All properties are bound via `DoxisOutputProperties` under the `spring.opencrawling.output.doxis` prefix.

| Parameter | Spring Property Key | Default Value | Description |
| :--- | :--- | :--- | :--- |
| **Base URL** | `spring.opencrawling.output.doxis.base-url` | `https://dochorizon.klippa.com` | Doxis AI.dp API host (`https://de.dochorizon.klippa.com` for the Germany region) |
| **API Key** | `spring.opencrawling.output.doxis.api-key` | — | API key sent as `x-api-key` (required) |
| **Dataset ID** | `spring.opencrawling.output.doxis.dataset-id` | — | Existing Dataset v3 id; when empty the dataset is resolved by name |
| **Dataset Name** | `spring.opencrawling.output.doxis.dataset-name` | `OpenCrawling Ingestion` | Dataset looked up (or created) by name |
| **Auto-Create Dataset** | `spring.opencrawling.output.doxis.auto-create-dataset` | `true` | Create the dataset when no dataset with that name exists |
| **Upload Content** | `spring.opencrawling.output.doxis.upload-content` | `true` | Store the original binary in the `document` cell |
| **Include Source Metadata** | `spring.opencrawling.output.doxis.include-source-metadata` | `true` | Store non-promoted metadata as JSON in `metadata_json` |
| **Apply Security ACLs** | `spring.opencrawling.output.doxis.apply-security-acls` | `true` | Store the OIS security model in the `acl` / `security_*` columns |
| **Conflict Resolution** | `spring.opencrawling.output.doxis.conflict-resolution` | `REPLACE` | `REPLACE` or `UPDATE_METADATA` |
| **Max Retries** | `spring.opencrawling.output.doxis.max-retries` | `3` | Retries for connection failures and `429`/`502`/`503`/`504` |
| **Timeout** | `spring.opencrawling.output.doxis.timeout-seconds` | `60` | HTTP request timeout |

To select this connector, set `spring.opencrawling.output.type=doxis`.

In the admin UI, a **Doxis AI.dp** output connector stores the same settings in its JSON configuration under these keys:
- `doxisBaseUrl`
- `doxisApiKey`
- `doxisDatasetId`
- `doxisDatasetName`
- `doxisAutoCreateDataset`
- `doxisUploadContent`
- `doxisIncludeSourceMetadata`
- `doxisApplySecurityAcls`
- `doxisConflictResolution`
- `doxisMaxRetries`
- `doxisTimeoutSeconds`

`JobController` resolves them per job.

```yaml
spring:
  opencrawling:
    output:
      type: doxis
      doxis:
        base-url: "https://dochorizon.klippa.com"
        api-key: "${DOXIS_API_KEY}"
        dataset-name: "OpenCrawling Ingestion"
        conflict-resolution: REPLACE
```

## Row Mapping

The dataset columns (see `DoxisConstants.COLUMNS`) and a document row as sent to `POST /rows`:

```json
{
  "external_id": "sharepoint-01ABCDEF9876",
  "document_id": "sharepoint-01ABCDEF9876",
  "record_type": "document",
  "uri": "sharepoint://contoso.sharepoint.com/items/01ABCDEF9876",
  "title": "Master Services Agreement 2026",
  "source_system": "sharepoint",
  "mime_type": "application/pdf",
  "content_length": 2097152,
  "last_modified": "2026-09-30T08:00:00Z",
  "ingested_at": "2026-10-01T10:15:00Z",
  "acl": "elena.weber,group:legal-counsel",
  "security_inheritance": true,
  "security_allowed_read": "elena.weber,group:legal-counsel",
  "security_denied_read": "contractors",
  "security_json": "{\"inheritanceEnabled\":true,\"permissions\":[...]}",
  "metadata_json": "{\"department\":\"Legal\"}",
  "document": {
    "data": "JVBERi0xLjcK...",
    "filename": "Master Services Agreement 2026.pdf",
    "content_type": "application/pdf"
  }
}
```

| Column | Data type | Document rows | Chunk rows (Kafka writer) |
| :--- | :--- | :--- | :--- |
| `external_id` | `text` | document id | chunk id |
| `document_id` | `text` | document id | document id |
| `record_type` | `text` | `document` | `chunk` |
| `uri`, `title`, `source_system`, `mime_type` | `text` | ✓ | ✓ |
| `content_length` | `int` | bytes of the binary | characters of the chunk |
| `last_modified`, `ingested_at` | `timestamp` | ✓ | ✓ |
| `acl`, `security_allowed_read`, `security_denied_read`, `security_json` | `text` | ✓ | ✓ |
| `security_inheritance` | `bool` | ✓ | ✓ |
| `metadata_json` | `text` | ✓ | ✓ |
| `chunk_text` | `text` | — | chunk text |
| `document` | `document` | original binary | — |

## Testing & Execution

### 1. Run Unit Tests
```bash
mvn test -pl oc-doxis-output-connector
```
`DoxisClientTest` runs the REST client against OkHttp `MockWebServer`, using response fixtures in `src/test/resources/doxis-mock-responses/` that follow the OpenAPI response schemas. It covers authentication, cursor pagination, dataset and column creation, row insert, search, patch and delete, error envelopes, and retry/backoff. `DoxisOutputConnectorTest`, `DoxisDatasetManagerTest` and `DoxisStoreWriterConsumerTest` cover:
- descriptor and ACL mapping
- `REPLACE` ordering
- `UPDATE_METADATA`
- tombstones
- dataset resolution
- the Kafka writer

### 2. Connection Check
With a configured connector, `oc connector check --name <connector-name> --type output` (or **Test Connection** in the admin UI) calls `GET /api/services/auth/v1/info`, and `GET /datasets/v3/datasets/{id}` when a dataset id is set.

## Known Limitations

- Doxis AI.dp is a hosted service, so there is no local container and no `scripts/test-doxis-*.sh` end-to-end script. Live verification needs an API key.
- The Doxis AI.dp Dataset API has no folder (*Akten*) hierarchy or ACL engine. The OIS security model is stored as row data for downstream enforcement; Doxis does not enforce it.
- Content is sent inline as base64 in the row-insert request, so very large files are bounded by the platform's request size limit (HTTP `413`).
- The decoupled Kafka writer stores chunk text, not the original binary: by the time a chunk reaches `opencrawling-embedded`, the claim-check object has usually been cleaned up. Use the direct `send()` path (`spring.opencrawling.output.type=doxis` on the crawler) to archive original files.
- Embedding vectors are not stored. Doxis AI.dp computes its own embeddings for document cells.
