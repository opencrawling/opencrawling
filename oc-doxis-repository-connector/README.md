# OpenCrawling - Doxis Repository Connector

The Doxis Repository Connector (`oc-doxis-repository-connector`) crawls a **Doxis 4** DMS repository (SER Group) through the
Doxis CSB REST API (`/restws/publicws/rest/api/v1`, contract 14.4.1) and streams the documents into the OpenCrawling
pipeline as Open Ingestion Standard (OIS) documents, with their descriptors, content and permissions. It uses the shared
Doxis REST client in `oc-doxis-client` (issue #122).

> **The connector needs a licensed, running Doxis installation. It does not include Doxis, and it does not include any
> Doxis licence.** OpenCrawling only talks to a Doxis CSB that you already operate and are licensed to use. Doxis is a
> product of SER Group, and its licence terms are agreed between you and SER.

## Prerequisites

Make sure all of the following are in place before you configure the connector or start a crawl. If any of them is
missing, the connection check or the crawl fails.

1. **A valid Doxis licence.**
   - A valid, unexpired Doxis 4 licence for the CSB you connect to, with the CSB REST API (`restws`) enabled.
   - The licence must allow **API / technical-user sessions** for the connector. CSB counts these sessions against the
     licence. A crawl holds exactly one session for its whole run and logs out at the end; the connection check opens and
     closes one more. Plan session capacity with your Doxis administrator.
   - Using Doxis through this connector must be allowed under your agreement with SER, for example for automated reading
     or indexing of content into a search or AI system. Check with SER or your Doxis partner if you're unsure.
2. **A reachable Doxis CSB.** Doxis 4 CSB 14.4 or later with its public REST API at
   `<scheme>://<csb-host>:<port>/restws/publicws/rest/api/v1`, network access from the OpenCrawling runtime (firewalls,
   proxies, TLS certificates), and the **customer (tenant) name** the repository belongs to.
3. **A technical user and role** (`role`, default `admins`) with at least these rights:

   | Needed for | Doxis right |
   |---|---|
   | Logging in | Login allowed. No "change password at next login" flag, which blocks API sign-in |
   | Searching | `searchInContentRepository` on the DMS repository |
   | Reading documents | Read and `VIEW_DOCUMENT_CONTENTS` on the document classes to crawl, and read on their descriptors |
   | Folder mode and e-file ACLs | Read and `VIEW_FOLDER_CONTENTS` on the e-file (record) classes |
   | Mapping ACLs to users and groups | Read access to users, groups and roles (`/users`, `/groups`, `/roles`) |

   The connector only **reads**: it never creates, changes or deletes anything in Doxis. A document the user can't see isn't
   crawled. When the user lacks read rights on a class, Doxis 14.4 answers without the document's representations; the
   connector then logs a warning.
4. **Repository configuration.** The DMS repository to crawl (`repository-id`) must exist. CQL filters (`search-query`) use
   descriptor short names (for example `OBJECTNUMBER2` for `ObjectNumberExternal`). Folder mode needs the e-file UUID to
   start from (`root-folder-id`).
5. **OpenCrawling side.** A running OpenCrawling runtime (Java 25). The password, session ticket and OAuth2 client secret
   are secrets: supply them through the Admin UI, environment variables or your secret store, never through source control.

Use the Admin UI's **Test Connection** button, or `oc connector check --name <name> --type repository`, to check items 1–4
before the first crawl. Both log in, read the repository, and log out.

## Features

1. **CQL search crawl** (`crawl-mode: search`): `SELECT * FROM <repository short name> [WHERE <search-query>]`, paged through
   `GET /documents/searchResults/{searchId}` with 1-based offsets. The open result set is always closed (`DELETE`), and the
   listing ends on the number of distinct documents seen, so a repeated hit can neither loop nor hide the last document.
2. **E-file crawl** (`crawl-mode: folder`): an e-file (*Akte*) with its folder nodes, optionally descending into child nodes
   and sub-e-files. A document listed in several nodes is emitted once.
3. **Authentication**: `basic` (user name and password, `POST /login`), `ticket` (a CSB session ticket,
   `POST /loginBySessionTicket`) or `oauth2` (an OIDC/OAuth2 access token, `POST /loginOIDCWithAccessToken`). The token is
   given directly or obtained with the client-credentials grant and refreshed before it expires. An expired CSB session is
   re-opened once, transparently. Passwords, tickets, secrets and tokens are never logged.
4. **Versions**: the current version (`latest_only`) or every version (`all_versions`; older versions get their own id).
5. **Descriptors** under their Doxis names (`ObjectName`, `ObjectDate`, …) or with a configurable prefix
   (`doxis_desc_ObjectName`), with values **normalised by data type** (dates, date-times, integers, decimals, booleans).
   A value that changed keeps its original under `doxis.raw.<name>`.
6. **Content**: the default representation's first content object is downloaded to a temporary file (an atomic `.part`
   file per attempt) and handed to the claim-check store; the file is deleted when the stream is closed. Content-link
   documents (binaries stored outside Doxis) are emitted with their link in `doxis.contentLink` and no content.
