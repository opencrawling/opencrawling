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
| UiParity | `PlatformApiV1UiParityTest` | Every call the admin UI makes is a documented v1 operation, read from the UI's sources, and everything it did before still uses one |
| Sdk | `PlatformApiV1SdkTest` | The Java SDK's `platform()` client against the runtime: it reaches every operation in the API description and reads each answer |
| Idempotency | `IdempotencyStoreTest`, `RedisIdempotencyStoreIT` | Both idempotency stores keep the same contract; the Redis one on a real Redis, with expiry and 32 racing instances of which exactly one runs |
| Unit | `PlatformApiV1UnitTest` | Cursors, ETags, merge patch, idempotency store, secret masking, request scoping, error mapping |
| Console | `oc-admin-ui/src/lib/apiConsole.test.ts` | The API console's logic: reading the description, building and describing requests, history |
| UiClient | `oc-admin-ui/src/lib/api.test.ts` | The admin UI's API client: v1 URLs and methods, cursor following, ETags and `If-Match`, problem messages |
| SdkClient | `oc-java-client-sdk/src/test/java/org/opencrawling/sdk/v1/PlatformClientTest.java` | The SDK's paths, headers, cursor streams, errors from proxies, unhealthy health, lossless settings |
| Cli | `oc-cli/src/test/java/org/opencrawling/cli/PlatformApiCommandsTest.java` | Each CLI command's v1 requests (method, path, headers, body) and output; no legacy endpoint called |
| Script | `scripts/test-platform-api-v1.sh` | End to end against a real runtime (CI: "Platform API v1 Test Suite") |

## Guarantee layers

