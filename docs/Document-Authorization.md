# Document Authorization (ACLs and OpenFGA)

Every document OpenCrawling retrieves for a caller (through the MCP tools, and so for any LLM or agent using them) is checked against that document's permissions **on the server**, before anything is returned. This page explains how those decisions are made, the two ways of making them (the ACLs stored with each document, or a relationship store, [OpenFGA](https://openfga.dev)), how to roll out OpenFGA safely, and how to operate it.

---

## How a retrieval is authorized

```
 caller (principal + roles)
        │
        ▼
 MCP tool ──► store backend ──► candidates (ranked chunks)
                                     │
                                     ▼
                           DocumentAccessGate  ──►  AuthorizationProvider
                                     │                 (metadata | shadow | intersection | openfga)
                                     ▼
                         only readable candidates are returned
```

- All nine store backends (pgvector, Qdrant, Milvus, OpenSearch 2/3, Solr, Vespa, Luxir, OpenArch) hand their candidates to one component, the `DocumentAccessGate`. No backend makes its own access decision.
- The gate asks the configured `AuthorizationProvider` about the whole page of candidates at once (one round trip for a remote engine).
- **Fail closed.** A document with no permission data is denied. If the provider cannot decide (engine down, timeout, malformed answer), the whole request fails with an error. It never falls back to weaker rules.

### Who the caller is

The caller's principal and roles come from one of two places, set by `opencrawling.security.identity.mode`:

- **`none` (default):** the MCP tool arguments `userPrincipal` and `userRoles`. The client asserts them and the server cannot check them, so the MCP endpoint must only be reachable by trusted clients.
- **`jwt`:** the request's bearer token, validated by the runtime as an OAuth2 resource server. The tool arguments are ignored, so a client cannot claim to be someone else. A request without a valid token gets `401` and reaches no store.

