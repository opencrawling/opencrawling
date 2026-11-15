# OpenCrawling - SharePoint Repository Connector

The SharePoint Repository Connector (`oc-sharepoint-repository-connector`) crawls the document libraries of a
**SharePoint Online** site through **Microsoft Graph v1.0**. It streams the files into the OpenCrawling pipeline as Open
Ingestion Standard (OIS) documents, with their list item columns, content and permissions (issue #126). It authenticates
app-only with the OAuth 2.0 client-credentials grant against Microsoft Entra ID.

## Prerequisites

1. **An app registration in Microsoft Entra ID** (Entra admin center → App registrations → New registration), with:
   - a **client secret** (Certificates & secrets). Note the secret's *value*, the **Application (client) ID** and the
     **Directory (tenant) ID**;
   - the Microsoft Graph **application** permission **`Sites.Read.All`**. Microsoft's permission tables for every Graph
     API the connector calls accept it, and it is the least privileged permission that covers them all: the site lookup
     needs it, the drive and item APIs would also take `Files.Read.All`. The conformance test checks this against
     Microsoft's documentation on every run. **`Sites.Selected`** (with a `read` grant on the site, made by an
     administrator through `POST /sites/{site-id}/permissions`) is Microsoft's site-scoped alternative. It doesn't appear
     in those per-API tables and has not been tested with this connector;
   - **admin consent** for that permission. Without it the token is issued but every Graph call answers 403.

   The connector only **reads**: it never creates, changes or deletes anything in SharePoint.
2. **The site** to crawl, as its URL (`https://contoso.sharepoint.com/sites/engineering`) or its Graph site id
   (`host,siteCollectionId,webId`), and optionally the names of the document libraries to crawl (all of the site's
   document libraries by default).
3. **Network access** from the OpenCrawling runtime to `login.microsoftonline.com` and `graph.microsoft.com` (or the
   sovereign-cloud equivalents, see `authority-url` and `graph-url`), and to the `*.sharepoint.com` download URLs.
4. **OpenCrawling side.** A running OpenCrawling runtime (Java 25). The client secret is a secret: supply it through the
   Admin UI, environment variables or your secret store, never through source control.

Use the Admin UI's **Test Connection** button, or `oc connector check --name <name> --type repository`, before the first
crawl. Both get a token, read the site and list its document libraries, and report a configured library that doesn't
exist.

## Features

1. **Delta crawl** (`crawl-mode: delta`, the default): `GET /drives/{id}/root/delta`. The first run enumerates the whole
   library; later runs fetch only what changed since the stored delta link. Folders and deletes are applied page by page,
   a deleted folder takes its files with it, and an expired delta link (410 Gone) triggers a full re-enumeration that
   reconciles with the stored state.
2. **Tree crawl** (`crawl-mode: tree`): a traversal of the folders from `root-folder-path`, optionally recursive. With
   `incremental: true` it compares each file's eTag and permission fingerprint with the previous run, re-emits only what
   changed and sends tombstones for what vanished. It is the mode that notices **permission-only changes** (see below).
3. **Libraries:** every document library of the site, or those named in `drive-names` (or one `drive-id`). OneDrive
   (`business`) drives and non-library lists are ignored.
4. **List item columns** (`includeCustomFields`): each column of the file's list item, as `sharepoint_field_<InternalName>`,
   with person, lookup and managed-metadata values reduced to their labels. SharePoint's system columns are skipped.
5. **Content:** the file is downloaded through its pre-authenticated `@microsoft.graph.downloadUrl` **without** the Graph
   token, falling back to `/content` when that URL has expired. It is streamed to a temporary file, handed to the
   claim-check store and deleted when the stream is closed. Files above `max-file-size-bytes` are indexed by metadata only.
6. **Security:** each file's Graph permissions mapped to OIS permissions, failing closed (see *Security mapping*).
7. **Throttling:** 429, 503 and 504 honour `Retry-After` through one gate shared by all workers. The header is treated as
   a floor: it is whole seconds and may be rounded down, so the connector waits one second more, and never less than its
   exponential back-off with jitter. A `Retry-After: 0` can't turn into a burst of retries. A throttled or failing item is
   never mistaken for a deleted one. An expired token (401) is renewed once. Like Microsoft's Graph SDK, 500, 502 and 507
   are not retried: the item fails, the delta link doesn't advance, and the next crawl tries it again.
