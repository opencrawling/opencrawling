# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenCrawling is a Java 25 / Spring Boot 4 data-ingestion and vector-search platform. Repository connectors crawl sources. Apache Tika extracts the text, Spring AI's `TokenTextSplitter` chunks it, Ollama or OpenAI generate embeddings, and a pluggable output connector stores the vectors (pgvector by default). Stages talk over Kafka. An MCP server exposes the stored vectors to AI clients and enforces ACLs server-side.

Longer-form docs live in `docs/`: `Architecture.md` (pipeline, tracing, the connector blueprint), `Configuration-Guide.md`, `Model-Context-Protocol.md` and `Migration-Guide.md`.

## Build and Test

You can use the cross-platform root CLI wrappers (`./run.sh` on macOS/Linux, `run.bat` on Windows) or invoke Maven/Docker directly:

```bash
./run.sh build                               # build via containerized oc-maven-builder (mounts ~/.m2)
./run.sh build:offline                       # fast offline build via oc-maven-builder
./run.sh build:host                          # build using host JDK 25 & Maven
./run.sh mvn test -pl oc-runtime             # run custom Maven goals inside oc-maven-builder
./run.sh test:unit                           # run unit tests via oc-maven-builder
./run.sh test:it [filter]                    # run integration tests with local BuildKit cache overlay
./run.sh test:local-dev                      # run local Docker build acceleration test suite
./run.sh ui:dev                              # start React Admin UI Vite dev server on :5173
./run.sh ui:build                            # build React Admin UI production bundle
./run.sh license:format                      # apply Apache 2.0 license headers
./run.sh clean [--prune]                     # clean target/ dirs (and optionally prune Docker build cache)
```

Direct host commands:

```bash
mvn clean install                            # all modules + unit tests (also builds oc-admin-ui); ~8 min
mvn clean install -DskipTests                # fast build
mvn clean verify -DfailIfNoTests=false       # what CI runs (unit tests only)

mvn test -pl oc-runtime -Dtest=McpVectorServerTest                    # single unit test
mvn verify -pl oc-runtime -DskipITs=false                             # JUnit ITs (Failsafe, *IT classes)
mvn verify -pl oc-runtime -DskipITs=false -Dit.test=OpenCrawlingIT    # single IT

mvn spring-boot:run -pl oc-runtime -Dspring-boot.run.profiles=dev   # runtime on :8080
mvn spring-boot:run -pl oc-embedding-service                        # embedding service on :8085

mvn license:format                           # add Apache 2.0 headers to new files

cd oc-admin-ui && npm install && npm run dev  # Vite dev server on :5173
cd oc-admin-ui && npm run build               # tsc && vite build (CI runs this)
cd oc-admin-ui && npm run lint                # ESLint
```

- The root `pom.xml` sets `<skipITs>true</skipITs>`, so the JUnit ITs never run unless you pass `-DskipITs=false`.
- `-pl` runs resolve sibling modules from the local repository, so run `mvn install` once first.
- Preview features are required. `--enable-preview` (plus `--enable-native-access=ALL-UNNAMED` for the tests) is set in the root `pom.xml` for the compiler, Surefire, Failsafe and the Spring Boot plugin. Your IDE needs preview features enabled too.

### Integration test scripts

End-to-end tests are bash scripts in `scripts/`. They bring up Docker Compose stacks and drive the REST API and MCP endpoints. Each connector or output usually has two:
- `test-<name>-connector.sh` tests the connector on its own.
- `test-<name>-decoupled.sh` tests the full pipeline from source to Kafka, embedding, store and MCP.

Build first (`mvn clean install -DskipTests`).

```bash
./run-integration-tests.sh --list               # discover scripts
./run-integration-tests.sh qdrant               # run scripts whose filenames match a keyword
./run-integration-tests.sh scripts/test-cli.sh  # run one script
./run-integration-tests.sh -v --fail-fast       # stream output, stop at first failure
```

Logs go to `logs/integration-tests/`. CI runs these scripts as a matrix (`.github/workflows/integration-tests.yml`).

### JUnit ITs in `oc-runtime`

