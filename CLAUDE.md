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
- **Kafka groups:** `spring.kafka.consumer.group-id` is randomized per run (`test-group-${random.uuid}`) so runs don't collide on offsets.
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

The runtime's topic names are constants in `oc-runtime/.../config/KafkaConfig.java`. `oc-embedding-service` has its own `KafkaConfig` copy, so if you rename a topic, change both.

- **Claim-check store:** `spring.opencrawling.claim-check.store` is `local` (default dir `target/claims`) or `ozone`.
  - Claims are deleted on consume (`cleanup-on-consume`). `ClaimCheckGarbageCollector` sweeps orphans every hour by default.
  - In decoupled deployments every stage must be able to reach the store: use a shared volume or Ozone.
- **Tombstones:** `DocumentAction.DELETE` skips claim-check storage, Tika, chunking and embedding. The writer then purges that document's chunks.
- **Pipeline mode:** `opencrawling.pipeline.mode` is `RAG` (default) or `MIGRATION`, and a job can override it.
- **Stage toggles:** each of these defaults to `true`. Turning them off is how the decoupled containers split one codebase into separate stages.
  - `opencrawling.consumer.ingestion.enabled`
  - `opencrawling.consumer.embedding.enabled`
  - `opencrawling.consumer.writer.enabled`
  - `opencrawling.mcp.server.enabled`
- **Output selection:** `spring.opencrawling.output.type` picks the active writer.
  - Values: `pgvector` (default), `milvus`, `qdrant`, `vespa`, `solr`, `opensearch2`, `opensearch3`, `seatunnel`, `ozone`, `luxir`.
  - Each writer consumer is gated on this property with `@ConditionalOnProperty` or `@ConditionalOnExpression`. New outputs must follow the same pattern.
- **Push ingestion:** `OisIngestController` accepts documents without a crawler. It writes each UPSERT's text to the claim-check store.

### Concurrency and tracing constraints

- **Virtual threads** are enabled globally (`spring.threads.virtual.enabled=true`). Job API runs start through `JobRunRegistry` (one run per job, optional cap `opencrawling.jobs.max-concurrent-runs`, real stop). A stop sets a flag that `JobOrchestrator` checks before every document and interrupts the run's thread, so a crawl ends at the latest before its next document. Connectors should let an interrupt end a blocking call in `scan` rather than swallow it, so a stop does not have to wait for the next document.
- **Fan-out** goes through `StructuredTaskScope` (a preview API).
- **Trace context** isn't inherited by virtual threads forked in a `StructuredTaskScope`. Wrap every forked task in `ObservabilityTask.observed(...)` (from `oc-observability`) so the trace reaches the Kafka `traceparent` headers.
- **Streaming:** connectors must keep heap use constant. Use cursor fetch sizes and `InputStream` content in `RepositoryDocument`. Never materialize whole result sets or files.

### MCP server

`McpVectorServer` uses Spring AI MCP with the `STREAMABLE_HTTP` transport on the runtime port and exposes three `@McpTool` methods:
- **`secureVectorSearch`** runs a similarity search pre-filtered on the `security_read_keys`, `security_allowed_read` and `acl` metadata fields (pgvector), then authoritatively re-checks each hit against `SecurityConfig` via `McpAclEvaluator`.
- **`getDocumentContent`** returns a document's chunks by URI, behind the same ACL guard.
- **`listAccessibleSources`** lists the indexed URIs that a principal and its groups can see.

When the output type is `vespa`, `VespaMcpVectorServer` replaces it.

### Security / ACL model

- **Where it lives:** `SecurityConfig` is stored as a JSON map in `metadata["security"]`, and flattened into `security_allowed_read` and `security_denied_read` lists for vector-store pre-filtering.
- **Rules:** it holds `PermissionRule` records with these fields:
  - `identity`
  - `identityType` (`USER`, `GROUP` or `TENANT`)
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

- **Saved state:** job, connector, settings and transport configs are JSON files in `data/`, relative to the working directory. They are read and written through `PersistenceHelper` (`jobs.json`, `connectors.json`, `settings.json`, `transport-settings.json`).
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
