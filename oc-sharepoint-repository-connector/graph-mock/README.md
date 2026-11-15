# Graph mock: a Microsoft Entra ID + Microsoft Graph (SharePoint) emulator

A real SharePoint Online tenant can't be started in a container, so this emulator stands in for one. It is a small,
stateful emulator of the **Entra ID client-credentials token endpoint** and the **Microsoft Graph v1.0 SharePoint APIs**
the connector uses. It serves a generated site, so the SharePoint repository connector can be tested end to end, over
real HTTP, without a Microsoft 365 tenant. It is one Python file using only the standard library.

> **How far to trust it.** The emulator is held against what Microsoft publishes, not against its author's reading of it:
> [`conformance/`](conformance/) checks every request and response of a test run against Microsoft's Graph v1.0 OpenAPI
> description (strictly: no property that Microsoft neither declares nor shows in its examples), and the connector's
> tests run Microsoft's own example payloads from the Graph documentation. Its identities follow those examples: a
> permission's `user` carries an object id and no e-mail, and one permission pattern is exactly the shape of Microsoft's
> "list permissions" example. Where Microsoft's documentation is silent, for example how complete `/permissions` is for an
> app-only caller, real throttling thresholds or delta latency, nothing here can stand in for a tenant: run the
> connector's read-only live probe against a real one for that.

## Running it

```bash
python3 oc-sharepoint-repository-connector/graph-mock/graph_mock.py                    # http://localhost:8080

docker build -t opencrawling/graph-mock:local oc-sharepoint-repository-connector/graph-mock
docker run --rm -p 8091:8080 opencrawling/graph-mock:local                               # http://localhost:8091
```

Point the connector at it:

```yaml
tenant-id: contoso-tenant
client-id: opencrawling-test-app
client-secret: test-secret
authority-url: http://localhost:8091
graph-url: http://localhost:8091/v1.0
site-url: https://contoso.sharepoint.com/sites/engineering
```

| Environment variable | Default | Meaning |
|---|---|---|
| `GRAPH_PORT` | `8080` | Listening port |
| `GRAPH_TENANT`, `GRAPH_CLIENT_ID`, `GRAPH_CLIENT_SECRET` | `contoso-tenant`, `opencrawling-test-app`, `test-secret` | The accepted tenant and app registration |
| `GRAPH_SHAREPOINT_HOST`, `GRAPH_SITE_PATH` | `contoso.sharepoint.com`, `/sites/engineering` | The site |
| `GRAPH_FILE_COUNT` | `40` | Generated files |
| `GRAPH_SEED` | `42` | The site is generated deterministically from this seed |
| `GRAPH_PAGE_SIZE` | `200` | Default page size (the client's `$top` wins, up to 999) |
| `GRAPH_TOKEN_SECONDS` | `3599` | Access token lifetime |
| `GRAPH_PUBLIC_URL` | (request host) | Base URL of Graph links (`@odata.nextLink`, …), e.g. `https://graph.microsoft.com` behind Dev Proxy |
| `GRAPH_DOWNLOAD_URL` | (request host) | Base URL of the pre-authenticated download links, e.g. `https://contoso.sharepoint.com` behind Dev Proxy |
| `GRAPH_RECORD` | — | Append every token, Graph and download exchange (request, status, headers, body) to this JSON-lines file, for `conformance/check_conformance.py` |
| `GRAPH_QUIET` | `false` | Suppress the request log |

## The generated site

- **Libraries:** two document libraries, `Documents` and `Contracts`, with folders (`Specs`, `Specs/2026`, `Policies`,
  `Archive`). There is also a `business` (OneDrive) drive, which the connector must ignore.
- **Files:** real **PDF, DOCX and TXT** files that Apache Tika extracts. Every file's text carries a unique reference code
  `SPX-<itemId>-e<version>` that changes with each edit.
- **List item columns:** `Title`, `ProjectCode`, `DocumentStatus` (choice), `Classification` (managed metadata term),
  `Reviewers` (people), `Budget` (number), plus system columns that the connector must skip.
- **Users:** Adele, Megan and Diego, each with an e-mail (UPN), a SharePoint site-user id and an Entra object id
  (`/mock/stats` lists the object ids).
- **Permissions:** six patterns, repeating by file number. A user grant is shaped like Microsoft's documented permissions:
  `grantedToV2.user` with the object id and display name (no e-mail), beside a `siteUser` with a membership claim.
  0. "Everyone except external users";
  1. an Entra group with write and Adele with read;
  2. the "Engineering Owners" SharePoint group plus a specific-people sharing link to Megan (object id only);
  3. only an organization and an anonymous sharing link;
  4. Megan, exactly in the shape of Microsoft's "list permissions" example: her `siteUser.loginName` is her display name,
     not a claim, so only her object id identifies her;
  5. no permissions at all.
- **Delta:** the first enumeration lists every item in an order that puts some files before their folders. Later calls
  with a token return only the changes, the last occurrence of each item only. A deleted folder is reported on its own,
  without its children.
- **Download URLs:** SharePoint's shape (`<site>/_layouts/15/download.aspx?UniqueId=…&tempauth=…`), pre-authenticated and
  valid for an hour. They **reject any request that carries an `Authorization` header**, so a client that forwards its
  Graph token there fails loudly. `/content` answers with a 302 to a fresh download URL.
- **Errors and ids:** errors carry Graph's `innerError` (`date`, `request-id`, and the caller's `client-request-id`), and
  Graph responses carry `request-id` and echo `client-request-id`.

