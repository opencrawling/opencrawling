# CSGQL mock: an IBM FileNet Content Services GraphQL emulator

IBM FileNet P8 has no free container image, and no open-source emulator of its GraphQL API exists. This mock lets the
FileNet repository connector be tested end to end without a FileNet installation: it is a real GraphQL server over a
generated object store.

> **How faithful it is, and how that is checked.** Without a FileNet system to test against, three things keep it honest:
>
> 1. **Schema conformance.** `schema.graphql` is a hand-written subset of IBM's published schema
>    ([ibm-ecm/ibm-content-platform-engine-samples](https://github.com/ibm-ecm/ibm-content-platform-engine-samples),
>    `CS-GraphQL-Schema`). `conformance.py` downloads IBM's schema at a pinned commit and checks that every type, field,
>    argument and enum value here exists there with exactly the same type, and that every GraphQL document the connector
>    sends validates against IBM's full schema. IBM's schema itself is not part of this repository: it is distributed
>    under IBM's own licence.
> 2. **A real GraphQL engine** ([graphql-core](https://github.com/graphql-python/graphql-core)), so a query the real
>    API would reject (an unknown field, a wrong argument, a missing variable) is rejected here too.
> 3. **Protocol from IBM's own clients**: the Content Services sample application in the repository above and IBM's
>    [Content Services MCP server](https://github.com/ibm-ecm/ibm-content-services-mcp-server), plus the API's
>    developer guide (search syntax, paging, enumeration page size).
>
> Where those sources are silent, the mock makes an assumption, listed below. The connector treats each of them as
> configurable or opaque.

## Running it

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
CSGQL_PORT=18190 .venv/bin/python csgql_mock.py
# or
docker build -t opencrawling/csgql-mock:local . && docker run -p 18190:8080 opencrawling/csgql-mock:local

.venv/bin/python conformance.py          # schema and connector queries against IBM's schema (needs network)
```

| Variable | Default | Meaning |
|---|---|---|
| `CSGQL_PORT` | `8080` | Port |
| `CSGQL_OBJECT_STORE` | `OS1` | Object store symbolic name (its id is derived from it) |
| `CSGQL_USERS` | `p8crawler:secret` | Basic-authentication users, `user:password,...` |
| `CSGQL_OAUTH_CLIENTS` | `opencrawling:client-secret` | Client-credentials clients for `POST /oauth/token` |
| `CSGQL_DOCUMENT_COUNT` | `60` | Version series generated |
| `CSGQL_DEFAULT_PAGE_SIZE`, `CSGQL_MAX_PAGE_SIZE` | `50`, `1000` | Search page size when none is given, and its cap |
| `CSGQL_ENUM_PAGE_SIZE` | `50` | Page size of enumeration fields (IBM's `ecm.content.graphql.enumPropertyPageSize`) |
| `CSGQL_AUDIT` | `true` | Record `DeletionEvent`s |
| `CSGQL_SEED`, `CSGQL_QUIET` | `125`, `false` | Data seed; no request log |

## Protocol

| Request | Behaviour |
|---|---|
| `POST /content-services-graphql/graphql` | JSON `{query, variables, operationName}`, or the raw query as `application/graphql`. Results and GraphQL errors both come back with HTTP 200; errors carry CE codes such as `E_OBJECT_NOT_FOUND`, `E_BAD_VALUE` |
| `GET /content-services-graphql/ping` | Liveness; returns an `ECM-CS-XSRF-Token` header |
| `GET /content-services-graphql/content?...` | The content of one element: the `downloadUrl` of a `ContentTransfer`, relative to `/content-services-graphql` |
| `POST /oauth/token` | Client-credentials tokens for bearer authentication (mock-only) |

Every request except `/ping` needs Basic or Bearer authentication (401 otherwise) and the `ECM-CS-XSRF-Token` header
with a cookie of the same value (403 otherwise), the double-submit check IBM's clients satisfy with a client-generated
UUID.

Queries: `_apiInfo`, `objectStore`, `document`, `documents` / `moreDocuments`, `folder`, `folders` / `moreFolders`,
`repositoryRows` / `moreRepositoryRows`. Searches take the CE SQL subset the connector and IBM's examples use:
`[Prop]` and `alias.[Prop]`, `AND` / `OR` / `NOT` / parentheses, `= <> < > <= >=`, `LIKE`, `IS [NOT] NULL`,
`This INFOLDER '/path'`, `This INSUBFOLDER '/path'`, `ISCLASS(alias, Class)`, string, number, `True` / `False`, GUID
(`{...}`) and unquoted timestamp (`20260101T000000Z`) literals, `FROM Class [alias] [WITH INCLUDESUBCLASSES |
EXCLUDESUBCLASSES]` and `ORDER BY`. An unknown property or class is an error, as in CE. Results page with an opaque
token kept server-side for 15 minutes and usable once.

## The object store

- Three classes: `Document`, and its subclasses `Contract` (`ContractNumber`, `Counterparty`, `ContractValue`, `Regions`
  (a list), `SignedDate`) and `Invoice` (`InvoiceNumber`, `Amount`, `Paid`, `LineCount`).
- Folders `/Contracts`, `/Contracts/2026`, `/Contracts/Archive`, `/Invoices`, `/Policies`; some documents are filed in
  two folders, some in none. `/Contracts` passes Finance's read access (`cn=Finance,ou=groups,dc=example,dc=com`) down to
  everything below it.
- Version series with one or two released versions, some with an in-process minor version on top; each version is its
  own document with its own id, as in FileNet.
- Content: real PDF, DOCX and TXT files containing a reference code `FNX-<n>-v<major>.<minor>`; some documents have a
  second element (an annex, `...-A`), some an external content reference, some no content.
- Permissions, one pattern per document (`n % 7`): a group with the view level and a user with full control;
  `#AUTHENTICATED-USERS`; a group and a user, with a deny of view content only for `mallory`; a default entry only;
  read without view content plus full control for `ceadmin`; no direct entries; an unresolved SID plus a group that a
  marking denies. The last pattern's documents also carry an active marking (`Confidential`) in `ActiveMarkings`.

## Admin endpoints (mock-only)

| Endpoint | Effect |
|---|---|
| `GET /mock/stats` | Counts: series, versions, events, content, requests per operation, downloads, auth and XSRF failures |
| `GET /mock/documents`, `GET /mock/documents/{seriesId}` | The store as test ground truth (ids, code, folders, versions, elements, effective permissions; text for one) |
| `POST /mock/documents` | Create a document: `{class, title, folders, text, pattern, markings}` |
| `POST /mock/documents/{seriesId}/checkin` | A new version: `{major (default true), text}` |
| `POST /mock/documents/{seriesId}/delete` | Delete the series, or `{currentVersionOnly: true}` the current version; records `DeletionEvent`s |
| `POST /mock/documents/{seriesId}/permissions` | Replace the direct permissions: `{permissions: [{granteeName, granteeType, accessType, accessMask}]}` |
| `POST /mock/documents/{seriesId}/markings` | Replace the active markings: `{markings: ["Confidential"]}`. Permissions are left as they are, as when a marking's effect does not show in them |
| `POST /mock/documents/{seriesId}/file`, `/unfile` | File in or unfile from `{folder}` |
| `POST /mock/faults` | Inject failures: `{op: "<root field>" or "download" or "*", status, count, retryAfter}` or `{op, graphqlError}`; `{clear: true}` |
| `POST /mock/audit` | `{enabled}`: record `DeletionEvent`s or not |
| `POST /mock/expire-tokens` | Expire every paging token and OAuth token |
| `POST /mock/reset` | Back to the generated store |

## Assumptions

Where IBM's material does not say, the mock assumes the following:

- **Download path.** `downloadUrl` is `/content?repositoryIdentifier=...&documentId=...&elementSequenceNumber=...`. The
  connector treats it as opaque and only resolves it against `/content-services-graphql`.
- **XSRF failure status.** A missing or mismatched XSRF token gives HTTP 403.
- **DeletionEvent properties.** Rows have `Id`, `SourceObjectId` (the deleted version's id), `SourceClassId`,
  `DateCreated` and `Creator`. The connector's audit query is configurable (`deletion-event-sql`).
- **DateTime format.** Values are ISO-8601 in UTC with milliseconds (`2026-03-06T12:15:30.000Z`). The connector accepts
  any ISO-8601 offset.
- **Markings.** `ActiveMarkings` is an `ObjectListProperty` with one `ActiveMarking` dependent object per marking, as
  IBM's schema types it; the mock does not model marking sets or their constraint masks.
- **Saves update the version.** A marking or permission change, filing or unfiling updates `DateLastModified` and the update
  sequence number of the current version, as any save does in Content Engine. An inherited permission changed on a
  folder does not touch the documents below it, so only a full crawl sees it.
- **Effective permissions.** `permissions` returns the object's own entries plus those inherited from the first folder
  it is filed in; real security-parent rules (security folders, proxies, templates) are richer.
- **No `totalCount`.** `pageInfo.totalCount` is always null.