7. **Security**: the document's ACEs merged with those of its e-file (pass-down permissions), mapped to OIS permissions.
   Documents without instance ACEs get the configured `fallback-principals`, or no reader at all (deny by default).
   Optional **tenant isolation** qualifies every reader with the customer (`DX4/Legal`).
8. **Deletes**: logically removed documents become OIS `DELETE` tombstones. With `incremental: true`, documents that
   disappeared since the previous run (physical deletes) get tombstones too.
9. **Incremental crawls**: unchanged documents are skipped without any per-document call (see below).
10. **One CSB session per scan**, always logged out, with bounded parallelism (Java 25 `StructuredTaskScope` +
    `ObservabilityTask`), `Retry-After`-aware retries on 429 and 502–504, and an optional `max-documents` cap for test runs.

## Configuration Properties

The connector is configured either through Spring properties (`spring.opencrawling.connector.type=doxis` plus the keys
below under `spring.opencrawling.connector.doxis.`; YAML lists are accepted for list keys) or through a connector
configuration in the Admin UI / `connectors.json` (class `org.opencrawling.doxis.DoxisRepositoryConnector`, camelCase keys).

| Spring property (`spring.opencrawling.connector.doxis.*`) | `connectors.json` key | Default | Description |
|---|---|---|---|
| `url` | `url` | `http://localhost:8080/restws/publicws/rest/api/v1` | CSB REST base URL |
| `auth-type` | `authType` | `basic` | `basic`, `ticket` or `oauth2` |
| `customer-name` | `customerName` | — | Required. The CSB customer (tenant), e.g. `DX4` |
| `username`, `password` | `username`, `password` | — | Required for `basic` |
| `session-ticket` | `sessionTicket` | — | Required for `ticket` |
| `oauth2.token-url`, `oauth2.client-id`, `oauth2.client-secret`, `oauth2.scope` | `oauth2TokenUrl`, `oauth2ClientId`, `oauth2ClientSecret`, `oauth2Scope` | — | For `oauth2`: the identity provider's token endpoint and client credentials (client-credentials grant). The CSB customer must be configured for that identity provider |
| `oauth2.access-token` | `oauth2AccessToken` | — | For `oauth2`: a ready access token, instead of the client credentials |
| `role` | `role` | `admins` | Role the session uses |
| `repository-id` | `repositoryId` | — | Required. DMS repository (name or UUID); CQL uses its short name |
| `crawl-mode` | `crawlMode` | `search` | `search` or `folder` |
| `document-classes` | `documentClasses` | — | Document classes to keep (names or UUIDs) |
| `search-query` | `searchQuery` | — | CQL condition, e.g. `OBJECTNAME LIKE 'Contract*'` (short names; wildcard `*` with `LIKE`) |
| `root-folder-id` | `rootFolderId` | — | E-file UUID. Required in folder mode |
| `include-subfolders` | `includeSubfolders` | `true` | Folder mode: descend into child nodes and sub-e-files |
| `version-mode` | `versionMode` | `latest_only` | `latest_only` or `all_versions` |
| `include-content-stream` | `includeContentStream` | `true` | Download binaries |
| `max-content-size-bytes` | `maxContentSizeBytes` | `52428800` | Largest binary to download (larger ones: metadata only) |
| `include-descriptors` | `includeDescriptors` | `true` | Emit descriptor values |
| `descriptor-prefix` | `descriptorPrefix` | — | Prefix for descriptor keys, e.g. `doxis_desc_` |
| `include-acls` | `includeAcls` | `true` | Read document and e-file ACEs |
| `fallback-principals` | `fallbackPrincipals` | — | Readers for documents without instance ACEs (`group:X`, `user:Y`, `everybody`), e.g. `group:Legal` |
| `tenant-isolation` | `tenantIsolation` | `false` | Qualify every reader with the customer (`DX4/Legal`); `everybody` becomes `DX4/everybody` |
| `emit-logical-deletes` | `emitLogicalDeletes` | `true` | Search `ANY_OBJECTS` and send tombstones for logically removed documents |
| `incremental` | `incremental` | `false` | Search mode: skip unchanged documents and detect deletes between runs |
| `state-directory` | `stateDirectory` | `data/doxis-state` | Where the incremental state is kept (one JSON file per customer and repository) |
| `modified-since`, `modified-since-attribute` | `modifiedSince`, `modifiedSinceAttribute` | — / `DXE_MODDATE` | Experimental CQL lower bound on a date attribute. Leave empty unless your CSB maintains that attribute |
| `batch-size` | `batchSize` | `100` | Hits per search page |
| `parallelism` | `parallelism` | `2` | Documents processed at once. Keep it low on shared systems |
| `timeout-seconds` | `timeoutSeconds` | `120` | HTTP timeout. A busy CSB can take minutes to log in |
| `max-retries` | `maxRetries` | `2` | Retries on 429 (honouring `Retry-After`), 502–504 and refused connections |
| `max-documents` | `maxDocuments` | `0` | Stop after N documents (0 = no limit; for test runs) |

