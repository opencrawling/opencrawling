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
   - `CONTENT_LINK`: **zero-copy**. The document gets an external content link (UNC path or URL), and the binary stays where it is and is never stored by Doxis. This needs the optional [`oc-doxis-blueline-content-link`](../oc-doxis-blueline-content-link/README.md) module and the SER Doxis client jars, loaded in isolation from `content-link.client-lib-dir`. The source URI is mapped to the link by `content-link.uri-prefix` → `content-link.link-prefix`, or taken from the `doxisContentLink` metadata. Verified on CSB 14.4.1 with a UNC link; a physical delete leaves the external file untouched.
   - `AUTO` (default): use a locator if one resolves (experimental), else upload if the file is within the limit, else a content link if one resolves and the content-link module is configured, else `content.fallback`.
4. **Locator resolution** (`LocatorResolver`, pluggable as a Spring bean):
   - An explicit locator in the metadata (`doxisLocator`) wins.
   - Otherwise a document URI under `locator.uri-prefix` (e.g. a mounted Doxis file data store) maps its remainder to the locator.
5. **Content verification**: after each write the connector reads back the current version's content object (`GET …/versions?initializeRepresentations=true`). It fails the document if there is no content object (CSB silently stores a version as `NO_CONTENT` when a `predefinedLocator` is not applied), or if the length or SHA-256 differs from the source. A newly created document that fails verification is physically deleted again, so no empty documents are left behind.
6. **Upsert by external id**: the document id is stored in `external-id-attribute` (default `ObjectNumber`) and looked up with CQL (`SELECT * FROM <repo> WHERE OBJECTNUMBER = '…'`). Ids longer than the descriptor allows are stored as a deterministic `h:<sha256>` prefix. When the id already exists, `conflict-resolution` decides what happens:
   - `NEW_VERSION` (default) adds a version with the new content and descriptors.
   - `UPDATE_METADATA` patches only the current version's descriptors.
   - `SKIP` leaves the document unchanged.
   - With `content.change-marker-attribute` set (e.g. `SystemId1`), the connector stores `<lastModified>|<length>` of the source in that descriptor and does not create a new version when a re-crawl finds it unchanged.
7. **Descriptor mapping**: the title goes to `title-attribute` (default `ObjectName`). `attribute-mapping` maps any OIS metadata key, or the pseudo-keys `id` / `uri` / `lastModified`, to a Doxis descriptor. Values are converted to the descriptor's type: STRING is truncated to its length, DATE/DATETIME becomes epoch millis, numbers and BOOL are converted, and multi-value descriptors take every value.
8. **OIS security mapping** (`DoxisAclMapper`): OIS permissions become `DocumentAceParams` on creation. On re-crawls they are synced additively: permissions missing on the document are added, and existing entries (including ones set by Doxis administrators) are never removed.
   - `read` grants `VIEW_DOCUMENT_CONTENTS`.
   - `write` grants view plus `UPDATE_DOCUMENT` and `VERSION_DOCUMENT`.
   - `deny` denies `VIEW_DOCUMENT_CONTENTS`.
   - Identities resolve to Doxis users (login name or name) or groups; `public` maps to `everybody`.
   - Document types whose security object type forbids per-document rights (`SECU0050`) keep the type's ACL; the connector logs a warning.
