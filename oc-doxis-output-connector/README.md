# OpenCrawling - Doxis Output Connector

This module provides the `OutputConnector` implementation for **[Doxis 4](https://www.doxis.com/en/)** (SER Group's ECM / Intelligent Content Automation platform). Crawled documents are archived into a Doxis DMS repository through the **Doxis CSB REST API** (`/restws/publicws/rest/api/v1`, verified against CSB 14.4.1). That covers the document type, descriptors, ACLs, versions and deletes.

The connector keeps the **metadata plane** and the **content plane** separate. Metadata always goes through the REST API. The binary is handled by a per-document *content strategy*, so multi-terabyte files never have to be streamed. They can be registered **in place** by handing Doxis a `predefinedLocator` instead of the bytes.

## Feature Overview

1. **Session handling**:
   - `POST /login` with customer, user, password and an optional role (e.g. `admins`, needed when the user's default role lacks `createDocument`).
   - The JWT is sent as `Authorization: Bearer`.
   - A transparent re-login happens on HTTP 401.
   - `POST /logout` runs on shutdown, because the license counts technical sessions.
2. **Schema resolution** (`DoxisSchema`): the document type, descriptor definitions (UUID, data type, length, multi-value), Doxis MIME types and organisational elements (users/groups) are resolved by name and cached.
3. **Content strategies** (`ContentPlanner`), decided per document from file metadata only. Content is never read just to plan.
   - `UPLOAD`: the binary is streamed as the multipart `inputStream` part, without buffering, up to `content.upload-max-bytes`.
   - `PREDEFINED_LOCATOR`: the document is created with `predefinedLocator`, `contentLength` and the optional SHA-256, and **no bytes are sent**. This is for binaries that already sit in (or were staged into) the Doxis data store.
   - `REFERENCE_ONLY`: the document is created without content, and the source URI is recorded in `reference-attribute`.
   - `AUTO` (default): use a locator if one resolves, else upload if the file is within the limit, else `content.fallback`.
4. **Locator resolution** (`LocatorResolver`, pluggable as a Spring bean):
   - An explicit locator in the metadata (`doxisLocator`) wins.
   - Otherwise a document URI under `locator.uri-prefix` (e.g. a mounted Doxis file data store) maps its remainder to the locator.
5. **Content verification**: after each write the connector reads back the current version's content object (`GET …/versions?initializeRepresentations=true`). It fails the document if the length or SHA-256 differs from the source, which is how in-place registrations are confirmed without touching the binary.
6. **Upsert by external id**: the document id is stored in `external-id-attribute` (default `ObjectNumber`) and looked up with CQL (`SELECT * FROM <repo> WHERE OBJECTNUMBER = '…'`). Ids longer than the descriptor allows are stored as a deterministic `h:<sha256>` prefix. When the id already exists, `conflict-resolution` decides what happens:
   - `NEW_VERSION` (default) adds a version with the new content and descriptors.
   - `UPDATE_METADATA` patches only the current version's descriptors.
   - `SKIP` leaves the document unchanged.
7. **Descriptor mapping**: the title goes to `title-attribute` (default `ObjectName`). `attribute-mapping` maps any OIS metadata key, or the pseudo-keys `id` / `uri` / `lastModified`, to a Doxis descriptor. Values are converted to the descriptor's type: STRING is truncated to its length, DATE/DATETIME becomes epoch millis, numbers and BOOL are converted, and multi-value descriptors take every value.
8. **OIS security mapping** (`DoxisAclMapper`): OIS permissions become `DocumentAceParams` on creation.
   - `read` grants `VIEW_DOCUMENT_CONTENTS`.
   - `write` grants view plus `UPDATE_DOCUMENT` and `VERSION_DOCUMENT`.
   - `deny` denies `VIEW_DOCUMENT_CONTENTS`.
   - Identities resolve to Doxis users (login name or name) or groups; `public` maps to `everybody`.
9. **OIS deletion tombstones**: `action: "DELETE"` is applied according to `delete-mode`.
   - `LOGICAL` (default) uses `POST …/remove`, which is reversible and leaves the binary in the data store.
   - `PHYSICAL` uses `DELETE …`, which is irrevocable.
10. **Decoupled Kafka writer**: with `opencrawling.consumer.writer.enabled=true`, `DoxisStoreWriterConsumer` consumes `IngestionMessage`s from `opencrawling-documents` in its own consumer group (`opencrawling-doxis-writer`).
    - The messages only carry a URI, never content.
    - Claim-check content is opened lazily, and only when the plan uploads it.
11. **Resilience**: connection failures and HTTP `429` (honouring `Retry-After`) / `502` / `503` / `504` are retried with exponential backoff. This only applies to requests whose body can be rebuilt; a single-use upload stream is sent once. Errors carry the CSB error code (e.g. `SECU0014`, `INSTANCE0014`).

## Configuration Parameters

All properties are bound via `DoxisOutputProperties` under the `spring.opencrawling.output.doxis` prefix.

| Parameter | Spring Property Key | Default Value | Description |
| :--- | :--- | :--- | :--- |
| **Base URL** | `…doxis.base-url` | `http://localhost:8080/restws/publicws/rest/api/v1` | CSB REST API base URL |
| **Customer** | `…doxis.customer-name` | — | Doxis customer (tenant) short name |
| **Username / Password** | `…doxis.username` / `…doxis.password` | — | Archiving user |
| **Role** | `…doxis.role` | — | Role to log in with (e.g. `admins`) |
| **Repository** | `…doxis.repository` | — | Target DMS repository (name or UUID) |
| **Document Type** | `…doxis.document-type` | `BaseDocument` | Document type (name or UUID); must be allowed in the repository |
| **External ID Descriptor** | `…doxis.external-id-attribute` | `ObjectNumber` | Descriptor used for upsert/delete lookup |
| **Title Descriptor** | `…doxis.title-attribute` | `ObjectName` | Descriptor receiving the title |
| **Source URI Descriptor** | `…doxis.reference-attribute` | — | Optional descriptor receiving the source URI |
| **Descriptor Mapping** | `…doxis.attribute-mapping.<metadataKey>` | — | `<OIS metadata key>: <Doxis descriptor>` |
| **Content Strategy** | `…doxis.content.strategy` | `AUTO` | `AUTO`, `UPLOAD`, `PREDEFINED_LOCATOR`, `REFERENCE_ONLY` |
| **Upload Limit** | `…doxis.content.upload-max-bytes` | `2147483648` | Largest binary streamed to Doxis |
| **Fallback** | `…doxis.content.fallback` | `REFERENCE_ONLY` | `AUTO` strategy for files above the limit without a locator |
| **Verify** | `…doxis.content.verify` | `true` | Read back and compare length / SHA-256 |
| **Locator Metadata Key** | `…doxis.locator.metadata-key` | `doxisLocator` | Metadata key carrying an explicit locator |
| **Locator URI Prefix** | `…doxis.locator.uri-prefix` | — | URI prefix of content that lives in the Doxis data store |
| **Locator Prefix** | `…doxis.locator.prefix` | `""` | Text prepended to derived locators |
| **Conflict Resolution** | `…doxis.conflict-resolution` | `NEW_VERSION` | `NEW_VERSION`, `UPDATE_METADATA`, `SKIP` |
| **Delete Mode** | `…doxis.delete-mode` | `LOGICAL` | `LOGICAL` or `PHYSICAL` |
| **Apply Security ACLs** | `…doxis.apply-security-acls` | `true` | Map OIS permissions to Doxis permissions on creation |
| **Max Retries / Timeout** | `…doxis.max-retries` / `…doxis.timeout-seconds` | `3` / `120` | HTTP resilience |
| **Writer Consumer Group** | `…doxis.consumer-group` | `opencrawling-doxis-writer` | Kafka group of the decoupled writer |

To select this connector, set `spring.opencrawling.output.type=doxis`.

The admin UI stores the same settings as `doxis*` keys in the connector's JSON configuration (e.g. `doxisCustomerName`, `doxisContentStrategy`, `doxisLocatorUriPrefix`, and `doxisAttributeMapping` as `key=Descriptor` pairs). `JobController` resolves them per job.

```yaml
spring:
  opencrawling:
    output:
      type: doxis
      doxis:
        base-url: "http://csb.example.com:8080/restws/publicws/rest/api/v1"
        customer-name: faststarter
        username: Supervisor
        password: "${DOXIS_PASSWORD}"
        role: admins
        repository: D_TEXTER
        document-type: BaseDocument
        attribute-mapping:
          author: ObjectAuthors
          lastModified: ObjectDate
        content:
          strategy: AUTO
          upload-max-bytes: 2147483648
        locator:
          uri-prefix: "file:///mnt/doxis-store/"
opencrawling:
  ingestion:
    max-extract-bytes: 536870912   # larger binaries skip Tika/embedding; metadata still reaches Doxis
```

## Request Mapping

A document registered in place: `POST /dmsRepositories/D_TEXTER/documents` with a single `documentParams` multipart part and **no** `inputStream` part.

```json
{
  "mimeTypeName": "application/mxf",
  "fullFileName": "master.mxf",
  "fileExtension": "mxf",
  "contentLength": 5497558138880,
  "hashAlgorithm": "SHA-256",
  "hashValue": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "predefinedLocator": "2026/10/master.mxf",
  "documentTypeUUID": "b89f3e49-ff11-467f-aba0-03740736646f",
  "attributes": [
    { "attributeDefinitionUUID": "90c6196e-0829-4322-836e-93898b4e39be", "attributeDataType": "STRING", "values": ["/mnt/doxis-store/2026/10/master.mxf"] },
    { "attributeDefinitionUUID": "55a1b1ce-1139-4f08-b9bc-89478d46079b", "attributeDataType": "STRING", "values": ["Film master"] }
  ]
}
```

For `UPLOAD` the same `documentParams` (without `predefinedLocator`) is followed by an `inputStream` part carrying the binary. A re-crawl of an existing external id sends `documentVersionParams` to `POST …/documents/{uuid}/versions`.

## Testing & Execution

### 1. Run Unit Tests
```bash
mvn test -pl oc-doxis-output-connector
```
Test classes and what they cover:
- **`DoxisClientTest`** runs the REST client against OkHttp `MockWebServer`. It uses fixtures in `src/test/resources/doxis-mock-responses/` modelled on CSB 14.4.1 responses, and covers login with role, bearer auth, re-login on 401, CQL search and search closing, streaming multipart with and without content, retries, error codes, logical and physical delete, and logout.
- **`ContentPlannerTest`** covers strategy selection, locator resolution, size limits, and that planning never reads content.
- **`DoxisDocumentMapperTest`** and **`DoxisAclMapperTest`** cover descriptor typing, external id hashing and permission mapping.
- **`DoxisOutputConnectorTest`** covers create, in-place registration, verification, versions, `UPDATE_METADATA`, `SKIP` and tombstones.
- **`DoxisStoreWriterConsumerTest`** and **`DoxisConnectorSettingsTest`** cover the Kafka writer and the admin UI settings.

### 2. Connection Check
`oc connector check --name <connector-name> --type output` (or **Test Connection** in the admin UI) logs in, reads the session user and checks that the repository is accessible.

## Known Limitations

- **`predefinedLocator` behaviour:** the field is part of the CSB 14.4.1 REST contract, but its semantics are not documented by SER yet. Open questions (raised with SER engineering):
  - the locator format and which data store it resolves against
  - whether existence or the hash is checked at create time
  - whether a physical delete removes the in-place binary

  Until confirmed, keep `delete-mode: LOGICAL` for in-place content.
- **Repository must allow the document type:** the target repository must allow the configured document type (configured in cubeDesigner). Otherwise creation fails with `INSTANCE0014`.
- **ACLs on existing documents:** ACLs are applied when a document is created. Permission changes on documents that are already archived are not synchronised.
- **`storageLocators` not exposed:** the content-object metadata in CSB 14.4.1 does not include `storageLocators`, so verification compares length and SHA-256 rather than the locator itself.
- **Decoupled upload mode and claim-check cleanup:** for claim-check content, `IngestionConsumer` may delete the claim-check object (`claimcheck.cleanup-on-consume`) before the writer reads it. Use the direct `send()` path, a locator, or disable cleanup.
- **No local Doxis container:** there is none and no `scripts/test-doxis-*.sh`. Live verification runs against a Doxis CSB (e.g. the SER-hosted training environment).
