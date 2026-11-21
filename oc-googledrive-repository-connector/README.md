# OpenCrawling - Google Drive Repository Connector

The Google Drive Repository Connector (`oc-googledrive-repository-connector`) crawls **Google Drive (My Drive)** and **Google Workspace Shared Drives** through the official **Google Drive REST API v3** (`https://www.googleapis.com/drive/v3`). It streams binaries and exported Google Workspace documents into OpenCrawling as Open Ingestion Standard (OIS) documents with rich metadata, breadcrumbs, and Zero-Trust Google Workspace ACLs (issue #339).

## Prerequisites

1. **Google Cloud Service Account (recommended)** with the **Google Drive API** enabled in your Google Cloud project:
   - Create a Service Account and download its JSON key file (or paste the inline JSON into the Admin UI).
   - **No Domain-Wide Delegation is required**: simply share the target folder(s) or Shared Drive with the Service Account email (Viewer / `drive.readonly`).
   - Optionally set `delegated-user` (Google Workspace user email, JWT `sub` claim) only if you want Domain-Wide Delegation to impersonate a Workspace user.
   - Alternatively, authenticate via **OAuth2 Refresh Token** (`auth-type: oauth2`) with `client-id`, `client-secret`, and `refresh-token`.
2. **Scope:** uses `https://www.googleapis.com/auth/drive.readonly` by default. The connector is strictly read-only.

Use the Admin UI's **Test Connection** button or `oc connector check --name <name> --type repository` to validate credentials, Shared Drive access, and configured root folders before running a job.

## Features

1. **Flexible Scope & Shared Drives**: Scope by whole My Drive, a Shared Drive (`drive-id`), or one or more explicit folder IDs (`root-folder-ids` / `root-folder-id`) with optional `include-subfolders` (`true` by default). Sends `supportsAllDrives=true` and `includeItemsFromAllDrives=true` when `include-shared-drives=true`.
2. **Native Google Workspace Exports & MIME Exclusions**:
   - Exports Google Docs as **HTML (`text/html`)** by default so headings survive for structure-aware chunking (#385).
   - Exports Google Slides as **plain text (`text/plain`)** by default.
   - Excludes Google Sheets (`application/vnd.google-apps.spreadsheet`) and Google Forms (`application/vnd.google-apps.form`) by default via `excluded-mime-types` (clear the list to include spreadsheets).
   - Streams standard binary files via `GET /drive/v3/files/{fileId}?alt=media` and exports via `GET /drive/v3/files/{fileId}/export` directly to temporary files (`TempFileInputStream`) with constant heap usage.
3. **Oversized File Reporting (`TOO_LARGE`)**: Files exceeding `max-file-size-bytes` (50 MB default) or Google's 10 MB `/export` cap are explicitly reported as `TOO_LARGE` (`SKIPPED` with reason `TOO_LARGE` in execution ledgers and telemetry) rather than silently skipped.
4. **Rich User-Facing Metadata & Version-Key Skipping**:
   - Maps `webViewLink` to `url` / `googledrive.webViewLink`, original Drive file name to `title`, and parent folder hierarchy to `breadcrumbs` and `path`.
   - Computes a composite `googledrive.versionKey` from Drive `version`, `headRevisionId`, and `md5Checksum` so unchanged files are skipped without downloading content.
5. **Incremental Changes API, Control-Plane State & Periodic Full Reconciliation**:
   - Uses `/drive/v3/changes/startPageToken` and `/drive/v3/changes` to sync creations, edits, renames, moves out of scope, trashed items, and deletions (including cascading folder tombstones).
   - Stores the Changes API page token and known item state in OpenCrawling's shared `ControlPlaneStore` (`oc_setting` in JDBC mode) so scheduled runs execute safely on any replica without local file divergence.
   - Supports periodic full reconciliation (`full-scan-interval`, `full-scan-window`, `full-scan-timezone`) protected by an **empty-listing guard** that prevents mass tombstoning if an API outage or revoked permission returns 0 files.
6. **Zero-Trust Security & Pre-Crawl Discovery**:
   - Maps Drive `user`, `group`, `domain`, and opt-in `anyone` (`allow-anyone-public=false` by default) permissions into OIS `SecurityConfig`, failing closed whenever permissions cannot be resolved.
   - Implements `DiscoverableRepositoryConnector` (`explore`, `dryRun`, `crawlStart`) and `DocumentFetchingRepositoryConnector` (`fetchDocument`) with page-by-page `VisibilityFilter` enforcement.

## Configuration Properties

Configure via Spring properties under `spring.opencrawling.connector.googledrive.*` (with `spring.opencrawling.connector.type=googledrive`) or via `connectors.json` / Admin UI (class `org.opencrawling.googledrive.GoogleDriveRepositoryConnector`, camelCase keys).

| Spring Property | `connectors.json` Key | Default | Description |
|---|---|---|---|
| `api-base-url` | `apiBaseUrl` | `https://www.googleapis.com/drive/v3` | Google Drive API v3 base URL (overridable for local mock testing) |
| `token-url` | `tokenUrl` | `https://oauth2.googleapis.com/token` | OAuth2 token endpoint URL |
| `auth-type` | `authType` | `service_account` | `service_account`, `oauth2`, or `bearer` |
| `service-account-json` | `serviceAccountJson` | *(empty)* | Inline Service Account JSON or path to key file |
| `delegated-user` | `delegatedUser` | *(empty)* | Optional Google Workspace user email for Domain-Wide Delegation (`sub`) |
| `scope` | `scope` | `https://www.googleapis.com/auth/drive.readonly` | OAuth2 scope requested for Service Account JWTs |
| `client-id` / `client-secret` / `refresh-token` | `clientId` / `clientSecret` / `refreshToken` | *(empty)* | OAuth2 credentials when `auth-type: oauth2` |
| `drive-id` | `driveId` | *(empty)* | Shared Drive ID (empty for My Drive) |
| `root-folder-id` | `rootFolderId` | `root` | Single starting folder ID |
| `root-folder-ids` | `rootFolderIds` | *(empty)* | Comma-separated folder IDs to scope the crawl |
| `include-subfolders` | `includeSubfolders` | `true` | Descend recursively into sub-folders |
| `include-shared-drives` | `includeSharedDrives` | `true` | Pass `supportsAllDrives=true` & `includeItemsFromAllDrives=true` |
| `follow-shortcuts` | `followShortcuts` | `false` | Resolve `application/vnd.google-apps.shortcut` targets |
| `crawl-mode` | `crawlMode` | `changes` | `changes` (Changes API incremental) or `tree` (folder traversal) |
| `state-file` | `stateFile` | *(empty)* | Optional local JSON path override (leave empty to use shared Control Plane store) |
| `full-scan-interval` | `fullScanInterval` | *(empty; `P7D` in UI default)* | Periodic full reconciliation interval (e.g. `P7D`, `PT24H`) |
| `full-scan-window` | `fullScanWindow` | *(empty)* | Optional 24h maintenance window (e.g. `01:00-05:00`) |
| `full-scan-timezone` | `fullScanTimezone` | `UTC` | IANA timezone for `full-scan-window` |
| `export-google-docs` | `exportGoogleDocs` | `true` | Export native Google Workspace files |
| `export-doc-mime-type` | `exportDocMimeType` | `text/html` | Target MIME type for Google Docs |
| `export-slides-mime-type` | `exportSlidesMimeType` | `text/plain` | Target MIME type for Google Slides |
| `export-sheet-mime-type` | `exportSheetMimeType` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | Target MIME type for Google Sheets (when not excluded) |
| `export-drawing-mime-type` | `exportDrawingMimeType` | `application/pdf` | Target MIME type for Google Drawings |
| `excluded-mime-types` | `excludedMimeTypes` | `application/vnd.google-apps.spreadsheet,application/vnd.google-apps.form` | Comma-separated MIME types to exclude (`none` or empty to exclude nothing) |
| `included-mime-types` | `includedMimeTypes` | *(empty)* | Optional allow-list of MIME types |
| `max-file-size-bytes` | `maxFileSizeBytes` | `52428800` | Maximum content size (50 MB); larger files are reported as `TOO_LARGE` |
| `include-custom-properties` | `includeCustomProperties` | `true` | Emit Drive `properties` / `appProperties` as `googledrive_prop_<key>` |
| `include-acls` | `includeAcls` | `true` | Fetch `/permissions` and enforce Zero-Trust OIS ACLs |
| `allow-anyone-public` | `allowAnyonePublic` | `false` | Whether `type=anyone` permissions map to public read access |
| `page-size` | `pageSize` | `100` | Drive API page size (1–1000) |
| `parallelism` | `parallelism` | `4` | Virtual-thread concurrency for file processing |
| `max-retries` | `maxRetries` | `5` | Retries on 429, Drive 403 rate limits, 5xx, and transient I/O errors |
| `initial-backoff-ms` | `initialBackoffMs` | `1000` | Initial exponential backoff in milliseconds |
| `timeout-seconds` | `timeoutSeconds` | `30` | HTTP request timeout in seconds |

## Local Testing

```bash
# Run unit tests + embedded HTTP mock tests
mvn test -pl oc-googledrive-repository-connector -am

# Run full standalone connector suite against the Python Google Drive API v3 emulator
./scripts/test-googledrive-connector.sh

# Run full decoupled Docker Compose pipeline test (Mock -> Crawler -> Kafka -> Tika -> Ollama -> pgvector -> MCP)
./scripts/test-googledrive-decoupled.sh --local
```