- **Docker:** the ITs need the infrastructure from `docker compose up -d`. The `docker-maven-plugin` in `oc-runtime/pom.xml` has `<skip>true</skip>`, so Maven won't start it for you.
- **Configuration:** the ITs use `@ActiveProfiles("test")`, but there is no `application-test.yml`. Each IT sets its overrides through `@SpringBootTest` properties.
- **Kafka groups:** each Spring IT gets a fresh `spring.kafka.consumer.group-id` from `KafkaTestGroup.register` (a `@DynamicPropertySource`), created with its offsets committed at the current end of the pipeline topics, so runs don't collide on offsets and a long-lived local broker's history is never replayed. New ITs must do the same rather than set a random `group-id` (`KafkaConfig` forces `auto.offset.reset=earliest`).
- **Similarity threshold:** `TestEmbeddingConsumer` emits `[1.0, 0, ...]` vectors, so the IT searches use `similarityThreshold(0.0)`.
- **Unit tests** use Mockito with no Spring context.

## Infrastructure (Docker)

```bash
./run.sh decoupled:up                                         # fast local decoupled stack (BuildKit ~/.m2 cache mounts)
./run.sh apps:up                                              # fast local monolithic apps stack
./run.sh infra:up                                             # core infra only (PostgreSQL+pgvector, Redis, Ollama, Kafka)
./run.sh status                                               # inspect running container status
./run.sh logs [service]                                       # follow container logs
./run.sh down                                                 # stop all active local stacks

docker compose up -d                                          # PostgreSQL+pgvector, Redis, Ollama, Kafka (KRaft)
docker compose -f docker-compose-apps.yml up -d --build       # monolithic app containers
docker compose -f docker-compose-decoupled.yml up -d --build  # one container per pipeline stage
```

- **Ports:** PostgreSQL `5432`, Redis `6379` (Insight UI `8001`), Ollama `11434`, Kafka `9092`, runtime `8080`, gRPC `9095`, embedding service `8082` (Docker; `8085` when run locally), admin UI `3000`.
- **Per-stage images** are in `docker/` (`Dockerfile.crawler`, `.ingestion-consumer`, `.embedding-consumer`, `.writer-consumer`, `.mcp-server`, `.cli`).
- **Per-connector stacks** are in `oc-<name>-*-connector/docker/docker-compose-decoupled-with-<name>.yml`.

## Architecture

### Modules

- **`oc-core`**: the SPI and the shared models.
  - The sealed `Connector` interface (`permits RepositoryConnector, TransformationConnector, OutputConnector`).
  - `RepositoryDocument` and `DocumentAction` (`UPSERT` / `DELETE`), `ConnectorSchema`.
  - The Kafka message records (`IngestionMessage`, `DocumentChunkMessage`, `DocumentEmbeddedMessage`).
  - `SecurityConfig` / `PermissionRule`, and the claim-check store abstraction.
- **`oc-runtime`**: the Spring Boot main app. It depends on every connector module and contains:
  - the REST controllers (`api/`) and `JobOrchestrator`
  - `IngestionConsumer` and `VectorStoreWriterConsumer` (pgvector)
  - `McpVectorServer` and `VespaMcpVectorServer`
  - reporting, AIOps and the gRPC transport
- **`oc-embedding-service`**: a standalone app. `EmbeddingConsumer` reads chunks, and `EmbeddingModelFactory` picks Ollama or OpenAI per job. It caches clients by `engine-modelName`.
- **`oc-*-repository-connector`**: the sources. Each one implements `scan(basePath) -> Flux<RepositoryDocument>` and, optionally, `getSchema()`.
  - A connector can also implement `DiscoverableRepositoryConnector` (`oc-core`, `connector.discovery`): `explore` lists the tree, `dryRun` previews what a crawl would take, both bounded and without reading content, and `crawlStart` resolves a folder a crawl may start from (CMIS, Alfresco, Zammad, and Google Drive). `DocumentFetchingRepositoryConnector.fetchDocument` fetches one document by id as the crawl would emit it, or reports it `Gone`. Each `DiscoveryNode` carries the source ACL and the id the crawler indexes it under; an unknown ACL is `null` and must be denied, never treated as public. A `VisibilityFilter` is applied page by page: hidden folders are never opened, a hidden start folder reads as not found, and the bounds count only visible items.
