# DCTM REST mock: an OpenText Documentum REST Services emulator

OpenText Documentum has no free container image (the Developer Edition ended with 6.x; the official images need an
OpenText support account), and no open-source emulator of its REST API exists. This mock lets the Documentum repository
connector be tested end to end without a Documentum installation. It is a single Python file using the standard library
only.

> **How faithful it is, and how that is checked.** Without a Documentum system to test against:
>
> 1. **Response shapes** follow a REST 16.7 server's published JSON (the Apache-2.0 WSO2 Documentum connector's
>    documentation) and OpenText's Apache-2.0 REST clients (`documentum-rest-client-java`, `-python`, `-dotnet`): the
>    home document, the repositories feed, the repository with its `dql` link template, feeds and entries, objects,
>    content resources with `enclosure` links, errors.
> 2. **A DQL engine over typed object tables**, strict where Documentum is: a type or attribute that does not exist, a
>    repeating attribute in a condition without `ANY`, a repeating attribute selected without `r_object_id`, a type
>    mismatch, a non-SELECT statement or an unsupported construct is an HTTP 400, so a statement Documentum would reject
>    fails in the tests too.
> 3. **Documentum's permission evaluation**, after OpenText's DFC documentation and Google's Apache-2.0 Documentum
>    adaptor: the most permissive access permit among the entries matching a user (their own, their groups' including
>    nested groups, `dm_world`, `dm_owner`, `dm_group`), lowered by matching access restrictions, removed outside required
>    groups and required group sets; superusers read everything. `GET /mock/access` returns it per user, and the connector's
>    tests compare its security mapping with it.
> 4. **OpenText's published OpenAPI document.** developer.opentext.com publishes the Documentum REST API as an OpenAPI
>    3 document ("Documentum D2", the D2 REST Services, which embed the core REST resources). `conformance/check_spec.py`
>    downloads it, crawls the mock the way a client does and checks every exchange against it (see
>    [Conformance](#conformance)). The 26.4 javadoc of the REST server and of DFC (OpenText's d2sv-sdk site) confirms the
>    constants the mock uses: permit types 0–7, permit levels 1–7, `SUPERUSER` 16, `VIEW_AUDIT` 32, `media-url-policy`
>    values.
> 5. **OpenText's own client, unmodified.** `documentum-rest-client-python` navigates the mock (home, repositories,
>    DQL and its `next` links, objects, contents and downloads, cabinets → folders → documents with its `filter`, users,
>    groups, types, formats, versions).
> 6. **Independent audits** of the mock against those sources. Their open points are the assumptions below; the
>    second one also listed what a real server may do that the mock did not show (a smaller page maximum, request line
>    limits, links naming another host, content redirects, daylight saving, login names shared across domains, folder
>    moves). Each is now something the mock can do, with a connector test that fails without the connector's handling.

## Running it

```bash
DCTM_PORT=18290 python3 dctm_mock.py
# or
docker build -t opencrawling/dctm-rest-mock:local . && docker run -p 18290:8080 opencrawling/dctm-rest-mock:local
```

| Variable | Default | Meaning |
|---|---|---|
| `DCTM_PORT` | `8080` | Port |
| `DCTM_REPOSITORY`, `DCTM_DOCBASE_ID` | `corp`, `0277b6` | Repository name, and the docbase id in object ids |
| `DCTM_USERS` | `dctm_crawler:secret,jdoe:secret` | Basic-authentication accounts, `user:password,...` (`dctm_crawler` is a superuser, `jdoe` is not) |
| `DCTM_OAUTH_CLIENTS`, `DCTM_OAUTH_USER` | `opencrawling:client-secret`, `dctm_crawler` | Client-credentials clients for `POST /oauth/token`, and the user their tokens act as |
| `DCTM_DOCUMENT_COUNT` | `60` | Documents (chronicles) generated |
| `DCTM_TZ_OFFSET` | `+00:00` | The server's time zone, an offset or a region (`Europe/Berlin`, with daylight saving): dates are returned in it, and DQL date literals are read in it |
| `DCTM_DEFAULT_PAGE_SIZE`, `DCTM_MAX_PAGE_SIZE` | `100`, `1000` | `items-per-page` default and maximum (`rest.paging.max.size`) |
| `DCTM_PAGE_OVERSIZE` | `error` | A larger `items-per-page`: `error` (HTTP 400) or `cap` (silently the maximum) |
| `DCTM_AUDIT` | `true` | Record `dm_destroy` events in `dm_audittrail` |
| `DCTM_ACS_HOST` | `acs.dctm-mock.invalid:9080` | Host of the (unreachable) ACS enclosure links |
| `DCTM_MAX_HEADER_SIZE` | `8192` | Largest request line plus headers; larger requests get Tomcat's bare HTML 400 (its `maxHttpHeaderSize` default) |
| `DCTM_PUBLIC_URL` | (the request's host) | The REST root the server writes its links with, as behind a proxy or TLS offloading |
| `DCTM_CONTENT_REDIRECT` | `false` | `content-media` first redirects to itself on the same server |
| `DCTM_SEED`, `DCTM_QUIET` | `124`, `false` | Data seed; no request log |

## Protocol

| Request | Behaviour |
|---|---|
| `GET /dctm-rest/services` | Home document (`application/home+json`), no authentication |
| `GET /dctm-rest/repositories` | Repositories feed, no authentication |
| `GET /dctm-rest/repositories/{repo}` | The repository, with the `http://identifiers.emc.com/linkrel/dql` link template |
| `GET /dctm-rest/repositories/{repo}?dql=...` | A DQL result feed: `page`, `items-per-page`; rows in `entries[].content.properties` |
| `GET .../objects/{id}` | An object with every attribute of its type |
| `GET .../objects/{id}/contents` | The renditions feed (`inline`, `media-url-policy`) |
| `GET .../objects/{id}/contents/content` | Content metadata (`format`, `page`, `media-url-policy`) with `enclosure` links: `LOCAL` for `local`, `ACS` for the default `dc-pref` and `dc-only`, both for `all` |
| `GET .../objects/{id}/content-media` | The content bytes (the `LOCAL` enclosure) |
| `GET .../acls/{id}`, `GET .../currentuser` | A permission set; the authenticated user |
| `GET .../documents/{id}`, `.../folders/{id}`, `.../cabinets/{id}` | The object through its own collection: its `name` (`document`, `folder`, `cabinet`, or `object` through `objects/`), `self` and `edit` links follow the URL, with a `canonical` link otherwise |
| `GET .../objects/{id}/versions`, `.../versions/current` | The version tree (newest first) and its CURRENT version |
| `GET .../objects/{id}/permission-set` | The permission set as the server renders it: `permitted`, `restricted` (accessor, `basic-permission`, `extend-permissions`, `application-permission`), `required-group`, `required-group-set` |
| `GET .../objects/{id}/permissions?accessor=` | The basic permission a user or group has, by the same evaluation as `/mock/access` |
| `GET .../objects/{id}/parent-links[/{folderId}]` | The folder links |
| `GET .../folders/{id}/folders`, `/documents`, `/objects` | A folder's children (CURRENT versions unless `include-all-versions=true`) |
| `GET .../cabinets`, `users[/{name}]`, `groups[/{name}]`, `acls`, `types[/{name}]`, `formats[/{name}]` | Collections and their members; `GET /dctm-rest/product-info` |
| `GET .../checked-out-objects`, `network-locations`, `relations`, `relation-types`, `aspect-types` | Empty feeds: nothing is checked out, and the repository has no BOCS servers, relations or aspects |
| `POST /oauth/token` | Client-credentials tokens for bearer authentication (mock-only) |

Everything under `/repositories/{repo}` needs Basic or Bearer authentication (401 otherwise); JSON resources need an
`Accept` that allows JSON (406 otherwise); writes are refused (405, also a GET on the POST-only `batches`). Errors are
`{status, code, message, details, id}`.

Feeds take `page`, `items-per-page` (default 100, maximum `DCTM_MAX_PAGE_SIZE`), `include-total`, `inline`, `sort`
(`attribute [asc|desc], ...`) and `filter` in the subset `starts-with(attribute,'value')` and `attribute='value'` joined
by `and`; any other filter is an HTTP 400 rather than an unfiltered feed. Every resource takes `links=false`, and
objects take `view` (`:default`, `:all` or an attribute list). Every link the mock advertises is served; links for
features it does not model (lifecycles, comments, virtual documents, aspects, checkout) are left out.

DQL: `SELECT <attributes> | * FROM <type> [(ALL)] [WHERE ...] [ORDER BY <attribute> [ASC|DESC], ...] [ENABLE (RETURN_TOP n)]`
with `AND` / `OR` / `NOT` / parentheses, `= <> != < > <= >=`, `LIKE` (`%`, `_`), `[NOT] IN (...)`, `IS [NOT] NULL`,
`ANY <repeating attribute> ...`, `UPPER()` / `LOWER()`, `FOLDER('/path'[, DESCEND])`, `CABINET('/path'[, DESCEND])`,
string, number, `TRUE` / `FALSE`, `DATE('...', 'format')`, `DATE(NOW)`, `DATE(TODAY)` and `NULLDATE` / `NULLSTRING`
literals.

- A type includes its subtypes; without `(ALL)` only CURRENT versions of sysobjects are returned.
- A user who is not a superuser only gets the sysobjects they may BROWSE, and `dm_audittrail` rows only with the View
  Audit extended privilege (`user_xprivileges` 32).
- Without `ORDER BY` the rows come back in a different order on every request, as a database gives no order and the
  server re-runs the query for every page.
- A page has a `next` link when more rows follow: REST Services reads one row past the page (`PagedQueryTemplate`
  pages with `RETURN_RANGE` up to `page × items-per-page + 1`). A page past the end has no `entries`.

## The repository

- Types: `dm_document` and two custom subtypes, `oc_contract` (`contract_number`, `counterparty`, `contract_value`,
  `regions` (repeating), `signed_date`) and `oc_submission` (`submission_id`, `agency`, `product_codes` (repeating),
  `approved`, `page_estimate`, `review_dates` (repeating times)); `dm_folder`, `dm_cabinet`, `dm_acl`, `dm_user`,
  `dm_group`, `dm_audittrail`. Attributes are those of a REST 16.7 server, in its order.
- Cabinets `/Corporate`, `/Regulatory`; folders below them; some documents are linked into two folders, some into a cabinet.
- Users with user names and login names (`John Doe` / `jdoe`, …); `Kim Lee` (`CORP`) and `Kevin Lee` (`EXT`) share the
  login name `klee`, which `user_login_name` allows across login domains. Groups including a nested one (`legal` is a
  member of `regulatory`), every group also a `dm_user` row with `r_is_group`.
- Versions `1.0`, `1.0 1.1`, `1.0 2.0` or `1.0 1.1 2.0 2.1` per document, with `i_chronicle_id`, `i_antecedent_id` and the
  CURRENT label; some documents share their predecessor's modification second.
- Content: real PDF, DOCX (`msw12`) and TXT (`crtext`) files containing a reference code `DCTM-<n>-v<label>`; some DOCX
  documents have a PDF rendition; some documents have no content.
- Permission sets, one pattern per document: a group and a user; `dm_world` READ; a group grant with an access
  restriction for Mallory Grey (who is in a granted group); a required group; a required group set; `dm_group`; the owner
  only, with an extended permit; BROWSE only, with an extended restriction; a custom `dm_45…` set per document. An
  application permit and extended permits appear too. Named permission sets are regular ones (`acl_class` 0), and
  `owner_permit`, `group_permit` and `world_permit` follow their `dm_owner`, `dm_group` and `dm_world` entries.

## Conformance

```bash
pip install pyyaml   # the document is YAML
python3 conformance/check_spec.py --mock http://localhost:18290/dctm-rest [--user jdoe --password secret]
```

The checker looks the current "Documentum D2" specification up on developer.opentext.com (the document is OpenText's
and is not redistributed here), follows the home document, every link the repository and the objects advertise, DQL
feeds and their paging links, contents and downloads, error cases and parameter variants, and checks each exchange:
documented path and method, documented query parameters, documented status, documented media type, and the JSON body
against the response schema. Exit status 0 means no deviation, 1 deviations, 2 the document could not be read.
`scripts/test-documentum-repository-connector.sh` runs it.

It reports as warnings, with the reason, the places where the published document does not describe the core REST
server: it lists the home document only as `application/home+xml` (OpenText's clients request JSON); it describes
`GET /repositories/{name}` only as the DQL feed sharing that path; and operations whose ids start with `getD2` are D2's
variants of core ones (D2 media types, no 404). One schema it references, `json-type`, is not defined in it.

Checked 2026-10-31 against Documentum D2 REST Services 26.2.0 (YAML sha256
`8ac0d4115ce3d55594273f11c66b3017e5ac79fae916c838efe837abf95d6421`): 186 exchanges, no deviation, as a superuser
and as `jdoe`.

## Admin endpoints (mock-only)

| Endpoint | Effect |
|---|---|
| `GET /mock/stats` | Requests per operation, authentication failures, downloads, the last DQL statements |
| `GET /mock/documents`, `GET /mock/documents/{chronicleId}` | The repository as test ground truth (versions, labels, formats, folders, permission set; text for one) |
| `GET /mock/access?user=<name or login>` | Per document version, whether Documentum lets the user READ it |
| `GET /mock/principals` | Users with their login name and domain, groups (nested membership resolved) and superuser flag |
| `POST /mock/documents` | Create a document with one version 1.0: `{type, folders, owner, pattern (index of a permission-set pattern), format (crtext, pdf or msw12), text}` |
| `POST /mock/documents/{chronicleId}/checkin` | A new version: `{major (default true), text}` |
| `POST /mock/documents/{chronicleId}/destroy` | Destroy the chronicle, or `{currentVersionOnly: true}` the CURRENT version (its antecedent becomes CURRENT); records `dm_destroy` events |
| `POST /mock/documents/{chronicleId}/update`, `/acl`, `/link`, `/unlink` | Set attributes; assign the permission set `{domain, name}`; link to or unlink from `{folder}` (each saves the object) |
| `POST /mock/acls/{domain}/{name}` | Replace a permission set's entries `{entries: [{name, permit, permitType, isGroup, xpermit}]}` in place: only the permission set changes |
| `POST /mock/groups/{name}` | Replace a group's users |
| `POST /mock/faults` | Inject failures: `{op: "dql", "object", "content", "download" or "*", status, count, retryAfter}`; `{clear: true}` |
| `POST /mock/config` | Change server settings until the next reset: `{maxPageSize, pageOversize, maxHeaderSize, publicUrl, contentRedirect, timeZone}` |
| `POST /mock/folders/move` | Relink a folder `{folder, to}`: its and its subfolders' paths change and only the folder is saved; the documents below keep their `r_modify_date` |
| `POST /mock/audit`, `/mock/purge-audit`, `/mock/expire-tokens`, `/mock/reset` | Audit on or off; delete the audit trail; expire OAuth tokens; back to the generated repository and the environment's settings |

## Assumptions

Where the sources above do not say, the mock assumes the following. The connector either checks the point when it
connects, handles both possibilities, or treats it as opaque.

- **Time zone of DQL date literals.** Literals are read in the time zone in which the REST server writes dates. The
  connector checks this on connect by looking up a cabinet by its exact `r_modify_date`.
- **Oversized pages.** Whether a larger `items-per-page` is refused or capped is not documented; both are modelled
  (`DCTM_PAGE_OVERSIZE`), and the connector pages until an empty page in either case.
- **Version filter without `(ALL)`.** The mock filters on the CURRENT label; the server may filter on `i_has_folder`.
  They differ only for branched versions.
- **Destroying the CURRENT version** moves CURRENT to its antecedent without saving it (`r_modify_date` unchanged).
- **Superusers** read everything and are not bound by restrictions or required groups. **Group owners**: members of a
  group that owns an object match `dm_owner`. **Owners** get no implicit basic permit beyond their `dm_owner` entry;
  some study material says owners implicitly get READ, which would make the connector deny where Documentum grants,
  never the reverse.
- **Not modelled:** `dm_read_all` / `dm_browse_all`, dynamic groups, inactive users (`user_state`), `i_all_users_names`,
  `dm_prune` events, multi-page content, DQL aggregates (`COUNT(*)`, …; an HTTP 400).
- **Errors.** The body `{status, code, message, details, id}` follows OpenText's clients' error model; the codes are
  invented (`E_DQL_QUERY_ERROR`, `E_BAD_CREDENTIALS`, …), and an object the user cannot see is a 404 (a server may answer
  403). The connector relies on HTTP statuses only.
- **`include-total`** is ignored on DQL feeds (OpenText's .NET client counts with a separate query; the published
  document does not list it for the DQL feed either).
- **Version history order**: newest first. **Type representation**: attribute datatypes as DFC's `IDfAttr.DM_*`
  names (`STRING`, `INTEGER`, `BOOLEAN`, `DOUBLE`, `TIME`, `ID`). **Extended permits**: DFC names them
  (`EXECUTE_PROC`, `CHANGE_LOCATION`, …) but does not publish their bits in `r_accessor_xpermit`; the mock's bits
  are assumed (the connector ignores extended permits).
- **Request size.** Tomcat's 8 KB `maxHttpHeaderSize` default is general knowledge about the servlet container, not
  in OpenText's material; a deployment may raise it. **Content redirects**: the server's `ClientInfoUtil` has an
  `X_NO_REDIRECTION` header, which suggests a server may redirect content requests; when and where to is not
  documented, so the mock redirects to itself only. **`time_stamp` of audit events**: the mock writes it like
  `time_stamp_utc`; OpenText's .NET client reads both, so a server may differ (the connector reads the audit trail a
  day back to absorb that). **Folder moves** leave the documents below unsaved (only the folder's `i_folder_id`
  changes).
- **A missing `FOLDER()` path** is an error; the connector checks the root path before crawling.
- **String comparison** is case-sensitive (database dependent).
