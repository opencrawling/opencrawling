# Platform API: traceability and test evidence

This page traces the scope of #313 part 1 (the management API, UI parity) to the endpoints that implement it and to the tests that prove them. It is the release checklist for the Platform API: every row must stay green.

Test classes (all in `oc-runtime/src/test/java/org/opencrawling/runtime/api/v1/`, prefixed `PlatformApiV1`):

| Label | Class | What it proves |
|---|---|---|
| Core | `PlatformApiV1HttpTest` | Jobs, runs, executions, connectors, settings and the conventions, over real HTTP |
| Authorization | `PlatformApiV1AuthorizationHttpTest` | The same API with JWT, `protect-api` and management authorization: who may do what |
| Operations | `PlatformApiV1OperationsHttpTest` | Observability, transport, Vespa, ManifoldCF (against fixtures), narrativization, export and query, with realistic data |
| Features | `PlatformApiV1FeaturesHttpTest` | Discovery, dynamic jobs and sidecars switched on |
| Examples | `PlatformApiV1ExamplesHttpTest` | Every documented request example is accepted by its endpoint |
| DocumentationContract | `PlatformApiV1DocumentationContractTest` | Every operation is fully documented (description, parameters, examples, schemas, problem details, code sample) |
| UiParity | `PlatformApiV1UiParityTest` | Every call the admin UI makes has a v1 operation, read from the UI's sources |
| Unit | `PlatformApiV1UnitTest` | Cursors, ETags, merge patch, idempotency store, secret masking, request scoping, error mapping |
| Console | `oc-admin-ui/src/lib/apiConsole.test.ts` | The API console's logic: reading the description, building and describing requests, history |
| Script | `scripts/test-platform-api-v1.sh` | End to end against a real runtime (CI: "Platform API v1 Test Suite") |

## Guarantee layers