## Mock-only administration (`/mock/…`, not authenticated)

| Endpoint | Effect |
|---|---|
| `GET /mock/stats` | Live files (total and per library), deleted items, tokens issued, requests per route |
| `GET /mock/files` | Every live file with its library and permission pattern |
| `GET /mock/items/{id}` | An item's name, path, eTag, text and permissions (ground truth for tests) |
| `POST /mock/files` | Add a file: `{"drive", "parent", "name", "text", "title", "permissions"}` |
| `POST /mock/items/{id}/modify` | New version: `{"appendText", "name"}`; reported by delta |
| `POST /mock/items/{id}/delete` | Delete a file or a folder (with its contents); reported by delta |
| `POST /mock/items/{id}/permissions` | Replace the permissions: `{"permissions": [...]}`. **Not** reported by delta, as on Graph without `Sites.FullControl.All` |
| `POST /mock/drives/{driveId}/resync` | Make every earlier delta token answer 410 Gone with a restart `Location` |
| `POST /mock/faults` | Fail the next `count` requests whose URL starts with `path` with `status` (and `retryAfter` seconds) |
| `POST /mock/expire-tokens`, `POST /mock/expire-downloads` | Invalidate every access token / download URL |
| `POST /mock/reset` | Regenerate the site from the seed |

## Conformance tools ([`conformance/`](conformance/))

| Tool | What it does |
|---|---|
| `fetch_doc_examples.py` | Extracts Microsoft's example requests, responses and resource representations from the Graph documentation pages of the APIs the connector uses, at a pinned commit of `microsoftgraph/microsoft-graph-docs-contrib` (standard library only) |
| `check_conformance.py` | Checks a `GRAPH_RECORD` recording (or example files) against Microsoft's Graph v1.0 OpenAPI description at a pinned commit of `microsoftgraph/msgraph-metadata`: paths, methods, query parameters, response schemas, undeclared properties (`--strict`, with `--documented` accepting what Microsoft's examples show), verbatim paging links and `client-request-id`. Needs `pyyaml` and `jsonschema` (`uv run --with …`) |
| `documented_permissions.py` | From the permission tables of the Graph API pages, the application permissions every recorded call accepts (standard library only) |
| `selftest-negative.jsonl` | A recording the checker must reject (a hand-built paging URL, a request without `client-request-id`) |
| `record_proxy.py` | A recording reverse proxy for another Graph-like server (same JSON-lines format), which can also answer the token endpoint with a fixed bearer (standard library only) |
| `compare_acl_oracle.py` | Holds the permissions the connector indexed against the RBAC oracle of the independent mock-graph-server, user by user (needs `pyyaml`) |

## Used by

- `SharePointMockServerTest` runs the connector scenarios over HTTP:
  - a full crawl reconciled with `/mock/stats`;
  - delta increments;
  - a deleted folder;
  - the permission-only change that only a tree crawl notices;
  - a resync;
  - throttling and expired download URLs;
  - a wrong secret.

  `scripts/test-sharepoint-repository-connector.sh` starts the emulator, runs these tests and the conformance checks.
- `scripts/test-sharepoint-devproxy.sh` runs the scenarios through Microsoft Dev Proxy, with the real Microsoft host
  names rewritten to the emulator.
- `scripts/test-sharepoint-independent-mock.sh` runs the connector against an independently written Graph mock
  (sergey-shulyak/mock-graph-server) and its RBAC oracle, as a second opinion on this one.
- `scripts/test-sharepoint-decoupled.sh` with `docker/docker-compose-decoupled-with-sharepoint.yml` runs the whole
  pipeline from the emulator through Kafka, Tika, Ollama and pgvector to MCP secure search.
