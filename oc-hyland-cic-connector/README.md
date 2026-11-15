# OpenCrawling - Hyland Content Innovation Cloud (CIC) Platform Connector

The Hyland Content Innovation Cloud (CIC) Platform Connector (`oc-hyland-cic-connector`) provides cloud-native, bi-directional integration with the **Hyland Experience Platform (HXP)** content microservices. It enables enterprise content discovery, Zero-Trust Access Control List (ACL) security synchronization, high-throughput ingestion into modern AI & RAG pipelines, dynamic job targeting, and direct archiving into HXP repositories (Issue #129).

> **Enterprise Notice:** This connector interacts with Hyland Content Innovation Cloud (HXP) REST services. OpenCrawling does not bundle proprietary Hyland software, runtime components, or tenant credentials. Connectivity requires an active Hyland Cloud subscription, valid tenant authorization, and OAuth2 credentials provisioned via the Hyland Identity Provider (IdP).

---

## Architecture & Capabilities

```
+-----------------------------------------------------------------------------------+
|                           OpenCrawling Runtime                                     |
|                                                                                   |
|  +-------------------------------------+   +------------------------------------+ |
|  |     HylandCicRepositoryConnector    |   |       HylandCicOutputConnector     | |
|  |  (DocumentFetching, Discoverable)   |   |           (OutputConnector)        | |
|  +-------------------------------------+   +------------------------------------+ |
|                     |                                         |                   |
|                     |                  +----------------------+                   |
|                     v                  v                                          |
|            +------------------------------------+                                 |
|            |          HylandCicClient           |                                 |
|            |  (OAuth2 Token Exchange & Caching) |                                 |
|            +------------------------------------+                                 |
+------------------------------|----------------------------------------------------+
                               | HTTPS (TLS 1.3)
                               v
+-----------------------------------------------------------------------------------+
|               Hyland Content Innovation Cloud (HXP Microservices)                 |
|                                                                                   |
|  +------------------------+  +------------------------+  +---------------------+  |
|  |   Hyland IdP (OAuth2)  |  |  Content REST Services |  | Document/Dossier API|  |
|  |   /connect/token       |  |  /api/content/folders  |  | /api/content/docs   |  |
|  +------------------------+  +------------------------+  +---------------------+  |
+-----------------------------------------------------------------------------------+
```

### 1. Repository Connector (`HylandCicRepositoryConnector`)
- **Pre-Crawl Discovery (`explore`)**: Breadth-first tree traversal discovering folder structures and document hierarchies up to configurable depth and node limits without downloading binary payloads.
- **Dry-Run Estimation (`dryRun`)**: Accurately previews document volume, collects samples, and estimates ingestion runtimes for both folder scopes and search queries.
- **Scope Targeting (`crawlStart`)**: Validates start folder references against configured scope and live permissions, enforcing strict anti-probing defense.
- **Single-Document Fetching (`fetchDocument`)**: Allows AI agents and workflows to fetch individual documents by UUID while strictly adhering to connector boundaries and visibility filters.
- **Java 25 Virtual Threads Reactive Streaming**: Emits document streams via non-blocking Project Reactor `Flux<RepositoryDocument>` driven by lightweight virtual threads.
- **Zero-Trust ACL Mapping**: Translates HXP Access Control Entries (ACEs) into Open Ingestion Standard (OIS) `SecurityConfig` rules with tenant-aware principal qualification.

### 2. Output Connector (`HylandCicOutputConnector`)
- **Enterprise Archival & Migration**: Ingests, versions, and archives crawled documents into HXP target folders/dossiers via multipart REST uploads.
- **Conflict Resolution**: Supports `create-version` (appends new document version), `overwrite` (replaces current version), and `skip` (preserves existing document).
- **Tombstone & Lifecycle Actions**: Deletes or archives records when downstream `DocumentAction.DELETE` events are received.

---

## Configuration Properties Reference

Properties can be configured via `application.yml`, Spring Boot environment variables (`SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_*`), or through the OpenCrawling Admin UI:

| Property (YAML / Map) | Spring Environment Variable | Default | Description |
|---|---|---|---|
| `base-url` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_BASE_URL` | `https://api.content.hylandcloud.com` | Base URL of Hyland Content REST API |
| `idp-token-url` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_IDP_TOKEN_URL` | `https://identity.hylandcloud.com/connect/token` | Hyland IdP OAuth2 Token Endpoint |
| `client-id` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_CLIENT_ID` | `""` | OAuth2 Client ID |
| `client-secret` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_CLIENT_SECRET` | `""` | OAuth2 Client Secret |
| `scopes` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_SCOPES` | `hxp hxp.integrations hxp.content` | Scopes requested during token exchange |
| `bearer-token` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_BEARER_TOKEN` | `""` | Optional direct token (bypasses IdP exchange) |
| `tenant-id` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_TENANT_ID` | `""` | HXP Tenant ID (sent in `X-Tenant-Id` header) |
| `root-folder-path` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_ROOT_FOLDER_PATH` | `/` | Root folder path in HXP to crawl |
| `root-folder-id` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_ROOT_FOLDER_ID` | `""` | Root folder UUID (overrides path if specified) |
| `content-types` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_CONTENT_TYPES` | `[]` | Comma-separated list restricting crawl to specific types |
| `include-subfolders` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_INCLUDE_SUBFOLDERS` | `true` | Whether to recursively descend into child folders |
| `excluded-folder-paths` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_EXCLUDED_FOLDER_PATHS` | `[]` | Comma-separated paths excluded from traversal |
| `query` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_QUERY` | `""` | Optional search query (overrides folder crawl) |
| `batch-size` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_BATCH_SIZE` | `100` | Paged fetch batch size |
| `include-acls` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_INCLUDE_ACLS` | `true` | Extract Zero-Trust ACLs and permission rules |
| `include-content-stream`| `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_INCLUDE_CONTENT_STREAM` | `true` | Download and stream document binaries |
| `max-content-size-bytes`| `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_MAX_CONTENT_SIZE_BYTES` | `52428800` (50MB) | Max binary size permitted for download |
| `connection-timeout-seconds` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_CONNECTION_TIMEOUT_SECONDS` | `30` | HTTP connection & request timeout |
| `max-retries` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_MAX_RETRIES` | `5` | Maximum retry attempts for HTTP 429 and 5xx |
| `target-folder-path` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_TARGET_FOLDER_PATH` | `/Enterprise_Ingestion/Automated_Archive` | Output target folder path |
| `target-folder-id` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_TARGET_FOLDER_ID` | `""` | Output target folder UUID (overrides path) |
| `default-content-type` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_DEFAULT_CONTENT_TYPE` | `ArchivedDocument` | Content type applied on document upload |
| `conflict-resolution` | `SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_CONFLICT_RESOLUTION` | `create-version` | Conflict policy: `create-version`, `overwrite`, `skip` |

---

## Setting Environment Variables

Depending on your deployment topology (local standalone process, container cluster, integration test script, or Kubernetes), configure the environment variables as follows:

### 1. Local Terminal / Shell Session
Export the Spring Boot relaxed environment variables directly in your bash or zsh shell before launching OpenCrawling:

```bash
# Core Endpoint & Authentication
export SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_BASE_URL="https://content.my-tenant.hyland.cloud"
export SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_IDP_TOKEN_URL="https://identity.my-tenant.hyland.cloud/oauth/token"
export SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_CLIENT_ID="opencrawling-service-client"
export SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_CLIENT_SECRET="sec_0192837465abcdef"
export SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_TENANT_ID="tenant-enterprise-01"

# Crawl Scope & Behavior
export SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_ROOT_FOLDER_PATH="/Finance/Invoices/2026"
export SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_INCLUDE_SUBFOLDERS="true"
export SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_BATCH_SIZE="100"

# Output Connector Target
export SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_TARGET_FOLDER_PATH="/Archive/AI_Processed"
export SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_CONFLICT_RESOLUTION="create-version"
```

### 2. Decoupled Integration Test Script (`test-hyland-cic-decoupled.sh`)
The automated integration test script [`scripts/test-hyland-cic-decoupled.sh`](../scripts/test-hyland-cic-decoupled.sh) accepts short-form `HYLAND_CIC_*` environment variables. You can pass them inline to test against a live HXP tenant:

```bash
HYLAND_CIC_BASE_URL="https://content.my-tenant.hyland.cloud" \
HYLAND_CIC_TOKEN_URL="https://identity.my-tenant.hyland.cloud/oauth/token" \
HYLAND_CIC_CLIENT_ID="opencrawling-service-client" \
HYLAND_CIC_CLIENT_SECRET="sec_0192837465abcdef" \
HYLAND_CIC_TENANT_ID="tenant-enterprise-01" \
HYLAND_CIC_REPOSITORY_ID="repo-finance" \
./scripts/test-hyland-cic-decoupled.sh
```

> **Note:** If no live cloud credentials are provided, `test-hyland-cic-decoupled.sh` automatically falls back to the hermetic embedded MockWebServer test harness, verifying 100% of connector capabilities offline.

### 3. Docker Compose & `.env` File
For multi-container deployments using [`docker-compose-decoupled-with-hyland-cic.yml`](docker/docker-compose-decoupled-with-hyland-cic.yml), create or edit a `.env` file in the project directory:

```env
# .env file
HYLAND_CIC_BASE_URL=https://content.my-tenant.hyland.cloud
HYLAND_CIC_TOKEN_URL=https://identity.my-tenant.hyland.cloud/oauth/token
HYLAND_CIC_CLIENT_ID=opencrawling-service-client
HYLAND_CIC_CLIENT_SECRET=sec_0192837465abcdef
HYLAND_CIC_TENANT_ID=tenant-enterprise-01
HYLAND_CIC_REPOSITORY_ID=repo-finance
```

Then start the stack:
```bash
docker compose -f oc-hyland-cic-connector/docker/docker-compose-decoupled-with-hyland-cic.yml up -d
```

### 4. Kubernetes Deployment (ConfigMap & Secret)
In production Kubernetes environments, store non-sensitive configuration in a `ConfigMap` and credentials in a `Secret`:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: hyland-cic-credentials
type: Opaque
stringData:
  SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_CLIENT_SECRET: "sec_0192837465abcdef"
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: hyland-cic-config
data:
  SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_BASE_URL: "https://content.my-tenant.hyland.cloud"
  SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_IDP_TOKEN_URL: "https://identity.my-tenant.hyland.cloud/oauth/token"
  SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_CLIENT_ID: "opencrawling-service-client"
  SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_TENANT_ID: "tenant-enterprise-01"
  SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_ROOT_FOLDER_PATH: "/Enterprise_Content"
  SPRING_OPENCRAWLING_CONNECTOR_HYLAND_CIC_BATCH_SIZE: "100"
```

Reference them in your OpenCrawling Pod or Deployment specification:

```yaml
spec:
  containers:
    - name: opencrawling-runtime
      image: ghcr.io/opencrawling/oc-runtime:latest
      envFrom:
        - configMapRef:
            name: hyland-cic-config
        - secretRef:
            name: hyland-cic-credentials
```

### 5. `application.yml` Example
You can also configure defaults directly in `application.yml` using property placeholders with environment variable fallbacks:

```yaml
spring:
  opencrawling:
    connector:
      hyland-cic:
        base-url: ${HYLAND_CIC_BASE_URL:https://api.content.hylandcloud.com}
        idp-token-url: ${HYLAND_CIC_TOKEN_URL:https://identity.hylandcloud.com/connect/token}
        client-id: ${HYLAND_CIC_CLIENT_ID:}
        client-secret: ${HYLAND_CIC_CLIENT_SECRET:}
        tenant-id: ${HYLAND_CIC_TENANT_ID:}
        root-folder-path: ${HYLAND_CIC_ROOT_FOLDER_PATH:/}
        include-subfolders: true
        batch-size: 100
        include-acls: true
        include-content-stream: true
        target-folder-path: /Archive/AI_Processed
        conflict-resolution: create-version
```

---

## HXP REST Endpoints Reference

| Operation | Method & Endpoint | Connector Purpose |
|---|---|---|
| Health Check | `GET /api/content/health` | Connector `connect()` & readiness check |
| IdP Token Exchange | `POST {idpTokenUrl}` | OAuth2 Client Credentials authentication |
| Folder by Path | `GET /api/content/folders?path={path}` | Resolve starting and target folders |
| Folder by ID | `GET /api/content/folders/{id}` | Inspect folder metadata and permissions |
| Folder Children | `GET /api/content/folders/{id}/items?offset&limit` | Tree exploration, discovery, and folder crawls |
| Content Query | `POST /api/content/query` | Search query crawling and dry runs |
| Document Metadata | `GET /api/content/documents/{id}` | Single document fetching and metadata mapping |
| Download Content | `GET /api/content/documents/{id}/content` | Binary payload streaming |
| Upload Document | `POST /api/content/documents` | Output connector multipart document ingestion |
| Delete Document | `DELETE /api/content/documents/{id}` | Downstream tombstone / deletion processing |

---

## Testing & Verification

### Run Automated Unit & Mock Tests (Offline)
Execute the complete Maven test suite (36 tests) using the isolated MockWebServer fixtures:

```bash
mvn test -pl oc-core,oc-hyland-cic-connector -am
```

The module includes comprehensive unit and integration tests covering:
- **`HylandCicPropertiesTest`**: Configuration parsing, environment binding, and defaults.
- **`HylandCicSecurityMapperTest`**: ACE translation, permission normalization, deny-rule precedence, and tenant qualification.
- **`HylandCicClientTest`**: MockWebServer-based tests for OAuth2 token exchange, token caching, expiration refresh, folder paging, query execution, content download, multipart upload, and automatic backoff on HTTP 429 rate limits.
- **`HylandCicRepositoryConnectorTest`**: Discovery tree exploration, dry run tallying, scope targeting, anti-probing validation, single document fetch, and virtual-thread reactive scan.
- **`HylandCicOutputConnectorTest`**: Upsert multipart uploads, deletion handling, and target folder resolution.

### Run Decoupled Integration Test Script
To run the full end-to-end multi-service integration test suite (validating Kafka, Redis, PostgreSQL/pgvector, Ollama, Apache Ozone, and the MCP Server):

```bash
./scripts/test-hyland-cic-decoupled.sh
```
