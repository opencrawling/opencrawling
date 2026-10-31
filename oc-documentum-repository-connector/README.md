# OpenCrawling - OpenText Documentum Repository Connector

The OpenText Documentum Repository Connector (`oc-documentum-repository-connector`) crawls a repository (docbase) of an
**OpenText Documentum Content Server** through **Documentum REST Services** and streams its documents into the
OpenCrawling pipeline as Open Ingestion Standard (OIS) documents, with their attributes (including the single and repeating
attributes of custom types), content or a preferred rendition, folder paths, versions and permission sets (issue #124).

> **The connector needs a licensed, running OpenText Documentum installation with Documentum REST Services. It does not
> include OpenText Documentum, and it does not include any OpenText licence.** OpenCrawling only talks to a Documentum
> system that you already operate and are licensed to use.

## Prerequisites

1. **A licensed OpenText Documentum Content Server** with **Documentum REST Services** deployed
   (`https://<host>/dctm-rest`) and reachable from the OpenCrawling runtime. Using Documentum through this connector must
   be allowed under your OpenText licence, for example for automated reading or indexing of content into a search or AI
   system.
2. **The repository name** (docbase), as listed at `https://<host>/dctm-rest/repositories`.
3. **An account** that can read the documents to crawl. A non-superuser only sees, through DQL, the objects it has at least
   BROWSE on, and can only download content it has READ on, so a superuser (or an account with READ on everything in
   scope) is the usual choice. The account must also be able to query `dm_acl`, `dm_user` and `dm_folder`, which every
   user can by default.
   - `auth-type: basic` (default): a user name and password. A Documentum login ticket (`DM_TICKET=...`) works as the
     password;
   - `auth-type: oauth2`: a client that OpenText Directory Services (OTDS) or another identity provider in front of REST
     Services accepts, with the client-credentials grant, or a ready access token (`bearer`).

   Kerberos (SPNEGO) is not supported. The connector only **reads**: it sends GET requests only, and never creates,
   changes or deletes anything.
4. **For `deletion-tracking: audit` only:** the account must be a superuser or have the **View Audit** extended privilege
   (the connector checks this when it connects), and `dm_destroy` (and `dm_prune`) must be audited for the crawled types.
   The default `reconcile` tracking needs neither.

## Features

- **Documentum REST Services, as OpenText's own REST clients use them.** DQL runs through the repository's `dql` link
  (`GET <repository>?dql=...&items-per-page=...`); objects, content metadata and content come from
  `objects/<id>`, `objects/<id>/contents/content?media-url-policy=local` and its `LOCAL` enclosure. Every request is a
  GET with `Accept: application/vnd.emc.documentum+json`, so no CSRF token is needed.
- **DQL and folder crawls.** Per object type (subtypes included), with an optional DQL condition, or a full DQL statement;
  in folder mode restricted to a cabinet or folder tree (`FOLDER('/path', DESCEND)`) or a single folder.
- **Version policies.** The CURRENT version of each document, its latest major version (`n.0`), or every version.
- **Content and renditions.** The primary content (page 0), or a preferred rendition such as `pdf` when the document has
  one, downloaded to a temporary file. Content over `max-content-size-bytes` is indexed by its metadata only.
- **Fail-closed security mapping** of permission sets: access permits, access restrictions, `dm_owner`, `dm_group`,
  `dm_world`, required groups and required group sets (see Security mapping).
- **Incremental crawls** from an `r_modify_date` watermark, skipping unchanged documents, with detection of edited
  permission sets and deletion tracking by reconciliation or the audit trail.
- **Keyset paging** in modification order, not REST page numbers, so objects changed or saved within the same second
  during a crawl are neither missed nor repeated (see Incremental crawls).
- **Bounded concurrency** on virtual threads (`StructuredTaskScope` + `ObservabilityTask`), retries with back-off for
  429 and 502-504 (honouring `Retry-After`), and credentials that never leave the REST server.

## Configuration Properties

Spring properties live under `spring.opencrawling.connector.documentum.*` (kebab-case); a connector configuration in the
Admin UI or `connectors.json` uses the camelCase key.

| Property | Key | Default | Description |
|---|---|---|---|
| `url` | `url` | `http://localhost:8080/dctm-rest` | The REST Services context |
| `repository-name` | `repositoryName` | — | The repository (docbase) name (required) |
| `auth-type` | `authType` | `basic` | `basic`, `oauth2` or `bearer`; `kerberos` is refused |
| `username`, `password` | `username`, `password` | — | For `basic` |
| `oauth2.token-url`, `oauth2.client-id`, `oauth2.client-secret`, `oauth2.scope` | `oauth2TokenUrl`, … | — | Client-credentials grant for `oauth2` |
| `oauth2.access-token` | `oauth2AccessToken` | — | A ready token, for `bearer` (or `oauth2`) |
| `crawl-mode` | `crawlMode` | `dql` | `dql` or `folder` |
| `object-types` | `objectTypes` | `dm_document` | Types queried one after the other; subtypes are included |
| `where` | `where` | — | An extra DQL condition, e.g. `a_status = 'Approved'` |
| `dql-query` | `dqlQuery` | — | A full `SELECT ... FROM <type> [WHERE ...]`: its type and condition are used |
| `root-cabinet-path` | `rootCabinetPath` | `/` | Cabinet or folder of `folder` mode, e.g. `/Regulatory/Submissions` |
| `include-subfolders` | `includeSubfolders` | `true` | `FOLDER(..., DESCEND)`, or the folder itself only |
| `version-mode` | `versionMode` | `current_only` | `current_only`, `latest_major` or `all_versions` |
| `include-content-stream` | `includeContentStream` | `true` | Download content |
| `max-content-size-bytes` | `maxContentSizeBytes` | `52428800` | Larger content is indexed by metadata only; 0 = no limit |
| `prefer-rendition` | `preferRendition` | — | A format (dm_format name) indexed instead of the primary content when present, e.g. `pdf` |
| `include-acls` | `includeAcls` | `true` | Map permission sets; `false` makes every document public |
| `world-identity` | `worldIdentity` | `dm_world` | Group identity for `dm_world`; empty grants nothing |
| `world-public` | `worldPublic` | `false` | Map `dm_world` to `public` instead |
| `principal-format` | `principalFormat` | `name` | `name`: user names as stored (`user_name`); `login`: `user_login_name` |
| `required-group-documents` | `requiredGroupDocuments` | `exclude` | `exclude`, or `fallback` (index for the fallback principals only) |
| `fallback-principals` | `fallbackPrincipals` | — | Readers of documents nobody can read through their permission set |
| `include-custom-attributes` | `includeCustomAttributes` | `true` | Attributes beyond dm_sysobject's, as `<attribute-prefix><name>` |
| `include-system-attributes` | `includeSystemAttributes` | `false` | The remaining dm_sysobject attributes too |
| `include-folder-paths` | `includeFolderPaths` | `true` | Resolve `i_folder_id` to folder paths |
| `attribute-prefix` | `attributePrefix` | `documentum_attr_` | Prefix of attribute metadata keys |
| `deletion-tracking` | `deletionTracking` | `reconcile` | `reconcile`, `audit` or `none` |
| `audit-deletion-enabled` | `auditDeletionEnabled` | `false` | Shorthand for `deletion-tracking: audit` |
| `audit-events` | `auditEvents` | `dm_destroy,dm_prune` | Audit events that mean a version is gone |
| `incremental-enabled` | `incrementalEnabled` | `true` | Watermark and skip-unchanged |
| `modified-after` | `modifiedAfter` | — | Only objects modified since this date or instant |
| `server-time-zone` | `serverTimeZone` | `auto` | Time zone in which DQL reads date literals: `auto` uses the offset of each date the REST server returns; a region such as `Europe/Berlin` follows daylight saving; an invalid value is an error |
| `clock-skew-seconds` | `clockSkewSeconds` | `300` | Margin subtracted from the watermark |
| `batch-size` | `batchSize` | `100` | Rows per DQL page, for every query; at most 1000 and at most the server's `rest.paging.max.size` (checked on connect) |
| `parallelism` | `parallelism` | `4` | Documents processed at once |
| `timeout-seconds` | `timeoutSeconds` | `30` | HTTP timeout |
| `max-retries` | `maxRetries` | `3` | Retries of throttled or failed requests |
| `max-documents` | `maxDocuments` | `0` | Index at most this many changed documents per run (0 = no limit); unchanged ones do not count, so successive incremental runs work through a large repository |
| `state-directory` | `stateDirectory` | `data/documentum-state` | Incremental crawl state |

## Example `application.yml`

```yaml
spring:
  opencrawling:
    connector:
      type: documentum
      documentum:
        url: "https://dctm.example.com/dctm-rest"
        repository-name: "corp_repo"
        auth-type: "basic"
        username: "dctm_crawler"
        password: "${DOCUMENTUM_PASSWORD}"
        crawl-mode: "folder"
        root-cabinet-path: "/Regulatory/Submissions"
        object-types: "my_submission"
        where: "a_status = 'Approved'"
        version-mode: "current_only"
        prefer-rendition: "pdf"
        deletion-tracking: "reconcile"
        batch-size: 100
```

## OIS Document Mapping

| OIS field | Value |
|---|---|
| `id` | `documentum://<repository>/documents/<i_chronicle_id>`; in `all_versions` mode `…/versions/<r_object_id>`. A new version replaces the previous one downstream |
| `content` | The primary content (page 0), or the preferred rendition |
| `lastModified` | `r_modify_date` |
| `metadata.name`, `title`, `mimeType` | `object_name`, `title` (or the name), the indexed content's MIME type |
| `metadata.documentum.*` | `repository`, `objectId`, `chronicleId`, `objectName`, `title`, `subject`, `type`, `creator`, `created`, `modifier`, `modified`, `owner`, `group`, `aclDomain`, `aclName`, `versionLabels`, `versionLabel`, `isCurrentVersion`, `authors`, `keywords`, `status`, `contentFormat`, `contentSize`, `lockOwner`, `indexedFormat`, `renditionFormat` (when a rendition was indexed), `folderPath` (every folder the object is linked into) |
| `metadata.<prefix><attribute>` | Attributes of custom types (`contract_number` → `documentum_attr_contract_number`); repeating attributes as several values, dates as ISO instants |
| `metadata.documentum_identity_users`, `documentum_identity_groups` | The readers |
| `security` | See below |

A DELETE tombstone carries `documentum.status: DELETED`.

### Security mapping

A document's permission set (`acl_domain`, `acl_name`) is read with DQL from `dm_acl` (cached, and re-read when it
changes). Documentum gives a user the most permissive basic permit among the entries that match them, lowers it by any
matching access restriction, and takes it away when they are outside a required group or required group set:

| Permission set entry (`r_permit_type`) | OIS rule |
|---|---|
| Access permit (0) of READ (3) or more | `read` for the accessor |
| Access permit below READ (NONE, BROWSE) | nothing |
| Access restriction (3) of READ or less (takes READ away) | `deny` for the accessor (wins over every grant) |
| Access restriction of RELATE or more | nothing: READ is left |
| `dm_owner`, `dm_group` | the document's `owner_name` (a user, or a group when the owner is one), `group_name` |
| `dm_world` | the `world-identity` group, or `public` when `world-public`; a restriction on `dm_world` leaves only the fallback principals |
| Required group (6), required group set (7) | an AND of groups OIS rules cannot express: the document is left out (`exclude`), or indexed for the fallback principals only (`fallback`) |
| Extended (1, 4) and application (2, 5) permits and restrictions | nothing: they never grant READ |
| Alias accessors (`%name`) | nothing (only ACL templates have them) |
| A restriction whose accessor has no identity (`principal-format: login`: an unknown user, or a login name several login domains share) | only the fallback principals: dropping it would grant that user |
| No reader | `fallback-principals`, or nobody. Never public |

Identities are lower-cased user and group names (`principal-format: login` uses users' `user_login_name`, resolved with
DQL from `dm_user`). A login name is unique only within a `user_login_domain`; one that users of several domains share
does not identify a user, so it is never used as an identity (logged as a warning). Where the mapping differs from Documentum it only restricts: superusers, members of
`dm_read_all` / `dm_browse_all` and any implicit rights of an object's owner beyond its `dm_owner` entry are not
represented.