The principal and the groups come from separate token claims and are never mixed, which keeps the `user:` / `group:` rule below intact. Setup and claim mapping: [Model Context Protocol, Caller identity](Model-Context-Protocol#-caller-identity).

### Beyond search

The tools that let agents work before or around the index follow the same rules:
- **OIS sidecars** (`readOisSidecars`) go through the same gate as search, on each sidecar's document id and permissions.
- **Discovery** (`exploreRepositoryTree`, `queryRepositoryDryRun`), and the checks a dynamic job or single-document ingest makes before it starts, apply the metadata rules to the permissions the *source repository* reports now. They do this whatever the mode, because OpenFGA only knows indexed documents. So group memberships held only in OpenFGA are not used there, and a caller may see less than it may search, never more.

`DiscoveryVersusSearchTest` checks that the two paths agree for the same caller across a mixed corpus. See [Enterprise Multi-Agent Integration](Enterprise-Multi-Agent-Integration).

## Permissions: what gets indexed

Connectors describe a document's permissions as a `SecurityConfig` (a list of rules: `identity`, `identityType`, `access`), plus, for older connectors, a legacy comma-separated `acl` string. One normalizer, `EffectiveAcl`, turns them into what every store indexes (`security_allowed_read`, `security_denied_read`, `security_inheritance`) and into what OpenFGA receives:

| Source permission | Effect |
|---|---|
| `access` = `read`, `write` or `admin` | grants read |
| `access` = `deny` | denies read; a deny always wins over any grant |
| `identityType` = `public` (for example CMIS `anonymous`, `GROUP_EVERYONE`) | indexed as `public`: everyone may read |
| rules present | they decide; the legacy `acl` string is ignored (it often holds values that are not identities) |
| only the default placeholder `public` rule, plus an `acl` naming identities | the `acl` decides: a connector that could not read the source permissions must not make the document public |
| no rules, only an `acl` | the `acl` tokens grant read (`read` itself is a keyword and is dropped) |
| neither, including a message that carries no `security` object at all | nobody may read the document. The pipeline never reads missing permissions as public: search, OpenFGA and the Ozone/S3 sidecars all record no grants |

**Caller matching.** Names are compared case-insensitively, without a leading `user:` on the caller's principal or `group:` on its roles. The permission identity's own prefix decides what it can match:

| Identity | Matches |
|---|---|
| `user:alice` | a caller whose principal is `alice` (or `user:alice`), never a role |
| `group:finance` | a caller holding the role `finance` (or `group:finance`), never a principal |
| `finance` (no prefix) | a principal or a role named `finance` |
| `public` | everyone |

A user is therefore never granted, or denied, as a group of the same name (#224). Connectors that write identities without a prefix keep the behaviour they had. A blank identity matches no caller.

**pgvector read keys.** pgvector pre-filters candidates in SQL by exact match, which cannot express the rule above. Its writers therefore also store `security_read_keys`: `user:Alice` as `u:alice`, `group:Finance` as `g:finance`, a bare `Finance` as both `u:finance` and `g:finance`, and `public` as `public`. The search asks for `public`, `u:<principal>` and `g:<role>` for each role, with the same case folding and prefix handling, so the pre-filter keeps exactly the documents the rule grants. Chunks written before these keys existed are still matched on their raw identities, including the caller's `user:`/`group:` forms; a grant that differs from the caller only in case (`Finance` against the role `finance`) is found after the document is re-indexed.

> [!NOTE]
> Today the caller's principal and roles are **asserted by the MCP client** in the tool arguments. They are not verified by the server. Verified identities (OAuth2/OIDC) are the subject of issue #187; the gate's `Caller` already carries an `authenticated` flag for it.

## Authorization modes

Set with `opencrawling.security.authorization.mode` (`OPENCRAWLING_SECURITY_AUTHORIZATION_MODE`):

| Mode | Who decides | Use it for |
|---|---|---|
| `metadata` (default) | The permissions stored with each chunk in the vector store | Every deployment that does not run OpenFGA. Nothing OpenFGA-related is started. |
| `shadow` | Stored permissions decide. OpenFGA is asked the same question, and every disagreement is logged and counted. | Proving OpenFGA gives the same answers before trusting it. |
| `intersection` | Both must allow | A cautious cut-over: OpenFGA can only remove access, never add it. |
| `openfga` | OpenFGA alone | The target state: permission and membership changes apply immediately, without re-indexing. |

`shadow`, `intersection` and `openfga` need the `oc-authorization-openfga` module (included in the runtime images) and its settings. Requesting one of them without valid settings stops startup with a clear message. It never silently runs in `metadata` mode.

## How OpenFGA decides

The bundled authorization model (`oc-authorization-openfga/src/main/resources/openfga/model.fga`):

```
model
  schema 1.1

type user

type group
  relations
    define member: [user, user:*, group#member]

type document
  relations
    define grantee: [user, user:*, group#member]
    define blocked: [user, user:*, group#member]
    define can_read: grantee but not blocked
```

- **Documents** are `document:<sha256 of the document id>` (fixed length, safe for any id).
- **Each permission identity** becomes group membership tuples: `grantee` for grants, `blocked` for denies. `public` becomes `user:*`. Each identity is written to the group of its kind: `user:alice` to `u_alice`, `group:finance` to `g_finance`, and an identity without a prefix to both `u_<name>` and `g_<name>`. Names are lower-cased and percent-encoded; very long ones are hashed.
- **The caller** is checked as a user whose principal (`u_<name>`) and roles (`g_<name>` each) are passed with the request as *contextual* memberships.

With this mapping OpenFGA reproduces the stored-ACL decisions exactly. A differential test checks 37,500 random document/caller pairs against a real OpenFGA server (`OpenFgaDifferentialIT`).

**Real relationship-based access control on top.** Group memberships can also be *stored* in OpenFGA (for example `group:g_finance#member@user:p_dave`, or nested groups). A stored membership takes effect on the next request, without re-indexing anything. Syncing memberships from an identity provider or from repository group directories is a follow-up step tied to #187.

### Keeping OpenFGA in step (permission sync)

`AuthorizationSyncConsumer` reads the documents topic (`opencrawling-documents`) with its own consumer group (`opencrawling-authz-sync-v2`). That covers crawled, OIS-REST and gRPC-pushed documents alike, whatever the output store. For each document it:

1. reads the document's current tuples from OpenFGA,
2. computes the tuples its current permissions call for,
3. writes only the difference. Writes are idempotent (`on_duplicate`/`on_missing` = ignore), so replays are safe. A change of more than 100 tuples is applied in the order *add denies → add grants → remove grants → remove denies*, so the document is never more readable than either its old or its new permissions.

A deleted document (tombstone) loses all its tuples, so it becomes unreadable even if a store failed to purge its chunks. A failed sync is retried with exponential backoff (6 attempts, 0.5 s doubling to 10 s), then recorded in `opencrawling-documents.authz-dlt` and skipped, so one bad message cannot stall the others. Until the document syncs again, it keeps its previous tuples (none for a new document, which therefore stays unreadable in `openfga` mode).

### Search results stay complete

When OpenFGA can deny results the store ranked highly (`intersection` and `openfga` modes), backends fetch `limit × overfetch-factor` candidates (default 10×, never more than `candidate-budget`, default 500). They also skip their own stored-ACL pre-filters (pgvector's SQL filter, Vespa's deny clause), so the gate sees every candidate. In `metadata` and `shadow` modes, searches behave exactly as before.

## Configuration

| Property | Environment variable | Default | Description |
|---|---|---|---|
| `opencrawling.security.authorization.mode` | `OPENCRAWLING_SECURITY_AUTHORIZATION_MODE` | `metadata` | `metadata`, `shadow`, `intersection` or `openfga` |
| `opencrawling.security.authorization.overfetch-factor` | | `10` | Candidates fetched per requested result in `intersection`/`openfga` |
| `opencrawling.security.authorization.candidate-budget` | | `500` | Upper bound on candidates fetched for one request |
| `opencrawling.security.authorization.openfga.api-url` | `OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_API_URL` | `http://localhost:8080` | OpenFGA HTTP API |
| `opencrawling.security.authorization.openfga.api-token` | `OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_API_TOKEN` | *(empty)* | Pre-shared key, sent as a bearer token |
| `opencrawling.security.authorization.openfga.store-id` | `OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_STORE_ID` | *(empty)* | Store to use. **Pin it in production.** |
| `opencrawling.security.authorization.openfga.model-id` | `OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_MODEL_ID` | *(empty)* | Authorization model to use. **Pin it in production.** |
| `opencrawling.security.authorization.openfga.auto-provision` | `OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_AUTO_PROVISION` | `false` | Create or reuse the store named `store-name` and the bundled model when ids are not pinned (development, tests) |
| `opencrawling.security.authorization.openfga.store-name` | | `opencrawling` | Store name used by auto-provisioning |
| `opencrawling.security.authorization.openfga.timeout` | | `5s` | Per-request timeout |
| `opencrawling.security.authorization.openfga.batch-size` | | `50` | Checks per BatchCheck request (OpenFGA's default server limit) |
| `opencrawling.security.authorization.openfga.max-parallel-requests` | | `10` | BatchCheck requests sent in parallel |
| `opencrawling.security.authorization.openfga.sync.enabled` | `OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_SYNC_ENABLED` | `true` | Run the permission sync consumer in this service |
| `opencrawling.security.authorization.openfga.sync-group-id` | | `opencrawling-authz-sync-v2` | Consumer group of the permission sync |

Configure the mode in the services that **serve** retrieval (the MCP server). The sync consumer runs where the module is active; with several such services, Kafka spreads the partitions across them.

## Deployment

`docker-compose-openfga.yml` adds OpenFGA v1.22.0 with a PostgreSQL datastore and pre-shared-key authentication, as an overlay on any stack:

```bash
export OPENFGA_PRESHARED_KEY="$(openssl rand -hex 32)"
docker compose -f docker-compose.yml -f docker-compose-openfga.yml up -d
```

Then point the MCP server at it, for example:

```yaml
environment:
  - OPENCRAWLING_SECURITY_AUTHORIZATION_MODE=shadow
  - OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_API_URL=http://openfga:8080
  - OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_API_TOKEN=${OPENFGA_PRESHARED_KEY}
  - OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_STORE_ID=<store id>
  - OPENCRAWLING_SECURITY_AUTHORIZATION_OPENFGA_MODEL_ID=<model id>
```

To obtain pinned ids, start once with `..._AUTO_PROVISION=true` and read the ids from the log line `OpenFGA authorization uses store <id> and model <id>`. Then set them and turn auto-provisioning off. OpenFGA's HTTP API is published on host port `${OPENFGA_HTTP_PORT:-8089}` for administration; keep it off public networks.

## Rolling out OpenFGA

1. **Deploy OpenFGA** and run the MCP server in `shadow` mode. The permission sync starts filling OpenFGA.
2. **Backfill** the documents indexed before the sync existed (next section).
3. **Watch** `oc.authz.shadow.mismatch` and the `Authorization shadow mismatch` warnings. A document missing from OpenFGA shows up here as a disagreement. Move on only when mismatches stay at zero over a representative period.
4. **Switch** to `intersection` (cautious) or directly to `openfga`.

To go back, set the mode to `metadata` and restart. The stored permissions are always kept up to date, so rolling back is immediate.

### Backfill

OpenFGA must receive the permissions of documents that were indexed before the sync consumer existed:

- **Replay from Kafka (automatic).** The sync consumer group starts at the earliest retained offset of `opencrawling-documents`, so on first start it replays every document still on the topic (topic retention, 7 days by default). To replay again later, stop the services running the sync and reset the group:

  ```bash
  kafka-consumer-groups.sh --bootstrap-server <kafka> --group opencrawling-authz-sync-v2 \
    --topic opencrawling-documents --reset-offsets --to-earliest --execute
  ```

- **Re-crawl** for everything older than the retained topic. Re-crawling republishes each document with its current source permissions. That is also the only way to refresh what the vector stores hold for documents indexed before the current permission rules.

Shadow mode (step 3 above) tells you when the backfill is complete. There is deliberately no command that copies permissions out of the vector stores into OpenFGA: stored permissions of old documents may predate the current normalization rules, and copying them would carry those differences into OpenFGA.

### Upgrading from the first OpenFGA release

Releases before #224 stored every identity as two groups (`r_<raw>` and `s_<stripped>`) and let users and groups of the same name match each other. The new groups (`u_<name>`, `g_<name>`) start empty, so a document is denied in `intersection`/`openfga` mode until its permissions are synced again. The default sync consumer group changed to `opencrawling-authz-sync-v2` for this reason: on first start it replays the retained documents topic and rewrites each document's tuples (the old `r_`/`s_` tuples are removed in the same sync).

1. Before upgrading, switch the MCP server to `shadow` (or `metadata`) mode.
2. Deploy. The sync replays the retained topic; re-crawl anything older (see Backfill).
3. Wait until `oc.authz.shadow.mismatch` stays at zero, then switch back.
4. Delete the old consumer group: `kafka-consumer-groups.sh --bootstrap-server <kafka> --delete --group opencrawling-authz-sync`.

If you set `sync-group-id` yourself, either change it or reset that group to the earliest offset (Backfill above). Stored group memberships written under `group:s_<name>` must be rewritten as `group:g_<name>`.

## Operations

**Metrics** (Micrometer, under `/actuator/metrics`; also exported by any Micrometer registry you add), tagged with `provider`:

| Metric | Meaning |
|---|---|
| `oc.authz.check` | Time to decide one page of candidates |
| `oc.authz.candidates` | Candidates checked |
| `oc.authz.denied` | Candidates denied |
| `oc.authz.errors` | Requests denied because the provider failed |
| `oc.authz.shadow.mismatch` | Shadow mode: decisions where OpenFGA disagreed |
| `oc.authz.shadow.errors` | Shadow mode: OpenFGA failures (the caller is not affected) |

**When OpenFGA is unavailable:**
- in `openfga` and `intersection` modes, retrieval answers an error and returns no documents;
- in `shadow` mode, callers are unaffected and `oc.authz.shadow.errors` increases;
- permission syncs are retried and, if still failing, are recorded in `opencrawling-documents.authz-dlt`. **Do not replay that topic as it is.** A later update of the same document may already have been applied, and replaying the older one would roll its permissions back. Once OpenFGA is healthy, reset the sync group to the first dead letter's original offset (record header `kafka_dlt-original-offset`) so that updates replay in order, or re-crawl the affected documents:

  ```bash
  kafka-consumer-groups.sh --bootstrap-server <kafka> --group opencrawling-authz-sync-v2 \
    --topic opencrawling-documents:<partition> --reset-offsets --to-offset <original offset> --execute
  ```

**Limits:**
- A caller may carry at most 99 roles in `intersection`/`openfga` mode. OpenFGA accepts 100 contextual tuples per check: one for the principal and one per role. A caller with more is denied. Stored memberships remove this limit.
- Results are checked by their **source document id**. Chunks written without one are denied in `intersection`/`openfga` modes until they are re-crawled: this applies to chunks Qdrant, Milvus, OpenSearch and Solr wrote before the tombstone fix, and to chunks from the all-in-one pgvector, OpenSearch and Vespa connectors written before #45. The `metadata` and `shadow` modes are unaffected.
- OpenArch federated search checks OpenArch's own document ids. Unless those documents also flow through the documents topic, they have no tuples and are denied in `intersection`/`openfga` modes.

## Extending: a different provider

`AuthorizationProvider` (oc-core) is the extension point:

```java
public interface AuthorizationProvider {
    String name();
    boolean[] canRead(Caller caller, List<ResourceRef> candidates); // one decision per candidate, fail closed
    default boolean metadataPrefilterSafe() { return true; }
}
```

A provider that can allow documents the stored permissions do not show (as a relationship store can) implements `RelationshipAuthorizationProvider`. The runtime then composes it with the stored-ACL provider according to the mode, widens the candidate window and disables store-side pre-filters.

## Testing

- **Unit tests**: `DiscoveryVersusSearchTest` (discovery and search show each caller the same documents, and the store pre-filter never hides one the gate allows); `EffectiveAcl` rules and a 20,000-case consistency check between stores that keep the `security` object and stores that keep only the flattened fields; identifier encoding; tuple diff and fail-closed ordering; the gate, modes and metrics.
- **Integration tests** against OpenFGA (Testcontainers; run with `-DskipITs=false`): `OpenFgaAuthorizationIT` (sync, check, large changes, deletes, outage) and `OpenFgaDifferentialIT` (37,500 random decisions, stored ACLs vs OpenFGA).
- **End to end**: `./scripts/test-openfga-decoupled.sh` builds the decoupled stack with OpenFGA (images tagged `:openfga-45`, MCP on port 18080, its own network and volumes) and checks, through the OIS endpoint and the MCP tools: per-caller search results, a membership change without re-indexing, a permission change, a delete, fail-closed behaviour with OpenFGA stopped, and the metrics.
