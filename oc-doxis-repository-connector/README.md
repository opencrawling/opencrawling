# OpenCrawling - Doxis Repository Connector

Crawls a **Doxis 4 CSB** DMS repository (SER Group) through the CSB REST API (`/restws/publicws/rest/api/v1`, verified
against 14.4.1), using the shared Doxis REST client in `oc-doxis-client` (issue #122).

## Prerequisites

> **The connector needs a licensed, running Doxis installation. It does not include Doxis, and it does not include any
> Doxis licence.** OpenCrawling only talks to a Doxis CSB that you already operate and are licensed to use. Doxis is a
> product of SER Group, and its licence terms are agreed between you and SER.

Make sure all of the following are in place before you configure the connector or start a crawl. If any of them is
missing, the connection check or the crawl fails.

### 1. A valid Doxis licence

- A **valid, unexpired Doxis 4 licence** for the CSB you connect to, with the CSB REST API (`restws`) enabled.
- The licence must allow **API / technical-user sessions** for the connector. CSB counts these sessions against the licence.
  A crawl holds exactly one session for its whole run and logs out at the end, and the connection check opens and closes
  one more. Plan session capacity with your Doxis administrator, especially if other integrations share the same CSB.
- Using Doxis through this connector must be allowed under your agreement with SER. Check the terms with SER or your Doxis
  partner if you're unsure, for example about automated mass reading or indexing of content into a search or AI system.

### 2. A reachable Doxis CSB

- **Doxis 4 CSB 14.4 or later** with the public REST API at `<scheme>://<csb-host>:<port>/restws/publicws/rest/api/v1`.
  The connector was built and tested against the 14.4.1 REST contract.
- **Network access** from the OpenCrawling runtime to that URL (firewalls, proxies, TLS certificates). The connector
  doesn't use the SOAP, Blueline or webCube interfaces.
- The **customer (tenant) name** the repository belongs to.

### 3. A technical user, a role, and their rights

A dedicated Doxis user for the connector, with a password and a role (`role`, default `admins`) that has at least these
rights:

| Needed for | Doxis right |
|---|---|
| Logging in | Login allowed. No "change password at next login" flag, which blocks API sign-in |
| Searching | `searchInContentRepository` on the DMS repository |
| Reading documents | Read and `VIEW_DOCUMENT_CONTENTS` on the document classes to crawl, and read on their descriptors |
| Folder mode and e-file ACLs | Read and `VIEW_FOLDER_CONTENTS` on the e-file (record) classes |
| Mapping ACLs to users and groups | Read access to users, groups and roles (`/users`, `/groups`, `/roles`) |

The connector only **reads**. It never creates, changes or deletes anything in Doxis, so it doesn't need write or delete
rights. Grant only what is listed. A document or e-file the user can't see isn't crawled. Only the instance ACEs the user
can read are mapped to OpenCrawling permissions.

### 4. Repository configuration

- The **DMS repository** to crawl (`repository-id`) must exist, and the technical user must be able to access it.
- For **CQL filters** (`search-query`): the descriptor short names used in the condition (for example `OBJECTNUMBER2` for
  `ObjectNumberExternal`) must exist in that repository.
- For **folder mode**: the e-file UUID to start from (`root-folder-id`).
- **Content-link documents** (binaries stored outside Doxis) are indexed with their link only. Indexing their content
  needs separate access to that external storage.

### 5. OpenCrawling side

- A running OpenCrawling runtime (Java 25), with the claim-check store and pipeline it normally uses.
- The **password is a secret**. Supply it through the Admin UI, an environment variable
  (`SPRING_OPENCRAWLING_CONNECTOR_DOXIS_PASSWORD`) or your secret store. Never commit it to source control.

Use the Admin UI's **Test Connection** button, or `oc connector check --name <name> --type repository`, to check items
1–4 before the first crawl. They log in, read the repository, and log out.

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
| `doxis.raw.<Doxis name>` | The original value of a descriptor whose value was normalised (see below) |

Descriptor values are normalised by their Doxis data type, so typed descriptors arrive in a standard form:

| Doxis type | Example | Emitted |
|---|---|---|
| `DATE` | `20230222` | `2023-02-22` |
| `DATETIME` | `20230222143005` | `2023-02-22T14:30:05` (ISO-8601 values unchanged) |
| `INTEGER`, `LONGINTEGER` | ` 042` | `42` |
| `FLOATINGPOINT` | `750000,50` | `750000.50` |
| `BOOL` | `1`, `yes` | `true` (and `false`) |

Other types (strings, references, enumerations, …) and values that don't parse are passed through unchanged. When a value
changes, the original is kept under `doxis.raw.<Doxis name>`.

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

This case is common: documents that are not filed in an e-file usually carry no instance ACEs, so access comes only
from class-level rights. To index them, name the group that should read them, for example:

```yaml
spring.opencrawling.connector.doxis.fallback-principals: group:Legal
```

or `"fallbackPrincipals": "group:Legal"` in the connector configuration. Several identities can be listed,
comma-separated (`group:Legal, user:jane.doe`). The fallback applies only to documents with no readable instance ACEs.
Documents with their own ACEs, or filed in an e-file that has ACEs, always keep the mapped Doxis permissions.

## Configuration

There are two sources:
- **Admin UI / `connectors.json`:** class `org.opencrawling.doxis.DoxisRepositoryConnector`, with the camelCase keys below.
- **Spring properties:** `spring.opencrawling.connector.type=doxis` plus `spring.opencrawling.connector.doxis.<kebab-case key>`.
  YAML lists are accepted for list keys.

| Key (`connectors.json` / Spring) | Default | Meaning |
|---|---|---|
| `url` / `url` | `http://localhost:8080/restws/publicws/rest/api/v1` | CSB REST base |
| `authType` / `auth-type` | `basic` | How the CSB session is opened: `basic` (user name and password, `POST /login`), `ticket` (a CSB session ticket, `POST /loginBySessionTicket`) or `oauth2` (an OIDC/OAuth2 access token, `POST /loginOIDCWithAccessToken`). Anything else is rejected |
| `customerName` / `customer-name` | — | Required. The CSB tenant, e.g. `DX4` |
| `username`, `password` | — | Required for `basic` |
| `sessionTicket` / `session-ticket` | — | Required for `ticket` |
| `oauth2TokenUrl`, `oauth2ClientId`, `oauth2ClientSecret`, `oauth2Scope` / `oauth2.token-url`, `oauth2.client-id`, `oauth2.client-secret`, `oauth2.scope` | — | For `oauth2`: the identity provider's token endpoint and client credentials. The connector requests a token with the client-credentials grant and refreshes it before it expires. The CSB customer must be configured for that identity provider |
| `oauth2AccessToken` / `oauth2.access-token` | — | For `oauth2`: a ready access token, instead of the client credentials |
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
| `fallbackPrincipals` / `fallback-principals` | empty | Read access when no instance ACEs exist (`group:X`, `user:Y`, `everybody`), e.g. `group:Legal` |
| `emitLogicalDeletes` / `emit-logical-deletes` | `true` | Search `ANY_OBJECTS` and send tombstones for removed documents |
| `modifiedSince` / `modified-since`, `modifiedSinceAttribute` | empty / `DXE_MODDATE` | **Experimental.** A CQL lower bound on a date attribute. On the tested CSB 14.4 lab, `DXE_MODDATE` matched nothing in any literal format, so leave it empty unless your CSB maintains that attribute |
| `batchSize` / `batch-size` | `100` | Hits per search page |
| `parallelism` | `2` | Documents processed at once. Keep it low on shared systems |
| `timeoutSeconds` / `timeout-seconds` | `120` | HTTP timeout. A busy CSB can take minutes to log in |
| `maxRetries` / `max-retries` | `2` | Retries on 429 (honours `Retry-After`), 502–504 and refused connections |
| `maxDocuments` / `max-documents` | `0` | Stop after N documents (for test runs) |

The Admin UI's **Test Connection** button and `oc connector check --name <name> --type repository` both log in, read the
repository, and log out.

## Notes on issue #122

Issue #122 was written before the CSB REST contract was available, and its endpoint paths (`/doxis/rest/auth/login`,
`/system/info`, the `X-Doxis-Ticket` header) don't exist on CSB 14.4. The connector uses the documented 14.4.1 paths instead.
The three authentication types the issue asks for all map onto CSB logins:

