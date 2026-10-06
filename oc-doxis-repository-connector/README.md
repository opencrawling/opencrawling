# OpenCrawling - Doxis Repository Connector

Crawls a **Doxis 4 CSB** DMS repository (SER Group) through the CSB REST API (`/restws/publicws/rest/api/v1`, verified
against 14.4.1). It is the input-side counterpart of `oc-doxis-output-connector`; both use the shared `oc-doxis-client`
(issue #122).

## Crawl modes

- **`search`** (the default) runs one CQL search: `SELECT * FROM <repository short name> [WHERE <search-query>]`. It is paged
  through `GET /documents/searchResults/{searchId}`, and the open result set is always closed (`DELETE`).
  - An empty result ends the listing, whatever `searchResultRestrictionMode` says.
  - The scan warns when CSB serves fewer hits than `totalHitCount`.
- **`folder`** crawls an e-file (*Akte*, `root-folder-id`). The connector reads it with its node hierarchy
  (`…/records/{uuid}?initializeNodeHierarchy=true`), then lists each folder node's documents
  (`…/nodes/{nodeId}/referencedInformationObjects`). With `include-subfolders`, it also descends into child nodes and
  sub-e-files.
  - A document listed in several nodes is emitted once.

A job path that starts with `SELECT ` replaces the search. In folder mode, an e-file UUID as the job path replaces
`root-folder-id`.

## Per document

At most `parallelism` documents are in flight at a time.

- **Logically removed** documents become OIS `DELETE` tombstones (`emit-logical-deletes`). The tombstone carries
  `doxis.documentId` and `doxis.status=DELETED`.
- **Versions:** the current version is read (`…/versions?initializeRepresentations=true`). With `version-mode: all_versions`,
  every version is read. Older versions get their own id, `…/documents/<uuid>/versions/<n>`.
- **Content:** the default representation's first content object is downloaded to a temporary file, which is deleted when
  the stream is closed. Each attempt writes a `.part` file that is moved into place only on success.
  - **Content-link** documents (length 0, external file) are emitted without content. Their link is in `doxis.contentLink`;
    REST can't download them (`SEDNA0104`).
- **ACL:** the document's ACEs are merged with its e-file's (`primaryParentObjectUUID`, pass-down permissions).

**One session per scan:** login (`POST /login` with a role) happens once, and logout always follows, on success or failure.
CSB licences count technical sessions.

## Metadata

| Key | Content |
|---|---|
| `<descriptor-prefix><Doxis name>` | Descriptor values; multi-value descriptors keep all values. With the default empty prefix, the Doxis names are used as-is (`ObjectName`, `ObjectNumberExternal`, `ObjectDate`, `ObjectAuthors`, `URL`, …), as the ManifoldCF Doxis 4 connector emits them. `doxis_desc_` gives `doxis_desc_ObjectName` |
| `doxis.documentId`, `doxis.documentClass`, `doxis.documentClassId` | UUID, class name and class UUID |
| `doxis.version`, `doxis.isLatestVersion` | Version number; `true` for the current version |
| `doxis.createdDate`, `doxis.createdBy`, `doxis.modifiedDate`, `doxis.modifiedBy` | Dates. The users are resolved to login names |
| `doxis.parentFolderId`, `doxis.parentFolderName` | The e-file the document is filed in |
| `doxis.fileName`, `doxis.fileSize`, `doxis.contentLink` | Content object |
| `doxis.customer`, `doxis.repository`, `doxis.lifecycleState` | Tenant, repository short name, lifecycle state |
| `name`, `title`, `mimeType` | File name, `ObjectName`, MIME type |

The document id and URI are `doxis://<customer>/<repository>/documents/<uuid>`.

## Security mapping

| Doxis ACE | OIS rule |
|---|---|
| GRANT `VIEW_DOCUMENT_CONTENTS` / `VIEW_FOLDER_CONTENTS` | `read` |
| GRANT `UPDATE_DOCUMENT` / `UPDATE_FOLDER` | `write` |
| DENY on a view permission | `deny` (wins) |
| group `everybody` | `public` |
| user / group / role | the user's login name, or the group or role name |

Class-level rights ("All instances") can't be read through REST. A document with no instance ACEs therefore gets
`fallback-principals`. When that is empty, the document gets **no** permission (deny by default). It is never made public.

## Configuration

There are two sources:
- **Admin UI / `connectors.json`:** class `org.opencrawling.doxis.DoxisRepositoryConnector`, with the camelCase keys below.
- **Spring properties:** `spring.opencrawling.connector.type=doxis` plus `spring.opencrawling.connector.doxis.<kebab-case key>`.
  YAML lists are accepted for list keys.

| Key (`connectors.json` / Spring) | Default | Meaning |
|---|---|---|
| `url` / `url` | `http://localhost:8080/restws/publicws/rest/api/v1` | CSB REST base |
| `authType` / `auth-type` | `basic` | CSB `POST /login` with user name and password. CSB 14.4 REST offers no other scheme for technical users. Anything else is rejected |
| `customerName` / `customer-name` | — | Required. The CSB tenant, e.g. `DX4` |
| `username`, `password` | — | Required |
| `role` | `admins` | Role to log in with |
| `repositoryId` / `repository-id` | — | Required. DMS repository (name or UUID); CQL uses its short name |
| `crawlMode` / `crawl-mode` | `search` | `search` or `folder` |
| `documentClasses` / `document-classes` | empty | Document classes to keep (names or UUIDs) |
| `searchQuery` / `search-query` | empty | CQL condition, e.g. `OBJECTNUMBER2 LIKE 'contracts-2026*'` (short names; wildcard `*` with `LIKE`) |
| `rootFolderId` / `root-folder-id` | — | E-file UUID. Required in folder mode |
| `includeSubfolders` / `include-subfolders` | `true` | Folder mode: descend into child nodes and sub-e-files |
| `versionMode` / `version-mode` | `latest_only` | `latest_only` or `all_versions` |
| `includeContentStream` / `include-content-stream` | `true` | Download binaries… |
| `maxContentSizeBytes` / `max-content-size-bytes` | 50 MB | …up to this size |
| `includeDescriptors` / `include-descriptors` | `true` | Emit descriptor values |
| `includeAcls` / `include-acls` | `true` | Read document and e-file ACEs |
| `descriptorPrefix` / `descriptor-prefix` | empty | Prefix for descriptor keys |
| `fallbackPrincipals` / `fallback-principals` | empty | Read access when no instance ACEs exist (`group:X`, `user:Y`, `everybody`) |
| `emitLogicalDeletes` / `emit-logical-deletes` | `true` | Search `ANY_OBJECTS` and send tombstones for removed documents |
| `modifiedSince` / `modified-since`, `modifiedSinceAttribute` | empty / `DXE_MODDATE` | **Experimental, unverified** incremental filter |
| `batchSize` / `batch-size` | `100` | Hits per search page |
| `parallelism` | `2` | Documents processed at once. Keep it low on shared systems |
| `timeoutSeconds` / `timeout-seconds` | `120` | HTTP timeout. A busy CSB can take minutes to log in |
| `maxRetries` / `max-retries` | `2` | Retries on 429 (honours `Retry-After`), 502–504 and refused connections |
| `maxDocuments` / `max-documents` | `0` | Stop after N documents (for test runs) |

The Admin UI's **Test Connection** button and `oc connector check --name <name> --type repository` both log in, read the
repository, and log out.

## Notes on issue #122

Issue #122 was written before the CSB REST contract was available. Its endpoints don't exist on CSB 14.4: there is no
`/doxis/rest/auth/login`, `/system/info` or `X-Doxis-Ticket`, and no OAuth2 for technical users. The connector uses the
verified 14.4.1 paths instead:

| Issue #122 | CSB REST 14.4.1 |
|---|---|
| login / ticket | `POST /login` → JWT as `Authorization: Bearer` |
| system info | `GET /dmsRepositories/{repo}` (connection check) |
| search with pagination | `POST /documents/search` + `GET /documents/searchResults/{id}?offset&limit` |
| document metadata | `GET …/documents/{uuid}/versions?initializeRepresentations=true` |
| content | `GET …/versions/{v}/representations/{rep}/contentObjects/{id}` |
| folder children | `GET …/records/{uuid}?initializeNodeHierarchy=true` + `…/nodes/{nodeId}/referencedInformationObjects` |
| security | `GET …/documents/{uuid}/permissions` + `GET …/records/{uuid}/permissions` |

## Not yet verified against a live CSB

- Search paging: whether `offset` is absolute.
- What search hits contain.
- The node listing of e-files filed via primary parent only.
- The `DXE_MODDATE` incremental filter. Auditing is off on the DX4 lab, so the audit trail isn't used.
- The effective ACLs of filed documents.

Physical deletes aren't detected yet; finding them needs a full reconciliation run.
