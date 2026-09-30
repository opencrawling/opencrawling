# OpenCrawling - Alfresco Process Services (APS) Repository Connector

The `oc-aps-repository-connector` module allows OpenCrawling to crawl workflow instances, historic process executions, task data, and process variables from an **Alfresco Process Services (APS) 26.2** REST API (`/activiti-app/api/enterprise`).

## Features
- Crawls historic and active process instances from APS Enterprise REST API (`/historic-process-instances/query`).
- Extracts standard process execution metadata (`processDefinitionId`, `processDefinitionKey`, `processDefinitionName`, `businessKey`, `startUserId`, `startTime`, `endTime`, `durationInMillis`, `tenantId`).
- Dynamically maps process variables into metadata attributes prefixed with `aps_var_<varName>`.
- Extracts user tasks, assignees, and candidate groups/users, generating fine-grained Zero-Trust Access Control Lists (ACLs) compatible with Open Ingestion Standard (OIS).
- **Workflow Attachment Ingestion**: Discovers and ingests documents and text attachments attached at the process instance level (`/process-instances/{id}/content`) or task level (`/tasks/{id}/content`), fetching the raw content binary streams (`/content/{contentId}/raw`), preserving zero-trust security ACLs, and propagating process variables.
- **APS Model Context Protocol (MCP) Server Integration**: Uses `ApsMcpClient` to dynamically discover deployed workflow definitions (`tools/call: list_process_definitions`), variable schemas, and candidate groups over standard JSON-RPC 2.0.
- High-concurrency batch execution using **Java 25 Virtual Threads** and **Structured Task Scope**.
- Validated against **Alfresco Process Services 26.2.0** (`alfresco/process-services:26.2.0`).

## Configuration Properties

| Property key | Environment Variable | Default | Description |
|---|---|---|---|
| `url` | `APS_URL` | `http://localhost:8080/activiti-app/api/enterprise` | Base URL of the APS Enterprise REST API |
| `username` | `APS_USERNAME` | `admin@app.activiti.com` | APS Basic Auth username |
| `password` | `APS_PASSWORD` | `admin` | APS Basic Auth password |
| `batch-size` | `APS_BATCH_SIZE` | `100` | Process instance page batch size |
| `process-definition-key` | `APS_PROCESS_DEFINITION_KEY` | `""` | Optional process definition key filter (or empty for MCP discovery / all workflows) |
| `include-variables` | `APS_INCLUDE_VARIABLES` | `true` | Whether to fetch process instance variables |
| `include-tasks` | `APS_INCLUDE_TASKS` | `true` | Whether to fetch tasks and candidate group ACLs |
| `include-attachments` | `APS_INCLUDE_ATTACHMENTS` | `true` | Whether to fetch and ingest workflow instance attachments and documents |
| `tenant-id` | `APS_TENANT_ID` | `""` | Optional tenant ID filter for multi-tenant APS |
| `scope` | `APS_SCOPE` | `all` | State filter: `all`, `completed`, or `active` |
| `mcp.enabled` | `APS_MCP_ENABLED` | `false` | Enable dynamic workflow and schema discovery via APS MCP Server |
| `mcp.url` | `APS_MCP_URL` | `""` (defaults to `<url>/mcp`) | Endpoint URL for the APS MCP Server |

## Integration Testing

The module provides end-to-end integration tests that run against real Alfresco Process Services 26.2 container instances:

### 1. Standalone Connector Integration Test
Runs [`ApsRepositoryConnectorIT.java`](file:///Users/piergiorgiolucidi/Documents/workspaces/opencrawling/opencrawling-in-org/oc-aps-repository-connector/src/test/java/org/opencrawling/aps/ApsRepositoryConnectorIT.java) against an APS 26.2 container:

```bash
./scripts/test-aps-connector.sh
```

This script:
- Verifies Docker and dependency prerequisites.
- Starts APS 26.2 and PostgreSQL via [`docker-compose-aps.yml`](file:///Users/piergiorgiolucidi/Documents/workspaces/opencrawling/opencrawling-in-org/oc-aps-repository-connector/docker/docker-compose-aps.yml) if not already online.
- Validates `/profile` and authenticated REST engine connectivity.
- Automatically deploys the sample BPMN 2.0 workflow (`invoiceApproval.bpmn20.xml`), publishes the App Definition, and seeds 3 process instances with variables via [`scripts/seed-aps-workflows.sh`](file:///Users/piergiorgiolucidi/Documents/workspaces/opencrawling/opencrawling-in-org/scripts/seed-aps-workflows.sh).
- Runs Maven unit and integration tests (`mvn test -pl oc-aps-repository-connector -Dtest="*Test,*IT"`).
- Automatically cleans up test containers.

### 2. Full Decoupled Pipeline Integration Test
Validates end-to-end distributed ingestion across the entire multi-service architecture:

```bash
./scripts/test-aps-decoupled.sh
```

Tests: APS 26.2 -> Workflow Seeding (`invoiceApproval` with business variables & candidate groups) -> OpenCrawling Crawler -> Apache Kafka / Redpanda -> Ingestion Consumer (Claim-Check & Tika chunking) -> Ollama (vector embeddings) -> PostgreSQL (pgvector writer) -> OpenCrawling MCP Server.

## Enterprise Licensing Note

Alfresco Process Services (APS) 26.2 is an enterprise commercial product. The official Docker image (`alfresco/process-services:26.2.0`) requires an enterprise license file (`activiti.lic`) to enable App Modeler features (importing BPMN files and publishing App definitions), and optionally a transformation license file (`transform.lic`) for transformation services.

- **Unlicensed Mode (Default / Community Evaluation)**: The connector connects to APS, verifies authentication, checks health, and performs queries against an empty repository. The test scripts (`test-aps-connector.sh` and `test-aps-decoupled.sh`) gracefully detect the unlicensed state and validate the pipeline cleanly without errors.
- **Enterprise Mode (With License)**: If you possess Alfresco Process Services license files (`activiti.lic` and/or `transform.lic`), you can place them in any of the following locations (checked automatically in order of precedence):
  1. **User Home Directory (Recommended for local dev)**:
     - `~/.activiti/enterprise-license/activiti.lic`
     - `~/.activiti/enterprise-license/transform.lic`
     *(Keeps your licenses outside the codebase, ensuring they are never accidentally committed).*
  2. **Project Root Directory**:
     - `opencrawling/activiti.lic`
     - `opencrawling/transform.lic`
     *(All `*.lic` files are ignored in `.gitignore`).*
  3. **Connector Docker Directory**:
     - `oc-aps-repository-connector/docker/activiti.lic`
     - `oc-aps-repository-connector/docker/transform.lic`
  4. **Scripts Directory**:
     - `scripts/activiti.lic`
     - `scripts/transform.lic`
  5. **Custom Paths via Environment Variables (Ideal for CI/CD)**:
     ```bash
     export APS_LICENSE_FILE="/path/to/activiti.lic"
     export APS_TRANSFORM_LICENSE_FILE="/path/to/transform.lic"
     ```

When detected, the test runner scripts (`test-aps-connector.sh` and `test-aps-decoupled.sh`) automatically mount both `activiti.lic` and `transform.lic` into the container at `/home/alfresco/.activiti/enterprise-license/` and Tomcat classpath `/usr/local/tomcat/lib/`.
Furthermore, [`scripts/seed-aps-workflows.sh`](file:///Users/piergiorgiolucidi/Documents/workspaces/opencrawling/opencrawling-in-org/scripts/seed-aps-workflows.sh) will register the license with the running APS container via `POST /activiti-app/api/enterprise/license`, import the sample `invoiceApproval` workflow, publish the application, and seed process instances populated with variables and tasks.