8. **Token safety:** redirects are followed by hand, and the token is only ever sent to the configured Graph host.
9. **Bounded parallelism** (Java 25 `StructuredTaskScope` + `ObservabilityTask`) and an optional `max-documents` cap for
   test runs. Requests carry the `ISV|OpenCrawling|OpenCrawling-SharePoint-Connector/1.0` User-Agent Microsoft recommends
   for throttling diagnostics, and a `client-request-id` GUID per request. An error names Graph's `request-id` and the
   `client-request-id`, which Microsoft's support asks for.

## Configuration Properties

The connector is configured either through Spring properties (`spring.opencrawling.connector.type=sharepoint` plus the
keys below under `spring.opencrawling.connector.sharepoint.`; YAML lists are accepted for list keys) or through a connector
configuration in the Admin UI / `connectors.json` (class `org.opencrawling.sharepoint.SharePointRepositoryConnector`,
camelCase keys).

| Spring property (`spring.opencrawling.connector.sharepoint.*`) | `connectors.json` key | Default | Description |
|---|---|---|---|
| `tenant-id` | `tenantId` | — | Required. Directory (tenant) ID or domain |
| `client-id` | `clientId` | — | Required. Application (client) ID |
| `client-secret` | `clientSecret` | — | Required. Client secret value |
| `authority-url` | `authorityUrl` | `https://login.microsoftonline.com` | Entra ID authority (sovereign clouds use their own) |
| `graph-url` | `graphUrl` | `https://graph.microsoft.com/v1.0` | Graph base URL including the version |
| `site-url` | `siteUrl` | — | The site, e.g. `https://contoso.sharepoint.com/sites/engineering`. This or `site-id` is required |
| `site-id` | `siteId` | — | Graph site id (`host,siteCollectionId,webId`) instead of the URL |
| `drive-names` (or `drive-name`) | `driveNames` (or `driveName`) | all | Document libraries to crawl, by display name |
| `drive-id` | `driveId` | — | One document library by id instead of names |
| `crawl-mode` | `crawlMode` | `delta` | `delta` or `tree` |
| `root-folder-path` | `rootFolderPath` | `/` | Tree mode: folder to start from, relative to the library root |
| `include-subfolders` | `includeSubfolders` | `true` | Tree mode: descend into sub-folders |
| `include-content-stream` | `includeContentStream` | `true` | Download file content |
| `max-file-size-bytes` | `maxFileSizeBytes` | `52428800` | Larger files are indexed by metadata only |
| `include-custom-fields` | `includeCustomFields` | `true` | Emit list item columns |
| `field-prefix` | `fieldPrefix` | `sharepoint_field_` | Prefix of list item column keys |
| `include-acls` | `includeAcls` | `true` | Read item permissions. When `false`, every document is **public** |
| `user-identity` | `userIdentity` | `email` | `email`: a person is granted by lower-case e-mail / UPN **and** by Entra object id, whichever the permission carries. `id`: by object id only |
| `fallback-principals` | `fallbackPrincipals` | — | Readers for files with no readable permission, comma-separated: `group:X`, `user:Y`, a bare name (a group) or `public`. Empty means nobody |
| `everyone-identity` | `everyoneIdentity` | — | Identity granted for SharePoint's "Everyone" claim (e.g. `public`); empty grants nothing |
| `everyone-except-external-identity` | `everyoneExceptExternalIdentity` | — | Identity for "Everyone except external users" (e.g. an all-employees group); empty grants nothing |
| `organization-link-identity` | `organizationLinkIdentity` | — | Identity for organization-wide sharing links; empty grants nothing |
| `anonymous-link-identity` | `anonymousLinkIdentity` | — | Identity for "anyone with the link" sharing links; empty grants nothing |
| `incremental` | `incremental` | `true` | Keep a crawl state per library (delta link, eTags, permission fingerprints) |
| `state-directory` | `stateDirectory` | `data/sharepoint-state` | Where the crawl state is kept (one JSON file per library) |
| `batch-size` | `batchSize` | `100` | Page size (`$top`, 1–999) of children and delta requests |
| `parallelism` | `parallelism` | `4` | Items processed at once |
| `timeout-seconds` | `timeoutSeconds` | `30` | HTTP timeout |
| `max-retries` | `maxRetries` | `5` | Retries on 429, 503, 504 and connection failures |
| `initial-backoff-ms` | `initialBackoffMs` | `1000` | First back-off when a throttled response has no `Retry-After` |
| `max-documents` | `maxDocuments` | `0` | Stop after N files (0 = no limit; for test runs) |