1. **Contract:** the OpenAPI document lists exactly the expected operations at their methods and paths (Core and Features: `theOpenApiDocumentDescribesThePlatformApiOnly`, `theOptionalFeaturesAreDescribedAtTheirPaths`). An endpoint that moves or appears without being documented fails.
2. **Documentation:** DocumentationContract fails on any operation without a summary, a description, parameter descriptions, a request example, a response schema, problem-detail error responses or a curl sample. Examples proves the examples work.
3. **Behaviour:** every one of the 61 operations is called by at least one HTTP test (table below).
4. **Security:** Authorization calls each admin-only route as a job creator and as a plain user and expects `403`, checks job visibility, ownership, `401` and per-caller idempotency.
5. **Clients:** UiParity, Sdk and Cli check that the admin UI, the SDK and the CLI use the Platform API. Sdk fails when an operation has no SDK method.
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
| §8 Convention: deprecation headers for old endpoints | `LegacyApi`, `LegacyDeprecationFilter`: `Deprecation` and `Link: rel="successor-version"` on every legacy management endpoint; deprecated in the "all" OpenAPI group; the SDK's earlier clients warn once | Core `legacyEndpointsAreMarkedDeprecatedWithTheirSuccessor`; Unit `legacyEndpointsHaveTheirPlatformApiSuccessor` | Done (removal: to be announced) |
| §9 `Idempotency-Key` on start | `IdempotencyFilter` (all POSTs); keys in memory or, shared by every runtime instance, in Redis | Core `startingAJob...`, `anIdempotencyKeyCannotBeReused...`; Authorization `idempotencyKeysAreScopedToTheCaller`; Idempotency; Unit | Done |
| §9 Follow a run (poll) | `GET /job-runs/{id}`, job `state`, executions | Core `aRunInProgressIsReadAndStoppedThroughItsOwnResource` | Done |
| §9 SSE, webhooks, review tasks, scenario runs | — | — | Part 2 (scenario runs) |
| §10 Bearer tokens, installation roles | #187; v1 admin rules in `ManagementAuthorizationInterceptor` | Authorization | Done |
| §10 Document authorization never widened | v1 calls the same controllers and services | Authorization `jobsFollowTheirAccess`; Features delegation | Done (scenario-level checks: part 2) |
| §11 Who started each run | `ExecutionProvenance` via `JobController.start` | `JobStartProvenanceHttpTest`; Core start with `X-Correlation-Id` | Done |
| §13 OpenAPI 3.1 in Swagger UI | `/v3/api-docs/v1`, `/swagger-ui.html`. Generated from the code (code-first), with the contract pinned by tests: the exact set of operations, their documentation, their examples and the problem types | DocumentationContract; Examples; Core OpenAPI test | Done (contract-first, writing the description before the code, was not used) |
| §13 A way to try every endpoint | API console in the admin UI; Swagger UI; curl samples; Bruno/Postman/Insomnia import | Console; Script | Done |
| §13 SDK and CLI for `/api/v1` | SDK `client.platform()` (`org.opencrawling.sdk.v1`); every CLI command, and `oc mcf apply` through the migrator's writer | Sdk; SdkClient; Cli | Done |
| §2 The admin UI on the same API | `oc-admin-ui/src/lib/api.ts` on `/api/v1` | UiParity; UiClient | Done |
| §16 Existing endpoints keep working | Legacy endpoints answer as before. Inside, `JobController.save`/`start` return typed outcomes and `save` gained a replace mode, settings are validated before they are applied, and connectors are replaced in place under a lock | Core `theLegacyEndpointsAnswerAsBefore`; all legacy test classes | Done |

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
| `/settings/transport` had no ETag, and its read answered a wrapper that its write did not take | Moving the admin UI to v1 |
| Behind a compressing proxy, ETags turn weak and every `If-Match` change would have answered `412` | Moving the admin UI to v1 |
| `POST /connectors:check` refused a definition without a name, which a connector being written may not have yet | Moving the admin UI to v1 |
| A dynamic job's `statusUrl` pointed at the legacy path | Sdk |
| The SDK's settings model had 7 of the 24 settings, so saving through it would reset the others to defaults | Writing the SDK client (v1 keeps every field; the earlier `system().updateSettings` now sends the runtime's settings with only the model's fields changed, `SystemClientTest`) |
| The CLI jar had two SLF4J providers and printed warnings on every command | Real-stack run (the migrator's `slf4j-simple` is now excluded, as in the runtime) |
| `oc job start --wait` never ended: it waited for a status that jobs never have | Moving the CLI to v1 (now follows the run) |

## Independent review

Before release, the API had a read-only review by a reviewer that had not written it. They checked it against:
- RFC 9110, 9457, 8288, 9745, 7396 and 6585;
- the IETF Idempotency-Key draft;
- the Google, Zalando and Microsoft API guidelines;
- the OWASP API Security Top 10.

Each finding was checked against the code first; all of the ones below held up.

| Finding | Fix | Evidence |
|---|---|---|
| A job's ETag covered only its definition, so `If-None-Match` could answer `304` while its state changed | The tag covers the whole representation (definition, state, the caller's permissions) | Core `aConditionalReadSeesARunsStateChange...` |
| `If-Match` was checked outside the lock that writes: two changes from one version could both pass (lost update) | Check and write under one lock for jobs, connectors (also create) and settings | Core `ofManyChangesMadeFromTheSameVersionExactlyOneWins` (12 concurrent; fails without the lock) |
| Settings were applied before a value was checked; the documented example had an invalid heap value | Validate first, then apply; the example is `-Xmx512m`; v1 names the field | Core `invalidSettingsAreRefusedWithTheirFieldsAndChangeNothing` |
| `s3SecretAccessKey`, header settings and URL passwords were returned in clear | Secrets by name (`*secret*`, headers, connection strings) and in URLs are masked | Core masking test; Unit `secretsAreRecognisedByNameAndInsideUrls` |
| A stored secret sent back as the mask could be sent to a new address | Refused when an address setting changes | Core; Unit `storedSecretsAreKeptOnlyWhileTheConnectorPointsWhereItDid` |
| A secret rotation did not change the connector ETag | Keyed digest of the secrets in the tag | Core; Unit `theSecretsDigestChangesWithASecretAndNeedsTheKey` |
| `job-runs/{id}:stop` stopped the job's current run, maybe a newer one | Stops exactly that run | Core; Unit `aRunIsStoppedOnlyWhileItIsTheJobsRun` |
| No cache policy or security headers; `401` was not a problem detail | `Cache-Control`, `Vary`, `nosniff`, CSP on every answer; `401` problem with `WWW-Authenticate` | Core `everyAnswerCarriesTheCacheAndSecurityHeaders`; Authorization |
| Request bodies were unbounded (OWASP API4) | 1 MiB cap, with or without `Content-Length`; `413`; maps at most 200 entries | Core `anOversizedBodyIsRefused...` |
| Problem types incomplete in the guide; type URIs did not resolve | Registry enforced in code; the guide lists every type under the anchor its URI names | `PlatformApiV1ProblemTypesTest` |
| API description lacked shared headers, parameters and responses; a curl sample broke shell quoting; two index links shared a name | Documented; quoted; unique names | Core `theApiDescriptionShows...`, `theIndexNamesEachLinkOnce`; Features curl test |
| Idempotency: Redis write failure, short in-progress timeout, global eviction in memory, unbounded stored answers, draft key format refused, keyed uploads ignored | Retried writes and renewed markers; per-caller shares, running keys never dropped; `idempotency-answer-unavailable`; Structured Field keys; keyed uploads refused; `Retry-After` | `IdempotencyStoreTest`, `RedisIdempotencyStoreIT`, Core |
| Offset cursors gave duplicates or gaps when items arrived; unstable order | Cursors resume after their last item; the id breaks ties in every sort | Unit `aCursorResumesAfterItsLastItem...` |
| Every execution read parsed every report on disk | Read by id | Core and Operations execution tests |
| SDK never retried, could not pass options everywhere, buffered exports; the UI saved jobs and connectors without `If-Match` | Retries with backoff (same key); `withDefaults`; streamed export; the UI reads and sends versions | SDK `PlatformClientTest`; `api.test.ts` |
| Deprecation pointed at collections only | Exact successors, `rel="deprecation"` link, context path | Unit; Core |
| `executions:query` reported server faults as invalid queries and showed server paths | `500` for faults, paths shortened | Operations |
| No way to require `If-Match` | `opencrawling.api.require-if-match` gives `428` | `PlatformApiV1RequireIfMatchHttpTest` |