1. **Contract:** the OpenAPI document lists exactly the expected operations at their methods and paths (Core and Features: `theOpenApiDocumentDescribesThePlatformApiOnly`, `theOptionalFeaturesAreDescribedAtTheirPaths`). An endpoint that moves or appears without being documented fails.
2. **Documentation:** DocumentationContract fails on any operation without a summary, a description, parameter descriptions, a request example, a response schema, problem-detail error responses or a curl sample. Examples proves the examples work.
3. **Behaviour:** every one of the 61 operations is called by at least one HTTP test (table below).
4. **Security:** Authorization calls each admin-only route as a job creator and as a plain user and expects `403`, checks job visibility, ownership, `401` and per-caller idempotency.
5. **Parity:** UiParity keeps the API complete as the admin UI grows.
6. **Mutation check:** 16 deliberate breakages of the API's rules (access rules, error scoping, ETags, cursors, idempotency, secret masking, validation, request ids, Location) are each caught by a test.
7. **Real runtime:** the script runs the API against the full decoupled stack (see [Real-stack run](#real-stack-run)).

## Scope to evidence

| Scope item (#313) | Implemented by | Evidence | Status |
|---|---|---|---|
| §2 Management: everything the admin UI can do, without the UI | 49 core operations under `/api/v1` | UiParity; Core; Operations | Done |
| §2 One consistent, versioned, documented surface | `/api/v1`, OpenAPI group `v1`, `docs/Platform-API.md` | DocumentationContract; Core OpenAPI test | Done |
| §8 Platform: catalog, health, version | `GET /api/v1`, `/health`, `/version` (the scenario catalog is part 2) | Core | Done (catalog of scenarios: part 2) |
| §8 Management endpoints (jobs, connectors, runs, reports/audit, settings, discovery, observability, migrations) | Jobs, Job runs, Executions, Connectors, Settings, Operations, Tools, Discovery | Core; Operations; Features | Done |
| §8 Convention: resource naming, `:action` custom methods | e.g. `POST /jobs/{id}:start`, `/connectors:check` | Core OpenAPI test (paths pinned) | Done |
| §8 Convention: cursor pagination, filtering | `Paging`, `page.nextCursor`, `Link: rel="next"` | Core `jobsAreFilteredSortedAndPagedWithACursor`; Unit | Done |
| §8 Convention: RFC 9457 problem details | `ApiV1ExceptionResolver` | Core `errorsBeforeAndOutsideControllersAreProblemsToo`; every 4xx test | Done |
| §8 Convention: ETags | `Etags`, `If-None-Match`, `If-Match` | Core `replacingAndPatchingRespectIfMatch`, `settingsHaveETagsAndRefuseStaleChanges`; Unit | Done |
| §8 Convention: deprecation headers for old endpoints | Documented path in `docs/Platform-API.md` | — | Planned: after the admin UI moves to `/api/v1` |
| §9 `Idempotency-Key` on start | `IdempotencyFilter` (all POSTs) | Core `startingAJob...`, `anIdempotencyKeyCannotBeReused...`; Authorization `idempotencyKeysAreScopedToTheCaller`; Unit | Done |
| §9 Follow a run (poll) | `GET /job-runs/{id}`, job `state`, executions | Core `aRunInProgressIsReadAndStoppedThroughItsOwnResource` | Done |
| §9 SSE, webhooks, review tasks, scenario runs | — | — | Part 2 (scenario runs) |
| §10 Bearer tokens, installation roles | #187; v1 admin rules in `ManagementAuthorizationInterceptor` | Authorization | Done |
| §10 Document authorization never widened | v1 calls the same controllers and services | Authorization `jobsFollowTheirAccess`; Features delegation | Done (scenario-level checks: part 2) |
| §11 Who started each run | `ExecutionProvenance` via `JobController.start` | `JobStartProvenanceHttpTest`; Core start with `X-Correlation-Id` | Done |
| §13 OpenAPI 3.1 in Swagger UI, contract-first | `/v3/api-docs/v1`, `/swagger-ui.html` | DocumentationContract; Examples | Done |
| §13 A way to try every endpoint | API console in the admin UI; Swagger UI; curl samples; Bruno/Postman/Insomnia import | Console; Script | Done |
| §13 SDK and CLI for `/api/v1` | — | — | Follow-up (they use the legacy endpoints, which keep working) |
| §16 Existing endpoints keep working | Legacy controllers unchanged in behaviour | Core `theLegacyEndpointsAnswerAsBefore`; all legacy test classes | Done |

## Operations

All 61 operations (49 core, 12 in optional features). Every operation is also covered by DocumentationContract, and those with a request body by Examples.

| Operation | Method and path | Who may call it | Behaviour tested in |
|---|---|---|---|
| `getApiIndex` | `GET /api/v1` | Any caller | `Features.theIndexAnnouncesTheEnabledFeatures`<br>`Core.theIndexLinksEveryCollectionAndTheVersionAndHealthAnswer` |
| `getVersion` | `GET /api/v1/version` | Any caller | `Core.theIndexLinksEveryCollectionAndTheVersionAndHealthAnswer` |
| `getHealth` | `GET /api/v1/health` | Any caller | `Core.theIndexLinksEveryCollectionAndTheVersionAndHealthAnswer` |
| `getCurrentCaller` | `GET /api/v1/me` | Any caller | `Authorization.jobsFollowTheirAccess` |
| `listJobs` | `GET /api/v1/jobs` | Per job access (READ, EXECUTE, MANAGE) | `Authorization.jobsFollowTheirAccess`<br>`Authorization.withoutATokenNothingAnswersButTheProblemHasARequestId`<br>`Core.anIdempotencyKeyCannotBeReusedForADifferentRequest`<br>`Core.jobsAreFilteredSortedAndPagedWithACursor`<br>`Operations.aManifoldCfMigrationIsPlannedAndAppliedInProcess` |
| `createJob` | `POST /api/v1/jobs` | Admins, job creators (caller becomes owner) | `Authorization.idempotencyKeysAreScopedToTheCaller`<br>`Authorization.jobsFollowTheirAccess`<br>`Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Core.aJobIsCreatedReadAndConditionallyRead`<br>`Core.anIdempotencyKeyCannotBeReusedForADifferentRequest`<br>`Core.errorsBeforeAndOutsideControllersAreProblemsToo`<br>`Core.invalidJobsAreRefusedWithFieldErrors` |
| `getJob` | `GET /api/v1/jobs/{id}` | Per job access (READ, EXECUTE, MANAGE) | `Authorization.jobsFollowTheirAccess`<br>`Core.aDeletedJobIsGone`<br>`Core.aJobIsCreatedReadAndConditionallyRead`<br>`Core.aRunInProgressIsReadAndStoppedThroughItsOwnResource`<br>`Core.errorsBeforeAndOutsideControllersAreProblemsToo`<br>`Core.replacingAndPatchingRespectIfMatch` |
| `replaceJob` | `PUT /api/v1/jobs/{id}` | Per job access (READ, EXECUTE, MANAGE) | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Core.replacingAndPatchingRespectIfMatch` |
| `patchJob` | `PATCH /api/v1/jobs/{id}` | Per job access (READ, EXECUTE, MANAGE) | `Authorization.jobsFollowTheirAccess`<br>`Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Core.aConnectorInUseIsOnlyDeletedWithForce`<br>`Core.replacingAndPatchingRespectIfMatch` |
| `deleteJob` | `DELETE /api/v1/jobs/{id}` | Per job access (READ, EXECUTE, MANAGE) | `Authorization.idempotencyKeysAreScopedToTheCaller`<br>`Authorization.jobsFollowTheirAccess`<br>`Core.aDeletedJobIsGone` |
| `startJob` | `POST /api/v1/jobs/{id}:start` | Per job access (READ, EXECUTE, MANAGE) | `Authorization.jobsFollowTheirAccess`<br>`Core.aRunInProgressIsReadAndStoppedThroughItsOwnResource`<br>`Core.startingAJobAnswersWithItsRunAndAnIdempotencyKeyPreventsASecondRun` |
| `stopJob` | `POST /api/v1/jobs/{id}:stop` | Per job access (READ, EXECUTE, MANAGE) | `Core.startingAJobAnswersWithItsRunAndAnIdempotencyKeyPreventsASecondRun` |
| `pauseJob` | `POST /api/v1/jobs/{id}:pause` | Per job access (READ, EXECUTE, MANAGE) | `Core.pausingMarksTheJobAndRunsInProgressAreListed` |
| `listJobExecutions` | `GET /api/v1/jobs/{jobId}/executions` | Per job access (READ, EXECUTE, MANAGE) | `Core.aJobsExecutionsAreListedForAJobTheCallerMaySee` |
| `diagnoseJob` | `GET /api/v1/jobs/{jobId}/diagnosis` | Per job access (READ, EXECUTE, MANAGE) | `Operations.aJobsDiagnosisTracesAndErrorsComeFromObservability` |
| `getJobTraces` | `GET /api/v1/jobs/{jobId}/traces` | Per job access (READ, EXECUTE, MANAGE) | `Operations.aJobsDiagnosisTracesAndErrorsComeFromObservability` |
| `getJobErrors` | `GET /api/v1/jobs/{jobId}/errors` | Per job access (READ, EXECUTE, MANAGE) | `Operations.aJobsDiagnosisTracesAndErrorsComeFromObservability` |
| `listJobRuns` | `GET /api/v1/job-runs` | Per job access (READ, EXECUTE, MANAGE) | `Core.aRunInProgressIsReadAndStoppedThroughItsOwnResource`<br>`Core.pausingMarksTheJobAndRunsInProgressAreListed` |
| `getJobRun` | `GET /api/v1/job-runs/{executionId}` | Per job access (READ, EXECUTE, MANAGE) | `Core.aRunInProgressIsReadAndStoppedThroughItsOwnResource`<br>`Core.pausingMarksTheJobAndRunsInProgressAreListed` |
| `stopJobRun` | `POST /api/v1/job-runs/{executionId}:stop` | Per job access (READ, EXECUTE, MANAGE) | `Core.aRunInProgressIsReadAndStoppedThroughItsOwnResource` |
| `listExecutions` | `GET /api/v1/executions` | Per job access (READ, EXECUTE, MANAGE) | `Core.executionsAreListedNewestFirstAndTheirLedgerIsPaged` |
| `getExecution` | `GET /api/v1/executions/{executionId}` | Per job access (READ, EXECUTE, MANAGE) | `Core.executionsAreListedNewestFirstAndTheirLedgerIsPaged` |
| `listExecutionDocuments` | `GET /api/v1/executions/{executionId}/documents` | Per job access (READ, EXECUTE, MANAGE) | `Core.executionsAreListedNewestFirstAndTheirLedgerIsPaged` |
| `exportExecution` | `GET /api/v1/executions/{executionId}/export` | Per job access (READ, EXECUTE, MANAGE) | `Core.executionsAreListedNewestFirstAndTheirLedgerIsPaged`<br>`Operations.anExecutionsReportFileIsDownloadedAndTheAuditLakeQueried` |
| `queryExecutions` | `POST /api/v1/executions:query` | Admins | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Core.executionsAreListedNewestFirstAndTheirLedgerIsPaged`<br>`Operations.anExecutionsReportFileIsDownloadedAndTheAuditLakeQueried` |
| `listConnectors` | `GET /api/v1/connectors` | Admins, job creators | `Authorization.jobCreatorsListConnectorsWithoutConfiguration`<br>`Core.connectorsAreManagedWithTheirSecretsMasked` |
| `createConnector` | `POST /api/v1/connectors` | Admins | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Core.aConnectorInUseIsOnlyDeletedWithForce`<br>`Core.connectorNamesMayHaveInnerSpacesAsMigrationsCreate`<br>`Core.connectorsAreManagedWithTheirSecretsMasked` |
| `getConnector` | `GET /api/v1/connectors/{name}` | Admins, job creators | `Authorization.jobCreatorsListConnectorsWithoutConfiguration`<br>`Core.connectorsAreManagedWithTheirSecretsMasked`<br>`Operations.aManifoldCfMigrationIsPlannedAndAppliedInProcess` |
| `replaceConnector` | `PUT /api/v1/connectors/{name}` | Admins | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Core.connectorNamesMayHaveInnerSpacesAsMigrationsCreate`<br>`Core.connectorsAreManagedWithTheirSecretsMasked` |
| `deleteConnector` | `DELETE /api/v1/connectors/{name}` | Admins | `Core.aConnectorInUseIsOnlyDeletedWithForce` |
| `checkConnector` | `POST /api/v1/connectors/{name}:check` | Admins | `Core.connectorsAreManagedWithTheirSecretsMasked` |
| `checkConnectorDefinition` | `POST /api/v1/connectors:check` | Admins | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Core.connectorsAreManagedWithTheirSecretsMasked` |
| `getSystemSettings` | `GET /api/v1/settings/system` | Admins | `Authorization.adminOnlyRoutesRefuseEveryoneElse`<br>`Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Core.settingsHaveETagsAndRefuseStaleChanges` |
| `replaceSystemSettings` | `PUT /api/v1/settings/system` | Admins | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Core.settingsHaveETagsAndRefuseStaleChanges` |
| `getTransportSettings` | `GET /api/v1/settings/transport` | Admins | `Operations.transportSettingsAreReadReplacedAnded` |
| `replaceTransportSettings` | `PUT /api/v1/settings/transport` | Admins | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Operations.transportSettingsAreReadReplacedAnded` |
| `testGrpcTransport` | `POST /api/v1/settings/transport:test-grpc` | Admins | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Operations.transportSettingsAreReadReplacedAnded` |
| `getSystemStatus` | `GET /api/v1/system/status` | Any caller | `Operations.metricsThroughputAndLogsAnswer` |
| `getSystemThroughput` | `GET /api/v1/system/throughput` | Any caller | `Operations.metricsThroughputAndLogsAnswer` |
| `getSystemLogs` | `GET /api/v1/system/logs` | Admins | `Authorization.adminOnlyRoutesRefuseEveryoneElse`<br>`Operations.metricsThroughputAndLogsAnswer` |
| `getMetrics` | `GET /api/v1/observability/metrics` | Admins | `Operations.metricsThroughputAndLogsAnswer` |
| `planManifoldCfMigration` | `POST /api/v1/migrations/manifoldcf:plan` | Admins | `Operations.aManifoldCfMigrationIsPlannedAndAppliedInProcess` |
| `applyManifoldCfMigration` | `POST /api/v1/migrations/manifoldcf:apply` | Admins | `Operations.aManifoldCfMigrationIsPlannedAndAppliedInProcess` |
| `getVespaHealth` | `GET /api/v1/integrations/vespa/health` | Admins | `Operations.vespaIsAdministeredThroughTheIntegrationEndpoints` |
| `getVespaDocumentCounts` | `GET /api/v1/integrations/vespa/document-counts` | Admins | `Operations.vespaIsAdministeredThroughTheIntegrationEndpoints` |
| `queryVespa` | `POST /api/v1/integrations/vespa:query` | Admins | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Operations.vespaIsAdministeredThroughTheIntegrationEndpoints` |
| `deployVespaBundledSchema` | `POST /api/v1/integrations/vespa:deploy-bundled` | Admins | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Operations.vespaIsAdministeredThroughTheIntegrationEndpoints` |
| `deployVespaCustomSchema` | `POST /api/v1/integrations/vespa:deploy-custom` | Admins | `Operations.vespaIsAdministeredThroughTheIntegrationEndpoints` |
| `generateNarrativizationTemplate` | `POST /api/v1/narrativization:generate-template` | Admins, job creators | `Authorization.jobCreatorsListConnectorsWithoutConfiguration`<br>`Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Operations.narrativizationSuggestsATemplate` |
| `startDynamicJob` | `POST /api/v1/dynamic-jobs` | Verified caller; the feature's own rules | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Features.dynamicJobsAreStartedFollowedAndDecidedOn` |
| `ingestDynamicDocument` | `POST /api/v1/dynamic-jobs:ingest-document` | Verified caller; the feature's own rules | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Features.aSingleDocumentIsIngestedThroughTheCustomMethod` |
| `listDynamicJobs` | `GET /api/v1/dynamic-jobs` | Verified caller; the feature's own rules | `Features.dynamicJobsAreStartedFollowedAndDecidedOn`<br>`Core.errorsBeforeAndOutsideControllersAreProblemsToo` |
| `getDynamicJob` | `GET /api/v1/dynamic-jobs/{jobId}` | Verified caller; the feature's own rules | `Features.dynamicJobsAreStartedFollowedAndDecidedOn` |
| `stopDynamicJob` | `POST /api/v1/dynamic-jobs/{jobId}:stop` | Verified caller; the feature's own rules | `Features.dynamicJobsAreStartedFollowedAndDecidedOn` |
| `approveDynamicJob` | `POST /api/v1/dynamic-jobs/{jobId}:approve` | Admins | `Features.dynamicJobsAreStartedFollowedAndDecidedOn` |
| `rejectDynamicJob` | `POST /api/v1/dynamic-jobs/{jobId}:reject` | Admins | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Features.dynamicJobsAreStartedFollowedAndDecidedOn` |
| `listDiscoveryConnectors` | `GET /api/v1/discovery/connectors` | Verified caller; the feature's own rules | `Features.discoveryBrowsesWithTheParametersSent` |
| `getDiscoveryTree` | `GET /api/v1/discovery/connectors/{connector}/tree` | Verified caller; the feature's own rules | `Features.discoveryBrowsesWithTheParametersSent` |
| `dryRunDiscovery` | `POST /api/v1/discovery/connectors/{connector}:dry-run` | Verified caller; the feature's own rules | `Examples.everyDocumentedExampleIsAcceptedByItsEndpoint`<br>`Features.discoveryBrowsesWithTheParametersSent` |
| `listSidecarStores` | `GET /api/v1/sidecar-stores` | Verified caller; the feature's own rules | `Features.sidecarsAreReadWithPrefixLimitAndCursor` |
| `listSidecars` | `GET /api/v1/sidecar-stores/{connector}/sidecars` | Verified caller; the feature's own rules | `Features.sidecarsAreReadWithPrefixLimitAndCursor` |

## Defects found by these tests and fixed

| Defect | Found by |
|---|---|
| Idempotency keys were scoped by the JWT `sub`; tokens without one shared a scope, so one user's key could replay another's answer | Authorization |
| A merge patch could not clear optional fields (the legacy update keeps stored values) | Core |
| Errors raised outside the Platform API would have become problem details (resolver not scoped) | Mutation check |
| `POST /dynamic-jobs:ingest-document` was mapped to `/dynamic-jobs/:ingest-document` | Features |
| A connector name with a space gave `500` (unencoded `Location`) | Core |
| Connector names created by ManifoldCF migrations (with spaces) could not be replaced through v1 | Operations |
| ManifoldCF `apply` wrote back over unauthenticated loopback HTTP, failing with `protect-api` on | Operations (now in-process, as the caller) |
| A missing `mcfUrl` answered `502` instead of `400` | Operations |
| The entry point was described twice (`/api/v1` and `/api/v1/`) | Core OpenAPI test |
| The smoke script failed on macOS bash 3.2 | Script |

## Real-stack run

Run on 2026-11-15 against the full decoupled stack (`docker-compose-decoupled.yml`: runtime and MCP server, ingestion, embedding and writer consumers, Kafka, PostgreSQL with pgvector, Redis, Ollama, Ozone, admin UI), built from this branch, in an isolated Compose project.

**`scripts/test-platform-api-v1.sh`: 28 of 28 checks passed.** They cover the index, version, OpenAPI, `me`, connector listing with masked secrets, create with `Location`, an `Idempotency-Key` retry, `If-None-Match` (304), merge patch with `If-Match` and with a stale one (412), filtered paging, `:start` (202), the run ending `Completed`, the execution report and its documents, problem details with a request id for 404 and 400, and delete (204).

**Further checks on the same stack:**

| Check | Result |
|---|---|
| A real crawl through the pipeline | The run ended `Completed`: 1 document `SUCCESS`, 1 chunk, embedded by Ollama and stored in `vector_store` |
| Run provenance | `triggerSource: api`, with the `X-Correlation-Id` sent on `:start` |
| `GET /executions/{id}/export` | `json` 200, `csv` 200, `parquet` 200 (`application/vnd.apache.parquet`); `avro` 404 problem, because Avro reports are off by default (`spring.opencrawling.reporting.formats.avro`) |
| `POST /executions:query` | SQL over the audit lake answered (`status`, `documents` = `SUCCESS`, 1) |
| `GET /health` | 200 `HEALTHY`, with each component's state |
| Diagnosis, traces, errors | Answered with live data |
| `:start` twice | 202, then 409 `job-already-running` naming the run |

**API Console (admin UI, browser):** the 61 operations load from `/v3/api-docs/v1`. Checked:

- A list request, then a read showing the `ETag` with its explanation.
- The same read with that ETag in `If-None-Match`, answering 304.
- A missing job, rendered as a problem (title, type, detail, instance, request id).
- A create from the built-in example, answering 201 with `ETag` and `Location`.
- The Curl tab repeating the request exactly.
- Ctrl+Enter to send.

The run found one console fix: without sign-in, the console no longer claims to send a token or adds one to the curl.

**Environment note:** during the run, a PostgreSQL backend process exited with code 2 and PostgreSQL restarted and recovered. The runtime stalled until the database came back, then answered normally. The database log shows very slow disk writes on the shared test machine just before (a 2.5 MB checkpoint took 117 s). Nothing in the API caused it.