A job path other than empty, `default` or `/` replaces `root-folder-path`, and that run is a non-incremental tree crawl.

## Example `application.yml`

```yaml
spring:
  opencrawling:
    repository-connector-type: sharepoint
    connector:
      type: sharepoint
      sharepoint:
        tenant-id: "00000000-0000-0000-0000-000000000000"
        client-id: "11111111-1111-1111-1111-111111111111"
        client-secret: "${SHAREPOINT_CLIENT_SECRET}"
        site-url: "https://contoso.sharepoint.com/sites/engineering"
        drive-names:
          - "Documents"
          - "Contracts"
        crawl-mode: "delta"
        everyone-except-external-identity: "all-employees"
        incremental: true
        parallelism: 4
```

## OIS Document Mapping

A crawled file (`action: UPSERT`):

```json
{
  "id": "sharepoint://contoso.sharepoint.com/drives/b!AbC.../items/01ABCDEF2345GHIJ",
  "action": "UPSERT",
  "metadata": {
    "name": ["Design Spec.docx"],
    "title": ["Design Specification"],
    "mimeType": ["application/vnd.openxmlformats-officedocument.wordprocessingml.document"],
    "sharepoint.siteId": ["contoso.sharepoint.com,7c0e...,9a3f..."],
    "sharepoint.siteUrl": ["https://contoso.sharepoint.com/sites/engineering"],
    "sharepoint.driveId": ["b!AbC..."],
    "sharepoint.driveName": ["Documents"],
    "sharepoint.itemId": ["01ABCDEF2345GHIJ"],
    "sharepoint.listItemUniqueId": ["5f1c..."],
    "sharepoint.name": ["Design Spec.docx"],
    "sharepoint.path": ["/Specs/2026/Design Spec.docx"],
    "sharepoint.webUrl": ["https://contoso.sharepoint.com/sites/engineering/Shared%20Documents/Specs/2026/Design%20Spec.docx"],
    "sharepoint.size": ["48213"],
    "sharepoint.etag": ["\"{01ABCDEF-2345-GHIJ},3\""],
    "sharepoint.cTag": ["\"c:{01ABCDEF-2345-GHIJ},3\""],
    "sharepoint.createdDateTime": ["2026-03-02T09:14:00Z"],
    "sharepoint.createdBy": ["Adele Vance"],
    "sharepoint.lastModifiedDateTime": ["2026-10-20T16:02:11Z"],
    "sharepoint.lastModifiedBy": ["Megan Bowen"],
    "sharepoint_field_ProjectCode": ["ENG-042"],
    "sharepoint_field_DocumentStatus": ["Approved"],
    "sharepoint_field_Classification": ["Internal"],
    "sharepoint_field_Reviewers": ["Adele Vance", "Megan Bowen"]
  },
  "security": {
    "inheritanceEnabled": false,
    "permissions": [
      { "identity": "0d1e6b2a-0000-4000-8000-00000000e001", "identityType": "group", "access": "write" },
      { "identity": "adele.vance@contoso.com", "identityType": "user", "access": "read" },
      { "identity": "a1d4e1e0-0000-4000-8000-0000000000a1", "identityType": "user", "access": "read" }
    ]
  }
}
```

A deleted file (`action: DELETE`) carries the same `id` and `sharepoint.status: ["DELETED"]`.

### Security mapping

| Graph permission | OIS rule |
|---|---|
| `grantedToV2` / `grantedToIdentitiesV2` person, role `read` | `read` for the person: the e-mail / UPN from the `siteUser` membership claim or the `user` facet, and the Entra object id from `user.id` (only the object id with `user-identity: id`) |
| … role `write` or `owner` | `write` (which also allows reading) |
| Entra security or Microsoft 365 group | the group's object id |
| SharePoint group (`siteGroup`) | `sharepoint-group:<siteId>:<groupId>` |
| "Everyone" claim (`c:0(.s\|true`) | `everyone-identity`, or nothing |
| "Everyone except external users" (`spo-grid-all-users`) | `everyone-except-external-identity`, or nothing |
| Group claims (`c:0t.c\|tenant\|…`, `c:0o.c\|federateddirectoryclaimprovider\|…`) | the group's object id |
| Sharing link, scope `users` | the people the link was shared with |
| Sharing link, scope `organization` / `anonymous` | `organization-link-identity` / `anonymous-link-identity`, or nothing |
| Sharing link, scope `existingAccess`, and any other role | nothing |