A job path that starts with `SELECT ` replaces the configured search (that run is not incremental). In folder mode, an
e-file UUID as the job path replaces `root-folder-id`.

## Example `application.yml`

```yaml
spring:
  opencrawling:
    repository-connector-type: doxis
    connector:
      type: doxis
      doxis:
        url: "https://csb.example.com/restws/publicws/rest/api/v1"
        customer-name: "DX4"
        auth-type: "basic"
        username: "crawler"
        password: "${DOXIS_PASSWORD}"
        role: "admins"
        repository-id: "DB1"
        crawl-mode: "search"
        search-query: "OBJECTNAME LIKE 'Contract*'"
        document-classes:
          - "ContractDocument"
        version-mode: "latest_only"
        fallback-principals: "group:Legal"
        incremental: true
        batch-size: 100
        parallelism: 2
```

For OAuth2, replace the user name and password with:

```yaml
        auth-type: "oauth2"
        oauth2:
          token-url: "https://auth.example.com/oauth/token"
          client-id: "opencrawling-client"
          client-secret: "${DOXIS_CLIENT_SECRET}"
          scope: "doxis.read"
```

## OIS Document Mapping

A crawled document (`action: UPSERT`):

```json
{
  "id": "doxis://DX4/DB1/documents/3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f",
  "action": "UPSERT",
  "metadata": {
    "name": ["CON-2023-0120.pdf"],
    "title": ["Consulting Agreement (2023)"],
    "mimeType": ["application/pdf"],
    "ObjectName": ["Consulting Agreement (2023)"],
    "ObjectNumberExternal": ["contracts-2026/CON-2023-0120"],
    "ObjectDate": ["2023-02-22"],
    "doxis.raw.ObjectDate": ["20230222"],
    "ObjectAuthors": ["Facilities Management", "Legal Ops"],
    "doxis.documentId": ["3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f"],
    "doxis.documentClass": ["ContractDocument"],
    "doxis.documentClassId": ["7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f"],
    "doxis.version": ["1"],
    "doxis.isLatestVersion": ["true"],
    "doxis.createdDate": ["2026-10-05T20:45:51.491Z"],
    "doxis.createdBy": ["crawler"],
    "doxis.modifiedDate": ["2026-10-05T20:45:51.491Z"],
    "doxis.modifiedBy": ["crawler"],
    "doxis.parentFolderId": ["9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4"],
    "doxis.parentFolderName": ["Facilities Management Master File"],
    "doxis.fileName": ["CON-2023-0120.pdf"],
    "doxis.fileSize": ["3150"],
    "doxis.customer": ["DX4"],
    "doxis.repository": ["DB1"]
  },
  "security": {
    "inheritanceEnabled": true,
    "permissions": [
      { "identity": "Legal", "identityType": "group", "access": "read" },
      { "identity": "jane.doe", "identityType": "user", "access": "deny" }
    ]
  }
}
```

