# OpenCrawling - Flowable Repository Connector

This module provides the repository connector for **Flowable BPMN Process Engine**. It connects to the Flowable REST API, crawls historic and active process instances, downloads process metadata, BPMN variables, and historic identity links, constructing standard `RepositoryDocument` instances for OpenCrawling's RAG/vectorization ingestion pipeline.

## Feature Overview

1. **Historic Process Ingestion**: Crawls `/history/historic-process-instances` with support for filtering by process definition key and status scope (`all`, `completed`, `active`).
2. **Dynamic BPMN Variables Extraction**: Fetches `/history/historic-variable-instances` per process instance and attaches variables to metadata (mapped as `flowable_var_<varName>`) and document JSON body.
3. **Identity Links & Zero-Trust Security Mapping**: Automatically extracts identity links via `/history/historic-process-instances/{id}/identitylinks` and maps start users and candidate groups/users to Open Ingestion Standard (OIS) `SecurityConfig` and `PermissionRule` entries.
4. **Collocated & Distributed Architecture Support**:
   - **Collocated DB Pattern (pgvector on Flowable PostgreSQL)**: Stamps entity references (`flowable_proc_inst_id`, `flowable_scope_id`, `flowable_scope_type`) so similarity searches can perform query-time SQL `EXISTS` subqueries against Flowable's native `ACT_RU_IDENTITYLINK` and `ACT_HI_IDENTITYLINK` tables without distributed ACL synchronization lag.
   - **Distributed DB Pattern (External Vector DBs like Qdrant, Milvus, OpenSearch, Vespa)**: Generates standard OIS `security_allowed_read` and `security_denied_read` metadata for zero-trust query pre-filtering.
5. **Structured Concurrency & Virtual Threads**: Leverages Java 25 Virtual Threads (`Thread.ofVirtual()`) and `StructuredTaskScope` for concurrent processing across process instance batches.

## Configuration Parameters

| Parameter | Spring Property Key | Default Value | Description |
| :--- | :--- | :--- | :--- |
| **URL** | `spring.opencrawling.connector.flowable.url` | `http://localhost:8080/flowable-rest/service` | Base URL of Flowable REST service |
| **Username** | `spring.opencrawling.connector.flowable.username` | `admin` | Basic auth username |
| **Password** | `spring.opencrawling.connector.flowable.password` | `test` | Basic auth password |
| **Batch Size** | `spring.opencrawling.connector.flowable.batch-size` | `100` | Process instances page size |
| **Process Definition Key** | `spring.opencrawling.connector.flowable.process-definition-key` | `""` | Optional filter by process definition key |
| **Include Variables** | `spring.opencrawling.connector.flowable.include-variables` | `true` | Include historical BPMN variables |
| **Include ACLs** | `spring.opencrawling.connector.flowable.include-acls` | `true` | Extract identity links and map to SecurityConfig |
| **Scope** | `spring.opencrawling.connector.flowable.scope` | `all` | Scope of instances (`all`, `completed`, `active`) |

## Metadata Mapping Example

```json
{
  "id": "proc-1234",
  "uri": "flowable://process-instances/proc-1234",
  "metadata": {
    "mimeType": ["application/json"],
    "processDefinitionId": ["invoice-process:2:98765"],
    "processDefinitionKey": ["invoice-process"],
    "businessKey": ["INV-2026-001"],
    "startUserId": ["finance_agent_1"],
    "startTime": ["2026-07-23T08:00:00Z"],
    "endTime": ["2026-07-23T08:15:00Z"],
    "durationInMillis": ["900000"],
    "flowable_proc_inst_id": ["proc-1234"],
    "flowable_process_instance_id": ["proc-1234"],
    "flowable_scope_id": ["proc-1234"],
    "flowable_scope_type": ["processInstance"],
    "flowable_identity_users": ["finance_agent_1"],
    "flowable_identity_groups": ["finance_managers"],
    "flowable_var_totalAmount": ["250.50"],
    "flowable_var_customerName": ["ACME Corp"]
  },
  "security": {
    "inheritanceEnabled": false,
    "permissions": [
      {"identity": "finance_agent_1", "identityType": "user", "access": "read"},
      {"identity": "finance_managers", "identityType": "group", "access": "read"}
    ]
  }
}
```

## Zero-Trust Query Integration Patterns

### 1. Collocated pgvector Query Pushdown (Flowable Entity Link)
When Flowable and pgvector share the same PostgreSQL database, query-time filtering avoids synchronization lag by correlating with Flowable's native identity link tables:

```sql
SELECT *, embedding <=> :queryEmbedding AS distance 
FROM vector_store 
WHERE (
    EXISTS (
        SELECT 1 FROM ACT_RU_IDENTITYLINK I 
        WHERE I.PROC_INST_ID_ = (metadata->>'flowable_proc_inst_id')
          AND (I.USER_ID_ = :currentUserId OR I.GROUP_ID_ IN (:currentUserGroups))
    )
    OR EXISTS (
        SELECT 1 FROM ACT_HI_IDENTITYLINK HI 
        WHERE HI.PROC_INST_ID_ = (metadata->>'flowable_proc_inst_id')
          AND (HI.USER_ID_ = :currentUserId OR HI.GROUP_ID_ IN (:currentUserGroups))
    )
)
ORDER BY distance 
LIMIT :topK;
```

### 2. Distributed Vector Store Metadata Filtering
For external vector engines (Qdrant, Milvus, OpenSearch, Vespa), vector payloads automatically receive `security_allowed_read`:
```json
{
  "security_allowed_read": ["finance_agent_1", "finance_managers"],
  "security_denied_read": [],
  "security_inheritance": false
}
```
Query pre-filters match the user identity and groups against `security_allowed_read`.
