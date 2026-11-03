# OpenCrawling - Alfresco Content Services Output Connector

This module provides the output connector for **Alfresco Content Services (ACS)**, purpose-built for enterprise ingestion and **Migration Mode** (`OPENCRAWLING_PIPELINE_MODE=migration`).

It streams pristine binary payloads directly from Claim Check storage into target Alfresco instances via the native REST API v1, recreates folder hierarchies (`cm:folder`), synchronizes metadata/properties and Zero-Trust ACL permissions, handles duplicate conflict strategies, and processes OIS lifecycle tombstones (e.g. `MOVE_TO_TRASH` into Alfresco Trashcan).

---

## Configuration Parameters

When configuring the Alfresco Output Connector via the Admin UI, configuration YAML, or environment variables:

| Parameter | Configuration Key / Environment Variable | Default Value | Description |
| :--- | :--- | :--- | :--- |
| **Alfresco URL** | `spring.opencrawling.output.alfresco.url`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_URL` | `http://localhost:8081/alfresco` | Base URL of the target Alfresco instance |
| **Username** | `spring.opencrawling.output.alfresco.username`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_USERNAME` | `admin` | Target Alfresco admin/service user |
| **Password** | `spring.opencrawling.output.alfresco.password`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_PASSWORD` | `admin` | Target Alfresco credentials |
| **Target Parent Node ID** | `spring.opencrawling.output.alfresco.target-parent-node-id`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_TARGET_PARENT_NODE_ID` | `-root-` | Starting parent node UUID or alias (`-root-`, `-my-`, `-shared-`) |
| **Target Path Prefix** | `spring.opencrawling.output.alfresco.target-path-prefix`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_TARGET_PATH_PREFIX` | `MigratedContent/v1` | Root folder path prefix under the parent node |
| **Path Strategy** | `spring.opencrawling.output.alfresco.path-strategy`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_PATH_STRATEGY` | `HIERARCHICAL` | `HIERARCHICAL` (preserves subfolders) or `FLAT` |
| **Conflict Strategy** | `spring.opencrawling.output.alfresco.conflict-strategy`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_CONFLICT_STRATEGY` | `OVERWRITE` | `OVERWRITE` (version bump/replace), `RENAME`, or `SKIP` |
| **Sync Permissions** | `spring.opencrawling.output.alfresco.sync-permissions`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_SYNC_PERMISSIONS` | `true` | Replicate Zero-Trust ACLs and permission entries |
| **Authority Check Mode**| `spring.opencrawling.output.alfresco.authority-check-mode`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_AUTHORITY_CHECK_MODE` | `PRE_CHECK` | `PRE_CHECK` (validates authority existence via REST before applying) or `ON_FAILURE` (logs & falls back on 400/404) |
| **Default Role** | `spring.opencrawling.output.alfresco.default-permission-role`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_DEFAULT_PERMISSION_ROLE` | `Consumer` | Default role when role mapping is unspecified |
| **Tombstone Action** | `spring.opencrawling.output.alfresco.tombstone-action`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_TOMBSTONE_ACTION` | `MOVE_TO_TRASH` | `MOVE_TO_TRASH` (recoverable) or `PERMANENT_DELETE` |
| **Consumer Group** | `spring.opencrawling.output.alfresco.consumer-group`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_CONSUMER_GROUP` | `opencrawling-alfresco-output-group` | Kafka consumer group name |
| **Parallelism** | `spring.opencrawling.output.alfresco.parallelism`<br>`SPRING_OPENCRAWLING_OUTPUT_ALFRESCO_PARALLELISM` | `4` | Concurrency worker threads for writing to Alfresco |

---

## Docker Compose & Search Subsystem (Solr6 vs noindex)

The decoupled stack definition is provided at:
`oc-alfresco-output-connector/docker/docker-compose-decoupled-with-alfresco.yml`.

### Search Engine Disabled by Default (`noindex`)

In **Migration Mode**, OpenCrawling writes documents directly into Alfresco using repository node endpoints (`/nodes/{parentId}/children`, `/nodes/{id}/content`, `/nodes/{id}/permissions`). All folder lookups, content creation, and permission updates are performed directly against Alfresco's relational database (PostgreSQL).

Because no search queries (AFTS/Solr) are executed during migration:
- `ALFRESCO_INDEX_SUBSYSTEM` defaults to `noindex`.
- Alfresco Content Services skips Solr tracking and polling entirely.
- Startup time is reduced significantly (~25–30s vs ~90s).
- Eliminates `solr6:8983` connection retry logs and warnings.
- Greatly lowers CPU and memory consumption during high-throughput bulk migrations.

### Enabling Search Services (Solr6) on Demand

If full-text search indexing and AFTS query capabilities are required on the target Alfresco instance (e.g. for search verification or downstream applications), a fully configured `solr6` container is available under the `search` / `solr` Compose profile.

To spin up Alfresco with Solr 6 enabled:

```bash
# 1. Set the index subsystem to solr6
export ALFRESCO_INDEX_SUBSYSTEM=solr6

# 2. Launch the stack with the search profile
docker compose -f oc-alfresco-output-connector/docker/docker-compose-decoupled-with-alfresco.yml --profile search up -d
```

When activated, Solr 6 exposes port `8083` (Solr web console at `http://localhost:8083/solr`) with shared-secret mutual communication configured with Alfresco ACS.