- **`oc-*-output-connector`**: the sinks. Each non-pgvector sink ships its own `messaging/*StoreWriterConsumer`.
- **`oc-observability`**: OTel / Micrometer helpers.
- **`oc-grpc-api`**: the Protobuf contracts for the optional transport (`opencrawling.transport.mode` = `AUTO` | `GRPC` | `REST`).
- **Supporting modules:**
  - `oc-cli` (the `oc` command)
  - `oc-java-client-sdk`
  - `oc-mcf-migrator` (ManifoldCF import)
  - `oc-connector-archetypes` (Maven archetypes for new connectors)
  - `oc-admin-ui` (React 19 + TypeScript + Tailwind; its `dist/` is copied into `META-INF/resources` and served by the runtime)

### Kafka pipeline (claim-check pattern)

```
RepositoryConnector.scan()
  -> JobOrchestrator: content to the claim-check store, IngestionMessage (URI only) -> [opencrawling-documents]
    -> IngestionConsumer: Tika extract + TokenTextSplitter -> [opencrawling-chunks]
      -> EmbeddingConsumer (oc-embedding-service)          -> [opencrawling-embedded]
        -> <Output>StoreWriterConsumer -> vector store
```

The topic names are defined once, in `PipelineTopics` (`oc-core`, `core.messaging`); both services' `KafkaConfig` constants and every writer's `@KafkaListener` refer to it, so name a topic through it, never as a string.

- **Claim-check store:** `spring.opencrawling.claim-check.store` is `local` (default dir `target/claims`) or `ozone`.
  - Claims are deleted on consume (`cleanup-on-consume`). `ClaimCheckGarbageCollector` sweeps orphans every hour by default, keeping the claims that messages on `opencrawling-documents.DLT` still reference (`DeadLetteredClaims`, passed to `ClaimCheckStore.deleteExpired(maxAge, retain)`); if it cannot read that topic it skips the sweep. A store must give `retain` each object's URI exactly as `put()` returned it, and implement the `retain` overload: `ClaimCheckStore`'s default sweeps nothing (a store that cannot honor `retain` is left unswept), and `OzoneClientStrategy` has no default at all. `OzoneClaimCheckStore` also checks an object's names on the other transport (`s3://b/k` is `ofs://<s3-gateway-volume>/b/k` and `o3fs://b.<s3-gateway-volume>/k`, volume `s3v` by default), since both sweep the same buckets. Known limitation: claims of document messages not yet dead-lettered (pending, or being retried) are protected only by the TTL, so a message that waits longer than the TTL can lose its claim, including one dead-lettered during a sweep; keeping claims referenced by unconsumed messages on the documents topic would close it.
  - In decoupled deployments every stage must be able to reach the store: use a shared volume or Ozone.
