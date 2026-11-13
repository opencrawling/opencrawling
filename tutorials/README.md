# OpenCrawling Tutorials — Workspace Resources

This directory contains self-contained resources for hands-on OpenCrawling tutorials.

## Directory Layout

| Folder | Contents |
| :--- | :--- |
| `docker/` | Dedicated Docker Compose stacks (`docker-compose-alfresco-pgvector-mcp.yml`) |
| `data/` | Sample documents organized by repository & permission tier (`data/alfresco/...`) |
| `config/` | Reference OpenCrawling connector (`alfresco-connector.json`) and job (`alfresco-pgvector-job.json`) JSON payloads |
| `mcp/` | MCP Inspector configuration (`mcp-inspector-config.json`) and caller persona query examples (`sample-queries.json`) |
| `scripts/` | Shell scripts to boot Docker, provision Alfresco ACLs, run/configure jobs, verify PGVector/Reports/MCP, and tear down |

## Tutorial: Alfresco Content Services + PGVector + MCP Inspector

### 1. Start the Local Docker Environment

```bash
./tutorials/scripts/start-env.sh
```

Wait until all services report healthy:
* Alfresco Share UI: `http://localhost:8081/share/` (`admin` / `admin`)
* Alfresco Content App / Digital Workspace (Community): `http://localhost:8081/workspace/` (alias `http://localhost:8081/content-app/`)
* Alfresco REST API v1: `http://localhost:8081/alfresco/api/-default-/public/alfresco/versions/1`
* OpenCrawling Admin UI: `http://localhost:3000`
* OpenCrawling MCP Server (Streamable HTTP): `http://localhost:8080/mcp`
* PGVector: `localhost:5432` (database `opencrawling`, user `opencrawling`)

> **Note**: In ACS Community Edition, `alfresco/alfresco-content-app` provides the open-source Community counterpart to Enterprise Digital Workspace (`quay.io/alfresco/alfresco-digital-workspace`, which requires Enterprise registry credentials). All Alfresco services sit behind the bundled Traefik reverse proxy on host port `8081`.

### 2. Provision Alfresco Test Data & Permissions

```bash
./tutorials/scripts/provision-alfresco-data.sh
```

This creates three users (`alice`, `bob`, `charlie`, password `Password123!`), two groups (`GROUP_engineering`, `GROUP_finance`), and four documents under `/opencrawling-tutorial`:
* `public/company-handbook.txt` -> `GROUP_EVERYONE` (`ALLOWED`)
* `finance/q4-financial-forecast.txt` -> `GROUP_finance` (`ALLOWED`, inheritance off)
* `engineering/zero-trust-architecture.txt` -> `GROUP_engineering` (`ALLOWED`), `bob` (explicit `DENIED`)
* `executive/board-resolution-2026.txt` -> `alice` only (`ALLOWED`, inheritance off)

#### Inspecting the Permission Schema Visually (Share & Content App)
Before crawling, sign into **Alfresco Share (`http://localhost:8081/share/`)** or **Alfresco Content App / Workspace (`http://localhost:8081/workspace/`)**:
1. As **`admin` / `admin`**, open **Repository -> `opencrawling-tutorial`** to inspect permission inheritance and folder/document ACLs via **Manage Permissions** (Share) or **More actions (`...`) -> Permissions** (Content App). *(Note: Share's legacy Manage Permissions dialog omits explicit `DENIED` ACEs; inspect `bob`'s deny via `GET /nodes/{id}?include=permissions` or by signing in as `bob`.)*
2. Sign in as **`alice`**, **`bob`**, or **`charlie`** (password `Password123!`) and browse **Repository -> `opencrawling-tutorial`** to verify effective visibility: `alice` sees all 4 documents, `charlie` sees `public` and `finance`, and `bob` sees `public` and `engineering/` as an empty folder because the explicit `DENIED` ACE blocks `zero-trust-architecture.txt`.
3. Folder browsing in **Repository** works directly against PostgreSQL even with `-Dindex.subsystem.name=noindex` (global UI search requires Solr/Elasticsearch, while semantic search in this tutorial runs through OpenCrawling + PGVector).

### 3. Configure Connector & Ingestion Job (Admin UI or Script)

In **Admin UI (`http://localhost:3000`)**:
1. Open **Connectors** -> add/save `Alfresco Content Services Repository` (`Alfresco_Tutorial`) using `http://alfresco:8080/alfresco/api/-default-/public/alfresco/versions/1`, `Crawl Mode = folder`, `Root Folder Path = /opencrawling-tutorial`, and `Include Access Control Lists (ACLs) & Collocated Security = true` (see `config/alfresco-connector.json`). Click **Test Connection**.
2. Open **Job Management** -> **Create Job** (`Alfresco_Tutorial_PGVector_Job`) linking `Alfresco_Tutorial` -> `PGVector_Output` with transformation `Ollama_Embedding_Default`, `Pipeline Mode = RAG Mode`, and `path = /opencrawling-tutorial` (see `config/alfresco-pgvector-job.json`). Click **Start**.

*(Or automate steps 3 & 4 via REST API: `./tutorials/scripts/configure-opencrawling.sh`)*

### 4. Inspect Execution in Reports & Audit

In **Admin UI (`http://localhost:3000`) -> Reports & Audit**, inspect:
* Execution summary metrics & document ledger rows
* **Query with DuckDB** over Parquet reports (e.g. `SELECT status, count(*) FROM read_parquet('/data/reports/**/*.parquet') GROUP BY status`)
* **CSV Manifest**, **Parquet File**, and **JSON Summary** downloads

### 5. Launch MCP Inspector & Verify Permissions / Queries

Launch MCP Inspector Web UI preloaded with the OpenCrawling server:

```bash
npx @modelcontextprotocol/inspector --config tutorials/mcp/mcp-inspector-config.json --server opencrawling-alfresco
```

Once connected in MCP Inspector:
* See [mcp/sample-queries.json](mcp/sample-queries.json) for ready-to-paste tool arguments across four caller personas (`guest`, `charlie`, `bob`, `alice`).
* **Tip for `listAccessibleSources`**: pass `"dimensions": 1024` so it queries `vector_store_1024` only (when jobs run through the Admin UI on a decoupled stack, `VectorOutputConnector` also writes to `vector_store` while Kafka consumers write to `vector_store_1024`). For `secureVectorSearch`, omitting `dimensions` automatically selects `vector_store_1024` from the 1024-d query vector.
* Remember Alfresco groups carry the `GROUP_` prefix (`GROUP_finance`, `GROUP_engineering`).

Or run automated verification (PGVector SQL + Reports API + DuckDB + MCP Inspector CLI across `guest`, `charlie`, `bob`, `alice` + Discovery `exploreRepositoryTree`):

```bash
./tutorials/scripts/verify-tutorial.sh
```

### 6. Tear Down

```bash
./tutorials/scripts/stop-env.sh          # stop containers, preserve volumes
./tutorials/scripts/stop-env.sh --clean  # remove containers and tutorial volumes
```
