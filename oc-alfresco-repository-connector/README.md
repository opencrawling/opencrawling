# OpenCrawling - Alfresco Content Services Repository Connector

The **Alfresco Content Services (ACS) Repository Connector** (`oc-alfresco-repository-connector`) crawls Alfresco Content Services (7.x / 23.x / 25.x / 26.x, Community & Enterprise) via the official Alfresco Core, Search, and Authentication REST APIs v1, emitting Open Ingestion Standard (OIS) documents and `DELETE` tombstones into OpenCrawling.

## Key Features

- **Three Crawl Modes (`crawl-mode`)**:
  - `folder` *(default)*: Hierarchical folder traversal with bounded Java 25 Virtual Thread parallelism, incremental skip-unchanged (before downloading `/content`), and orphan reconciliation.
  - `search_delta`: Incremental Search API delta crawling (`cm:modified:["<watermark>" TO MAX]` anchored to ACS's HTTP `Date` clock minus `clock-skew-seconds`), paired with Trashcan (`/deleted-nodes`) or Audit API deletion tracking and optional periodic full scans (`full-scan-interval`, `full-scan-window`, `full-scan-timezone`) to detect ACL-only changes, hard deletes, and out-of-scope moves.
  - `query`: Targeted AFTS, CMIS SQL, or Lucene query crawling via `/public/search/versions/1/search`.
- **Empty-Enumeration Guard**: A full enumeration returning 0 documents while documents are already indexed emits no vanished-document tombstones.
- **Enterprise Authentication (`auth-type`)**: `basic`, `ticket` (`POST /public/authentication/versions/1/tickets` with automatic 401 refresh and logout on disconnect), `bearer`, and `oauth2` (Keycloak / Alfresco Identity Service client-credentials grant or static bearer token).
- **Versions & Renditions**: `version-mode` (`current`, `latest_major`, `all` under `<nodeId>;v=<versionId>`) and `prefer-rendition` (e.g. `pdf` via Alfresco Transform Service, with primary-content fallback).
- **Selection Filters & Rich Metadata**: `site-ids`, `node-types`, `included-aspects`, `excluded-aspects`, `exclude-working-copies` (default `true`, skipping `cm:workingcopy`), `included-tags`, `excluded-tags`, `created-after` / `modified-after` (ISO date, instant, or period `P2Y`/`P90D`/`PT36H`), `excluded-name-patterns` (regex), exact/path-based `excluded-folders`, `mime-type-filter`, `max-documents`, multi-valued property preservation, `alfresco_path`, `alfresco_site`, `alfresco_tags`, `alfresco_comments`, `alfresco_web_url`, and `getSchema()`.
- **Zero-Trust Security & Policies**: Fail-closed ACL mapping unified across crawl and discovery, with configurable `everyone-public`, `everyone-identity`, `fallback-principals`, and `principal-format` (`raw` | `normalized`).
- **Production Resilience**: HTTP `429`/`502`/`503`/`504` and transient I/O retries honoring `Retry-After` with jittered exponential backoff, bounded `TempFileInputStream` binary spooling, cooperative `Flux` cancellation, and proper `HttpClient` cleanup.

## Configuration Parameters (`spring.opencrawling.connector.alfresco.*`)

| Spring Property | Config Key | Default | Description |
|---|---|---|---|
| `url` | `url` | `http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1` | Base URL of the Alfresco Core REST API v1 |
| `auth-type` | `authType` | `basic` | `basic`, `ticket`, `bearer`, or `oauth2` |
| `username` / `password` | `username` / `password` | `admin` / `admin` | Credentials for `basic` or `ticket` auth |
| `oauth2-token-url` | `oauth2TokenUrl` | — | OAuth2/OIDC token endpoint for `client_credentials` |
| `oauth2-client-id` / `oauth2-client-secret` / `oauth2-scope` | `oauth2ClientId` / `oauth2ClientSecret` / `oauth2Scope` | — | OAuth2 client credentials and optional scope |
| `oauth2-access-token` | `oauth2AccessToken` | — | Static Bearer/OAuth2 access token (`accessToken` alias) |
| `ticket` | `ticket` | — | Optional pre-issued Alfresco authentication ticket |
| `crawl-mode` | `crawlMode` | `folder` | `folder`, `search_delta`, or `query` |
| `root-folder-path` / `root-folder-id` | `rootFolderPath` / `rootFolderId` | `/` / — | Starting folder path or node UUID |
| `site-id` / `site-ids` | `siteId` / `siteIds` | — | Single or comma-separated Alfresco site short names |
| `include-subfolders` | `includeSubfolders` | `true` | Recurse into subfolders in `folder` mode |
| `excluded-folders` | `excludedFolders` | `Data Dictionary` | Exact folder names, node IDs, or paths to skip (`""` = none) |
| `search-query` / `query-language` | `searchQuery` / `queryLanguage` | `TYPE:'cm:content'` / `afts` | Query and language (`afts`, `cmis`, `lucene`) |
| `version-mode` | `versionMode` | `current` | `current`, `latest_major`, or `all` |
| `prefer-rendition` | `preferRendition` | — | Optional rendition ID (e.g. `pdf`) with primary fallback |
| `node-types` | `nodeTypes` | *(all)* | Comma-separated content types (e.g. `cm:content, my:invoice`) |
| `included-aspects` / `excluded-aspects` | `includedAspects` / `excludedAspects` | — | Comma-separated aspect filters |
| `exclude-working-copies` | `excludeWorkingCopies` | `true` | Skip nodes carrying `cm:workingcopy` |
| `included-tags` / `excluded-tags` | `includedTags` / `excludedTags` | — | Comma-separated tag filters |
| `created-after` / `modified-after` | `createdAfter` / `modifiedAfter` | — | ISO date (`2024-01-01`), instant, or period/duration (`P2Y`, `P90D`, `PT36H`) |
| `excluded-name-patterns` | `excludedNamePatterns` | — | Java regexes (newline- or comma-separated) excluding matching filenames |
| `mime-type-filter` | `mimeTypeFilter` | *(all)* | Comma-separated MIME types / wildcards (e.g. `application/pdf,image/*`) |
| `max-documents` | `maxDocuments` | `0` | Optional document cap (`0` = unlimited) |
| `include-content-stream` / `max-content-size-bytes` | `includeContentStream` / `maxContentSizeBytes` | `true` / `52428800` | Download binary content up to size cap (`0` = unlimited) |
| `include-tags` / `include-comments` | `includeTags` / `includeComments` | `false` / `false` | Fetch node tags (`alfresco_tags`) and comments (`alfresco_comments`) |
| `include-system-properties` / `property-prefix` | `includeSystemProperties` / `propertyPrefix` | `true` / `""` | Include `sys:*` properties and optional metadata key prefix |
| `web-app-url` | `webAppUrl` | — | Optional ACA / Digital Workspace base URL for `alfresco_web_url` |
| `include-acls` | `includeAcls` | `true` | Enforce Zero-Trust ACL mapping (`false` marks documents `public`) |
| `everyone-public` / `everyone-identity` | `everyonePublic` / `everyoneIdentity` | `true` / `""` | Map `GROUP_EVERYONE` to `public` or a specific group identity (empty = omit) |
| `fallback-principals` | `fallbackPrincipals` | — | Optional principals when permissions are unreadable (empty = fail-closed) |
| `principal-format` | `principalFormat` | `raw` | `raw` (`GROUP_FINANCE`) or `normalized` (`finance`) |
| `incremental-enabled` | `incrementalEnabled` | `true` | Enable crawl state, skip-unchanged, and deletion tombstones |
| `state-file` | `stateFile` | `data/alfresco-state` | JSON state file or directory |
| `deletion-tracking` | `deletionTracking` | `reconcile` | `reconcile` (full reconcile + Trashcan), `trashcan`, `audit`, or `none` |
| `clock-skew-seconds` | `clockSkewSeconds` | `300` | Watermark overlap margin (seconds) absorbing Solr indexing lag |
| `full-scan-interval` / `full-scan-window` / `full-scan-timezone` | `fullScanInterval` / `fullScanWindow` / `fullScanTimezone` | — / — / `UTC` | Periodic full enumeration inside `search_delta` (e.g. `P1D`, `01:00-05:00`, `Europe/Berlin`) |
| `audit-application` | `auditApplication` | `alfresco-access` | Alfresco audit application for `deletion-tracking: audit` |
| `batch-size` / `parallelism` | `batchSize` / `parallelism` | `100` / `4` | Page size (`1..1000`) and bounded Virtual Thread concurrency |
| `timeout-seconds` / `max-retries` / `initial-backoff-ms` | `timeoutSeconds` / `maxRetries` / `initialBackoffMs` | `30` / `3` / `500` | HTTP timeout and retry backoff settings |
