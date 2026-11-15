# MFWS mock: an M-Files Web Service emulator

M-Files Server is a licensed Windows product without a container image, so there is no throwaway M-Files to start in a
test. The MFWS mock fills that gap: a small, stateful emulator of the **M-Files Web Service REST API** that serves a
generated vault, so the M-Files repository connector can be tested end to end, over real HTTP, without an M-Files
licence. It is one Python file using only the standard library.

> **What it is not.** The mock follows the public MFWS reference (developer.m-files.com) and the behaviour of real
> M-Files servers where it is known (the `{"Value": …}` token, anonymous tokens for empty credentials, `MoreResults`,
> unsorted search results). Its `/objects/{type}/{id}/{version}/permissions` payload mirrors one captured from a live
> vault (numeric codes with `1` = allow, `IsPsudoUser`, the effective `AccessControlList` and the separate `NamedACL`
> definition); its deny code `2` is an assumption. It proves the connector against an MFWS-shaped server, not every
> M-Files behaviour: run the connector's read-only live probe against a real server for that.

## Running it

```bash
# locally (Python 3.10+)
python3 oc-mfiles-repository-connector/mfiles-mock/mfws_mock.py          # http://localhost:8080/REST

# in Docker
docker build -t opencrawling/mfws-mock:local oc-mfiles-repository-connector/mfiles-mock
docker run --rm -p 8090:8080 opencrawling/mfws-mock:local                # http://localhost:8090/REST
```

Point the connector at it with `url: http://localhost:8090/REST`, `vault-guid: {C0B3B52E-6512-4BD8-8F2B-2A8E9A349201}`,
`username: crawler`, `password: secret`.

| Environment variable | Default | Meaning |
|---|---|---|
| `MFWS_PORT` | `8080` | Listening port |
| `MFWS_VAULT_GUID`, `MFWS_VAULT_NAME` | `{C0B3B52E-…-2A8E9A349201}`, `Contracts (mock)` | The vault |
| `MFWS_USERS` | `crawler:secret` | Accepted `user:password` pairs, comma-separated |
| `MFWS_BEARER_TOKENS` | — | Accepted OAuth2 bearer tokens, comma-separated |
| `MFWS_OBJECT_COUNT`, `MFWS_CUSTOMER_COUNT` | `600`, `20` | Generated documents and customers |
| `MFWS_SEED` | `42` | The vault is generated deterministically from this seed |
| `MFWS_DEFAULT_LIMIT`, `MFWS_VIEW_LIMIT` | `500`, `500` | Results per search / view request before `MoreResults` |
| `MFWS_VAF_PATH` | `/vaf/indexer` | Path of the emulated Vault Application Framework app |
| `MFWS_PERMISSIONS` | `acl` | `absent` emulates a server without the permissions resource (404) |
| `MFWS_QUIET` | `false` | Suppress the request log |

## The generated vault

- **Object types** Document (0) and Customer (103); classes Non-Disclosure Agreement (14), Master Services Agreement
  (22), Invoice (5), Policy (40) and Customer (31).
- **Documents** with real **PDF, DOCX and TXT** files that Apache Tika extracts. Every file's text carries a unique
  reference code `MFX-<objectId>-v<version>-f<fileId>`, so a test can trace any chunk back to its object, version and
  file. Every third Master Services Agreement is a **three-file document**. A quarter of the documents have 2–4
  **versions**. Every twentieth document is **deleted**. Every twenty-fifth was created in one **bulk import** (the
  same second), to exercise time windows that hold more results than the limit.
- **Typed properties**: text, multi-line text, floating, date, timestamp, boolean, lookups (customer, department,
  workflow, state, users) and a multi-select lookup (tags).
- **Value lists** 6 (users), 16 (user groups), as on a real vault, plus 200 (tags), 201 (departments), 7 and 8 (workflow and states).
- **Permissions**, six patterns repeating by document: everybody (group 1); legal group plus a user; an object ACL
  restricted by a named ACL "Legal only" with a deny for user 15; finance plus the pseudo-user "Project manager"; two
  users; and no entries at all.
- **Views**: `v101` (by class, property folders `L<classId>`), `v102` (customers), `v103` (modified in 2026) with the
  nested view `v104` (large contracts).
- **VAF**: `/REST/vaf/indexer/changes?since=<cursor>&pageSize=<n>` and
  `/REST/vaf/indexer/object/{type}/{id}/{version}/normalized`, whose ACL tokens are computed the way a vault application
  would: pseudo-users resolved, components intersected, denies listed separately.

## Behaviour emulated

- `POST /REST/server/authenticationtokens` returns `{"Value": token}`. Empty credentials give an anonymous token that
  fails every vault call with 403 ("Authentication token does not contain user information"). A token without a vault
  GUID is not vault-specific, and a wrong password is a 403.
- `X-Username` / `X-Password` / `X-Vault` headers (with `DOMAIN\user`) and `Authorization: Bearer` with `X-Vault`.
- `DELETE /REST/session` ends the session (also as `POST …?_method=DELETE`); the token stops working.
- `GET /REST/objects` with `q`, `o`, `d=include`, `p<id>` with `=`, `>=`, `<=`, `*=` (contains) and `^=` (starts with),
  and `limit`. Results are deliberately **not** sorted by last-modified time, and `MoreResults` is set when the limit
  cut the result.
- Object versions, `?include=properties`, `/properties`, `/files`, `/files/{id}/content`, `/history`, `/permissions`,
  `/session/vault`, `/structure/objecttypes|properties|classes`, `/valuelists/{id}/items`, `/views/…/items`,
  `/server/status`, `/server/vaults`.
- Errors in the MFWS shape: `{"Status", "URL", "Method", "Message", "Exception": {"Name", "Message"}}`.

## Mock-only administration (`/mock/…`, not authenticated)

| Endpoint | Effect |
|---|---|
| `GET /mock/stats` | Counts (objects, live files, deleted, multi-file, VAF events), active and ended sessions, requests per route |
| `GET /mock/objects/{type}/{id}` | An object's version, permissions, effective VAF tokens and file texts (ground truth for tests) |
| `POST /mock/objects` | Create a document: `{"classId", "title", "text", "extension"}` |
| `POST /mock/objects/{type}/{id}/modify` | New version: `{"removeFileIds": [...], "appendText", "title"}` |
| `POST /mock/objects/{type}/{id}/delete` | Delete the object (and log a VAF `DELETED` change) |
| `POST /mock/faults` | Fail the next `count` requests whose URL starts with `path` with `status`; `expireTokens: true` also ends every session |
| `POST /mock/expire-tokens` | End every session |
| `POST /mock/reset` | Regenerate the vault from the seed |

## Used by

- `MFilesMockServerTest` (connector scenarios over HTTP: full crawl reconciled with `/mock/stats`, incremental run after
  changes, VAF with resolved pseudo-users, retries, token renewal, header and bearer logins), started by
  `scripts/test-mfiles-repository-connector.sh`.
- `scripts/test-mfiles-decoupled.sh` with `docker/docker-compose-decoupled-with-mfiles.yml`: the whole pipeline from the
  mock through Kafka, Tika, Ollama and pgvector to MCP secure search.
