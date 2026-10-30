# OpenCrawling - AWS S3 Output Connector

`oc-s3-output-connector` is an OpenCrawling output connector dedicated **exclusively** to **Migration Mode** (`opencrawling.pipeline.mode=migration`).

## Overview

Unlike vector and search connectors designed for semantic search and RAG indexing, the AWS S3 Output Connector is built specifically for high-throughput, bit-for-bit enterprise content migration and Zero-Trust data liberation into [Amazon Simple Storage Service (Amazon S3)](https://aws.amazon.com/s3/) or S3-compatible object storage (e.g. MinIO, Ceph, Dell ECS).

### Key Capabilities

- **Strict Migration Mode Enforcement**: Rejects non-migration mode executions fail-fast (`IllegalStateException` and HTTP 409 in `JobController`), preventing accidental execution under RAG pipelines and eliminating unnecessary text extraction, chunking, and vector embedding overhead.
- **Bit-for-Bit Binary Parity**: Streams pristine content binaries directly from Claim Check storage into target S3 buckets while computing SHA-256 hashes on the fly.
- **Companion OIS JSON Sidecars**: Produces companion `<key>.ois.json` sidecar documents conforming to the Open Ingestion Standard (`https://opencrawling.org/schemas/v1/ois-document.json`), preserving source repository metadata, content references, digests, and Zero-Trust access control lists (`security.inheritanceEnabled`, `permissions[]`).
- **Flexible Key Strategies**: Supports `HIERARCHICAL` (preserving original folder hierarchy), `FLAT`, and `DATE_PARTITIONED` (`yyyy/MM/dd`) S3 object key layouts.
- **Enterprise Authentication**:
  - `DEFAULT_CHAIN`: standard AWS Default Credentials Provider Chain (IAM roles, IRSA in Kubernetes/EKS, environment variables, EC2 instance profiles).
  - `STATIC`: access key, secret key, and optional session token.
  - `ASSUME_ROLE`: cross-account STS role assumption with optional external ID and custom session names.
- **Storage Tiering & Cost Optimization**: Configurable S3 Storage Classes (`STANDARD`, `INTELLIGENT_TIERING`, `GLACIER_IR`, `GLACIER`, `DEEP_ARCHIVE`).
- **Enterprise Security & Compliance**:
  - Server-Side Encryption: `SSE_S3` (AES-256) and `SSE_KMS` with custom KMS Key IDs and S3 Bucket Keys.
  - S3 Object Lock (WORM): Compliance and Governance modes, retention period, and legal holds.
- **Custom HTTP Request Headers & Multi-Vendor Presets**: Decorates S3 operations (`PutObjectRequest`, `CreateMultipartUploadRequest`, `DeleteObjectRequest`, `GetObjectRequest`) with proprietary vendor HTTP headers (e.g. Dell EMC ECS metadata search `x-emc-metadata-search` and namespace `x-emc-namespace`, IBM Cloud Object Storage Key Protect encryption `ibm-sse-kp-*`, Hitachi HCP WORM `x-hcp-*`, NetApp StorageGRID compliance `x-ntap-sg-*`, Cloudflare R2 `cf-placement`, and standard `x-amz-meta-*`).
- **Dynamic Template Interpolation Engine**: Supports placeholders in header templates, resolving dynamic tokens like `${metadata.<key>}`, `${metadata.<key> | fallback}`, `${document.id}`, `${document.uri}`, and `${document.action}`.
- **High Performance & Large Binary Multipart Upload**: Uploads large objects exceeding `multipart-threshold` using parallel multipart uploads (`S3MultipartUploader`).
- **Full OIS Lifecycle Management**: Supports `UPSERT` and `DELETE` tombstone actions (`DELETE_OBJECTS` or `ARCHIVE_TOMBSTONE`).
- **Lakehouse Document Ledger Accumulation**: Enriches document metadata (`checksum_sha256`, `target_bucket`, `target_key`, `binary_size`) enabling automatic Parquet audit trails in the Lakehouse ledger.

---

## Configuration

Add the following properties to `application.yml`:

```yaml
spring:
  opencrawling:
    output:
      type: s3
      s3:
        bucket: enterprise-migration-lake
        region: us-east-1
        endpoint-override: "" # Set for S3-compatible storage (e.g., http://minio:9000)
        path-style-access: false # Set true for MinIO/LocalStack
        credentials-strategy: DEFAULT_CHAIN # Options: DEFAULT_CHAIN, STATIC, ASSUME_ROLE
        # For STATIC:
        access-key: ""
        secret-key: ""
        session-token: ""
        # For ASSUME_ROLE:
        assume-role-arn: ""
        assume-role-external-id: ""
        assume-role-session-name: "OpenCrawlingMigrationSession"
        # Layout & Storage:
        key-strategy: HIERARCHICAL # Options: HIERARCHICAL, FLAT, DATE_PARTITIONED
        key-prefix: "migration/v1/"
        sidecar-suffix: ".ois.json"
        storage-class: "INTELLIGENT_TIERING"
        # Encryption & Compliance:
        server-side-encryption: "SSE_S3" # Options: NONE, SSE_S3, SSE_KMS
        kms-key-id: ""
        bucket-key-enabled: true
        object-lock-enabled: false
        object-lock-mode: "GOVERNANCE" # Options: GOVERNANCE, COMPLIANCE
        object-lock-retention-days: 365
        object-lock-legal-hold: false
        # Lifecycle:
        tombstone-action: "DELETE_OBJECTS" # Options: DELETE_OBJECTS, ARCHIVE_TOMBSTONE
        auto-create-bucket: false
        # Custom HTTP Headers / Vendor Extensions:
        custom-headers:
          x-emc-namespace: "finance-tenant"
          x-emc-metadata-search: "Department=${metadata.department},Category=${metadata.category}"
          x-migration-source: "IBM-FileNet-P8"
        # Performance & Decoupled Consumer:
        multipart-threshold: "64MB"
        multipart-part-size: "16MB"
        multipart-concurrency: 4
        consumer-group: "opencrawling-s3-migration-group"
        consumer-concurrency: 3

opencrawling:
  pipeline:
    mode: migration # Mandatory for AWS S3 output connector
```

### Configuration Reference

| Property | Default | Description |
|---|---|---|
| `spring.opencrawling.output.s3.bucket` | `enterprise-migration-lake` | Target S3 bucket name. |
| `spring.opencrawling.output.s3.region` | `us-east-1` | AWS region. |
| `spring.opencrawling.output.s3.endpoint-override` | `""` | Custom S3 endpoint URL (for MinIO, LocalStack, Ceph). |
| `spring.opencrawling.output.s3.path-style-access` | `false` | Enable path-style access (`true` required for MinIO). |
| `spring.opencrawling.output.s3.credentials-strategy` | `DEFAULT_CHAIN` | `DEFAULT_CHAIN`, `STATIC`, or `ASSUME_ROLE`. |
| `spring.opencrawling.output.s3.access-key` | `""` | AWS Access Key ID (used when strategy is `STATIC` or base for `ASSUME_ROLE`). |
| `spring.opencrawling.output.s3.secret-key` | `""` | AWS Secret Access Key. |
| `spring.opencrawling.output.s3.session-token` | `""` | Optional session token for temporary static credentials. |
| `spring.opencrawling.output.s3.assume-role-arn` | `""` | Target IAM Role ARN to assume via AWS STS. |
| `spring.opencrawling.output.s3.assume-role-external-id` | `""` | Optional STS External ID for cross-account security. |
| `spring.opencrawling.output.s3.key-strategy` | `HIERARCHICAL` | Key strategy: `HIERARCHICAL`, `FLAT`, `DATE_PARTITIONED`. |
| `spring.opencrawling.output.s3.key-prefix` | `migration/v1/` | Base prefix prepended to all migrated S3 keys. |
| `spring.opencrawling.output.s3.storage-class` | `INTELLIGENT_TIERING` | S3 Storage Class applied to binary objects. |
| `spring.opencrawling.output.s3.server-side-encryption` | `SSE_S3` | Encryption mode: `NONE`, `SSE_S3` (AES-256), `SSE_KMS`. |
| `spring.opencrawling.output.s3.kms-key-id` | `""` | AWS KMS Key ARN or ID when using `SSE_KMS`. |
| `spring.opencrawling.output.s3.object-lock-enabled` | `false` | Enable S3 Object Lock on migrated objects. |
| `spring.opencrawling.output.s3.tombstone-action` | `DELETE_OBJECTS` | `DELETE_OBJECTS` (purge binary, sidecar, index) or `ARCHIVE_TOMBSTONE`. |
| `spring.opencrawling.output.s3.custom-headers` | `{}` | Map of custom HTTP request header names to static values or dynamic interpolation templates. |
| `spring.opencrawling.output.s3.multipart-threshold` | `64MB` | Size threshold at which binaries switch to multipart upload. |
| `spring.opencrawling.output.s3.multipart-part-size` | `16MB` | Part size for multipart uploads (minimum 5MB). |
| `spring.opencrawling.output.s3.multipart-concurrency` | `4` | Parallel threads per multipart upload. |
| `spring.opencrawling.output.s3.consumer-group` | `opencrawling-s3-migration-group` | Kafka consumer group for decoupled S3 migration writer. |
| `spring.opencrawling.output.s3.consumer-concurrency` | `3` | Parallel writer threads for Kafka consumer. |

---

## Object Layout

For each migrated document (`UPSERT`), the connector stores:

| S3 Object Key | Content |
|---|---|
| `<key-prefix><path-or-filename>` | Pristine binary content transferred bit-for-bit. |
| `<key-prefix><path-or-filename>.ois.json` | Companion OIS JSON sidecar containing source lineage, digest, and Zero-Trust permissions. |
| `.opencrawling/index/<sha256(docId)>` | Index entry mapping document ID to S3 key for O(1) tombstone lookups. |

When `tombstone-action` is `ARCHIVE_TOMBSTONE`, a deleted document writes:
- `.tombstones/<key>.ois.json`: Tombstone metadata envelope recording document deletion event and timestamp.

---

## Decoupled Architecture

In decoupled production topologies:
1. **`oc-crawler`**: Scans the source repository, streams content into the Claim Check store (S3 or MinIO), and publishes an `IngestionMessage` to Kafka topic `opencrawling-documents` with `pipelineMode=migration`.
2. **`oc-writer-consumer` (`S3MigrationWriterConsumer`)**: Subscribes to `opencrawling-documents` under consumer group `opencrawling-s3-migration-group`. When a message arrives in `migration` mode, it retrieves the binary from Claim Check and writes both binary and `.ois.json` sidecar to S3.
3. **`oc-ingestion-consumer` & `oc-embedding-service`**: Recognize `pipelineMode=migration` and automatically bypass chunking, narrativization, and vector database persistence.

---

## Admin UI Integration & Multi-Vendor Storage Presets

The OpenCrawling Admin UI (`oc-admin-ui`) includes first-class support for `AWS S3 (Migration Mode only)`:
- Automatic validation preventing S3 output configuration when RAG mode is selected.
- Comprehensive UI form fields for S3 bucket, region, endpoint overrides, credentials strategies, KMS, Object Lock, and key strategies.
- Visual badge indicator highlighting migration mode connectors.
- **Custom HTTP Request Headers Editor**: Dynamic key-value table enabling operators to declare custom HTTP headers injected into S3 calls.
- **Multi-Vendor Quick Presets Dropdown**:

| Storage Vendor / Target | Preset Name | Injected Headers | Description & Purpose |
|---|---|---|---|
| **Dell EMC ECS** | *Metadata Search & Multi-Tenancy* | `x-emc-metadata-search`<br>`x-emc-namespace`<br>`x-emc-retention-period` | Injects real-time search indexing properties, tenant namespace routing, and object retention. |
| **Dell EMC ECS** | *Tenant Namespace only* | `x-emc-namespace` | Routes writes and reads to an isolated tenant namespace without bucket redesign. |
| **IBM Cloud Object Storage** | *Key Protect Customer Root Key* | `ibm-sse-kp-customer-root-key-crn`<br>`ibm-sse-kp-encryption-algorithm` | Enforces envelope encryption using IBM Key Protect customer root keys (CRN). |
| **Hitachi Content Platform (HCP)** | *HCP Retention & Shredding* | `x-hcp-retention`<br>`x-hcp-shred` | Enforces regulatory WORM hold period and cryptographic erasure shredding on disposal. |
| **NetApp StorageGRID** | *Compliance Retention* | `x-ntap-sg-compliance-retention` | Sets NetApp StorageGRID regulatory compliance retention timestamp. |
| **Cloudflare R2** | *Jurisdiction Placement* | `cf-placement` | Enforces regional data sovereignty and localization hints (e.g. `eu`, `eeur`). |
| **Standard S3 / MinIO** | *Custom User Metadata* | `x-amz-meta-source-id`<br>`x-amz-meta-department` | Attaches user-defined metadata headers using dynamic document and metadata variables. |

### Header Template Placeholder Syntax

The connector resolves dynamic placeholders before request execution:
- `${metadata.<name>}`: Injects value from `document.metadata()`. Multiple values are joined with commas.
- `${metadata.<name> | <fallback>}`: Injects value, falling back to `<fallback>` if missing or empty.
- `${document.id}`: Unique identifier of the repository document.
- `${document.uri}`: Source URI of the repository document.
- `${document.action}`: Document lifecycle action (`UPSERT` or `DELETE`).
- `${job.id}`: Identifier of the crawling job.

---

## Testing

Run unit tests:
```bash
mvn test -pl oc-s3-output-connector
```

Run end-to-end decoupled integration tests with MinIO:
```bash
./scripts/test-s3-migration-decoupled.sh
```

---

See also the wiki page: [AWS S3 Output Connector (Migration Mode)](https://github.com/opencrawling/opencrawling/wiki/AWS-S3-Output-Connector).