## Incremental Crawls and Delete Detection

The crawl state (`state-directory`, one JSON file per repository and scope) holds an `r_modify_date` watermark, the
`i_vstamp` of each permission set in use and, per document, the object id, `i_vstamp` and permission fingerprint last
indexed.

- **Listings** select only single-valued attributes, ordered by `r_modify_date, r_object_id`, and continue after the last
  row with `r_modify_date > t OR (r_modify_date = t AND r_object_id > id)`, until a page comes back empty. REST page
  numbers would re-run the query and shift when objects change during a crawl, and `r_modify_date` has one-second
  precision. Every query that can span REST pages is ordered.
- **Date literals.** DQL reads `DATE('...')` literals in the Content Server's time zone, while REST returns dates with an
  offset. The connector writes each literal in the offset the server gave that date (`server-time-zone: auto`), or in
  the configured zone. When it connects it looks up a cabinet by its exact `r_modify_date` written that way, and refuses
  to crawl if the server does not find it, because a wrong zone makes cursors and watermarks skip documents silently.
  With `auto`, the state records the offset the server was in when the watermark was taken, and the next run writes
  the watermark in that offset (widened by the difference when it has changed): a daylight saving change between two
  runs then skips nothing.
- A run lists `r_modify_date >= <watermark>` and skips documents whose object, `i_vstamp` and mapped permissions are
  unchanged. The watermark only advances after a run without failures.