9. **Filing into e-files (records), configurable per use case.**

   `filing.mode` chooses the e-file:
   - `NONE`
   - `FIXED`: always `record-id`
   - `METADATA` (default): the record UUID in `doxisRecordId` metadata
   - `SOURCE_FOLDER`: one e-file per source folder
   - `KEY_METADATA`: one e-file per value of `record-key-metadata-key`, e.g. a customer id

   For the keyed modes, the e-file is found by `record-key-attribute` (CQL on `/records/search`). With `auto-create` it is created in `record-class` when missing. Long keys are hashed deterministically.

   `filing.method` attaches the document:
   - `PRIMARY_PARENT` (default): `PUT …/documents/{uuid}/primaryParent`, for uploads and content links;
   - `RELATIONSHIP`: `relationshipParams` in the REST create, into a folder node of the e-file. This applies to uploads only; content links fall back to `PRIMARY_PARENT`. The node is `doxisFolderNodeId` / `folder-node-id` when given. Otherwise it is the document node named `folder-node-name` (default `Documents`) in each e-file: found in the e-file's node tree, or created as a local `STATIC` node under the root when missing (with `auto-create`). It is resolved once per e-file per run. A node of that name that cannot hold the document class fails the document.

   **Security, also configurable** (`security.mode`):
   - `DOCUMENT`: per-document ACEs (the default; needs instance rights on the document class).
   - `RECORD`: ACEs on the e-file (`VIEW_FOLDER_CONTENTS`, `UPDATE_FOLDER`, `EDIT_FOLDER_DESCRIPTORS`, DENY view). Documents inherit them when the document class has *Primary parent objects → Pass down permissions* enabled. The e-file's permissions come from the first document that creates it. `record-acl-sync: ADDITIVE` lets later documents add missing entries. It applies in every filing mode, both when a new document joins an existing e-file and when a re-crawled document carries new identities (a re-crawl never creates an e-file). It reads an e-file's permissions once per run and then only adds entries it has not seen; it never removes any.
   - `DOCUMENT_AND_RECORD`
   - `NONE`

   `security.grant-connector-user` (default `true`) also grants the connector's own technical user view/update/edit/delete/set-primary-parent on every e-file it creates. Instance ACLs replace the class ACL, so without these grants the connector locks itself out of its own e-files.

   **Doxis permissions the technical user needs** (live findings on CSB 14.4.1):
   - *Document - Change primary parent object* (`SET_PRIMARY_PARENT`) on the document classes, for `PRIMARY_PARENT` filing; otherwise `SECU0015 … setPrimaryParent permission`.
   - *All instances - Read* and *All instances - Write* on the e-file class (the high-volume write right), to remove or delete e-files; otherwise `SECU0015 … high volume write permission`.
   - The DMS repository needs a *content repository for primary parent objects* (Designer, *DMS → Databases*); otherwise filing fails with `INSTANCE0207`.
   - `RELATIONSHIP` filing needs a folder node that may hold documents: the e-file root node is `NODES_ONLY` (`RELATIONSHIP0134`), and omitting the node fails with `PUBLICWS0320`. The connector creates the `folder-node-name` node itself, which needs `CREATE_FOLDER` on the e-file (included in the connector-user grants).

   `security.strict` fails and rolls back a document whose permissions cannot be applied (`SECU0050`, or identities without a Doxis user or group). `security.remove-stale` removes the connector-managed document permissions (view/update/version) that no longer exist at the source; permissions set by administrators are never touched.
10. **OIS deletion tombstones**: `action: "DELETE"` is applied according to `delete-mode`.
   - `LOGICAL` (default) uses `POST …/remove`, which is reversible and leaves the binary in the data store.
   - `PHYSICAL` uses `DELETE …`, which is irrevocable.
11. **Decoupled Kafka writer**: with `opencrawling.consumer.writer.enabled=true`, `DoxisStoreWriterConsumer` consumes `IngestionMessage`s from `opencrawling-documents` in its own consumer group (`opencrawling-doxis-writer`).
    - The messages only carry a URI, never content.
    - Claim-check content is opened lazily, and only when the plan uploads it.