A removed document (`action: DELETE`):

```json
{
  "id": "doxis://DX4/DB1/documents/3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f",
  "action": "DELETE",
  "metadata": {
    "doxis.documentId": ["3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f"],
    "doxis.status": ["DELETED"],
    "doxis.customer": ["DX4"],
    "doxis.repository": ["DB1"]
  }
}
```

Other keys: `doxis.contentLink` (content-link documents), `doxis.lifecycleState`. Older versions in `all_versions` mode use
the id `…/documents/<uuid>/versions/<n>`.

### Descriptor values

| Doxis type | Example | Emitted |
|---|---|---|
| `DATE` | `20230222` | `2023-02-22` |
| `DATETIME` | `20230222143005` | `2023-02-22T14:30:05` (ISO-8601 values unchanged) |
| `INTEGER`, `LONGINTEGER` | ` 042` | `42` |
| `FLOATINGPOINT` | `750000,50` | `750000.50` |
| `BOOL` | `1`, `yes` | `true` (and `false`) |

Other types (strings, references, enumerations, …) and values that don't parse are passed through unchanged.

### Security mapping

| Doxis ACE | OIS rule |
|---|---|
| GRANT `VIEW_DOCUMENT_CONTENTS` / `VIEW_FOLDER_CONTENTS` | `read` |
| GRANT `UPDATE_DOCUMENT` / `UPDATE_FOLDER` | `write` |
| DENY on a view permission | `deny` (wins) |
| group `everybody` | `public` (`<customer>/everybody` with tenant isolation) |
| user / group / role | the user's login name, or the group or role name (prefixed with the customer when tenant isolation is on) |

Class-level rights ("All instances") can't be read through REST. Documents with no instance ACEs, which is common for
documents not filed in an e-file, get `fallback-principals`, or no permission at all. They are never made public.

## Incremental Crawls and Delete Detection

On the tested CSB 14.4 the audit trail was off and the `DXE_MODDATE` attribute was not maintained, while every search hit
carries the document's `modificationDate`. With `incremental: true` (search mode), the connector keeps one state file per
customer and repository, recording each document's modification date and the ids it emitted:

- **Unchanged documents** (same modification date as last run) are skipped without any further call.
- **New and changed documents** are crawled as usual.
- **Deletes:** after a complete listing, documents of the previous run that are no longer listed get `DELETE` tombstones:
  physical deletes, and documents that left the search's scope. A listing cut short (`max-documents`, a server limit)
  infers no deletes.
- **Failures:** a document that fails keeps its previous entry, so it is retried in the next run and stays tracked.

The state is saved when the scan completes. A change that doesn't touch a document's modification date (for example a
permission change on its e-file) is picked up by a full crawl, so run one now and then (`incremental: false`, or delete
the state file).

## Admin UI & CLI Integration

### Admin UI (`oc-admin-ui`)

Select **Doxis Repository (Intelligent Content Automation)** as the repository connector. The form opens with the
licence notice and has fields for every property above, including the ticket and OAuth2 fields for the selected
authentication type. **Test Connection** logs in, reads the repository and logs out.

### CLI (`oc-cli`)

```bash
# Check the Doxis connection
oc connector check --name doxis-prod --type repository
```

## Testing & Execution

### 1. Unit and mock-server tests

```bash
mvn test -pl oc-doxis-client,oc-doxis-repository-connector -am
```

The tests run against MockWebServer with Doxis CSB 14.4.1 response fixtures in
`src/test/resources/doxis-mock-responses/`: search paging, e-file traversal, tombstones, content download, ACL mapping,
version modes, typed values, the three logins, the OAuth2 token source and incremental crawls.

### 2. Standalone connector test

```bash
./scripts/test-doxis-repository-connector.sh
```

The script probes a Doxis CSB at `DOXIS_URL` (unauthenticated `GET /customers`) when one is reachable, and runs the test
suite above.

### 3. Read-only live probe against a CSB