- **Edited permission sets.** Editing a permission set changes no document, so each run compares the `i_vstamp` of the
  permission sets in use and lists the documents of a changed one again.
- **Moved folders.** Moving a folder saves only the folder. In `folder` crawl mode each run also lists, in full, the
  folders below the root saved since the watermark (moved, renamed, re-secured or new), so documents brought into the
  scope with their folder are found; unchanged documents among them are skipped as usual.
- **Content the account may not read** (it may BROWSE a document but not READ it: HTTP 403), or that is only offered by
  another server, is indexed by metadata only with a warning; it does not count as a failed document, which would hold
  the watermark back on every run.
- **Deletions.** The first run tombstones everything previously known that it did not find. Later runs:
  - `reconcile` lists the object ids in scope and compares them with the state;
  - `audit` reads `dm_audittrail` events (`audit-events`) since the last run, and a day before it: events read again
    only re-check documents still known, while an error of an hour in how the server reads the literal (the zone of
    `time_stamp`, daylight saving) would lose deletions.

  A document whose indexed object is gone (destroyed, pruned, out of scope, or no longer CURRENT) is listed again by
  chronicle: when another version is in scope now (destroying the CURRENT version makes its antecedent CURRENT without
  modifying it) it is re-indexed, otherwise it is tombstoned. `audit` sees destroyed versions only; documents that leave
  the scope without being destroyed need `reconcile` or a full run.

## Admin UI & CLI Integration

### Admin UI (`oc-admin-ui`)

**OpenText Documentum (REST Services)** in the repository connector list, with every setting above, and a
**Test Connection** button that reads the repository, the root folder in folder mode, and checks how the server reads
date literals.

### CLI (`oc-cli`)

```bash
oc connector check --name dctm-corp --type repository
```

## Testing & Execution

OpenText Documentum has no free container image, so the tests run against the **DCTM REST mock**
([`documentum-mock`](documentum-mock/README.md)): a Documentum REST Services emulator with a DQL engine over typed object
tables, in the response shapes of a REST 16.7 server and OpenText's own REST clients. It also implements Documentum's
permission evaluation, and the security tests compare the connector's mapping with it user by user.

### 1. Unit and mock-server tests

```bash
mvn test -pl oc-documentum-repository-connector -am
```

`DocumentumMappingTest` (settings, DQL statements and date literals, attribute values, every permission rule),
`DocumentumClientTest` (the protocol against MockWebServer with the responses in `src/test/resources/documentum-mock-responses`:
DQL paging, REST errors, retries, OAuth2 renewal, LOCAL and off-host enclosures, a whole crawl) and, when
`DCTM_MOCK_URL` is set, `DocumentumMockServerTest`: full crawls reconciled with the mock's ground truth; for every user,
the documents the OIS rules let them read are exactly those Documentum lets them READ (by user name and by login);
folder, DQL and version modes; renditions; incremental runs after check-ins, edited permission sets, required groups
added, destroyed documents and versions, and folder moves (reconcile and audit); keyset paging with objects sharing a
second at page sizes 1 to 1000; a misconfigured time zone; throttling, OAuth2, bad credentials and missing audit
privileges. And what a real server may do differently: a smaller page maximum, a request line limit, links naming
another host (a proxy), content redirects, a daylight saving change between runs, a region time zone, login names
shared across domains, a folder moved into the scope, and content the account may browse but not read. Each of these
tests was checked to fail without the connector's handling.

The mock itself is checked against OpenText's published OpenAPI document for the REST API and driven by OpenText's
own Python REST client: see its README.

### 2. Standalone connector test, with the DCTM REST mock

```bash
./scripts/test-documentum-repository-connector.sh
```

Starts the mock (Python standard library only) with a server time zone of +02:00, checks it against OpenText's
published OpenAPI document (downloaded; needs PyYAML, which the CI workflow installs; skipped locally without it or
without network), runs the module tests, then runs them again as a server at -05:00 that silently caps every DQL page
at 5 rows.

### 3. Decoupled pipeline test

```bash
./scripts/test-documentum-decoupled.sh
# LOCAL_BUILD=true builds from docker/local (BuildKit Maven cache); MCP_PORT and OC_IMAGE_TAG move the MCP server's host
# port and tag the images, to run beside another stack
```