**Second review.** A second independent read checked these fixes and found what the first round missed. Each item was verified, then fixed and tested:

| Finding | Fix | Evidence |
|---|---|---|
| Critical: the job state writers (run end, every pushed document) and the legacy delete did not take the jobs' lock, so they could revert a saved definition or overwrite another job | Every writer of the job list is synchronized on the controller | Core `everyJobWriterWaitsForTheLockThatConditionalChangesHold` (deterministic; fails if any writer loses the lock) |
| A Redis key renewal could land after the answer was written, cutting its lifetime to 10 minutes | Closing a hold stops and waits for the renewer, before the outcome is written | `closingAHoldWaitsForTheRenewerSoTheAnswerKeepsItsRetention` |
| `require-if-match` broke the shipped clients' deletes and replaces, and ignored a context path | UI and CLI read the version first; the filter strips the context path; legacy scope documented | `api.test.ts`; CLI `PlatformApiCommandsTest` |
| SDK: a timed-out POST's retry failed on `409 idempotency-in-progress`; defaults shared one key across calls; uploads got a key | That 409 is retried; defaults keep only ids; uploads never get a key; a longer `Retry-After` than allowed gives up | SDK `PlatformClientTest` |
| Masking gaps: `sessionTicket`, `password=` parameters, `@` in passwords, ports and TLS switches, `className` changes, edited masked values | All covered; `changeLogToken` is no longer treated as a secret | Unit masking tests |
| Minor: exact successors for dynamic-job actions and the report query; keyed-request size limit; reader-based bodies; case of `multipart`; dynamic-job order; lock-order notes; duplicated settings validation; export to a partial file | All fixed | Unit; Core; SDK |
| The guide said secrets are never returned, but the deprecated `GET /api/connectors` returns them to admins | The legacy endpoint is unchanged (compatibility); the guide says so | Guide |

**Deferred, with reasons:**
- **`RateLimit` headers and `429` on writes:** the runtime has no rate limiter yet, so there are no limits to advertise.
- **Long-running operations as resources, events and webhooks:** these are part 2 of #313 (scenario runs).
- **Discovery and sidecar collections in the `Page` envelope:** they keep the shapes of the features' own APIs, and sidecars have their own cursor.

