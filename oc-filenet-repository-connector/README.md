# OpenCrawling - IBM FileNet P8 Repository Connector

The IBM FileNet P8 Repository Connector (`oc-filenet-repository-connector`) crawls an object store of an **IBM FileNet P8
Content Platform Engine** through the **FileNet Content Services GraphQL API** and streams its documents into the
OpenCrawling pipeline as Open Ingestion Standard (OIS) documents, with their class properties, content elements, folder
filings, versions and access permissions (issue #125).

> **The connector needs a licensed, running IBM FileNet P8 installation with the Content Services GraphQL API. It does
> not include IBM FileNet, and it does not include any IBM licence.** OpenCrawling only talks to a FileNet system that you
> already operate and are licensed to use.

## Prerequisites

1. **A licensed IBM FileNet P8 Content Platform Engine** (FileNet Content Manager, on premises or in Cloud Pak for
   Business Automation) with the **Content Services GraphQL API** deployed (`https://<host>/content-services-graphql`) and
   reachable from the OpenCrawling runtime. Using FileNet through this connector must be allowed under your IBM licence,
   for example for automated reading or indexing of content into a search or AI system.
2. **The object store** to crawl: its symbolic name (`OS1`) or id.
3. **An account** with read access to the documents, and the right to read their permissions (FileNet's "read
   permissions" right, part of the default "view" level), so the connector can index who may read each document:
   - `auth-type: basic` (default): a directory user and password;
   - `auth-type: oauth2`: a client the platform's identity provider accepts (Cloud Pak IAM / Zen), with the
     client-credentials grant, or a ready access token (`bearer`);
   - `auth-type: ltpa`: a WebSphere Liberty `LtpaToken2` value.

   The connector only **reads**: it never creates, changes or deletes anything in FileNet. A document the account can't
   see is not crawled.
4. **For `deletion-tracking: audit` only:** deletion auditing enabled on the crawled document classes, so Content Engine
   records `DeletionEvent`s. The default `reconcile` tracking needs no auditing.

## Features

- **Content Services GraphQL, as IBM's own clients use it.** Searches are `documents(from, where)` paged with
  `moreDocuments(token)`; id sweeps and audit events use `repositoryRows(sql)`. Requests carry a client-generated
  `ECM-CS-XSRF-Token` as header and cookie (the API's double-submit check), and content is downloaded from each element's
  relative `downloadUrl` with the same credentials. The GraphQL documents are resources validated against IBM's published
  schema (see Testing).
- **Query and folder crawls.** Per document class (subclasses included), with an optional CE SQL condition, or a full CE
  SQL statement; in folder mode restricted to a folder subtree (`This INSUBFOLDER`) or a single folder (`INFOLDER`).
- **Version policies.** The current version of each document, its latest released (major) version, or every version.
- **Content elements.** Every `ContentTransfer` element is downloaded to a temporary file and becomes its own OIS
  document; an external `ContentReference` keeps its location. Content over `max-content-size-bytes` is indexed by its
  metadata only.
- **Fail-closed security mapping** of FileNet access permissions, including partial denies, inherited, template, proxy
  and marking entries (see Security mapping).
- **Incremental crawls** from a `DateLastModified` watermark, skipping unchanged versions, with deletion tracking by
  reconciliation or the audit log.
- **Bounded concurrency** on virtual threads (`StructuredTaskScope` + `ObservabilityTask`), retries with back-off for
  429 and 502-504 (honouring `Retry-After`), and credentials that never leave the FileNet host.

## Configuration Properties

Spring properties live under `spring.opencrawling.connector.filenet.*` (kebab-case); a connector configuration in the
Admin UI or `connectors.json` uses the camelCase key.

| Property | Key | Default | Description |
|---|---|---|---|
| `url` | `url` | `http://localhost:9080/content-services-graphql/graphql` | The GraphQL endpoint; downloads resolve against `/content-services-graphql` |
| `object-store` | `objectStore` | — | Object store symbolic name or id (required) |
| `auth-type` | `authType` | `basic` | `basic`, `oauth2`, `bearer` or `ltpa` |
| `username`, `password` | `username`, `password` | — | For `basic` |
| `oauth2.token-url`, `oauth2.client-id`, `oauth2.client-secret`, `oauth2.scope` | `oauth2TokenUrl`, … | — | Client-credentials grant for `oauth2` |
| `oauth2.access-token` | `oauth2AccessToken` | — | A ready token for `bearer` (or `oauth2`) |
| `ltpa-token` | `ltpaToken` | — | The `LtpaToken2` cookie value for `ltpa` |
| `crawl-mode` | `crawlMode` | `query` | `query` or `folder` |
| `document-classes` | `documentClasses` | `Document` | Classes searched, one search each, subclasses included |
| `where` | `where` | — | An extra CE SQL condition, e.g. `[DocumentTitle] LIKE 'Loan%'` |
| `sql-query` | `sqlQuery` | — | A full CE SQL statement; its `FROM` class and `WHERE` condition replace `document-classes` (the API builds the `SELECT` itself) |
| `root-folder-path`, `include-subfolders` | `rootFolderPath`, `includeSubfolders` | `/`, `true` | Folder mode scope |
| `version-mode` | `versionMode` | `current` | `current`, `released` (alias `latest_major`) or `all` (alias `all_versions`) |
| `include-content-stream` | `includeContentStream` | `true` | Download content |
| `max-content-size-bytes` | `maxContentSizeBytes` | `52428800` | Larger elements are indexed by metadata only |
| `include-acls` | `includeAcls` | `true` | Map permissions; without, documents are public |
| `include-custom-properties` | `includeCustomProperties` | `true` | Emit class properties as `<property-prefix><name>` |
| `include-system-properties` | `includeSystemProperties` | `false` | Also emit Content Engine system properties |
| `include-folder-paths` | `includeFolderPaths` | `true` | Read the folders each document is filed in |
| `property-prefix` | `propertyPrefix` | `filenet_prop_` | Prefix of property metadata keys |
| `read-access-mask` | `readAccessMask` | `131201` | Access rights a grantee needs to be a reader (FileNet's view level) |
| `authenticated-users-identity` | `authenticatedUsersIdentity` | `#authenticated-users` | Identity (a group) for the `#AUTHENTICATED-USERS` grantee; empty grants nothing |
| `authenticated-users-public` | `authenticatedUsersPublic` | `false` | Map `#AUTHENTICATED-USERS` to `public` instead |
| `principal-format` | `principalFormat` | `name` | `name` (grantee names lower-cased) or `short` (`cn=Finance,…` → `finance`, `CORP\jdoe` → `jdoe`) |
| `fallback-principals` | `fallbackPrincipals` | — | Identities granted read when no permission grants anyone; empty means nobody |
| `deletion-tracking` | `deletionTracking` | `reconcile` | `reconcile`, `audit` or `none` (`deletion-tracking-enabled: false` is `none`) |
| `deletion-event-sql` | `deletionEventSql` | `SELECT [Id], [SourceObjectId], [DateCreated] FROM DeletionEvent WHERE [DateCreated] > {since}` | The audit query |
| `incremental-enabled` | `incrementalEnabled` | `true` | Keep a crawl state between runs |
| `modified-after` | `modifiedAfter` | — | Lower bound on `DateLastModified` (ISO date or timestamp) |
| `clock-skew-seconds` | `clockSkewSeconds` | `300` | The watermark is set this far before the scan start |
| `batch-size` | `batchSize` | `100` | Search page size |
| `parallelism` | `parallelism` | `4` | Documents processed at the same time |
| `timeout-seconds`, `max-retries` | `timeoutSeconds`, `maxRetries` | `30`, `3` | Per request |
| `max-documents` | `maxDocuments` | `0` | Stop after this many documents (0 = no limit); for test runs |
| `state-directory` | `stateDirectory` | `data/filenet-state` | Incremental crawl state, one file per object store and scope |

A job path starting with `/` (e.g. `/Mortgages/Commercial`) runs a one-off, non-incremental crawl of that folder subtree;
`default` uses the configuration.

## Example `application.yml`

```yaml
spring:
  opencrawling:
    connector:
      type: filenet
      filenet:
        url: "https://cpe.example.com/content-services-graphql/graphql"
        object-store: "OS1"
        auth-type: "basic"
        username: "p8crawler"
        password: "${FILENET_PASSWORD}"
        crawl-mode: "folder"
        root-folder-path: "/Mortgages"
        document-classes: "LoanApplicationDocument"
        where: "[LoanStatus] <> 'WITHDRAWN'"
        version-mode: "current"
        deletion-tracking: "reconcile"
        batch-size: 100
```

## OIS Document Mapping

| OIS field | Value |
|---|---|
| `id` | `filenet://<object-store>/documents/<version-series-id>`; in `all` version mode `…/versions/<version-id>`; element 1+ of a document `…/elements/<n>` (braces removed) |
| `content` | The element's content |
| `lastModified` | `DateLastModified` |
| `metadata.name`, `title`, `mimeType` | Retrieval name (or the document name), the document name, the element's content type |
| `metadata.filenet.*` | `objectStore`, `id` (the version id), `versionSeriesId`, `name`, `class`, `creator`, `dateCreated`, `lastModifier`, `dateLastModified`, `majorVersion`, `minorVersion`, `versionLabel`, `isCurrentVersion`, `versionStatus`, `contentSize`, `folderPath` (every folder the document is filed in), `elementCount`, `elementSequenceNumber`, `retrievalName`, `elementSize`, `contentLocation` (references), `parentId` (elements 1+) |
| `metadata.<prefix><property>` | Class properties by symbolic name in camelCase (`LoanNumber` → `filenet_prop_loanNumber`); lists as several values, dates as ISO instants; object-valued and binary properties are skipped |
| `metadata.filenet_identity_users`, `filenet_identity_groups` | The readers |
| `security` | See below |

A DELETE tombstone carries `filenet.status: DELETED`.

### Security mapping

FileNet's `Permissions` list is already effective: direct and default entries plus those inherited from the security
parent (`SOURCE_PARENT`), a security template, a security proxy (`PROXY`) and markings (`MARKING`). For each grantee the
connector combines its `ALLOW` and its `DENY` access masks:

| FileNet permissions | OIS rule |
|---|---|
| The grantee's allowed rights cover `read-access-mask` (default 131201: read, view content, read permissions) and none of them is denied | `read` |
| The grantee is denied any right in `read-access-mask`, even view content alone | `deny` (wins over every grant) |
| Allowed rights that do not cover the mask (e.g. read only, without view content) | nothing |
| `#AUTHENTICATED-USERS` | the `authenticated-users-identity` group, or `public` when `authenticated-users-public` |
| `#CREATOR-OWNER`, entries without a grantee name, role and delegated permissions | nothing |
| A grantee of type `UNKNOWN` (an unresolved account, often a SID) | kept verbatim as a group |
| No reader | `fallback-principals`, or nobody. Never public |

Identities are the grantee names lower-cased (`principal-format: short` reduces distinguished names and `DOMAIN\user`).
Users are `identityType: user`, groups `group`. Content Engine ranks entries by source (a direct allow can override an
inherited deny); the mapping treats every deny as winning, so where it differs from FileNet it only restricts access.

## Incremental Crawls and Delete Detection

The crawl state (`state-directory`, one JSON file per object store and scope) holds a `DateLastModified` watermark and,
per document, the version id, update sequence number and permission fingerprint last indexed with the OIS ids emitted.

- A run searches `[DateLastModified] > <watermark>` and skips documents whose version, update sequence number and
  permissions are unchanged. The watermark only advances after a run without failures.
- A content element a document no longer has gets a tombstone.
- **Deletions.** The first run (no watermark) tombstones everything previously known that it did not find. Later runs:
  - `reconcile` lists the version ids in scope (`SELECT [Id] FROM <class> WHERE <scope>`) and compares them with the state;
  - `audit` reads `DeletionEvent`s created since the last run.

  A document whose tracked version is gone is searched again by version series: when another version is in scope now (the
  current version was deleted, a version was demoted) it is re-indexed, otherwise it is tombstoned.
- **Not seen by an incremental run** (a full run, `incremental-enabled: false` or a fresh state directory, catches them):
  permissions inherited from a folder whose own permissions changed (the documents are not modified), and documents
  that leave the scope without being modified.

## Admin UI & CLI Integration

### Admin UI (`oc-admin-ui`)

**IBM FileNet P8 (Content Platform Engine)** in the repository connector list, with every setting above, and a
**Test Connection** button that reads `_apiInfo` and the object store, so wrong credentials, a rejected XSRF check or an
unknown object store fail before the first crawl.

### CLI (`oc-cli`)

```bash
oc connector check --name filenet-p8 --type repository
```

## Testing & Execution

IBM FileNet P8 has no free container image, so the tests run against the **CSGQL mock**
([`filenet-mock`](filenet-mock/README.md)), a GraphQL server over a hand-written subset of IBM's published Content
Services GraphQL schema. Two checks keep the mock and the connector honest without a FileNet system:

- **Conformance with IBM's schema** (`filenet-mock/conformance.py`): the mock's schema is a subset of IBM's (same types,
  fields, arguments and enum values), and every GraphQL document the connector sends
  (`src/main/resources/filenet-graphql`) validates against IBM's full schema. IBM's schema is downloaded at a pinned
  commit and is not part of this repository (IBM licence).
- **A real GraphQL engine** in the mock, so a query FileNet would reject is rejected in the tests too.

### 1. Unit and mock-server tests

```bash
mvn test -pl oc-filenet-repository-connector -am
```

`FileNetMappingTest` (settings, ids, properties, security), `FileNetClientTest` (the protocol against MockWebServer:
XSRF double submit, Basic/bearer/LTPA, OAuth2 renewal, retries, GraphQL errors, relative and off-host downloads) and,
when `CSGQL_MOCK_URL` is set, `FileNetMockServerTest`: full crawls reconciled with the mock's ground truth, every
permission pattern, folder, class, SQL and version modes, incremental runs after check-ins, deletions, permission changes
and new documents (reconcile and audit), throttling and bad credentials.

### 2. Standalone connector test, with the CSGQL mock

```bash
./scripts/test-filenet-repository-connector.sh
```

Creates the mock's Python environment (`graphql-core`) under `target/`, runs the conformance check, starts the mock and
runs the module tests against it. In CI a missing environment or a failed conformance check fails the run.

### 3. Decoupled pipeline test

```bash
./scripts/test-filenet-decoupled.sh
# LOCAL_BUILD=true builds from docker/local (BuildKit Maven cache); MCP_PORT and OC_IMAGE_TAG move the MCP server's host
# port and tag the images, to run beside another stack
```

The CSGQL mock, Kafka, Redis, pgvector, Ollama and the decoupled OpenCrawling services: every content element is indexed
once, with its lineage, class properties, folder paths and permission rules; MCP secure search honours the permissions
(a group member finds a document, a user denied view content does not, an outsider finds nothing, a document without
readers stays closed in every tool); a new version, a deletion and a new document are brought into pgvector by an
incremental crawl.

#### Running the same stack against a real FileNet system

Set `FILENET_URL`, `FILENET_OBJECT_STORE`, `FILENET_USERNAME` and `FILENET_PASSWORD` (optionally `FILENET_AUTH_TYPE`,
`FILENET_DOCUMENT_CLASSES`, `FILENET_WHERE`, `FILENET_CRAWL_MODE`, `FILENET_ROOT_FOLDER_PATH`, `FILENET_MAX_DOCUMENTS`,
`FILENET_FALLBACK_PRINCIPALS`) before `docker compose -f oc-filenet-repository-connector/docker/docker-compose-decoupled-with-filenet.yml up`.
Start with `FILENET_MAX_DOCUMENTS` on a large object store.

## Content Services GraphQL Operations

| Operation | Use |
|---|---|
| `_apiInfo(repositoryIdentifier)`, `objectStore(identifier)` | Connection check |
| `documents(repositoryIdentifier, from, where, pageSize)` | Searches, with the document fields, content elements, folders filed in, permissions and properties in one round trip |
| `moreDocuments(token)` | The next page of a search |
| `moreFolders(token)` | Folders filed in beyond the first page (50 by default) |
| `repositoryRows(repositoryIdentifier, sql, pageSize)`, `moreRepositoryRows(token)` | Id sweeps (`reconcile`) and `DeletionEvent`s (`audit`) |
| `GET <downloadUrl>` | Content of one element, relative to `/content-services-graphql` |

## Known Limitations

- **Never run against a real FileNet system.** The connector is verified against IBM's published schema (every query it
  sends validates against it) and against the CSGQL mock, whose protocol follows IBM's own clients and developer guide.
  Unverified, and therefore configurable or handled defensively:
  - the exact `downloadUrl` format (treated as opaque);
  - the `DeletionEvent` property names (`deletion-event-sql`);
  - the integer values of individual access rights. Only IBM's composite masks (131201 for the view level, 998903 for
    full control) come from IBM's material, so `read-access-mask` defaults to the composite.
- **Source-ranked precedence.** Content Engine lets a direct allow override an inherited deny; the mapping lets every deny
  win, so it can only restrict.
- **Incremental runs** do not see permissions inherited from a folder whose permissions changed, or documents that leave
  the scope without being modified; schedule a periodic full crawl.
- **One class per search.** The API takes a single class in `from` (subclasses included), so multiple classes are
  searched one after the other, and joins are not supported.
- **Annotations, custom objects and folders themselves** are not crawled; only documents are.
- **Wiki page.** The issue's `FileNet-Repository-Connector.md` wiki page is a follow-up.
