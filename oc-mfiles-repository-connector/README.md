# OpenCrawling - M-Files Repository Connector

The M-Files Repository Connector (`oc-mfiles-repository-connector`) crawls an **M-Files** vault (M-Files Corporation)
through the M-Files Web Service (MFWS) REST API (`<server>/REST`) and streams its objects into the OpenCrawling pipeline as
Open Ingestion Standard (OIS) documents, with their typed property values, their files and their permissions (issue #123).

It is the OpenCrawling counterpart of a ManifoldCF M-Files repository connector: the same MFWS
calls, the same `mfiles:user:<id>` / `mfiles:group:<id>` permission tokens and the same optional Vault Application
Framework (VAF) change feed, rebuilt on the OpenCrawling SPI (reactive scan, virtual threads, OIS tombstones).

> **The connector needs a licensed, running M-Files Server. It does not include M-Files, and it does not include any
> M-Files licence.** OpenCrawling only talks to an M-Files Web Service that you already operate and are licensed to use.

## Prerequisites

Make sure all of the following are in place before you configure the connector or start a crawl. If any of them is
missing, the connection check or the crawl fails.

1. **A licensed M-Files Server** with the **M-Files Web Service** (REST API, normally `https://<server>/REST`) enabled and
   reachable from the OpenCrawling runtime (firewalls, proxies, TLS certificates). M-Files Cloud vaults work the same way.
   Using M-Files through this connector must be allowed under your M-Files licence, for example for automated reading or
   indexing of content into a search or AI system. The crawl account holds one MFWS session per scan.
2. **The vault GUID** (`vault-guid`, with or without braces). Without it M-Files issues a server-level token that cannot
   read any vault.
3. **An account** with read access to the objects to crawl:
   - `auth-type: token` (default) or `basic`: an M-Files user or a Windows account (`domain`). The connector refuses empty
     credentials, because M-Files would answer them with an anonymous token that fails every vault call;
   - `auth-type: oauth2`: a client the vault's OAuth 2.0 / Entra ID configuration accepts, or a ready access token.

   The connector only **reads**: it never creates, changes or deletes anything in M-Files. An object the account can't see
   isn't crawled. To map permissions by name (`principal-format: name`) the account must also be able to read the users and
   user groups value lists.
4. **OpenCrawling side.** A running OpenCrawling runtime (Java 25). The password and OAuth2 client secret are secrets:
   supply them through the Admin UI, environment variables or your secret store, never through source control.

Use the Admin UI's **Test Connection** button, or `oc connector check --name <name> --type repository`, to check items 1–3
before the first crawl. Both authenticate, read the session's vault and its object types (a vault-level call), and end
the session.

## Features

1. **Search crawl** (`crawl-mode: search`): `GET /objects` per class (`class-ids`, as `p100=<id>`), restricted to an object
   type (`object-type`, `0` = Document, `-1` = every type) and to MFWS search conditions (`search-query`, e.g. `p1020=5` or
   `q=contract`; plain text is a quick search). `all_objects` is the same without conditions.
2. **Complete listings beyond the MFWS limit.** MFWS cuts a search at `limit` results and has no offset to page with. A
   result marked `MoreResults` is searched again in last-modified windows (`p21>=` / `p21<=`) split in half until every
   window fits; objects on a window boundary are de-duplicated. (The ManifoldCF connector advanced a watermark to the
   newest timestamp of a truncated, unsorted page, which can skip objects.)
3. **View crawl** (`crawl-mode: view`): an M-Files view (`view-id`, e.g. `101` or `v101/L5`), descending into sub-views,
   lookup and text property folders and traditional folders. An object listed several times is emitted once.
4. **VAF change feed** (`crawl-mode: vaf`): the change feed and normalized objects of a Vault Application Framework app, the
   protocol of the ManifoldCF connector (`<vaf-base-path>/changes?since=<cursor>`, `<vaf-base-path>/object/{objType}/{objId}/{version}/normalized`),
   whose ACL tokens are effective permissions resolved inside the vault. Within a feed page only the last change of an
   object counts (an update followed by a delete is a delete).
5. **Authentication**: `token` (`POST /server/authenticationtokens` with the vault GUID and a session id, sent as
   `X-Authentication`), `basic` (`X-Username` / `X-Password` / `X-Vault` on every request) or `oauth2` (`Authorization:
   Bearer`, the token given directly or obtained with the client-credentials grant and refreshed before it expires). A 401
   or 403 renews the token once. Passwords, secrets and tokens are never logged, and a download URL from a VAF payload is
   only followed on the MFWS host, so credentials never go elsewhere.
6. **Single-file and multi-file documents**: a single-file document carries its file under the object's id; every file of
   a multi-file document is its own OIS document (`…/files/<fileId>`, with `mfiles.parentId`), so an agreement and its
   exhibits are each searchable. Objects without files (customers, projects, …) are indexed by their metadata.
7. **Versions**: the latest version (`latest_only`) under a stable id, so a new version replaces the old one downstream, or
   every version (`all_versions`; older versions get `…/versions/<n>`).
8. **Typed properties**: every property value under `mfiles_prop_<name>` (the property definition's name in lower camel
   case: `Contract Value` → `mfiles_prop_contractValue`), normalised by `MFDataType`; lookup ids under `…_id`.
9. **Security**: object permissions mapped to OIS permissions, fail-closed (see below), with optional fallback readers,
   name resolution and vault-qualified identities.
10. **Deletes**: deleted objects (searched with `d=include`) become OIS `DELETE` tombstones for every id they produced.
11. **Incremental crawls** from a watermark, skipping unchanged objects and purging files an object no longer has.
12. **One MFWS session per scan**, always ended (`DELETE /session`), with bounded parallelism (Java 25
    `StructuredTaskScope` + `ObservabilityTask`), retries with back-off on 429, 502–504 and refused connections, files
    streamed to temporary files with a size cap, and an optional `max-documents` cap for test runs.

## Configuration Properties

The connector is configured either through Spring properties (`spring.opencrawling.connector.type=mfiles` plus the keys
below under `spring.opencrawling.connector.mfiles.`; YAML lists are accepted for list keys) or through a connector
configuration in the Admin UI / `connectors.json` (class `org.opencrawling.mfiles.MFilesRepositoryConnector`, camelCase keys).

| Spring property (`spring.opencrawling.connector.mfiles.*`) | `connectors.json` key | Default | Description |
|---|---|---|---|
| `url` | `url` | `http://localhost/REST` | MFWS base URL, including `/REST` |
| `vault-guid` | `vaultGuid` | — | Required. The vault, with or without braces |
| `auth-type` | `authType` | `token` | `token`, `basic` or `oauth2` |
| `username`, `password` | `username`, `password` | — | Required for `token` and `basic` |
| `domain` | `domain` | — | Windows domain of the account |
| `oauth2.token-url`, `oauth2.client-id`, `oauth2.client-secret`, `oauth2.scope` | `oauth2TokenUrl`, `oauth2ClientId`, `oauth2ClientSecret`, `oauth2Scope` | — | For `oauth2`: the identity provider's token endpoint and client credentials (client-credentials grant) |
| `oauth2.access-token` | `oauth2AccessToken` | — | For `oauth2`: a ready access token, instead of the client credentials |
| `crawl-mode` | `crawlMode` | `search` | `search`, `view`, `all_objects` or `vaf` |
| `object-type` | `objectType` | `0` | Object type id (`0` = Document); `-1` crawls every type |
| `class-ids` | `classIds` | — | Classes to keep (one search per class); empty keeps every class |
| `search-query` | `searchQuery` | — | MFWS search conditions, e.g. `p1020=5&q=contract`; text without `=` is a quick search |
| `view-id` | `viewId` | — | Required in view mode: `101` or a view path such as `v101/L5` |
| `version-mode` | `versionMode` | `latest_only` | `latest_only` or `all_versions` |
| `include-files` | `includeFiles` | `true` | Download files |
| `max-file-size-bytes` | `maxFileSizeBytes` | `52428800` | Largest file to download (larger ones: metadata only) |
| `include-properties` | `includeProperties` | `true` | Emit every property value as `<property-prefix><name>` |
| `property-prefix` | `propertyPrefix` | `mfiles_prop_` | Prefix of property keys |
| `include-acls` | `includeAcls` | `true` | Read object permissions; without, documents are public |
| `fallback-principals` | `fallbackPrincipals` | — | Readers when no permissions are readable (`group:X`, `user:Y`, `public`); empty means nobody |
| `public-group-ids` | `publicGroupIds` | — | User group ids treated as everybody (e.g. the "All internal users" group) |
| `principal-format` | `principalFormat` | `token` | `token` (`mfiles:user:<id>`, `mfiles:group:<id>`) or `name` (resolved through the value lists) |
| `users-value-list`, `user-groups-value-list` | `usersValueList`, `userGroupsValueList` | `6`, `16` | Value lists holding users and user groups, for `principal-format: name` (M-Files built-in lists 6 and 16) |
| `qualify-principals` | `qualifyPrincipals` | `false` | Prefix every identity with the vault GUID; user and group ids are per vault |
| `emit-deletes` | `emitDeletes` | `true` | Search deleted objects too (`d=include`) and send tombstones for them |
| `incremental-enabled` | `incrementalEnabled` | `false` | Search from the last watermark, skip unchanged objects, purge ids an object no longer produces |
| `modified-after` | `modifiedAfter` | — | Lower bound on the last-modified time (`2026-01-01` or `2026-01-01T00:00:00Z`) |
| `clock-skew-seconds` | `clockSkewSeconds` | `300` | The incremental watermark is set this far before the scan start |
| `state-directory` | `stateDirectory` | `data/mfiles-state` | Where the incremental state is kept (one JSON file per vault and crawl scope) |
| `vaf-base-path` | `vafBasePath` | — | Required in vaf mode, e.g. `/vaf/<app>` |
| `vaf-change-feed-path`, `vaf-normalized-path` | `vafChangeFeedPath`, `vafNormalizedPath` | `/changes`, `/object/{objType}/{objId}/{version}/normalized` | VAF endpoint paths |
| `batch-size` | `batchSize` | `500` | Results per MFWS search request (`limit`; the MFWS default is 500) |
| `parallelism` | `parallelism` | `4` | Objects processed at once |
| `timeout-seconds` | `timeoutSeconds` | `30` | HTTP timeout |
| `max-retries` | `maxRetries` | `3` | Retries on 429, 502–504 and refused connections |
| `max-documents` | `maxDocuments` | `0` | Stop after N objects (0 = no limit; for test runs) |

A job path replaces the configured selection for that run: MFWS conditions (containing `=`) or plain text (a quick search)
replace the search, and a view path (`v101`, `v101/L5`) crawls that view. Such a run is not incremental.

## Example `application.yml`

```yaml
spring:
  opencrawling:
    repository-connector-type: mfiles
    connector:
      type: mfiles
      mfiles:
        url: "https://mfiles.example.com/REST"
        vault-guid: "{C0B3B52E-6512-4BD8-8F2B-2A8E9A349201}"
        auth-type: "token"
        username: "mfiles_service_user"
        password: "${MFILES_PASSWORD}"
        crawl-mode: "search"
        object-type: 0
        class-ids:
          - 14  # Non-Disclosure Agreement
          - 22  # Master Services Agreement
        version-mode: "latest_only"
        include-files: true
        max-file-size-bytes: 52428800
        fallback-principals: "group:records-managers"
        incremental-enabled: true
        modified-after: "2026-01-01T00:00:00Z"
        batch-size: 500
        timeout-seconds: 30
```

For OAuth2 / Entra ID, replace the user name and password with:

```yaml
        auth-type: "oauth2"
        oauth2:
          token-url: "https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token"
          client-id: "opencrawling-client-id"
          client-secret: "${MFILES_CLIENT_SECRET}"
          scope: "<the M-Files application's scope>/.default"
```

## OIS Document Mapping

A single-file document (`action: UPSERT`):

```json
{
  "id": "mfiles://C0B3B52E-6512-4BD8-8F2B-2A8E9A349201/objects/0/48192",
  "action": "UPSERT",
  "acl": "mfiles:user:12,mfiles:group:5",
  "metadata": {
    "name": ["Global Vendor NDA - Final Signed.pdf"],
    "title": ["Global Vendor NDA - Final Signed"],
    "mimeType": ["application/pdf"],
    "mfiles.vaultGuid": ["C0B3B52E-6512-4BD8-8F2B-2A8E9A349201"],
    "mfiles.vaultName": ["Contracts"],
    "mfiles.objType": ["0"],
    "mfiles.objId": ["48192"],
    "mfiles.version": ["3"],
    "mfiles.isLatestVersion": ["true"],
    "mfiles.title": ["Global Vendor NDA - Final Signed"],
    "mfiles.classId": ["14"],
    "mfiles.className": ["Non-Disclosure Agreement"],
    "mfiles.singleFile": ["true"],
    "mfiles.fileCount": ["1"],
    "mfiles.fileId": ["7"],
    "mfiles.fileName": ["Global Vendor NDA - Final Signed.pdf"],
    "mfiles.fileSize": ["1482910"],
    "mfiles.created": ["2026-04-10T10:30:00Z"],
    "mfiles.createdBy": ["sarah.connor"],
    "mfiles.lastModified": ["2026-09-27T15:20:00Z"],
    "mfiles.lastModifiedBy": ["alex.vance"],
    "mfiles.state": ["Approved"],
    "mfiles_identity_users": ["mfiles:user:12"],
    "mfiles_identity_groups": ["mfiles:group:5"],
    "mfiles_prop_customer": ["Cyberdyne Systems"],
    "mfiles_prop_customer_id": ["5"],
    "mfiles_prop_contractValue": ["150000"],
    "mfiles_prop_effectiveDate": ["2026-05-01"],
    "mfiles_prop_tags": ["Legal", "Vendor"]
  },
  "security": {
    "inheritanceEnabled": false,
    "permissions": [
      { "identity": "mfiles:user:12", "identityType": "user", "access": "read" },
      { "identity": "mfiles:group:5", "identityType": "group", "access": "read" },
      { "identity": "mfiles:user:9", "identityType": "user", "access": "deny" }
    ]
  }
}
```

The files of a multi-file document get `…/objects/0/48193/files/11`, `…/files/12`, each with `mfiles.parentId` set to
the object id. Older versions in `all_versions` mode get `…/objects/0/48192/versions/2` (and `…/versions/2/files/<id>`).

A deleted object (`action: DELETE`), one per id the object produced:

```json
{
  "id": "mfiles://C0B3B52E-6512-4BD8-8F2B-2A8E9A349201/objects/0/48192",
  "action": "DELETE",
  "metadata": {
    "mfiles.vaultGuid": ["C0B3B52E-6512-4BD8-8F2B-2A8E9A349201"],
    "mfiles.objType": ["0"],
    "mfiles.objId": ["48192"],
    "mfiles.status": ["DELETED"]
  }
}
```

Other keys: `mfiles.displayId`, `mfiles.workflow`, `mfiles.checkedOut`.

### Property values

| `MFDataType` | Example | Emitted |
|---|---|---|
| Date (5) | `2026-05-01T00:00:00Z` | `2026-05-01` |
| Timestamp (7) | `2026-09-27T17:20:00+02:00` | `2026-09-27T15:20:00Z` |
| Integer (2), Integer64 (11), Floating (3) | `150000.00` | `150000` |
| Boolean (8) | `true` | `true` / `false` |
| Lookup (9) | `{Item: 5, DisplayValue: "Cyberdyne Systems"}` | `Cyberdyne Systems`, id `5` under `…_id` |
| MultiSelectLookup (10) | two lookups | both display values; deleted lookup items are dropped |
| Text (1), MultiLineText (13), Time (6) | | the value |
| anything else | | the server's `DisplayValue` |

A property without a value is not emitted. Two property names that map to the same key keep the second as `…_<id>`.

### Security mapping

The connector reads `GET /objects/{type}/{id}/{version}/permissions`. The resource is not in the public MFWS reference;
the mapping below follows what a real M-Files server returns (verified against a live vault) and resolves every doubt to
"no access". The payload carries the object's effective `AccessControlList` (a `CustomComponent` plus zero or more
`AutomaticComponents`, e.g. workflow-state or metadata-driven permissions) and, separately, the `NamedACL` the object
uses, whose own definition is not read: it is already reflected in the effective list.