The DCTM REST mock, Kafka, Redis, pgvector, Ollama and the decoupled OpenCrawling services: every document with content
is indexed once with its custom attributes, folder paths and permission rules, and required-group documents are left
out; MCP secure search honours the permissions (a group member finds a document, a user under an access restriction does
not, an outsider finds nothing, a document without readers stays closed in every tool); a new version, a destroyed
document, a new document and an edited permission set reach pgvector through an incremental crawl.

#### Running the same stack against a real Documentum system

Set `DOCUMENTUM_URL`, `DOCUMENTUM_REPOSITORY`, `DOCUMENTUM_USERNAME` and `DOCUMENTUM_PASSWORD` (optionally
`DOCUMENTUM_AUTH_TYPE`, `DOCUMENTUM_OBJECT_TYPES`, `DOCUMENTUM_WHERE`, `DOCUMENTUM_CRAWL_MODE`,
`DOCUMENTUM_ROOT_CABINET_PATH`, `DOCUMENTUM_VERSION_MODE`, `DOCUMENTUM_PREFER_RENDITION`, `DOCUMENTUM_MAX_DOCUMENTS`,
`DOCUMENTUM_FALLBACK_PRINCIPALS`, `DOCUMENTUM_SERVER_TIME_ZONE`) before
`docker compose -f oc-documentum-repository-connector/docker/docker-compose-decoupled-with-documentum.yml up`.
Start with `DOCUMENTUM_MAX_DOCUMENTS` on a large repository.

## Documentum REST Requests

| Request | Use |
|---|---|
| `GET <url>/repositories/<repository>` | Connection check |
| `GET <url>/repositories/<repository>?dql=...&page=1&items-per-page=<batch>` | Listings (keyset paged), permission sets, users, folder paths, id sweeps, the audit trail, the date-literal check |
| `GET .../currentuser` | Privileges, for `audit` tracking |
| `GET .../objects/<id>` | Every attribute of a changed document |
| `GET .../objects/<id>/contents/content?page=0&media-url-policy=local[&format=<rendition>]` | Content metadata and its `LOCAL` enclosure link |
| `GET <enclosure>` | The content bytes, from the REST server: a `LOCAL` link naming another host for this REST application's path (a proxy) is followed on the configured URL, as are redirects to it; ACS and BOCS hosts are never sent credentials |

## Known Limitations

- **Never run against a real Documentum system.** The connector is verified against the DCTM REST mock, whose responses
  follow a REST 16.7 server's published JSON and OpenText's REST clients, and whose DQL and permission rules follow
  OpenText's DFC documentation and Google's open-source Documentum adaptor. The mock's README lists every assumption.
  Unverified, and therefore checked at run time or handled defensively: the time zone of DQL date literals (checked on
  connect), whether an oversized `items-per-page` is refused or capped (both handled, and `batch-size` is checked on
  connect), and the error codes (only HTTP statuses are relied on). DQL travels in GET URLs, so `IN` lists are kept to
  100 ids or 2,000 characters of names, under the servlet container's request line limit (Tomcat: 8 KB by default).
- **Kerberos** (SPNEGO) is not supported; use `basic` with a password or login ticket, or `oauth2`.
- **Required groups and required group sets** cannot be expressed as OIS rules: such documents are left out by default.
- **Multi-page content and virtual documents**: only page 0 of the primary content (or of the rendition) is indexed, and
  virtual document children are indexed as the documents they are, not assembled.
- **Audit tracking** sees destroyed and pruned versions only; reconcile also sees documents leaving the scope. It
  relies on `dm_destroy` being registered for auditing; the connector checks the account may read the audit trail,
  not the registration.
- **Content denied to the crawl account** stays metadata-only until the document changes; use a superuser or grant READ.
- **The repeated hour when clocks go back.** A date literal in that hour is ambiguous to DQL; if the Content Server reads
  it as the later hour, a listing crossing it at that moment can skip documents saved in the earlier one. Once a year,
  and only for documents saved in that hour; a full run, or `reconcile`, recovers them.
- **Large repositories.** The crawl state (one entry per document) and the reconcile sweep (the object ids in scope) are
  held in memory: plan heap for them, about a few hundred bytes per document.
- **A failed or cut-short run** (a document that could not be crawled, or `max-documents`) advances nothing: the
  watermark, the event watermark and the permission set stamps stay as they were, and every document the state knows
  stays known, so the next run retries without losing edits or deletions.
- **Folders, cabinets and non-sysobject types** are not crawled; only the configured document types are.

See also the wiki page: [OpenText Documentum Repository Connector](https://github.com/opencrawling/opencrawling/wiki/Documentum-Repository-Connector).
