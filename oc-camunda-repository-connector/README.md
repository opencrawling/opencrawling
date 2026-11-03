# OpenCrawling - Camunda Repository Connector

The `oc-camunda-repository-connector` module allows OpenCrawling to harvest BPMN process instances, user tasks, variables, and incidents from **Camunda 8 (Zeebe Orchestration Engine & REST API v2)** as well as legacy **Camunda Platform 7**.

## Features
- **Camunda 8 REST API v2 Integration**: Native interaction with `/v2/process-instances/search`, `/v2/user-tasks/search`, `/v2/variables/search`, and `/v2/incidents/search`.
- **Zero-Trust Security & ReBAC**: Translates user task `assignee`, `candidateUsers`, `candidateGroups`, and process starters into Open Ingestion Standard (OIS) `PermissionRule` entries for pgvector, OpenFGA, and MCP authorization.
- **Enterprise Multi-Tenancy**: Native support for Camunda 8 `tenantId`, isolating workflow knowledge graphs across multi-tenant vector indexes.
- **Dual Authentication Paradigms**:
  - **Basic Authentication**: For local development and self-managed Zeebe gateways.
  - **OAuth2 / OIDC Client Credentials**: For enterprise Keycloak, Camunda Identity, and Camunda 8 SaaS console.
- **Dynamic Variable Ingestion**: Extracts BPMN process and task variables into typed metadata (`camunda8_var_<name>` and `camunda_var_<name>`).
- **Observability & Incidents**: Streams active incident counts (`camunda8_incident_count`) for AIOps and workflow diagnostics.
- **High-Throughput Concurrency**: Parallelized batch processing using **Java 25 Virtual Threads** (`StructuredTaskScope`) with distributed trace observation.
- **Legacy Compatibility**: Automatically falls back to Camunda 7 `/engine-rest` when configured with legacy endpoints.

## Configuration Properties

| Property key | Environment Variable | Default | Description |
|---|---|---|---|
| `url` | `CAMUNDA_URL` | `http://localhost:8080/v2` | Base URL of the Camunda 8 REST API v2 or Camunda 7 `/engine-rest` |
| `auth-type` | `CAMUNDA_AUTH_TYPE` | `basic` | Authentication mode: `basic` or `oauth2` |
| `username` | `CAMUNDA_USERNAME` | `demo` | Basic Auth username |
| `password` | `CAMUNDA_PASSWORD` | `demo` | Basic Auth password |
| `oauth-token-url` | `CAMUNDA_OAUTH_TOKEN_URL` | `""` | OAuth2 token endpoint URL |
| `client-id` | `CAMUNDA_CLIENT_ID` | `""` | OAuth2 client identifier |
| `client-secret` | `CAMUNDA_CLIENT_SECRET` | `""` | OAuth2 client secret |
| `audience` | `CAMUNDA_AUDIENCE` | `zeebe-api` | OAuth2 audience identifier |
| `batch-size` | `CAMUNDA_BATCH_SIZE` | `100` | Process instance page batch size |
| `process-definition-key` | `CAMUNDA_PROCESS_DEFINITION_KEY` | `""` | Optional process definition key or BPMN process ID filter |
| `tenant-id` | `CAMUNDA_TENANT_ID` | `""` | Optional multi-tenant boundary filter |
| `include-variables` | `CAMUNDA_INCLUDE_VARIABLES` | `true` | Whether to fetch process and task variables |
| `include-user-tasks` | `CAMUNDA_INCLUDE_USER_TASKS` | `true` | Whether to query user tasks for security and lifecycle |
| `include-incidents` | `CAMUNDA_INCLUDE_INCIDENTS` | `true` | Whether to ingest active incident indicators |
| `include-acls` | `CAMUNDA_INCLUDE_ACLS` | `true` | Whether to compute Zero-Trust permissions from candidate users/groups |
| `scope` | `CAMUNDA_SCOPE` | `all` | State filter: `all`, `active`, `completed`, or `canceled` |

## Synthesized Document Specification

- **URI Format**: `camunda8://process-instances/{processInstanceKey}`
- **Metadata Attributes**:
  - `camunda8_process_instance_key`: Numeric Zeebe process instance key.
  - `camunda8_bpmn_process_id`: String BPMN identifier (e.g. `order-fulfillment-process`).
  - `camunda8_version`: Process definition version.
  - `camunda8_state`: `ACTIVE`, `COMPLETED`, or `CANCELED`.
  - `camunda8_start_date` / `camunda8_end_date`: ISO-8601 timestamps.
  - `camunda8_tenant_id`: Multi-tenant identifier.
  - `camunda8_incident_count`: Number of active incidents.
  - `camunda8_var_<name>`: Dynamically indexed workflow variables.
  - Collocated references: `camunda_proc_inst_id`, `camunda_process_instance_id`, `camunda_scope_id`, `camunda_identity_users`, `camunda_identity_groups`.