- **Tombstones:** `DocumentAction.DELETE` skips claim-check storage, Tika, chunking and embedding. The writer then purges that document's chunks.
- **Failures:** listeners must rethrow, never log and swallow. The same holds for output connectors on the direct path (a job with an output connector): `JobOrchestrator` records a document whose `send` fails as `FAILED` with `OUTPUT_ERROR`. Never store a chunk without its real vector; `PrecomputedEmbeddingModel` would give it a placeholder `[1, 0, …]` that no search finds. Every vector output connector, `EmbeddingConsumer` and every `*StoreWriterConsumer` enforce this through `RequiredEmbeddings` (`oc-core`, #333): no model, a failed call or an empty vector fails the document before anything is written, and per-job outputs from `ConnectorFactory` get the runtime's embedding model, text extraction and Kafka template. Each service's `KafkaConfig` sets a `DefaultErrorHandler` that retries with exponential back-off (`opencrawling.kafka.retry.*`) and then publishes the record to `<topic>.DLT`; a payload that cannot be deserialized goes there at once. Messages are keyed by document id (`partitionKey()`), so a document's chunks and its DELETE stay in order.
- **Pipeline mode:** `opencrawling.pipeline.mode` is `RAG` (default) or `MIGRATION`, and a job can override it.
- **Stage toggles:** each of these defaults to `true`. Turning them off is how the decoupled containers split one codebase into separate stages.
  - `opencrawling.consumer.ingestion.enabled`
  - `opencrawling.consumer.embedding.enabled`
  - `opencrawling.consumer.writer.enabled`
  - `opencrawling.mcp.server.enabled`
- **Output selection:** `spring.opencrawling.output.type` picks the active writer.
  - Values: `pgvector` (default), `milvus`, `qdrant`, `vespa`, `solr`, `opensearch2`, `opensearch3`, `elasticsearch`, `seatunnel`, `ozone`, `luxir`, `doxis`.
  - Each writer consumer is gated on this property with `@ConditionalOnProperty` or `@ConditionalOnExpression`, and on `opencrawling.consumer.writer.enabled` with `matchIfMissing = true` (on by default, like every stage toggle; OpenArch's is still off). New outputs must follow the same pattern.
- **One write path per run:** a job run always publishes its documents to the documents topic. It calls the output connector's `send` directly only if the output's pipeline writer does not store them in that mode (`OutputConnector.writtenByPipeline(mode)`, checked by `JobOrchestrator.sendsDirectly`). The vector stores (pgvector, Qdrant, Milvus, Vespa, Solr, OpenSearch 2/3, Elasticsearch, Luxir, SeaTunnel) return `true` for RAG, so a RAG run writes them through Kafka only; writing both ways stored every document twice under different chunk ids. Outputs with no pipeline writer for the mode (Hyland CIC, the migration and document outputs, OpenArch for now) keep the direct write. The pipeline only reaches the runtime's own destination, since Kafka messages carry no output route: a per-job Qdrant or Vespa output that `ConnectorFactory` builds is constructed outside the pipeline (direct write, a constructor flag) unless the runtime's active output is the same store with the same host, port and collection (Qdrant) or endpoint, namespace and configured document type (Vespa: where a chunk lands depends on its embedding's length, unknown when the factory decides, so only an identical configuration counts; another type never loses documents but may store them twice); the check compares this process's settings as written, so in a decoupled deployment the crawler must share the writer's output settings, and SeaTunnel is pipeline-written only when its `kafka-topic` is `PipelineTopics.EMBEDDED`. A new vector store must override `writtenByPipeline` and be listed in `OutputWritePathContractTest`; one that `ConnectorFactory` builds per job also needs the same-destination check, and its `send` must handle tombstones (a per-job output gets them directly). Vespa's does so through `VespaChunkRemover`: Vespa's delete by selection (`document_id == …`, then `uri == … and document_id == null` for chunks that predate the field), in its own namespace and document types and the configured `content-cluster`, with the feed client's TLS settings (`VespaTls`). Never delete by searching first: search is eventually consistent, case-insensitive and paged.
- **Push ingestion:** `OisIngestController` accepts documents without a crawler. It writes each UPSERT's text to the claim-check store.

### Concurrency and tracing constraints

- **Virtual threads** are enabled globally (`spring.threads.virtual.enabled=true`). Job API runs start through `JobRunRegistry` (one run per job, optional caps `opencrawling.jobs.max-concurrent-runs` and `max-concurrent-runs-per-caller`, real stop and pause). Runs are kept in a `RunStore` (#315, #316): in memory with the `file` control-plane store, in `oc_run`/`oc_run_step` with `jdbc`, where the rules hold across runtimes and a stop, pause or resume asked on another runtime reaches the run at its runtime's heartbeat; a run whose runtime stops renewing its heartbeat is marked `INTERRUPTED`. A stop sets a flag that `JobOrchestrator` checks before every document and interrupts the run's thread, so a crawl ends at the latest before its next document; a pause holds the run at that same check. `RunRetention` deletes ended runs, local report files and completed executions after `opencrawling.control-plane.run-retention` (90 days). Connectors should let an interrupt end a blocking call in `scan` rather than swallow it, so a stop does not have to wait for the next document.
- **Fan-out** goes through `StructuredTaskScope` (a preview API).
- **Trace context** isn't inherited by virtual threads forked in a `StructuredTaskScope`. Wrap every forked task in `ObservabilityTask.observed(...)` (from `oc-observability`) so the trace reaches the Kafka `traceparent` headers.
- **Streaming:** connectors must keep heap use constant. Use cursor fetch sizes and `InputStream` content in `RepositoryDocument`. Never materialize whole result sets or files.

### MCP server

`McpVectorServer` uses Spring AI MCP with the `STREAMABLE_HTTP` transport on the runtime port and exposes three `@McpTool` methods:
- **`secureVectorSearch`** runs a similarity search pre-filtered on the `security_read_keys`, `security_allowed_read` and `acl` metadata fields (pgvector), then authoritatively re-checks each hit against `SecurityConfig` via `McpAclEvaluator`.
- **`getDocumentContent`** returns a document by URI or id, behind the same ACL guard: every backend fetches the document's lowest chunk positions (up to `opencrawling.mcp.document-content.max-chunks`, default 200: a sort by `chunk_index`, or a position filter with a plain-lookup fallback for chunks stored without one), keeps the readable ones and joins them in order through `DocumentChunks`. It places chunks stored without an index by their pipeline id (`UUID(documentId#index)`), leaves out chunks of another version (another `total_chunks`), and reports `chunkCount`, `totalChunks` and `complete` (only with a recorded count). Writers must keep `chunk_index` and `total_chunks` queryable: Milvus collections have dynamic fields, Luxir stores `chunk_index_i`/`total_chunks_i`, the Vespa schemas declare `chunk_index`/`total_chunks`.
- **`listAccessibleSources`** lists the indexed URIs that a principal and its groups can see.

When the output type is `vespa`, `VespaMcpVectorServer` replaces it.

With `opencrawling.discovery.enabled=true` (off by default), `DiscoveryMcpTools` adds `listRepositoryConnectors`, `exploreRepositoryTree` and `queryRepositoryDryRun`, and `DiscoveryController` serves the same under `/api/discovery/**`. `DiscoveryService` decides what a caller sees with the metadata rules on each item's *source* ACL, through a `DocumentAccessGate` on `MetadataAuthorizationProvider`, whatever the authorization mode (OpenFGA only knows indexed documents). It needs a verified caller unless `allow-asserted-identity` is set, and never returns ACLs or repository error details.

With `opencrawling.jobs.dynamic.enabled=true` (off by default), `DynamicJobMcpTools` (`triggerDynamicJob`, `getDynamicJobStatus`, `listDynamicJobs`, `stopDynamicJob`) and `DynamicJobController` (`/api/jobs/dynamic/**`) let a token-verified caller with the `required-scope` crawl a folder of a connector listed in `opencrawling.jobs.dynamic.connectors`. `DynamicJobService` resolves the start folder with `DiscoverableRepositoryConnector.crawlStart` (in scope, readable by the caller's source-ACL rules) and runs the crawl through `JobRunRegistry` and `JobOrchestrator` with no output connector, so documents only go to the documents topic (RAG mode). With the `document-scope`, `ingestDocument`/`removeDeletedDocument` (REST `POST /api/jobs/dynamic/documents`) ingest one document as a one-document job, or publish a tombstone only when the repository reports the document gone. Status is visible to the job's user and the `admin-groups`; a dynamic job is one run in the registry's `RunStore` from its request to its end (#316, `RunDynamicJobStore`: kind `DYNAMIC_FOLDER`/`DYNAMIC_DOCUMENT`, the job's record in the run's `body`, keys in `oc_dynamic_job_key` with `jdbc`; shared by every runtime, kept across restarts, a pending job's requester identity included but never a token). One state machine for every run (`RunStore.RunState`): waiting (`QUEUED`, `PENDING_APPROVAL`; filed by `RunStore.request`), active (`RUNNING`, `PAUSED`; only these hold a slot and count towards the caps), ended (`COMPLETED`, `FAILED`, `STOPPED`, `INTERRUPTED`, `REJECTED`, `EXPIRED`); `claim` starts a waiting run in place, and a cap still answers `429`. The registry changes active runs; `RunDynamicJobStore.save` records only what the job decides (approval, the end of a job that never started), and the dynamic-job API shows `QUEUED` as `RUNNING`. Migration `V7` (a Java migration in `db.migration`, since H2 and PostgreSQL read JSON differently) moved V6's `oc_dynamic_job` into `oc_run`. A running dynamic job can be paused and resumed (`DynamicJobService.pause`/`resume`); one whose runtime died becomes `INTERRUPTED` through the run registry's interruption listener. `RunsV1Controller` (`/api/v1/runs`) lists and follows job runs and dynamic jobs together, each with its own visibility. With `require-approval`, agents' folder jobs and document ingests are filed as `PENDING_APPROVAL` (after the usual checks) until another admin calls `approve` (checks run again under the requester's identity, same job id) or `reject`; no MCP tool can approve. A job may carry its own `ChunkingSpec` (chunk size within `min-chunk-size`/`max-chunk-size`, minimums, separator); it travels on `IngestionMessage.chunking` (optional, `null` = defaults) and `IngestionConsumer` builds a matching `TokenTextSplitter`. `ChunkingDefaults` fills in the global `chunkSize` from the settings (`SystemController`) where a job gives none, in `JobOrchestrator.runJob` and `publishDocument`; the settings' `chunkOverlap` is not used.

With `opencrawling.events.bpm.enabled=true` (off by default), `BpmEventController` (`POST /api/events/bpm/document-transition`) lets a BPM engine trigger a single-document UPSERT or DELETE: the first matching `routes` entry (engine, process, transition) names the connector and action, and `BpmEventService` calls the same `DynamicJobService` path with `VisibilityFilter.ALL` (the route authorizes, not the sender). A sender authenticates by HMAC signature or Basic static token (`BpmEventAuthenticator`, configured `sources`, secrets of at least 32 characters) or by a bearer token with `oc:events:write`. With `protect-api`, `IdentitySecurityConfiguration` lets only signed or Basic requests to that path skip the JWT chain. Event ids are deduplicated per sender.

With `opencrawling.sidecars.enabled=true` (off by default), `SidecarMcpTools` (`listSidecarStores`, `readOisSidecars`) and `SidecarController` (`/api/sidecars/**`) let a verified caller with `oc:sidecars:read` read the `*.ois.json` sidecars of the registered Ozone or S3 output connectors listed in `opencrawling.sidecars.connectors`.
- **Storage:** `SidecarService` opens the store read-only from the connector's settings (`ConnectorFactory.s3Properties`/`ozoneProperties`, `OzoneStorageClient.listKeys`). It skips tombstones, the key index and anything that is not an `UPSERT`.
- **Authorization:** the runtime's `DocumentAccessGate` decides on each sidecar's document id and `security`. Answers carry no permissions or hidden counts.
- **Paging:** the cursor is AES-GCM sealed, because it may name a hidden key.

### Pre-embedding governance

With `opencrawling.governance.enabled=true` (off by default), `IngestionConsumer` passes each UPSERT's extracted text and metadata through a `GovernanceGate` before chunking it. The gate runs the `hooks` in order: the built-in `pii-masking` (`PiiMaskingGovernanceHook`) or `http` (`HttpGovernanceHook`, HMAC-signed, bounded retries), or any `PreEmbeddingGovernanceHook` bean (SPI in `oc-core`, `core.governance`). Each hook answers APPROVE, SANITIZE or REJECT.
- **Permissions:** hooks can never change the protected metadata keys (`GovernanceGate.isProtectedKey`).
- **Failures:** a hook that fails means nothing is embedded.
- **Rejections:** a rejection publishes a tombstone for the earlier version unless `on-rejected-update=keep`. It also publishes `IngestionMessage.governanceRejection` on the documents topic, so `AuthorizationSyncConsumer` removes the OpenFGA tuples (unless a later version was synced since); `IngestionConsumer` skips that notice.
- **Direct outputs:** with the gate on, `JobOrchestrator.bypassesGovernance` refuses RAG runs that would `send` documents directly to an output that embeds them itself, since that skips the gate. The job API answers `409`. RAG runs on the vector stores are not refused: they write through the pipeline only.

### Security / ACL model

- **Caller identity:** `opencrawling.security.identity.mode` is `none` (default; MCP tools trust the `userPrincipal`/`userRoles` arguments) or `jwt` (OAuth2 resource server; MCP endpoints need a bearer token and `CallerResolver` takes the identity from it). `IdentitySecurityConfiguration` keeps every other path open (no CSRF, no added headers) unless `protect-api` is set. New MCP tools must get their caller from `CallerResolver`, never straight from their arguments.
- **Management authorization (#187):** `opencrawling.security.management.enabled` (off by default; needs `jwt` mode with `protect-api`) gates the management plane on the verified caller. `ManagementAuthorization` holds the rules (admins, then a job's `JobAccess`: tenant, owner, allowed and viewer lists); `ManagementAuthorizationInterceptor` lists the admin-only routes in one place; `JobVisibility` decides on reports and traces, including dynamic jobs. Invisible jobs answer `404`. New management endpoints must either be added to the interceptor's list or check `JobAccess` in their controller. Internal callers that authenticate on their own (OIS ingest, gRPC push) use `JobController.findJobById`, never `getJob`.
- **Admin UI sign-in (#187):** with `opencrawling.security.identity.admin-ui.client-id` set (jwt mode), the UI reads the public `GET /api/auth/config` (`AuthController`; the only `/api/**` route outside the JWT chain besides OIS ingest and signed BPM events), signs in with oidc-client-ts (authorization code + PKCE, `src/lib/auth.ts`), and sends the access token via axios interceptors. It shows pages and job actions from `GET /api/v1/me` and each job's `permissions` in `GET /api/v1/jobs`; the runtime still enforces everything. Downloads go through the API client (`reportApi.downloadExport`), never a plain link, so they carry the token. Reference Keycloak: `docker/docker-compose-with-keycloak.yml`.
- **Client tokens (#187):** the SDK sends tokens from a `TokenProvider` (`org.opencrawling.sdk.auth`: fixed, `ClientCredentialsTokenProvider`, `RefreshTokenProvider`) and repeats a request once after a `401` if the provider got a new token. The CLI's `oc auth login` (device grant, `DeviceLogin`) stores sign-ins per server in `credentials.json` (owner-only) under `CliConfigService.configDir()`; `CliConfigService.tokenProvider` decides the precedence. Never print or log tokens or secrets; `OAuth2Client` errors never echo response bodies.
- **Platform API (#313):** `org.opencrawling.runtime.api.v1` serves `/api/v1` (docs/Platform-API.md). Its controllers call the legacy controllers (`JobController.save`/`start` return typed outcomes for this), so rules live in one place; they never duplicate validation or authorization. Errors are problem details via `ApiV1ExceptionResolver`, scoped to `/api/v1` (not `/api/v1/ingest`, `/api/v1/admin`), so legacy responses are unchanged. A new v1 route that is admin-only must be added to `ManagementAuthorizationInterceptor`; custom methods (`/x:check`) are separate path segments and need their own rule. Collections use `Paging`, single resources `Etags`, POSTs get `IdempotencyFilter` automatically; its keys live in `InMemoryIdempotencyStore` or, with `opencrawling.api.idempotency.store=redis` (the Compose stacks), `RedisIdempotencyStore`, or with `jdbc`, `JdbcIdempotencyStore` (`oc_idempotency_key` in the control-plane database). Clients: the admin UI (`src/lib/api.ts`, baseURL `/api/v1`), the SDK's `client.platform()` (`org.opencrawling.sdk.v1`) and the CLI use v1; `PlatformApiV1UiParityTest` (UI sources), `PlatformApiV1SdkTest` (the SDK reaches every operation) and `PlatformApiCommandsTest` (CLI) guard that. A new v1 operation needs an SDK method, or `PlatformApiV1SdkTest` fails. No HTTP answer carries connector secrets (`ConnectorResource.masked`, legacy reads included); runtime code that needs them uses `ConnectorController.connectorsWithSecrets`, `save` and `check`, never the HTTP handlers. The legacy management endpoints are deprecated (`LegacyApi` lists each with its successor; `LegacyDeprecationFilter` adds `Deprecation` and `Link: rel="successor-version"`); a new legacy route with a v1 replacement belongs in that list.
- **OIS blocks (#292):** `JobOrchestrator.withOisBlocks` (crawls) and `publishDocument(..., jobId, provenance, sourceType, sourceInstance)` (pushes) stamp every document's metadata with flat `ois:source.*` and `ois:provenance.*` keys (`OisMetadata`), replacing anything the source claimed; `IngestionConsumer` adds `ois:governance.*` after the gate and drops any claimed one. `GovernanceGate` protects all three. The Ozone and S3 writers fold them into the OIS sidecar (`OisSidecar` in `oc-core`), which must validate against the OIS document schema: `OisSidecarConformanceTest` checks it (bundled copy in `oc-runtime/src/test/resources/ois/`; CI's `ois-conformance` job uses the OIS repo's current schema). A repository connector reports its base URI with `sourceInstance()`.
- **Run provenance:** every ledger execution records an `ExecutionProvenance` (who started it, how, `X-Correlation-Id`), written to the run summary and to every Parquet/Avro row. Only a verified caller is ever recorded (`CallerResolver.verifiedCaller()`, read on the request thread, since the run's virtual thread has no security context). A new way of starting executions must pass its provenance to `JobLedgerAccumulator.startExecution`.
- **Where it lives:** `SecurityConfig` is stored as a JSON map in `metadata["security"]`, and flattened into `security_allowed_read` and `security_denied_read` lists for vector-store pre-filtering.
- **Rules:** it holds `PermissionRule` records with these fields:
  - `identity`
  - `identityType` (`USER`, `GROUP` or `TENANT`, or an OIS namespaced type: any `*-user` resolves as a user and any `*-group` as a group, e.g. `cmis-group`; the stored rule keeps the raw type)
  - `displayName`
  - `access` (`read`, `write` or `deny`)
- **Precedence:** an explicit `deny` always wins.
- **Legacy field:** the flat `acl` string is also supported alongside `security_allowed_read` in vector-store filter expressions. Connectors must populate `SecurityConfig` (and optionally `acl`).

### pgvector routing

- **Beans:** `oc-vector-output-connector` defines four `PgVectorStore` beans:
  - `vectorStore` (`@Primary`, table `vector_store`)
  - `vectorStore384`, `vectorStore768` and `vectorStore1024` (tables `vector_store_384`, `_768` and `_1024`)
- **Embedding model:** all four use `PrecomputedEmbeddingModel`, a no-op that accepts the vectors already computed upstream.
- **Store selection:** `VectorStoreWriterConsumer` picks the store from the length of the incoming vector.
- **Model dimensions:** the mapping from model name to dimension (`mxbai-embed-large` 1024, `nomic-embed-text` 768, `all-minilm` 384) lives in the admin UI (`Settings.tsx`), not the backend.

### Configuration and persistence

- **Saved state:** job, connector, settings and transport configs are read and written through the `ControlPlaneStore` bean (#315), never `PersistenceHelper` directly. `opencrawling.control-plane.store=file` (default) keeps them as JSON files in `data/` (`jobs.json`, `connectors.json`, `settings.json`, `transport-settings.json`); `jdbc` keeps them in the datasource's `oc_job`, `oc_connector` and `oc_setting` tables, created by Flyway (`db/migration`), and imports the files on its first start. Add schema changes as a new `V<n>__*.sql` that runs on both PostgreSQL and H2; never edit an applied one. Several runtimes may share a `jdbc` store: reads are read-through (cached per store revision), and every change that depends on what is stored (find, check `If-Match`, then save) must run inside `ControlPlaneStore.locked` (exposed as `JobController`/`ConnectorController`/`SystemController.locked`), the one lock for jobs, connectors and settings, here and in other runtimes; never hold a list in a field or use `synchronized` for this. The v1 ETag key (`api-v1-etag-key.json`) lives in the store too.
- **Datasource:** the default in `application.yml` is in-memory H2. Spring profiles: `dev` and `prod`.
- **API docs:** the REST API is documented with OpenAPI 3.1 and served through Swagger UI (`OpenApiConfig`).

### Adding a connector

Follow the blueprint and quality checklist in `docs/Architecture.md`, or generate a skeleton with `oc-connector-archetypes`. Points that aren't obvious from a single file:

- **Root `pom.xml`:** register the module in `<modules>` and `<dependencyManagement>`.
- **`oc-runtime/pom.xml`:** add the module as a dependency.
- **UI discovery:** `ConnectorController` lists connectors from two places. One is its hard-coded built-in defaults. The other is `ServiceLoader` discovery, which needs a `META-INF/services/org.opencrawling.core.connector.<Kind>Connector` file (the archetypes generate one).
- **Job dispatch:** jobs started through the job API get their connectors from `ConnectorFactory` (`oc-runtime/.../connector/`). `RepositoryKind` / `OutputKind` match substrings of the configured `className` (for example `contains("Jdbc")`). A new repository connector needs a kind and a builder there; an unknown repository class stops the job (no fallback to the file system), while unknown outputs use the runtime's configured output.
- **Health check:** add a probe in `ConnectorCheckerService`.
- **Admin UI:** add the connector's form to `oc-admin-ui/src/components/ConnectorForm.tsx`.
- **Docker:** add the JAR to `docker/Dockerfile.crawler` and `docker/Dockerfile.ingestion-consumer`, and give the module its own `docker/docker-compose-decoupled-with-<name>.yml`.
- **Tests:** add `scripts/test-<name>-connector.sh` and `scripts/test-<name>-decoupled.sh`.

## Conventions

- **Commits:** follow Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`), usually with a scope and issue number, e.g. `fix(tika): ... (#147)`. PRs target `main` and reference their issue.
- **Code style:** Java follows Google Java Style. Admin UI code is TypeScript only.
- **License headers:** every source file (including `.ts`, `.tsx` and shell scripts) carries the Apache 2.0 header managed by `license-maven-plugin`. Run `mvn license:format` after adding files.