## Real-stack run

Run on 2026-11-15 against the full decoupled stack (`docker-compose-decoupled.yml`: runtime and MCP server, ingestion, embedding and writer consumers, Kafka, PostgreSQL with pgvector, Redis, Ollama, Ozone, admin UI), built from this branch, in an isolated Compose project.

**`scripts/test-platform-api-v1.sh`: all 27 checks passed.** They cover the index, version, OpenAPI, `me`, connector listing with masked secrets, create with `Location`, an `Idempotency-Key` retry, `If-None-Match` (304), merge patch with `If-Match` and with a stale one (412), filtered paging, `:start` (202), the run ending `Completed`, the execution report and its documents, problem details with a request id for 404 and 400, and delete (204).

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

### Second run: clients and the Redis store

Run on 2026-11-15 on the same isolated stack, rebuilt from this branch. The API server ran with `OPENCRAWLING_API_IDEMPOTENCY_STORE=redis`, as the Compose files now set.

- **Script:** all 27 checks passed again, with a real crawl.
- **Redis idempotency store:**

| Check | Result |
|---|---|
| A `POST /jobs` retried with the same key | Same job, `Idempotent-Replayed: true` |
| What Redis holds | `oc:api:idempotency:<sha-256>` keys only, with no caller, path or key in them |
| The same retry after restarting the API server | Still replayed, with the same job; one job exists |
| A keyed `POST` while Redis is stopped | `503 idempotency-unavailable` with a request id; no job created |
| An unkeyed `POST` while Redis is stopped | `201`, not affected |
| The keyed `POST` again once Redis is back | `201`, runs normally |

- **Admin UI (browser):**
  - All the pages load: dashboard, jobs, connectors, settings, reports and the ledger with its status filter.
  - Editing a job sends `PATCH /api/v1/jobs/{id}`, and the change is stored.
  - A settings save after someone else changed the settings answered `412`. The page asked to reload and nothing was overwritten. After the reload, the system and transport saves went through with their ETags.
  - The session sent 69 requests: 67 to `/api/v1` and 2 to the public `/api/auth/config`. All succeeded except that deliberate `412`.
- **CLI (packaged jar):**
  - `oc system status` worked.
  - `oc connector check --name PGVector_Output` checked the live database.
  - `oc job start --wait` crawled for real, and ended when the run ended (Finished, Completed, 1 document).
  - `oc report ledger` read the run's documents.
  - `oc connector delete` on a connector that jobs use was refused, with the jobs named and `--force` suggested.

### Third run: multi-page ledger and deprecation

Run on 2026-11-15, rebuilt from this branch.

- **Ledger paging:** a crawl of 60 documents (all 60 `SUCCESS`). In the admin UI the ledger showed "Page 1 of 3".
  - Next, Next and Previous showed pages 2, 3 and 2 again, with the same documents on page 2 both times.
  - Next was disabled on the last page.
  - Each page was read with a `cursor` from the previous one.
- **Deprecation:** `GET /api/jobs`, `/api/system/settings`, `/api/reports` and `/api/v1/admin/settings/transport` answered with `Deprecation: @1794700800` and `Link: <successor>; rel="successor-version"`.
  - `/api/v1/jobs` and `/api/auth/config` had neither.
  - `/v3/api-docs/all` marks 46 legacy operations deprecated.
- **Found outside the Platform API:** Ollama's model was still being pulled when the crawl ran. `VectorOutputConnector` logged every embedding as failed, stored the chunks with a placeholder vector anyway, and the ledger reported `SUCCESS`. Reported for a separate fix.

**Environment note:** during the run, a PostgreSQL backend process exited with code 2 and PostgreSQL restarted and recovered. The runtime stalled until the database came back, then answered normally. The database log shows very slow disk writes on the shared test machine just before (a 2.5 MB checkpoint took 117 s). In the second run the API server's JVM froze for 94 s. The host had almost no free memory (swap 17.7 of 18.4 GB, with two Doxis lab VMs holding 18 GB; they were stopped before the third run) ("Thread starvation or clock leap detected"), with no request load, which points at the shared machine's Docker VM stalling rather than at the API. The API answered normally afterwards.