`DoxisLiveProbeTest` runs only when `DOXIS_LIVE_PROBE=1` is set. It reads the target from the environment (`DOXIS_URL`,
`DOXIS_CUSTOMER`, `DOXIS_USER`, `DOXIS_PASSWORD`, `DOXIS_ROLE`, `DOXIS_REPOSITORY`, `DOXIS_QUERY`, `DOXIS_FALLBACK`,
`DOXIS_STATE_DIR`, `DOXIS_PROBE_REPORT`) and never writes to Doxis:

| `DOXIS_PROBE_MODE` | What it does |
|---|---|
| `full` | Paging, a 3-document connector scan, an audit-trail search and a `DXE_MODDATE` search, in one session |
| `paging` | Offset semantics of `/documents/searchResults` and `DXE_MODDATE` literal formats |
| `crawl` | A full crawl of the query, content size-checked and discarded |
| `verify2` | The session-ticket login, an incremental first run and rerun, and typed values on real data |

```bash
DOXIS_LIVE_PROBE=1 DOXIS_PROBE_MODE=crawl DOXIS_URL=… DOXIS_CUSTOMER=… DOXIS_USER=… DOXIS_PASSWORD=… \
DOXIS_ROLE=admins DOXIS_REPOSITORY=… DOXIS_QUERY="OBJECTNAME LIKE '*'" DOXIS_FALLBACK=group:Legal \
mvn test -pl oc-doxis-client,oc-doxis-repository-connector -am -Dtest='DoxisLiveProbeTest#fullReadOnlyCrawl' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Verified read-only against a Doxis 4 CSB 14.4 lab:
- a full crawl of 120 documents in one session, every call successful and every downloaded file matching the recorded size;
- 1-based search paging (the first page reports `start=1`; `offset=3` returns hits 3–4); `searchResultRestrictionMode` is
  `RESTRICTED_BY_SERVER` on any paged result and does not mean the listing is truncated;
- the session-ticket login;
- an incremental first run of 120 documents and a rerun that skipped all 120 as unchanged, in 7 calls;
- typed values on real data (`ObjectDate` `20230222` → `2023-02-22`).

## Doxis REST Endpoints

| Purpose | CSB REST 14.4.1 |
|---|---|
| Login | `POST /login`, `POST /loginBySessionTicket`, `POST /loginOIDCWithAccessToken`; the JWT is sent as `Authorization: Bearer` |
| Connection check | `GET /dmsRepositories/{repo}` |
| Search | `POST /documents/search`, `GET /documents/searchResults/{id}?offset&limit`, `DELETE /documents/searchResults/{id}` |
| Document, versions, descriptors | `GET /dmsRepositories/{repo}/documents/{uuid}/versions?initializeRepresentations=true` |
| Content | `GET …/versions/{v}/representations/{rep}/contentObjects/{id}` |
| E-files | `GET …/records/{uuid}?initializeNodeHierarchy=true`, `GET …/records/{uuid}/nodes/{nodeId}/referencedInformationObjects` |
| Permissions | `GET …/documents/{uuid}/permissions`, `GET …/records/{uuid}/permissions` |
| Principals and schema | `GET /users`, `/groups`, `/roles`, `/attributeDefinitions`, `/documentTypes` |
| Logout | `POST /logout` |

Issue #122 was written before the CSB REST contract was available, and its endpoint paths (`/doxis/rest/...`,
`X-Doxis-Ticket`) don't exist on CSB 14.4; the table above lists the documented 14.4.1 equivalents.

## Known Limitations

- **No decoupled pipeline test script.** Doxis is a licensed product without a public container image, so there is no
  throwaway Doxis to start in `docker compose`. The connector is covered by the mock-server tests and the read-only live
  probe against a CSB you provide.
- **OAuth2 login** is implemented against the 14.4.1 API contract and unit-tested, but has not been run against a live CSB;
  it needs a CSB customer configured for an identity provider.
- **Folder mode** is unit-tested; it has not been run against live e-files.
- **Change detection relies on the modification date.** Changes that don't update a document's `modificationDate`, such
  as permission changes on its e-file, are only picked up by a full crawl.
- **Class-level rights** ("All instances") can't be read through the REST API; see `fallback-principals`.
