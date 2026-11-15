# Platform API (`/api/v1`)

The Platform API is one versioned REST interface to everything OpenCrawling does, so adopters can run it entirely without the admin UI (#313, part 1). It covers what the UI can do: jobs and their runs, executions and audit reports, connectors, settings, operations, migrations and integrations. It also covers the optional features: discovery, dynamic jobs and sidecars.

- **Base URL:** `http(s)://<runtime>/api/v1`
- **Description:** OpenAPI 3.1 at `/v3/api-docs/v1`; interactive at `/swagger-ui.html` (pick "Platform API v1").
- **Entry point:** `GET /api/v1` lists every collection and the optional features enabled on the installation.

The existing endpoints (`/api/jobs`, `/api/connectors`, ...) keep working unchanged. The v1 endpoints call the same controllers, so validation, authorization and audit are the same; see [Legacy endpoints](#legacy-endpoints) for the mapping.

---

## Authentication and authorization

- **Tokens:** with `opencrawling.security.identity.mode=jwt` and `protect-api=true`, every request needs `Authorization: Bearer <token>`. The SDK and the CLI fetch tokens themselves (`oc auth login`, client credentials). See [Security and Identity Management](Security-and-Identity-Management).
- **Who may do what:** with management authorization on, the rules are the legacy ones.
  - Admins may do everything.
  - Job creators may create jobs, list connectors (without their configuration) and use the narrativization copilot.
  - Each job's `access` block decides who may see, run and change it. A job the caller may not see answers `404`.
  - Admin-only: connector changes and checks, settings, system logs, metrics, `executions:query`, migrations and integrations.
- **`GET /api/v1/me`:** the caller as the runtime sees it, with `admin` and `canCreateJobs`.

---

## Conventions

### Errors: problem details (RFC 9457)

Every error is `application/problem+json`. The one exception is `GET /api/v1/health`, whose `503` carries the health report itself.

```json
{
  "type": "https://github.com/opencrawling/opencrawling/wiki/Platform-API#problem-validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "The request has 2 invalid values.",
  "instance": "/api/v1/jobs",
  "requestId": "6a1f0c1e-...",
  "errors": [
    {"field": "name", "message": "must not be blank"},
    {"field": "outputConnector", "message": "no connector named 'Nope'"}
  ]
}
```

- **`type`:** identifies the problem, and leads to its row in the table below (`...Platform-API#problem-<name>`). Clients should branch on the part after `#problem-`; the SDK gives it as `ApiProblem.code()`.
- **`detail`:** says what went wrong, for people.
- **`requestId`:** quote it when reporting a problem; it finds the request in the runtime's log.
- **Other members:** some types add them, such as `errors`, `currentEtag`, `executionId`, `jobs` or `maxBytes`.

| Problem type | Status | When |
|---|---|---|
| <a id="problem-validation-failed"></a>`validation-failed` | 400 | Invalid fields or parameters; `errors` lists each one with its message |
| <a id="problem-malformed-request"></a>`malformed-request` | 400 | The body is not valid JSON, or has a value of the wrong type |
| <a id="problem-bad-request"></a>`bad-request` | 400 | Another invalid request, for example a merge patch that is not a JSON object, or a request an optional feature refuses; `detail` says why |
| <a id="problem-job-invalid"></a>`job-invalid` | 400 | The repository connector rejects the job's settings |
| <a id="problem-invalid-query"></a>`invalid-query` | 400 | `executions:query` refused the SQL (not read-only, or not valid) |
| <a id="problem-unauthorized"></a>`unauthorized` | 401 | No valid bearer token, when the API requires one; `WWW-Authenticate` says why (RFC 6750) |
| <a id="problem-forbidden"></a>`forbidden` | 403 | The caller may not do this |
| <a id="problem-not-found"></a>`not-found` | 404 | No such resource, or one the caller may not see |
| <a id="problem-no-such-endpoint"></a>`no-such-endpoint` | 404 | Unknown path |
| <a id="problem-export-not-available"></a>`export-not-available` | 404 | The execution has no report file in that format (Avro needs `spring.opencrawling.reporting.formats.avro`) |
| <a id="problem-method-not-allowed"></a>`method-not-allowed` | 405 | Wrong method for the path; `Allow` lists the right ones |
| <a id="problem-not-acceptable"></a>`not-acceptable` | 406 | None of the media types in `Accept` can be produced |
| <a id="problem-job-already-running"></a>`job-already-running` | 409 | `:start` while the job runs; `executionId` names the run |
| <a id="problem-job-not-startable"></a>`job-not-startable` | 409 | The job's connectors or settings cannot be used; `detail` says why |
| <a id="problem-connector-exists"></a>`connector-exists` | 409 | A connector with that name exists; replace it with `PUT` |
| <a id="problem-connector-in-use"></a>`connector-in-use` | 409 | Jobs use the connector; `jobs` lists them; delete with `force=true` to delete anyway |
| <a id="problem-idempotency-in-progress"></a>`idempotency-in-progress` | 409 | A request with the same `Idempotency-Key` is still running; see `Retry-After` |
| <a id="problem-idempotency-answer-unavailable"></a>`idempotency-answer-unavailable` | 409 | The request with this `Idempotency-Key` already succeeded (`firstStatus`), but its answer was too large to keep. It was not run again; read the resource instead |
| <a id="problem-conflict"></a>`conflict` | 409 | Another conflict with the resource's state, reported by an optional feature |
| <a id="problem-precondition-failed"></a>`precondition-failed` | 412 | `If-Match` does not match the current version; `currentEtag` is the current one |
| <a id="problem-precondition-required"></a>`precondition-required` | 428 | A change without `If-Match`, on a runtime that requires it (`opencrawling.api.require-if-match`) |
| <a id="problem-body-too-large"></a>`body-too-large` | 413 | The request body is larger than allowed; `maxBytes` says how large it may be (for request bodies; uploads have their own limits) |
| <a id="problem-unsupported-media-type"></a>`unsupported-media-type` | 415 | The `Content-Type` is not one the operation takes |
| <a id="problem-idempotency-key-reused"></a>`idempotency-key-reused` | 422 | The `Idempotency-Key` was used before for a different request |
| <a id="problem-too-many-runs"></a>`too-many-runs` | 429 | A cap on concurrent runs is reached; see `Retry-After` |
| <a id="problem-too-many-requests"></a>`too-many-requests` | 429 | Another limit reported by an optional feature |
| <a id="problem-internal-error"></a>`internal-error` | 500 | Unexpected; `requestId` finds it in the runtime's log. The detail never shows internals |
| <a id="problem-upstream-error"></a>`upstream-error` | 502 | An external system (ManifoldCF) failed |
| <a id="problem-unavailable"></a>`unavailable` | 503 | Something the request needs is not available (for example identity in `/me`) |
| <a id="problem-idempotency-unavailable"></a>`idempotency-unavailable` | 503 | The idempotency store (Redis) cannot be reached; the request was not run; see `Retry-After` |
| <a id="problem-error"></a>`error` | 4xx | Any other error status an optional feature reports; `status` gives it |

The table is the complete list: a test checks that the runtime cannot answer with a type that is not in it.

### Request ids

Every response carries `X-Request-Id`. Send your own (1 to 64 letters, digits, `.`, `_`, `:` or `-`) to correlate with your logs; otherwise the runtime makes one. It is in every problem detail and in the runtime's log context (`requestId`).

### Response headers and limits

Every Platform API answer has these headers, whatever the identity mode (OWASP REST Security Cheat Sheet):

- **`Cache-Control`:** answers depend on the caller and may hold secrets, so nothing shared may cache them. Reads are `private, no-cache`: a client may keep them, but revalidates with `If-None-Match` (RFC 9111). Everything else is `no-store`.
- **`Vary: Authorization`:** an answer depends on who asks.
- **`X-Content-Type-Options: nosniff`** and **`Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`:** a browser never treats an answer as a page.

**Request size:** a request body may have at most 1 MiB (`opencrawling.api.max-request-bytes`); a larger one answers `413 body-too-large`, whether it announces its length or not. Uploads (multipart) have their own limits (`spring.servlet.multipart.max-file-size`), and too large an upload answers `413` too. Settings maps (`crawlSpec`, connector `configuration`) may have at most 200 entries.

### Collections: cursor paging, filters, sorting

```json
{
  "items": [ ... ],
  "page": {"limit": 50, "nextCursor": "djEuNTAuWnE3...", "totalItems": 120}
}
```

- **Paging:** `limit` from 1 to 500 (default 50); pass `page.nextCursor` as `cursor` for the next page. It is also in `Link: <...>; rel="next"`. `nextCursor` is `null` on the last page.
- **Cursors are opaque** and tied to the filters and sort they were made with; reusing one with others gives `400`.
- **Stable pages:** a cursor resumes right after the last item it returned. Items added or removed before it (new executions arrive first, newest first) cause no duplicates and no gaps. Every sort ends with the item's id, so items with equal sort values keep one order. An execution's documents also tie the cursor to `limit`, so keep the page size the same while following cursors.
- **Filters** are query parameters named after the field (`status`, `type`, `jobId`, ...); `q` searches text.
- **Sorting:** `sort=-lastRun,name`, comma-separated fields with `-` for descending. Each collection documents its fields; an unknown one gives `400`.

### Versions and concurrency: ETags

Single resources (jobs, connectors, system and transport settings) return an `ETag`.

- **What a tag covers:** the resource as you received it. For a job that includes its state and your permissions, so a run that changes the state changes the tag. For a connector it also includes its secrets (as a keyed digest), so changing a secret changes the tag although the masked view looks the same.
- **Conditional reads:** `GET` with `If-None-Match: <etag>` answers `304 Not Modified` only when nothing in the answer changed.
- **Conditional changes:** `PUT`, `PATCH` and `DELETE` with `If-Match: <etag>` answer `412` when the resource changed since you read it. Read it again, and redo the change on what is there now. A job whose run changed its state since you read it answers `412` too.
- **Atomic:** the check and the change happen as one step, so of several changes made from the same version exactly one succeeds and the others get `412` (no lost updates, RFC 9110 13.1.1).
- **Without `If-Match`:** the change is applied unconditionally, unless the runtime sets `opencrawling.api.require-if-match=true`. Then every change of a job, connector or settings document through the Platform API must carry `If-Match`, and one without it answers `428 precondition-required` (RFC 6585). The admin UI, the CLI and the SDK read the version first, so they keep working. The deprecated legacy endpoints are not covered.
- **Weak tags (a deliberate deviation from RFC 9110):** a weak copy of a tag (`W/"..."`) is accepted in `If-Match`. RFC 9110 asks for strong comparison there, but proxies that compress responses, such as nginx with `gzip on`, turn tags weak. The tag digests the JSON, not the bytes sent, so it still names the same version.
- **Browsers:** the runtime sends no CORS headers, so serve browser clients from the same origin, as the admin UI is. A proxy that adds CORS must expose the headers clients read: `Access-Control-Expose-Headers: ETag, Location, Link, X-Request-Id`.

### Changes: PUT and JSON Merge Patch

- `PUT` replaces the whole definition; optional fields left out are cleared, except a job's `access`, which is kept when left out (it is changed only by sending it).
- `PATCH` with `Content-Type: application/merge-patch+json` (RFC 7396) changes only the members sent; `null` removes an optional member.

### Custom methods

Operations other than create, read, update and delete are `POST` to `{resource}:{action}`, for example `POST /api/v1/jobs/{id}:start` or `POST /api/v1/connectors:check`.

### Idempotency keys

Send `Idempotency-Key: <key>` (1 to 200 letters, digits, `.`, `_`, `:` or `-`) with any `POST` except uploads. The key may also be sent as a Structured Field string, `Idempotency-Key: "<key>"`, as the IETF draft writes it.

- **Retry:** the first request runs; a retry with the same key and body gets the same answer again, with `Idempotent-Replayed: true` (a widely used convention; the IETF draft does not define a header for it).
- **Reuse:** the same key with another body gives `422`; a retry while the first request still runs gives `409` with `Retry-After`.
- **Size:** a request with a key may have at most 1 MiB of body. An answer larger than 256 KiB (`opencrawling.api.idempotency.max-stored-bytes`) is not kept: a retry gets `409 idempotency-answer-unavailable`, with the first status, and is not run again.
- **Same request:** a retry must send the same body, query string and `Content-Type`; otherwise it is another request (`422`).
- **Uploads:** a multipart request with a key answers `400`; send uploads without one.
- **Scope and lifetime:** keys are per caller, method and path, and remembered for 24 hours. Without sign-in (`identity.mode=none`) every caller is the same anonymous caller, so clients must pick keys that cannot collide, such as UUIDs.
- **Where they are kept:** in the runtime's memory by default. With `opencrawling.api.idempotency.store=redis` they are kept in Redis, shared by every runtime instance and kept across restarts; the Docker Compose stacks use Redis. See [Configuration Guide](Configuration-Guide.md#-platform-api-idempotency-keys).
- **Store unavailable:** if Redis cannot be reached, the request is refused with `503` (`idempotency-unavailable`, with `Retry-After`) and not run. Retry later with the same key.
- **Long requests:** while a request runs, its key is held (Redis renews it), however long the request takes.
- **Limits of the memory store:** at most 10,000 keys, and 1,000 per caller. A key whose request still runs is never dropped before the retention ends; when a new key would need dropping one, the request is refused with `503`. Finished keys may be dropped before 24 hours when a caller or the runtime is at its limit. Without sign-in all clients are one caller and share its 1,000 keys; use the Redis store when many clients send keys.
- **The one gap:** if Redis fails after the request ran and stays down while the runtime retries keeping its outcome, the outcome is lost. Once the key's in-progress marker expires (10 minutes), a retry would run the request again. The runtime logs this as an error.
- **Failures:** only successful answers are remembered, so a failed request can be retried with the same key.

Use one for `:start`, so that a network retry does not start a second run.

> **zsh:** in a shell variable followed by `:start`, zsh reads `:s` as a modifier, so `"$API/jobs/$ID:start"` loses the action. Write `${ID}:start`.

---

## Endpoints

### Platform

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1` | Entry point: `links` (`jobs`, `jobRuns`, `executions`, `connectors`, `systemSettings`, `transportSettings`, `systemStatus`, `systemThroughput`, `systemLogs`, `metrics`, `me`, and the enabled features' collections), enabled `features`, `documentation` |
| GET | `/api/v1/version` | API and runtime versions |
| GET | `/api/v1/health` | Health of the runtime and its dependencies (200 healthy, 503 otherwise) |
| GET | `/api/v1/me` | The current caller |

### Jobs

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/jobs` | List (filters: `status`, `pipelineMode`, `repositoryConnector`, `outputConnector`, `q`; sort: `id`, `name`, `status`, `lastRun`) |
| POST | `/api/v1/jobs` | Create; `201` with `Location` and `ETag` |
| GET | `/api/v1/jobs/{id}` | Read: definition, `state`, and the caller's `permissions` |
| PUT | `/api/v1/jobs/{id}` | Replace the definition |
| PATCH | `/api/v1/jobs/{id}` | Merge patch |
| DELETE | `/api/v1/jobs/{id}` | Delete (`204`) |
| POST | `/api/v1/jobs/{id}:start` | Start a run: `202` with the run and `Location: /api/v1/job-runs/{executionId}` |
| POST | `/api/v1/jobs/{id}:stop` | Stop the run (`202`), or mark a job with no run Finished (`200`) |
| POST | `/api/v1/jobs/{id}:pause` | Mark the job Paused, as the UI does; does not suspend a run |
| GET | `/api/v1/jobs/{id}/executions` | The job's executions |
| GET | `/api/v1/jobs/{id}/diagnosis` | AIOps root-cause analysis |
| GET | `/api/v1/jobs/{id}/traces` | OpenTelemetry spans |
| GET | `/api/v1/jobs/{id}/errors` | Error log entries (`timeframe`) |

A job: `id`, `name`, connectors (`repositoryConnector`, `outputConnector`, `transformationConnector`, `authorityConnector`), `path`, `pipelineMode`, `crawlSpec`, `narrativization`, `access`, then `state` (`status`, `stage`, `documents`, `lastRun`) and `permissions` (`READ`, `EXECUTE`, `MANAGE`). On input, `state` and `permissions` are not accepted. Every connector named must exist and be of the right category.

### Job runs (in progress)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/job-runs` | Runs in progress (`jobId`) |
| GET | `/api/v1/job-runs/{executionId}` | One run; when it has ended, see its execution |
| POST | `/api/v1/job-runs/{executionId}:stop` | Stop exactly this run: `404` if it has ended, and a newer run of the same job is never stopped |

### Executions (finished runs)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/executions` | List, newest first (filters: `jobId`, `status`, `pipelineMode`; sort: `startTime`, `endTime`, `durationMs`, `totalDocuments`, `jobName`) |
| GET | `/api/v1/executions/{executionId}` | The report: totals, timings, cost, report files, provenance |
| GET | `/api/v1/executions/{executionId}/documents` | The document ledger (`status`, `q`, paged) |
| GET | `/api/v1/executions/{executionId}/export?format=` | The report file: `json`, `csv`, `parquet`, `avro` (`avro` only when `spring.opencrawling.reporting.formats.avro` is on; otherwise `404`) |
| POST | `/api/v1/executions:query` | DuckDB SQL over the audit lake (admins) |

### Connectors

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/connectors` | List (`type`, `q`); job creators get no configuration |
| POST | `/api/v1/connectors` | Create (`409` if the name is taken) |
| GET | `/api/v1/connectors/{name}` | Read |
| PUT | `/api/v1/connectors/{name}` | Replace |
| DELETE | `/api/v1/connectors/{name}` | Delete; `409` while jobs use it, unless `force=true` |
| POST | `/api/v1/connectors/{name}:check` | Live connectivity check of a saved connector |
| POST | `/api/v1/connectors:check` | The same for a definition not saved yet |

**The Platform API never returns secrets.** The deprecated `GET /api/connectors` still returns them to admins, unchanged for existing integrations; that is one more reason to move off it.

- **Which keys are secret:** any key that contains `secret` (`clientSecret`, `s3SecretAccessKey`), keys that end in `password`, `token`, `apiKey`, `credential`, `privateKey`, `passphrase`, `ticket` or `connectionString` (but not `changeLogToken`, a position in a change log), and header settings (`customHeaders`, `authorization`).
- **How they look:** they are returned as `********`. A password inside a URL (`scheme://user:password@host`) is returned as `scheme://user:********@host`, and a `password=` parameter of a URL or connection string as `password=********`.
- **Edited values:** a value that still holds `********` but is not exactly the value that was read is refused, so the mask itself is never stored as a password.
- **Sending them back:** a value sent back as it was read keeps the stored secret, so a client can `GET`, change something and `PUT` without knowing the secrets.
- **Changing the address:** if the change also changes where or how the connector connects, secrets sent back as the mask are refused (`400`, naming them) and must be entered again. That covers settings named like a URL, host, server or port, endpoint, bootstrap, TLS or SSL settings, and a change of `className`. Otherwise a stored password could be sent to a server of the caller's choice, or in clear.

### Settings (admins)

| Method | Path | Purpose |
|---|---|---|
| GET, PUT | `/api/v1/settings/system` | Crawling and extraction settings (ETag, `If-Match`) |
| GET, PUT | `/api/v1/settings/transport` | Internal transport (gRPC or REST) |
| POST | `/api/v1/settings/transport:test-grpc` | Test the gRPC transport |

### Operations

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/system/status` | Each dependency: UP or DOWN |
| GET | `/api/v1/system/throughput` | Recent throughput |
| GET | `/api/v1/system/logs` | Recent log lines (admins) |
| GET | `/api/v1/observability/metrics` | Throughput metrics per connector (admins) |

### Tools

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/migrations/manifoldcf:plan` | Plan a ManifoldCF migration (admins) |
| POST | `/api/v1/migrations/manifoldcf:apply` | Apply it (admins) |
| GET | `/api/v1/integrations/vespa/health`, `/document-counts` | Vespa (admins) |
| POST | `/api/v1/integrations/vespa:query`, `:deploy-bundled`, `:deploy-custom` | Vespa (admins) |
| POST | `/api/v1/narrativization:generate-template` | Suggest a narrativization template (admins, job creators) |

### Optional features

Only present when the feature is enabled; `GET /api/v1` says which are.

| Feature | Endpoints |
|---|---|
| Discovery (`opencrawling.discovery.enabled`) | `GET /api/v1/discovery/connectors`, `GET .../{connector}/tree`, `POST .../{connector}:dry-run` |
| Dynamic jobs (`opencrawling.jobs.dynamic.enabled`) | `POST /api/v1/dynamic-jobs`, `POST /api/v1/dynamic-jobs:ingest-document`, `GET /api/v1/dynamic-jobs`, `GET .../{jobId}`, `POST .../{jobId}:stop`, `:approve`, `:reject` |
| Sidecars (`opencrawling.sidecars.enabled`) | `GET /api/v1/sidecar-stores`, `GET /api/v1/sidecar-stores/{connector}/sidecars` |

---

## Example: from nothing to an audited run

```bash
API=http://localhost:8080/api/v1
AUTH="Authorization: Bearer $TOKEN"     # leave out when the API is open

# Create a job (safe to retry)
curl -s -X POST "$API/jobs" -H "$AUTH" -H 'Content-Type: application/json' -H 'Idempotency-Key: hr-contracts-1' \
  -d '{"name":"HR contracts","repositoryConnector":"FileSystem_Local","outputConnector":"PGVector_Output",
       "path":"/data/hr","pipelineMode":"rag"}'
# 201 Location: /api/v1/jobs/1763038591234

# Start it, follow it
curl -s -X POST "$API/jobs/1763038591234:start" -H "$AUTH" -H 'Idempotency-Key: hr-contracts-run-1'
# 202 {"executionId":"exec-1a2b3c4d","jobId":"1763038591234",...}
curl -s "$API/jobs/1763038591234" -H "$AUTH" | jq .state

# The audit trail of the run
curl -s "$API/executions/exec-1a2b3c4d" -H "$AUTH"
curl -s "$API/executions/exec-1a2b3c4d/documents?status=FAILED&limit=100" -H "$AUTH"
curl -s -o run.parquet "$API/executions/exec-1a2b3c4d/export?format=parquet" -H "$AUTH"

# Rename it without overwriting someone else's change
ETAG=$(curl -s -D - -o /dev/null "$API/jobs/1763038591234" -H "$AUTH" | awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
curl -s -X PATCH "$API/jobs/1763038591234" -H "$AUTH" -H 'Content-Type: application/merge-patch+json' -H "If-Match: $ETAG" \
  -d '{"name":"HR contracts (2026)"}'
```

---

## Legacy endpoints

| Legacy | Platform API |
|---|---|
| `GET/POST /api/jobs`, `GET/DELETE /api/jobs/{id}` | `/api/v1/jobs`, `/api/v1/jobs/{id}` (plus `PUT`, `PATCH`) |
| `POST /api/jobs/{id}/start`, `/stop`, `/pause` | `POST /api/v1/jobs/{id}:start`, `:stop`, `:pause` |
| `GET /api/jobs/permissions` | `permissions` on each job |
| `GET /api/jobs/runs`, `GET /api/jobs/{id}/run` | `GET /api/v1/job-runs`, `GET /api/v1/job-runs?jobId=` |
| `GET /api/reports`, `/api/reports/{jobId}/{executionId}` | `GET /api/v1/executions`, `/api/v1/executions/{executionId}` |
| `GET /api/reports/{jobId}/{executionId}/documents`, `/export` | `.../executions/{executionId}/documents`, `/export` |
| `POST /api/reports/query` | `POST /api/v1/executions:query` |
| `/api/connectors...`, `POST /api/connectors/check` | `/api/v1/connectors...`, `POST /api/v1/connectors:check` |
| `/api/system/settings`, `/api/system/settings/transport` | `/api/v1/settings/system`, `/api/v1/settings/transport` |
| `/api/system/status`, `/throughput`, `/logs` | `/api/v1/system/status`, `/throughput`, `/logs` |
| `/api/observability/diagnose/{jobId}`, `/traces/{jobId}`, `/errors/{jobId}`, `/metrics` | `/api/v1/jobs/{id}/diagnosis`, `/traces`, `/errors`, `/api/v1/observability/metrics` |
| `/api/mcf-migration/plan`, `/apply` | `/api/v1/migrations/manifoldcf:plan`, `:apply` |
| `/api/vespa/...` | `/api/v1/integrations/vespa/...` |
| `/api/transformation/copilot/generate` | `/api/v1/narrativization:generate-template` |
| `/api/discovery/...`, `/api/jobs/dynamic/...`, `/api/sidecars/...` | `/api/v1/discovery/...`, `/api/v1/dynamic-jobs/...`, `/api/v1/sidecar-stores/...` |
| `/api/auth/me` | `/api/v1/me` |

Not duplicated: OIS ingest (`/api/v1/ingest/ois/{jobId}`) and BPM events (`/api/events/bpm/...`) are machine-to-machine endpoints with their own authentication; `/api/auth/config` stays public for the admin UI.

**Deprecation:** the admin UI, the CLI and the Java SDK's `platform()` client use `/api/v1`, so the legacy management endpoints in the table are deprecated. Each answer of one also links to its exact successor (`/api/jobs/42/start` to `/api/v1/jobs/42:start`), and to this section with `Link: <...>; rel="deprecation"` (RFC 9745). They still work exactly as before, for existing integrations. What marks them:

- **Response headers:** each answer carries `Deprecation: @1794700800` (RFC 9745: deprecated since 2026-11-15) and `Link: </api/v1/...>; rel="successor-version"` naming the replacement.
- **Swagger UI:** in "All endpoints" they are marked deprecated, with their successor.
- **SDK:** its earlier clients log a warning, once per client, pointing at `platform()`.

Removal will be announced separately; no `Sunset` date is set. The endpoints listed under "Not duplicated" are not deprecated.

---

## Clients

### Admin UI

Every call the admin UI makes goes to `/api/v1`, except the public `GET /api/auth/config`, which it reads before sign-in. A test (`PlatformApiV1UiParityTest`) reads the UI's sources and checks two things: each call is a documented v1 operation, and everything the UI did before still uses one.

- Editing a job or a connector, and saving settings, use the version read with the form (`If-Match`). A change made meanwhile answers `412`, and the page asks to reopen or reload. Deletes read the current version first.
- Lists follow the cursors.
- Errors show the problem's `detail` and field errors.

### Java SDK

`client.platform()` is the Platform API client (package `org.opencrawling.sdk.v1`):

```java
PlatformClient api = OpenCrawlingClient.builder().baseUrl("http://localhost:8080").tokenProvider(tokens).build().platform();

Versioned<Job> job = api.jobs().create(JobInput.of("HR contracts", "FileSystem_Local", "PGVector_Output", "/data/hr"),
        RequestOptions.idempotent("hr-contracts-1"));                          // a retry returns the same job
Versioned<JobRun> run = api.jobs().start(job.value().id(), RequestOptions.idempotent("hr-run-1"));

Versioned<Job> read = api.jobs().get(job.value().id());
api.jobs().patch(read.value().id(), Map.of("path", "/data/hr/2026"), RequestOptions.ifMatch(read.etag()));

api.executions().stream(ExecutionFilter.all().withJobId(job.value().id()))    // follows the cursors
        .forEach(execution -> System.out.println(execution.status()));

try {
    api.jobs().start(job.value().id(), RequestOptions.none());
} catch (ApiProblemException e) {
    e.code();                     // "job-already-running"
    e.problem().getRequestId();   // quote it when reporting a problem
}
```

- **Coverage:** one method per operation. A test (`PlatformApiV1SdkTest`) runs the SDK against the runtime and checks that it reaches every operation in the API description.
- **Retries:** requests that are safe to repeat are repeated after network errors, `429`, `502`, `503`, `504` and `409 idempotency-in-progress`, with backoff and the runtime's `Retry-After` (`RetryPolicy`). The safe ones are `GET`, `PUT`, `DELETE`, a `PATCH` with `If-Match`, and every `POST`, because the SDK gives each `POST` an `Idempotency-Key` unless you do. Uploads are sent once. `withDefaults(...)` applies correlation and request ids to every call; keys and `If-Match` belong to single calls.
- **Settings:** `SystemSettings` keeps every field the runtime sends, so a read, change and replace never resets the settings you did not touch.
- **Earlier clients:** the SDK's other clients (`jobs()`, `connectors()`, `system()`, ...) still use the legacy endpoints, which are deprecated; they log a warning once. Their `system().updateSettings` changes only the fields its model has and keeps the runtime's other settings.

### CLI

Every `oc` command uses `/api/v1`, with these exceptions and changes:

- **Discovery with an asserted identity:** `oc discovery ... --user-principal/--user-roles` keeps the legacy endpoint. Asserted identities are a dev/test feature that the Platform API never accepts; it takes the caller from the token.
- **`oc connector create`:** still creates or updates. It creates the connector, and replaces it when the name exists.
- **`oc connector delete`:** refuses a connector that jobs use, and names them; `--force` deletes it anyway.
- **`oc job start`:** creates and starts the job with idempotency keys. `--output` picks the output connector, and `--wait` follows the run until it ends.
- **`oc report ledger --page N`:** follows the cursors to page N.
- **`oc system settings`:** shows every setting.

---

## Trying it

### API Console (in the admin UI)

Open the admin UI and choose **API Console**. It is generated from `/v3/api-docs/v1`, so it always matches the runtime it talks to.

- **Explorer:** every operation, grouped by area, searchable.
- **Documentation:** each operation's description, its responses, and a curl sample.
- **Request builder:**
  - path, query and header parameters, each with its description, and the required ones marked;
  - free headers, with helpers for `Idempotency-Key` and `X-Request-Id`;
  - a body pre-filled with the documented example, checked as JSON as you type.
- **Sending:** **Send** or Ctrl+Enter. Requests carry your sign-in token; untick "Send my token" to see how the API answers an anonymous caller.
- **Response inspector:**
  - **Body:** status, time and size; the body pretty-printed; problem details shown as a readable card with the field errors; files offered as a download.
  - **Headers:** every response header, and what the important ones mean.
  - **REST:** the mechanics of the answer, with one-click follow-ups:
    - ETag to `If-Match` or `If-None-Match`;
    - open the `Location`;
    - load the next page;
    - idempotent replay, `Retry-After` and the request id.
  - **curl:** the exact request as a command, with the token as `$OC_TOKEN`.
  - **History:** the last 25 requests, restorable in one click. The token is never stored.

### Swagger UI

`/swagger-ui.html`, "Platform API v1": the same description, with "Try it out", and "Authorize" for a token.

### Bruno, Postman, Insomnia

Import `http(s)://<runtime>/v3/api-docs/v1` as an OpenAPI collection. Every operation comes with its parameters, descriptions and request examples. Set the bearer token at collection level when the API requires one.

### curl

Every operation's documentation ends with a ready curl command (`x-codeSamples`). It uses `$OC_URL` for the runtime and `$OC_TOKEN` for the token.

### End-to-end script

`scripts/test-platform-api-v1.sh --url http://localhost:8080 [--token $TOKEN] [--crawl-path /dir]` checks a running runtime:

- the index and the API description;
- connectors and secret masking;
- a job's whole lifecycle, with ETags, merge patch and idempotency;
- with `--crawl-path`, a real run and its execution report;
- the error format.

Without `--url` it boots the decoupled stack, as CI does ("Platform API v1 Test Suite").

## Guarantees

[Platform API: traceability and test evidence](Platform-API-Traceability) maps every scope item and every operation to the tests that prove it. The tests also guard the contract:

- the documented operations are exactly the implemented ones;
- every operation is fully documented;
- every documented example works;
- every admin UI call is a documented v1 operation, and nothing the UI did was lost;
- the Java SDK reaches every operation, and each CLI command sends the requests it should;
- every admin-only route refuses other callers;
- every deprecated legacy endpoint names its successor, and the others are not marked.
