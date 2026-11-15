# Enterprise Multi-Agent Integration

OpenCrawling can act as an identity-aware data plane for AI agents. An agent can:
- see what a repository holds before anything is ingested;
- decide what to ingest and start it itself;
- have business processes push single documents;
- read migrated content's metadata without downloading binaries;
- search.

Every answer is filtered by what the user the agent works for may read.

This page shows how the pieces fit together. Each feature's reference is linked from its section.

## What an agent can do

| Capability | MCP tools | REST | Scope (default) | Turned on by |
|---|---|---|---|---|
| Search indexed content | `secureVectorSearch`, `getDocumentContent`, `listAccessibleSources` | | | always |
| Look before ingesting | `listRepositoryConnectors`, `exploreRepositoryTree`, `queryRepositoryDryRun` | `/api/discovery/**` | | `opencrawling.discovery.enabled` |
| Ingest a folder | `triggerDynamicJob`, `getDynamicJobStatus`, `listDynamicJobs`, `stopDynamicJob` | `/api/jobs/dynamic/**` | `oc:jobs:write` | `opencrawling.jobs.dynamic.enabled` |
| Ingest or remove one document | `ingestDocument`, `removeDeletedDocument` | `POST /api/jobs/dynamic/documents` | `oc:jobs:run` | `opencrawling.jobs.dynamic.enabled` |
| React to a business process | | `POST /api/events/bpm/document-transition` | `oc:events:write`, or a signed request | `opencrawling.events.bpm.enabled` |
| Read migrated metadata | `listSidecarStores`, `readOisSidecars` | `/api/sidecars/**` | `oc:sidecars:read` | `opencrawling.sidecars.enabled` |

Everything except search is off by default, and each part can be turned on alone.

## Identity: who an agent works for

Agents must call with a bearer token, and the runtime must verify it:

```yaml
opencrawling:
  security:
    identity:
      mode: jwt
      protect-api: true          # REST endpoints need a token too
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://idp.example.com/realms/corp
          audiences: opencrawling
```