**Persons.** Microsoft's documented permissions identify a person by the `user` facet's object id, and often carry no
e-mail. The `siteUser` beside it holds the UPN in a membership claim (`i:0#.f|membership|<upn>`) when SharePoint returns
one, but Microsoft's examples also show it holding just the display name. The connector therefore grants a person by
every identifier it can establish: the e-mail / UPN, and the object id. It never uses a display name or SharePoint's
numeric site-user id, which identify nobody outside the site.

A file whose permissions grant nobody gets `fallback-principals`, or no reader at all. Since the MCP server treats an empty
permission list as "nobody", such a file is **never** made public. The broad SharePoint audiences (Everyone, organization
and anonymous links) only grant access when you map them to an identity your users carry, for example `public` or an
all-employees group. To let MCP clients match these rules, pass the user's e-mail as `userPrincipal`, and the user's
Entra object id (the token's `oid` claim) with their Entra group object ids (and any `sharepoint-group:` tokens) as
`userRoles`.

## Incremental Crawls and Delete Detection

With `incremental: true` the connector keeps one state file per library under `state-directory`: the delta link, and per
item its parent, name, eTag and permission fingerprint (a SHA-256 of the mapped rules).

- **Delta mode:** each run asks Graph for what changed since the stored delta link. New and edited files are emitted,
  files and folders reported `deleted` become tombstones (a folder's tombstone covers every file under it), moves update
  the path. The delta link only advances after a run without failures, so a failed item is retried. A 410 Gone resync
  re-enumerates the library, skips files whose eTag and permissions didn't change and tombstones what vanished.
- **Tree mode:** a full traversal compared with the state: changed eTags or permission fingerprints are re-emitted,
  vanished files get tombstones. A traversal cut short (`max-documents`) infers no deletes.
- **One state per library:** delta crawls and tree crawls of the whole library (`root-folder-path: /` with sub-folders)
  share the same state file, so either mode skips what the other already emitted. A tree crawl of a narrower scope (a
  sub-folder, or without sub-folders) keeps a state of its own, since every file outside its scope would otherwise look
  deleted.
- **Permission-only changes** are reported by Graph delta only to apps with `Sites.FullControl.All`. With the read-only
  permissions this connector asks for, schedule a **tree** crawl now and then (for example nightly, with delta crawls in
  between) over the whole library. It reuses the delta crawls' state and re-emits only the files whose permissions
  changed.

## Admin UI & CLI Integration

### Admin UI (`oc-admin-ui`)

Select **Microsoft SharePoint (Microsoft Graph API)** as the repository connector. The form has fields for every property
above. The broad SharePoint audiences are grouped under **Broad Access Grants**. **Test Connection** gets a token, reads the
site and lists its libraries.

### CLI (`oc-cli`)

```bash
# Check the SharePoint connection
oc connector check --name sharepoint-engineering --type repository
```

## Testing & Execution

### 1. Unit and mock-server tests

```bash
mvn test -pl oc-sharepoint-repository-connector -am
```

The unit tests run against MockWebServer with Graph response fixtures in `src/test/resources/sharepoint-mock-responses/`.
They cover tokens and renewal, throttling, redirects, paging, delta, the tree crawl, tombstones, downloads, list item
columns and the security mapping.

### 2. Standalone connector test, with conformance to Microsoft's published artifacts

```bash
./scripts/test-sharepoint-repository-connector.sh
```

The script starts the [Graph mock](graph-mock/README.md), an Entra ID + Microsoft Graph emulator, on a local port
(`GRAPH_MOCK_PORT`, default `18180`) and runs the tests above with `GRAPH_MOCK_URL` set, which adds
`SharePointMockServerTest`: eight connector scenarios over real HTTP (full crawl, delta increments, a deleted folder, a
permission-only change, a tree crawl after delta crawls, a resync, throttling and expired download URLs, a wrong secret).

No mock can prove what Microsoft's service does, so the emulator and the connector are held against what Microsoft
publishes itself, each pinned to a commit so that a run is reproducible (`CONFORMANCE=auto` runs this when `uv` is
installed). The tools are in [`graph-mock/conformance/`](graph-mock/conformance/):

| Check | Against | What it proves |
|---|---|---|
| `GraphDocsExamplesTest` | Microsoft's example payloads from the Graph documentation (`microsoftgraph/microsoft-graph-docs-contrib`) | The connector maps every documented permission to real identities (never a display name), builds documents from the documented items, and crawls the documented delta pages |
| `check_conformance.py --strict` | Microsoft's Graph v1.0 OpenAPI description (`microsoftgraph/msgraph-metadata`) | Every request the connector made exists in the API, with declared query parameters; every response the emulator gave validates against Microsoft's schema and has no property Microsoft doesn't declare or show in its examples; every paging request is exactly a link Graph returned; every Graph request carries a `client-request-id` |
| The checker's self-test | Microsoft's own examples, and a recording it must reject | The checker accepts Microsoft's payloads and catches a hand-built paging URL |
| `documented_permissions.py` | The permission tables of every Graph API page the connector calls | `Sites.Read.All` covers every call |

Microsoft's examples are documentation and are fetched when the tests run, not stored in this repository. The OpenAPI
description (~45 MB) is cached in `~/.cache/opencrawling/graph-openapi`.

### 3. Through Microsoft Dev Proxy

```bash
./scripts/test-sharepoint-devproxy.sh
```

[Microsoft Dev Proxy](https://aka.ms/devproxy) (`brew install dotnet/dev-proxy/dev-proxy`) sits between the connector and
the emulator. The connector talks to the real host names (`login.microsoftonline.com`, `graph.microsoft.com`,
`contoso.sharepoint.com`), and Dev Proxy's `RewritePlugin` routes them to the emulator. Microsoft's own plugins act on
the traffic:
- the eight scenarios must pass with `GraphRandomErrorPlugin` failing `ERROR_RATE`% (default 10) of Graph requests with
  429, 503 or 504, Microsoft's Graph rate-limiting preset (`RateLimitingPlugin`, window `RATE_LIMIT_WINDOW` seconds,
  default 10, preset 60), `RetryAfterPlugin` (a request sent too early is throttled) and the Graph guidance plugins;
- `SharePointDevProxyChaosTest` crawls three times through Microsoft's unchanged Graph error preset (50% of requests fail
  with 429, 500, 502, 503, 504 or 507). It requires that no crawl tombstones a live file, that the next crawl without errors
  brings every file in, and that the crawl after that finds nothing (the delta link never moved past a failure).

Dev Proxy runs contained: never as the system proxy, without its first-run keychain step, with its CA under
`target/devproxy` trusted by the test JVM only. Two of its findings are expected: a few requests that were already on
their way when another worker was throttled reach Graph inside the throttle window (they get a 429 and wait like any
other), and the paging guidance flags every page because the rewrite hides the original URL from it; the conformance
check above verifies the paging links instead.

### 4. Against an independent Graph mock

```bash
./scripts/test-sharepoint-independent-mock.sh
```

A second opinion from a Graph mock written by someone else:
[sergey-shulyak/mock-graph-server](https://github.com/sergey-shulyak/mock-graph-server) (MIT). It has sites, drives,
inherited permissions, SharePoint site groups, delta, 410 resync, throttling, an admin API, and an RBAC test oracle: the
exact files each of its 16 users can read, generated from its own permission model. The image runs pinned by digest,
locked down on `127.0.0.1`, behind `graph-mock/conformance/record_proxy.py`, which records the traffic and answers the
token endpoint that mock doesn't have.
- `IndependentGraphMockTest` crawls its 4 sites in delta and tree mode (both must find the same files with the same
  permissions), and drives changes through its admin API: new, replaced and deleted files, a deleted folder, a permission
  grant, throttling and a reset (410).
- `compare_acl_oracle.py` holds the permissions the connector indexed against that oracle, user by user: what the
  OpenCrawling MCP server would let each user read must equal what the oracle says, with no leak and no gap.
- `check_conformance.py` checks the recorded traffic against Microsoft's OpenAPI description. The connector's requests
  must pass. Where that mock deviates from Microsoft (it puts `email` on permission identities, adds fields to error
  bodies, refuses `$expand=listItem` and has no `/listItem` endpoint, and reports permission changes in plain delta),
  the checker reports it.

Running against it found one weakness of our own, now fixed: a file whose list columns couldn't be read was dropped;
it is now indexed without its columns.

### 5. Decoupled pipeline test

```bash
./scripts/test-sharepoint-decoupled.sh                     # images from docker/Dockerfile.*
LOCAL_BUILD=true ./scripts/test-sharepoint-decoupled.sh    # faster local builds from docker/local/
```

The script uses `docker/docker-compose-decoupled-with-sharepoint.yml`, with the Graph mock in a container. It crawls the
mock site through Kafka, Tika, Ollama and pgvector, then checks:
- every live file is indexed once, with its path, list item columns and permission rules;
- MCP `secureVectorSearch`, `getDocumentContent` and `listAccessibleSources` give each user exactly their files and keep
  files without readers closed;
- a delta increment carries an edit, a file delete, a folder delete and a new file;
- a permission-only change is not seen by delta, and is reconciled by a tree crawl.

### 6. Read-only live probe against a tenant

`SharePointLiveProbeTest` runs only when `SHAREPOINT_LIVE_PROBE=1` is set. It crawls up to `SHAREPOINT_MAX_DOCUMENTS`
files (default 25) of a real site with GET requests only, reads and discards their content, keeps the crawl state in a
temporary directory, and prints each file's path and mapped permissions with a count per identity. Compare those with
SharePoint's own **Manage access** view to see what your app registration is allowed to read.

```bash
SHAREPOINT_LIVE_PROBE=1 SHAREPOINT_TENANT_ID=… SHAREPOINT_CLIENT_ID=… SHAREPOINT_CLIENT_SECRET=… \
SHAREPOINT_SITE_URL=https://<tenant>.sharepoint.com/sites/<site> SHAREPOINT_DRIVE_NAMES=Documents \
mvn test -pl oc-sharepoint-repository-connector -am -Dtest=SharePointLiveProbeTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Microsoft Graph Endpoints

| Purpose | Graph v1.0 |
|---|---|
| Token | `POST {authority}/{tenant}/oauth2/v2.0/token` (`client_credentials`, scope `https://graph.microsoft.com/.default`) |
| Site | `GET /sites/{host}:/{path}`, `GET /sites/{site-id}` |
| Libraries | `GET /sites/{site-id}/drives` (`driveType` `documentLibrary`), `GET /drives/{drive-id}` |
| Delta | `GET /drives/{drive-id}/root/delta` (`@odata.nextLink`, `@odata.deltaLink`; 410 with `Location` on resync) |
| Tree | `GET /drives/{drive-id}/items/{item-id}/children`, `GET /drives/{drive-id}/root:/{path}` |
| Item, list item columns | `GET /drives/{drive-id}/items/{item-id}`, `…?$expand=listItem($expand=fields)` |
| Permissions | `GET /drives/{drive-id}/items/{item-id}/permissions` |
| Content | `@microsoft.graph.downloadUrl` (no token), else `GET /drives/{drive-id}/items/{item-id}/content` (302) |

## Known Limitations

- **Not yet run against a real tenant.** The connector is covered by the unit tests, the Graph mock scenarios, the
  conformance checks against Microsoft's OpenAPI description, examples and permission tables, Dev Proxy, an independent
  Graph mock with its RBAC oracle, and the decoupled pipeline test. What none of these can show is how a real tenant behaves where Microsoft's documentation is
  silent: how complete `/permissions` is for an app-only caller, real throttling thresholds, and how long delta lags
  behind a change. Run the read-only live probe first on a new tenant.
- **`Sites.Selected` is untested.** See the prerequisites.
- **App-only permission visibility.** Microsoft documents that `/permissions` may return only part of the permissions to
  callers that aren't owners. What an app with `Sites.Read.All` sees has not been verified on a real tenant.
- **SharePoint group membership is not expanded.** Graph has no API for SharePoint groups' members, so they're emitted as
  `sharepoint-group:<siteId>:<groupId>` tokens. Your MCP clients must pass those tokens in `userRoles` for users who
  belong to them. Entra groups are emitted by object id and are not expanded either.
- **Permission-only changes** need a tree crawl (see above), unless the app has `Sites.FullControl.All`.
- **Renamed folders in delta mode.** Graph delta doesn't report the new path of files under a renamed folder. Their
  `sharepoint.path` stays stale until the files change or a tree crawl runs.
- **Document libraries only.** Generic lists, list attachments, pages and OneDrive drives are out of scope, and only the
  current version of each file is indexed.