| Issue #122 | CSB REST 14.4.1 |
|---|---|
| basic login | `POST /login` (user name, password, role) → JWT as `Authorization: Bearer` |
| session ticket | `POST /loginBySessionTicket` |
| OAuth2 | client-credentials token from `oauth2.token-url`, then `POST /loginOIDCWithAccessToken` |
| system info | `GET /dmsRepositories/{repo}` (connection check) |
| search with pagination | `POST /documents/search` + `GET /documents/searchResults/{id}?offset&limit` |
| document metadata | `GET …/documents/{uuid}/versions?initializeRepresentations=true` |
| content | `GET …/versions/{v}/representations/{rep}/contentObjects/{id}` |
| folder children | `GET …/records/{uuid}?initializeNodeHierarchy=true` + `…/nodes/{nodeId}/referencedInformationObjects` |
| security | `GET …/documents/{uuid}/permissions` + `GET …/records/{uuid}/permissions` |

## Verified against a live CSB, and open points

Verified read-only on a Doxis 4 CSB 14.4 lab:
- a full crawl of 120 documents in one session, with every call successful and every downloaded file matching the
  recorded size;
- search paging is 1-based: the first page reports `start=1`, and `offset=3` returns hits 3–4. Search hits include the
  document's versions;
- `searchResultRestrictionMode` is `RESTRICTED_BY_SERVER` on any paged result, so it doesn't mean the listing is truncated;
- the audit trail returned no records (auditing off), and `DXE_MODDATE` matched nothing.

Open:
- **Session-ticket and OAuth2 logins** are implemented against the 14.4.1 API contract and unit-tested, but not yet run
  against a live CSB. OAuth2 needs a CSB customer configured for an identity provider.
- **Folder mode** is unit-tested only; the lab's documents aren't filed in e-files.
- **Incremental crawling and physical deletes:** with no usable audit trail or modification attribute, an incremental
  crawl has to compare each hit's `modificationDate` with the previous crawl, and detecting physical deletes needs a
  reconciliation run. Neither is implemented yet.