- **The token decides, not the tool arguments.** With `mode: jwt`, the `userPrincipal`/`userRoles` arguments of every tool are ignored. An agent that claims to be someone else in a tool call still gets the token's user's view.
- **The principal and groups** come from separate claims (`principal-claims`, `group-claims`), so a role can never stand in for a user grant. See [Model Context Protocol, Caller identity](Model-Context-Protocol#-caller-identity).
- **Scopes** gate each capability. They are read from the `scope` or `scp` claim, and each tool asks only for its own. A token that may read sidecars cannot start jobs unless it also carries `oc:jobs:write`.
- **Delegation:** an agent acting for a user should present a token for that user, obtained by OAuth 2.0 Token Exchange (RFC 8693).
  - If the token carries an `act` claim, its `act.sub` is recorded as the acting agent, and the user as the party it acted for.
  - Without `act`, the token's principal is recorded alone.
  - Check how your identity provider issues delegation tokens, and that it puts the agent in `act`.
- **A swarm** is several sub-agents, each working for a different user. It is safe as long as each sub-agent uses its own user's token. Calls are decided per request on that request's token, so concurrent sessions never see each other's results.

## The agentic ingestion flow

A typical agent works through five steps. Each step is filtered by the user's access.

1. **Discover.** `listRepositoryConnectors`, then `exploreRepositoryTree` on a connector.
   - The agent sees the folders and documents the user may read, by the permissions the repository reports now.
   - Folders the user cannot read are not opened.
   - No content is read.
2. **Preview.** `queryRepositoryDryRun` on a folder or a read-only query shows how many matching documents the user may read, with samples.
3. **Ingest.** `triggerDynamicJob` crawls a folder, and `ingestDocument` ingests a single document. Both run on the registered connectors listed in `opencrawling.jobs.dynamic.connectors`.
   - **Its own chunking:** the job may set `chunkSize` and related settings.
   - **Idempotency:** an `idempotencyKey` makes a retry return the same job.
   - **Approval:** with `require-approval`, the job waits for a member of `admin-groups`, other than the requester, to approve it. No MCP tool can approve.
4. **Follow.** `getDynamicJobStatus` and `listDynamicJobs` show the user's own jobs only. `stopDynamicJob` ends a run before its next document.
5. **Use.** `secureVectorSearch` and `getDocumentContent` return what the user may read. With OpenFGA, a group membership changed there applies to search at once, with no re-crawl.

Every ingested document keeps its source permissions, whoever triggered the crawl, so the index never shows anyone more than the source does.

```json
{"method": "tools/call", "params": {"name": "exploreRepositoryTree",
  "arguments": {"connector": "Alfresco_Main", "start": "/Finance", "depth": 2}}}

{"method": "tools/call", "params": {"name": "triggerDynamicJob",
  "arguments": {"connector": "Alfresco_Main", "start": "/Finance/2026", "idempotencyKey": "fin-2026-q3", "chunkSize": 400}}}
```

Details: [Model Context Protocol, Repository discovery](Model-Context-Protocol#-repository-discovery) and [Dynamic jobs](Model-Context-Protocol#-dynamic-jobs).

## Event-driven ingestion from business processes

A BPM engine (Flowable, Alfresco Process Services) can post a document-transition event when a process reaches a step, such as "contract signed". A route an administrator configured then has that one document ingested again, or removed from the index.

- **How a sender authenticates:** with an HMAC-signed request, a static token over Basic auth, or a bearer token carrying `oc:events:write`.
- **Duplicates:** event ids are deduplicated per sender.

See [Configuration Guide, BPM Document Events](Configuration-Guide#-bpm-document-events).

## Governance before embedding

With `opencrawling.governance.enabled`, every document passes a chain of hooks before it is chunked and embedded:
- the built-in PII masker;
- an HTTP governance service;
- your own `PreEmbeddingGovernanceHook`.

Each hook approves the document, approves a sanitized version, or rejects it.

- **Permissions:** a hook never changes a document's permissions.
- **Fail closed:** if a hook cannot decide, nothing is embedded.
- **Rejections:** a rejected new version removes the earlier one unless `on-rejected-update: keep`.
- **Direct outputs:** with governance on, jobs that would send documents straight to an output connector, and so bypass the gate, are refused. Run RAG jobs on the decoupled pipeline.

See [Configuration Guide, Pre-Embedding Governance](Configuration-Guide#%EF%B8%8F-pre-embedding-governance).

## Reading migrated content's metadata

Content migrated to Apache Ozone or AWS S3 has an OIS sidecar (`<key>.ois.json`) next to each binary. `readOisSidecars` returns a page of those sidecars, with totals over the page by MIME type and source, so an agent can learn about migrated content without downloading binaries.

- **Who sees what:** each sidecar is decided like a search result, on its document id and permissions.
- **What is returned:** answers carry no permissions and no hidden counts.
- **Paging:** the paging cursor is encrypted.

See [Model Context Protocol, OIS sidecars](Model-Context-Protocol#%EF%B8%8F-ois-sidecars).

## What the runtime guarantees

- **One decision point.** Search results, sidecars, and the jobs and documents an agent may touch are decided by the same rules for the same user.
  - Search and sidecars go through the authorization gate (`metadata`, `shadow`, `intersection` or `openfga` mode).
  - Discovery decides on the permissions the repository reports now, with the metadata rules, because OpenFGA only knows documents that have been indexed. Group memberships held only in OpenFGA are therefore not used by discovery: it may show less than search, never more.
- **Documents keep their source permissions.** Nothing an agent does widens access.
- **Fail closed.** An unverifiable caller gets `401`. If authorization or governance cannot decide, nothing is returned or embedded.
- **Bounded.**
  - Discovery, sidecar reads and governance calls have size, count and time limits.
  - Dynamic jobs have per-user, per-caller and overall caps (`429` beyond them).
- **Nothing reveals hidden content.**
  - An item the caller cannot see answers like one that does not exist.
  - Counts of hidden items are off by default.
  - Repository errors are not passed through.
- **Audited.**
  - Every execution records who started it, how (`trigger_source`), on whose behalf, and the `X-Correlation-Id`. These go to the run summary and to every row of the Parquet/Avro ledger (see [Lakehouse Audit Reporting & Ledger](Lakehouse-Audit-Reporting-and-Ledger)).
  - Governance outcomes are recorded per document in the job's trace.

## Configuration checklist

```yaml
opencrawling:
  security:
    identity:
      mode: jwt
      protect-api: true
    authorization:
      mode: openfga                 # or metadata (default), shadow, intersection
  discovery:
    enabled: true
  jobs:
    dynamic:
      enabled: true
      connectors: Alfresco_Main      # registered connectors agents may crawl
      admin-groups: oc-admins
      require-approval: false
  events:
    bpm:
      enabled: false
  governance:
    enabled: true
    hooks: pii-masking,http
  sidecars:
    enabled: true
    connectors: Lake_S3              # registered Ozone/S3 output connectors
```

Every setting is described in the [Configuration Guide](Configuration-Guide).

## How it is verified

- **Discovery and search agree.** `DiscoveryVersusSearchTest` takes a 400-document corpus with mixed permissions and sends it both ways. Discovery gets it as nodes; search gets it through the real ingestion consumer, the writer's permission fields and a JSON round trip, as a store keeps them.
  - The corpus covers case variants, `user:`/`group:` prefixes, bare identities, denies, public grants, and empty ACLs.
  - For every caller and every document with permissions, both show the same documents.
  - The vector store's pre-filter never hides a document the gate allows.
- **Agents stay apart.** `AgentSwarmHttpTest` runs over the real MCP protocol with real signed, delegated tokens.
  - Four sub-agents work in parallel for two users. Each claims to be the other user in its tool arguments, and each still sees only its own user's view in discovery, sidecars and search.
  - A delegated job records the agent and the user.
  - Each tool asks for its own scope.
  - A token signed by another key opens no session.