12. **Resilience**: connection failures and HTTP `429` (honouring `Retry-After`) / `502` / `503` / `504` are retried with exponential backoff. This only applies to requests whose body can be rebuilt; a single-use upload stream is sent once. Errors carry the CSB error code (e.g. `SECU0014`, `INSTANCE0014`).

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
| **Change Marker** | `…doxis.content.change-marker-attribute` | — | Descriptor storing `lastModified\|length`; unchanged re-crawls skip the new version |
| **Verify** | `…doxis.content.verify` | `true` | Read back and compare length / SHA-256 |
| **Locator Metadata Key** | `…doxis.locator.metadata-key` | `doxisLocator` | Metadata key carrying an explicit locator |
| **Locator URI Prefix** | `…doxis.locator.uri-prefix` | — | URI prefix of content that lives in the Doxis data store |
| **Locator Prefix** | `…doxis.locator.prefix` | `""` | Text prepended to derived locators |
| **Conflict Resolution** | `…doxis.conflict-resolution` | `NEW_VERSION` | `NEW_VERSION`, `UPDATE_METADATA`, `SKIP` |
| **Delete Mode** | `…doxis.delete-mode` | `LOGICAL` | `LOGICAL` or `PHYSICAL` |
| **Apply Security ACLs** | `…doxis.apply-security-acls` | `true` | Map OIS permissions to Doxis permissions on creation |
| **Max Retries / Timeout** | `…doxis.max-retries` / `…doxis.timeout-seconds` | `3` / `120` | HTTP resilience |
| **Content-Link Client Dir** | `…doxis.content-link.client-lib-dir` | — | Directory with the SER Doxis client jars + `oc-doxis-blueline-content-link`; enables `CONTENT_LINK` |
| **Content-Link URI Prefix / Link Prefix** | `…doxis.content-link.uri-prefix` / `link-prefix` | — | Maps crawled URIs to the external link, e.g. `file:///mnt/archive/` → `\\fileserver\archive\` |
| **Content-Link Type** | `…doxis.content-link.link-type` | `UNC` | `UNC` or `URL` |
| **Content-Link Document Type** | `…doxis.content-link.document-type` | `document-type` | Class for linked documents (must allow linking contents) |
| **Filing Mode** | `…doxis.filing.mode` | `METADATA` | `NONE`, `FIXED`, `METADATA`, `SOURCE_FOLDER`, `KEY_METADATA` |
| **Filing Method** | `…doxis.filing.method` | `PRIMARY_PARENT` | `PRIMARY_PARENT` or `RELATIONSHIP` |
| **E-file Class / Key / Title** | `…doxis.filing.record-class` / `record-key-attribute` / `record-title-attribute` | — / `ObjectNumberExternal` / `ObjectName` | For keyed modes and auto-create |
| **Key Metadata / Auto-create** | `…doxis.filing.record-key-metadata-key` / `auto-create` | — / `true` | `KEY_METADATA` source field; create missing e-files |
| **Security Mode** | `…doxis.security.mode` | `DOCUMENT` | `DOCUMENT`, `RECORD`, `DOCUMENT_AND_RECORD`, `NONE` |
| **Grant Connector User** | `…doxis.security.grant-connector-user` | `true` | Keep management rights for the technical user on e-files it creates |
| **Strict / Remove Stale / E-file ACL Sync** | `…doxis.security.strict` / `remove-stale` / `record-acl-sync` | `false` / `false` / `CREATE_ONLY` | See above |
| **Filing Record** | `…doxis.filing.record-id` | — | `FIXED`: the record (e-file) new documents are filed into |
| **Filing Record Repository** | `…doxis.filing.record-repository` | DMS repository | Repository of the record |
| **Filing Folder Node** | `…doxis.filing.folder-node-id` | — | Optional folder node inside the record |
| **Filing Folder Node Name** | `…doxis.filing.folder-node-name` | `Documents` | `RELATIONSHIP` without a node id: the document node found or created in each e-file |
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
        username: crawler
        password: "${DOXIS_PASSWORD}"
        role: admins
        repository: D_TEXTER
        document-type: TX_MigratedDocument            # or its UUID 271228ee-0f1c-4169-878e-b9d7a1b12525
        external-id-attribute: ObjectNumberExternal   # CQL field OBJECTNUMBER2
        title-attribute: ObjectName
        reference-attribute: URL
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

### 2. Contract Smoke Test Against a Running CSB
```bash
DOXIS_BASE_URL=http://<csb-host>:8080/restws/publicws/rest/api/v1 DOXIS_CUSTOMER=<customer> \
DOXIS_USER=<user> DOXIS_PASSWORD=<password> DOXIS_ROLE=admins DOXIS_REPOSITORY=<repo> \
./scripts/test-doxis-connector.sh
```
The script checks liveness, customer discovery, login, repository and the CQL lookup, then logs out. With `DOXIS_WRITE_TEST=true` (plus `DOXIS_DOCUMENT_TYPE_UUID`, `DOXIS_EXTERNAL_ID_ATTRIBUTE_UUID`, `DOXIS_MIME_TYPE`) it also creates a document, verifies the stored length and physically deletes it. Without `DOXIS_*` variables it skips, so it is safe in `run-integration-tests.sh`.

### 3. End-to-End Pipeline (filesystem → OpenCrawling → Doxis)
Verified 2026-10-02 against `D_TEXTER` / `TX_MigratedDocument` as `crawler`:
1. `docker compose up -d postgres kafka` (the dev profile needs Postgres; Redis/Ollama are optional for a Doxis-only run).
2. Build and start the runtime, e.g. `java --enable-preview -jar oc-runtime/target/oc-runtime-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev --server.port=8097 --grpc.server.port=9097`. Run it from an empty working directory to keep a separate `data/`.
3. `POST /api/connectors` an output connector with class `org.opencrawling.doxis.output.DoxisOutputConnector` and the `doxis*` configuration keys.
4. `POST /api/jobs` with `repositoryConnector: "FileSystem_Local"`, that output connector, the folder `path` and `transformationConnector: ""` (no embedding). Then `POST /api/jobs/{id}/start`.

Result: each file is archived with its title, source URL, `ObjectDate` and hashed external id, and verified. A re-crawl finds the documents again and adds versions, or skips them with a change marker. The job's Doxis session is logged out when the job ends.

### 4. Connection Check
`oc connector check --name <connector-name> --type output` (or **Test Connection** in the admin UI) logs in, reads the session user and checks that the repository is accessible.

## Known Limitations

Findings from live runs against a Doxis CSB 14.4.1 (SER training environment), recorded 2026-10-01:

- **SER guidance (2026-10-02): `predefinedLocator` is not a supported zero-copy mechanism.** SER's documented path for external, never-copied content is the Java/Blueline `IDocument.addContentLinkPartDocument()` (exactly one representation and one content object per document). It has no REST equivalent in 14.4.1, so the `PREDEFINED_LOCATOR` strategy is experimental. For in-place content, a content-link implementation via the Blueline API (licensed jars) or a newer server version is required. SER also notes:
  - Server-side processing can only be disabled per document class (content indexing off, no rendition rules, thumbnails off, no ML model).
  - Hashing is a storage-adapter setting.
  - A physical delete removes stored files.
  - FIPS cannot reference resident files.
- **`predefinedLocator` is a storage-system feature that is off by default.**
  - A create with `predefinedLocator` *and* content fails with `Archive operations with predefined locators are not allowed for repository sb1` (the storage repository). It has to be enabled on the storage system by the Doxis administrator.
  - A create with `predefinedLocator` and *no* content is accepted, but stores the version as `NO_CONTENT`: the locator is ignored. The connector detects this, fails the document and rolls it back.
  - Whether an enabled storage system can register an already-existing binary without upload, and what a physical delete then does to it, is still to be confirmed by SER. Keep `delete-mode: LOGICAL` for in-place content.
- **Document types restrict MIME types** (`allowedMimeTypes`, otherwise `SEDNA0204`). The connector falls back to `application/octet-stream` when the type allows it, and fails before writing otherwise.
- **The repository must allow the document type**; otherwise creation fails with `INSTANCE0014`. This is configured in cubeDesigner; the REST API cannot change it.
- **The client-supplied SHA-256 is not stored** by CSB 14.4.1 (`hashValue` is `null` on read-back), so verification compares the length; the hash is compared only when Doxis reports one. The content-object metadata does not include `storageLocators` either.
- **CQL needs the repository short name**; full names may contain dots, which the parser rejects (`INSTANCE0107`). The connector resolves the short name automatically. Wildcards are `*`, not `%`.
- **E-file filing is verified live** on CSB 14.4.1 (2026-10-02, `SOURCE_FOLDER` + `RECORD`, `PRIMARY_PARENT`): 4 documents (3 uploads and one 5 TB content link) filed into 3 auto-created `TX_SourceFolder` e-files, with no document-level ACEs; each e-file carried the source ACL plus the connector-user grants. A re-crawl reused the documents and e-files. `RELATIONSHIP` filing with an auto-created folder node and the `ADDITIVE` record-ACL sync are covered by unit tests against the 14.4.1 OpenAPI contract but not yet exercised live; effective inheritance for a non-admin user is still to be checked.
- **Decoupled upload mode and claim-check cleanup:** for claim-check content, `IngestionConsumer` may delete the claim-check object (`claimcheck.cleanup-on-consume`) before the writer reads it. Use the direct `send()` path, a locator, or disable cleanup.
- **No local Doxis container:** run `scripts/test-doxis-connector.sh` against a CSB.
