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

Every error is `application/problem+json`:

```json
{
  "type": "https://opencrawling.org/problems/validation-failed",
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

| `type` (after `.../problems/`) | Status | When |
|---|---|---|
| `validation-failed` | 400 | Invalid fields or parameters; `errors` lists them |
| `malformed-request` | 400 | Not valid JSON, or a value of the wrong type |
| `job-invalid` | 400 | The repository connector rejects the job's settings |
| `invalid-query` | 400 | `executions:query` SQL refused |
| `forbidden` | 403 | The caller may not do this |
| `not-found` | 404 | No such resource, or not visible to the caller |
| `no-such-endpoint` | 404 | Unknown path |
| `export-not-available` | 404 | The execution has no report file in that format |
| `method-not-allowed` | 405 | Wrong method; see `Allow` |
| `unsupported-media-type`, `not-acceptable` | 415, 406 | Wrong `Content-Type` or `Accept` |
| `job-already-running` | 409 | `:start` while running; `executionId` names the run |
| `job-not-startable` | 409 | The job's connectors or settings cannot be used; `detail` says why |
| `connector-exists`, `connector-in-use` | 409 | Name taken; jobs still use it (`jobs` lists them) |
| `idempotency-in-progress` | 409 | A request with the same `Idempotency-Key` is still running |
| `precondition-failed` | 412 | `If-Match` does not match; `currentEtag` is the current one |
| `idempotency-key-reused` | 422 | The key was used for a different request |
| `too-many-runs` | 429 | A cap on concurrent runs is reached; see `Retry-After` |
| `upstream-error` | 502 | An external system (ManifoldCF) failed |
| `internal-error` | 500 | Unexpected; `requestId` finds it in the log |

A missing or invalid token gives `401` with `WWW-Authenticate`, from Spring Security.

### Request ids

Every response carries `X-Request-Id`. Send your own (1 to 64 letters, digits, `.`, `_`, `:` or `-`) to correlate with your logs; otherwise the runtime makes one. It is in every problem detail and in the runtime's log context (`requestId`).

### Collections: cursor paging, filters, sorting

```json
{
  "items": [ ... ],
  "page": {"limit": 50, "nextCursor": "djEuNTAuWnE3...", "totalItems": 120}
}
```

- **Paging:** `limit` from 1 to 500 (default 50); pass `page.nextCursor` as `cursor` for the next page. It is also in `Link: <...>; rel="next"`. `nextCursor` is `null` on the last page.
- **Cursors are opaque** and tied to the filters and sort they were made with; reusing one with others gives `400`.
- **Filters** are query parameters named after the field (`status`, `type`, `jobId`, ...); `q` searches text.
- **Sorting:** `sort=-lastRun,name`, comma-separated fields with `-` for descending. Each collection documents its fields; an unknown one gives `400`.

### Versions and concurrency: ETags

Single resources (jobs, connectors, system settings) return an `ETag`.

- `GET` with `If-None-Match: <etag>` answers `304 Not Modified` when nothing changed.
- `PUT`, `PATCH` and `DELETE` with `If-Match: <etag>` answer `412` when the resource changed meanwhile. Without `If-Match`, the change is applied unconditionally.
- A job's ETag covers its definition, not its run state, so a running job can still be edited safely.

### Changes: PUT and JSON Merge Patch

- `PUT` replaces the whole definition; optional fields left out are cleared.
- `PATCH` with `Content-Type: application/merge-patch+json` (RFC 7396) changes only the members sent; `null` removes an optional member.

### Custom methods

Operations other than create, read, update and delete are `POST` to `{resource}:{action}`, for example `POST /api/v1/jobs/{id}:start` or `POST /api/v1/connectors:check`.

### Idempotency keys

Send `Idempotency-Key: <key>` (1 to 200 letters, digits, `.`, `_`, `:` or `-`) with any `POST`.

- **Retry:** the first request runs; a retry with the same key and body gets the same answer again, with `Idempotent-Replayed: true`.
- **Reuse:** the same key with another body gives `422`; a retry while the first request still runs gives `409`.
- **Scope and lifetime:** keys are per caller, method and path, and remembered for 24 hours in the runtime's memory.
- **Failures:** only successful answers are remembered, so a failed request can be retried with the same key.

Use one for `:start`, so that a network retry does not start a second run.

---

## Endpoints

### Platform

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1` | Entry point: links, enabled features, documentation |
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
| POST | `/api/v1/job-runs/{executionId}:stop` | Stop it |

### Executions (finished runs)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/executions` | List, newest first (filters: `jobId`, `status`, `pipelineMode`; sort: `startTime`, `endTime`, `durationMs`, `totalDocuments`, `jobName`) |
| GET | `/api/v1/executions/{executionId}` | The report: totals, timings, cost, report files, provenance |
| GET | `/api/v1/executions/{executionId}/documents` | The document ledger (`status`, `q`, paged) |
| GET | `/api/v1/executions/{executionId}/export?format=` | The report file: `json`, `csv`, `parquet`, `avro` |
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

Secret settings (keys ending in `password`, `secret`, `token`, `apiKey`, `credential`, `privateKey`, ...) are always returned as `********`. Send `********` back to keep the stored value, so a client can `GET`, change something and `PUT` without knowing the secrets.

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

**Deprecation path:** the legacy endpoints stay until the admin UI has moved to `/api/v1`. They will then be marked with `Deprecation` and `Link: rel="successor-version"` headers before any removal.

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
- every admin UI call has a v1 operation;
- every admin-only route refuses other callers.