| M-Files permissions | OIS rule |
|---|---|
| An entry (`UserOrGroupID`, `IsGroup`) with `ReadPermission: 1` (also textual `"Allow"`, or bit 1 of `AccessFlags`) | `read`, as `mfiles:user:<id>` / `mfiles:group:<id>` |
| Any other numeric `ReadPermission` (the deny and not-set codes are not documented for MFWS), or `"Deny"` | `deny` for that identity (wins); an unverified code can only restrict |
| Several active components (custom, workflow state, metadata-driven) | only identities allowed by **every** component get `read`, since M-Files combines them restrictively; inactive components (`IsActive: false`) are skipped |
| Pseudo-users (`IsPsudoUser: true`, M-Files' spelling, or `HasConcreteUserOrGruopID: false`; e.g. "Project manager") | nothing: they can't be resolved over MFWS |
| A group in `public-group-ids` (for example group 1, "All internal users") | `public` |
| No readable permissions, or a server without the resource | `fallback-principals`, or nobody. Never public |

On the verified vault every document used the built-in named ACL "Full control for all internal users" (group 1 with
code `1` for every permission); its documents map to `mfiles:group:1`.

In **vaf** mode the vault application returns `aclTokens` / `denyTokens` (`mfiles:user:<id>`, `mfiles:group:<id>`,
`mfiles:role:<id>`) that are already effective permissions, pseudo-users included, and they are mapped one to one. Use
the vaf mode when metadata-driven permissions matter.

`principal-format: name` replaces ids with the names from the users and user groups value lists; unknown ids keep their
token. `qualify-principals: true` prefixes every identity with the vault GUID, because M-Files user and group ids are only
unique within a vault. The flat `acl` string is the comma-joined readers (`public` for public documents), and the readers
are also listed under `mfiles_identity_users` and `mfiles_identity_groups`.

## Incremental Crawls and Delete Detection

With `incremental-enabled: true` (search, all_objects and vaf modes) the connector keeps one state file per vault and crawl
scope (crawl mode, object type, classes, query, view and version mode), recording each object's last-modified time and the
ids it emitted:

- **Search from the watermark:** each run searches objects modified since the previous scan started (minus
  `clock-skew-seconds`), deleted objects included.
- **Unchanged objects** (same last-modified time as recorded) are skipped without any further call.
- **Changed objects** are crawled as usual; ids they no longer produce (a file removed from a multi-file document, a
  document that became single-file) get `DELETE` tombstones.
- **Deleted objects** get a tombstone for every id they produced.
- **Failures:** the watermark only advances after a scan in which every listed object was processed. A failing object is
  therefore listed again next time. In vaf mode the change-feed cursor follows the same rule.

## Admin UI & CLI Integration

### Admin UI (`oc-admin-ui`)

Select **M-Files Repository (Knowledge Work Automation)** as the repository connector. The form opens with the licence
notice and has fields for every property above, including the OAuth2 fields for that authentication type and the
search, view or VAF field for the selected crawl mode. **Test Connection** authenticates, reads the vault and its object
types, and ends the session.

### CLI (`oc-cli`)

```bash
# Check the M-Files connection
oc connector check --name mfiles-vault --type repository
```

## Testing & Execution

### 1. Unit and mock-server tests

```bash
mvn test -pl oc-mfiles-repository-connector -am
```

The tests run against MockWebServer with MFWS-shaped fixtures in `src/test/resources/mfiles-mock-responses/`: searches per
class, time-window splitting, single- and multi-file documents, typed values, ACL components and denies, tombstones, both
version modes, incremental runs, views, the VAF change feed, the three authentication types, token renewal, retries,
logout and size-capped downloads. The runtime's connection check is covered by `ConnectorCheckerServiceTest`.

### 2. Standalone connector test, with the MFWS mock

```bash
./scripts/test-mfiles-repository-connector.sh
```

The script probes an MFWS at `MFILES_URL` (unauthenticated `GET /server/status`) when one is reachable, starts the
**MFWS mock** ([`mfiles-mock/`](mfiles-mock/README.md), an M-Files Web Service emulator with a generated vault) on a
local port when `python3` is available, and runs the test suite above plus `MFilesMockServerTest` against the mock
over real HTTP: a full crawl reconciled with the mock's own counts, an incremental run after a file was removed, an
object deleted and one added, the VAF change feed with resolved pseudo-users, injected 503s, a rejected token renewed
mid-crawl, and the header and bearer logins. To run them against a mock you started yourself:

```bash
python3 oc-mfiles-repository-connector/mfiles-mock/mfws_mock.py &      # MFWS_PORT=8080 by default
MFILES_MOCK_URL=http://localhost:8080 mvn test -pl oc-mfiles-repository-connector -Dtest=MFilesMockServerTest
```

### 3. Decoupled pipeline test

```bash
./scripts/test-mfiles-decoupled.sh                 # KEEP_CONTAINERS=true keeps the stack, SKIP_BUILD=true reuses images
```

[`docker/docker-compose-decoupled-with-mfiles.yml`](docker/docker-compose-decoupled-with-mfiles.yml) runs the MFWS mock
with Kafka, Redis, pgvector, Ollama and the decoupled OpenCrawling services. The script crawls the mock's vault and
checks pgvector against the mock's ground truth: every live file indexed exactly once, single- and multi-file documents
by their reference codes, the `mfiles.parentId` lineage, typed properties, permission rules (an allow, a deny and a
named-ACL restriction), and no deleted object. It then checks MCP secure search over streamable HTTP (a group member
finds a document, a denied user and an outsider don't), changes the vault (a file removed, a document deleted,
one added) and verifies that an incremental crawl brings pgvector up to date.

#### Running the same stack against a real M-Files server

The crawler reads the MFWS mock by default. Point it at a real server through the environment, start the
infrastructure, and run the crawler once, capped for a first test:

```bash
export MFILES_URL=https://<server>/REST MFILES_VAULT_GUID='{<vault-guid>}' MFILES_USERNAME=<user> MFILES_PASSWORD=<password>
export MFILES_MAX_DOCUMENTS=25            # optional: MFILES_DOMAIN, MFILES_CLASS_IDS, MFILES_OBJECT_TYPE (default -1 = all),
                                         # MFILES_FALLBACK_PRINCIPALS, MFILES_CRAWL_MODE, MFILES_VAF_BASE_PATH
COMPOSE="docker compose -f oc-mfiles-repository-connector/docker/docker-compose-decoupled-with-mfiles.yml"
$COMPOSE up -d postgres-vector redis ollama ollama-model-puller kafka \
  oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer oc-mcp-server
$COMPOSE up oc-crawler                   # crawls, logs the scan summary, exits 0 on success
```

`scripts/test-mfiles-decoupled.sh` itself always uses the mock: its checks compare against the mock's ground truth.

### 4. Read-only live probe against an M-Files server

`MFilesLiveProbeTest` runs only when `MFILES_LIVE_PROBE=1` is set and never writes to the vault. It records the payloads
the public MFWS reference leaves open (authentication, a `p21>=` search, and each object's permissions payload with the
security it maps to) and then runs a connector scan capped at `MFILES_MAX_DOCUMENTS` (default 5):

```bash
MFILES_LIVE_PROBE=1 MFILES_URL=https://<server>/REST MFILES_VAULT="{<vault-guid>}" MFILES_USER=… MFILES_PASSWORD=… \
mvn test -pl oc-mfiles-repository-connector -Dtest=MFilesLiveProbeTest -Dsurefire.failIfNoSpecifiedTests=false
```

The report (`target/mfiles-live-probe.txt`, or `MFILES_PROBE_REPORT`) holds vault data; keep it out of source control.

## MFWS REST Endpoints

| Purpose | MFWS |
|---|---|
| Authentication | `POST /server/authenticationtokens` (`Username`, `Password`, `Domain`, `VaultGuid`, `SessionID`) → `{"Value": token}`, sent as `X-Authentication` |
| Session end | `DELETE /session` (`POST /session?_method=DELETE` where IIS refuses `DELETE`) |
| Vault | `GET /session/vault` (`Name`, `GUID`) |
| Structure | `GET /structure/properties`, `GET /structure/objecttypes` |
| Search | `GET /objects?o=<type>&p100=<class>&p21>=<from>&p21<=<to>&d=include&limit=<n>` → `{Items, MoreResults}` |
| Views | `GET /views/<path>/items` → `{Items: [FolderContentItem], MoreResults}` |
| Properties | `GET /objects/{type}/{id}/{version}/properties` |
| Versions | `GET /objects/{type}/{id}/history` |
| Files | `GET /objects/{type}/{id}/{version}/files/{fileId}/content` (the file list comes with each `ObjectVersion`) |
| Permissions | `GET /objects/{type}/{id}/{version}/permissions` (not in the public reference; see above) |
| Users and groups | `GET /valuelists/{id}/items` (`principal-format: name`) |
| VAF | `<vaf-base-path>/changes`, `<vaf-base-path>/object/{objType}/{objId}/{version}/normalized` |

Differences from the text of issue #123, which was written before the MFWS reference was checked: the vault endpoint is
`GET /session/vault` (there is no `GET /vault`), property definitions are `GET /structure/properties` (not
`/structure/propertydefs`), and the id of the latest version carries no version number, so a new version replaces the
previous one downstream instead of leaving it indexed.

## Known Limitations

- **Verified against one live M-Files vault** (MFWS on IIS, Forms login, 106 single-file documents): login and session
  end, vault and structure reads, `p21>=` searches with `MoreResults`, properties, file downloads (all 106 documents with
  content) and the permissions payload. Not covered by that vault, so only tested against the MFWS mock: multi-file
  documents, deleted objects, versions, views, deny and pseudo-user entries, automatic permission components, and the vaf
  mode (the vault application was not installed).
- **Permissions over MFWS** come from a resource outside the public reference. The allow code (`1`) and the payload layout
  are verified against a live vault; the deny and not-set codes are not, so any other code denies that identity.
  Pseudo-users (metadata-driven permissions) can't be resolved over MFWS: use the vaf mode, `fallback-principals`, or both.
- **The pipeline tests run against the MFWS mock**, not a real M-Files Server, which has no container image. The mock's
  permissions payload mirrors the one captured from the live vault; its deny code (`2`) is an assumption, which the
  connector does not depend on (every code other than `1` denies).
- **Objects that leave the crawl scope** (for example a class change outside `class-ids`) are not detected by incremental
  runs; a full crawl into a clean index picks them up. Destroyed objects (removed rather than deleted) are not listed by
  MFWS and are not detected either.
- **Deleted objects are re-sent as tombstones on every full crawl** while M-Files keeps them (`emit-deletes: true`):
  harmless, since a DELETE of an id that is not indexed is a no-op, but on vaults with many deleted objects prefer
  incremental runs, which only see objects deleted since the watermark.
- **Changes that don't touch the last-modified time**, such as permission changes through a named ACL, are only picked up
  by a full crawl (`incremental-enabled: false`, or delete the state file).
