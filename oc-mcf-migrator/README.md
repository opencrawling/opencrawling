# oc-mcf-migrator

A Java CLI that migrates an Apache ManifoldCF crawler configuration —
repository/output/transformation connections and jobs — into
[OpenCrawling](https://github.com/opencrawling/opencrawling), skipping and clearly logging
anything with no direct connector-level mapping rather than guessing or silently dropping it.

Lives as a real module inside the `opencrawling/opencrawling` reactor (see this repo's root
`pom.xml`'s `<modules>`) — `mvn install` from the repo root builds it automatically alongside every
other `oc-*` module, no separate clone/build/install step required. It's versioned independently
of the platform (like its own dependency, `oc-java-client-sdk`) rather than `<parent>`-inherited,
since it's a reusable library/CLI with its own release cadence, not a platform-internal connector —
see "Build" below for what that means in practice. Still usable entirely on its own too: `cd
oc-mcf-migrator && mvn clean package` builds just this module and its standalone CLI jar without
touching the rest of the reactor.

A user-facing walkthrough lives in this repo's own docs: [Migration Guide](../docs/Migration-Guide.md).

Three subcommands, matching [opencrawling/opencrawling#96](https://github.com/opencrawling/opencrawling/issues/96)'s
proposed structure exactly — including the `oc-cli` prefix now that `oc-cli` exists (see "Relationship
to opencrawling/opencrawling#96" below):

- **`audit`** — extract + map + report. Never writes anything, anywhere.
- **`import`** — extract + map + write the supported half directly to a live OpenCrawling instance.
- **`convert`** — extract + map + write each supported job as an
  [OIS](https://github.com/opencrawling/open-ingestion-standard)-format (`ois/v1alpha1`) YAML/JSON
  file. No OpenCrawling API call at all — pure file-to-file.

All three share the same extract→map engine, so a connection/job is skipped for the exact same
reason regardless of which one you run. Available two ways, both backed by the identical command
classes with nothing reimplemented: standalone (`java -jar oc-mcf-migrator-cli.jar audit ...`, as
documented throughout this README) or as `oc mcf audit`/`import`/`convert` once `oc-cli` is built —
see "Also available via oc-cli" below.

## What this actually does

1. **Extract** — reads ManifoldCF's connections and jobs via its live REST API
   (`GET /json/repositoryconnections`, `/outputconnections`, `/transformationconnections`,
   `/authorityconnections`, `/jobs`), a previously-saved JSON snapshot of those same five payloads
   (`--mcf-input-dir`), or ManifoldCF's own native combined-configuration XML export
   (`--mcf-export-file`, from its `ExportConfiguration` tool) — pure file-to-file, no ManifoldCF
   connectivity needed at all. The XML path is the most experimental of the three: it's built on the
   (well-founded, but not independently confirmed against a real export file) assumption that
   ManifoldCF's XML and JSON serializations share the same node-type vocabulary, since both come
   from the same internal `ConfigurationNode` tree — see `McfXmlToJsonAdapter`'s javadoc. Spot-check
   the result before trusting it in production.
2. **Map** — for each connection, looks up a registered `ConnectorMapper` by the connection's
   ManifoldCF class name (an **exact** match, never a substring guess). No mapper → the connection
   is skipped, logged with its name, class, and the reason — unless it's named in
   `--map-connector "SourceName=TargetName"`, which manually redirects it to an OpenCrawling
   connector you've already created by hand, bypassing automatic mapping for that connection
   entirely (nothing is created for the source side; jobs referencing it get the target name
   substituted in and are treated as supported). A job is migratable only if its repository
   connection **and every pipeline stage** (transformation/output) it references are individually
   supported (or overridden); otherwise the job is skipped, naming exactly which connector(s)
   blocked it. Within an otherwise-supported connector, individual config fields with no target
   equivalent are dropped/defaulted/converted with a logged note — this is what makes any real
   migration possible at all, since most fields on each side rarely line up 1:1. A connector/job
   combination that maps successfully but falls outside OpenCrawling's own narrow
   dynamic-connector-resolution coverage (see "Known limitations" below) additionally gets a
   `RUNTIME_RISK` note — a step above `SCOPE_CHANGE` in severity, since it means the migrated item
   might not actually run as configured until you verify it.
3. **Report** (`audit`/`import`/`convert` all produce one) — a Markdown file by default, or a
   structured JSON file (`--report-format json`) or a self-contained single-file HTML report
   (`--report-format html`) — listing what migrated, what didn't and why, every field-level fidelity
   note (each with a `recommendedAction` next step), a `compatibilityScorePercentage` (job-based:
   jobs are the unit end users actually care about, not connections in isolation), and known
   limitations of the current OpenCrawling reference implementation that affect whether a migrated
   job will actually run as expected. Config values that look like secrets
   (password/secret/token/credential in the key name) are never printed anywhere.
4. **Write** — either `import`'s live write to OpenCrawling (`POST /api/connectors`, already
   idempotent by name on the server side, then `POST /api/jobs`, which this tool makes idempotent
   itself by looking up existing jobs by name first and updating in place) or `convert`'s OIS files
   on disk. `--only-connections`/`--only-jobs` narrow every subcommand's plan (and therefore its
   write) to just the named items.

## Web UI

This engine also backs a live migration wizard inside OpenCrawling's own admin dashboard
(`oc-admin-ui` → "ManifoldCF Migration" in the sidebar): Configure → Extract & Plan → Review →
Apply → Results, calling this same `MigrationEngine` through two new endpoints on `oc-runtime`
(`POST /api/mcf-migration/plan`, `POST /api/mcf-migration/apply` — the API-shaped equivalents of
`audit` and `import`) rather than a separate service — see
`oc-runtime/.../api/McfMigrationController.java`. The CLI and the UI
are two front ends over the identical engine; nothing about the migration logic is duplicated,
right down to sharing the same response DTOs and `recommendedAction`/`compatibilityScorePercentage`
computation (`org.opencrawling.migrator.mcf.report.MigrationReportData`) with the CLI's own JSON
report renderer. (The wizard doesn't yet expose `convert`'s OIS export, the XML export source, or
`--map-connector` — natural follow-ups, not built here since nothing in the issue's "Phase 2" wizard
suggestion asked for them specifically.)

The Review step lets you uncheck individual supported connections/jobs before applying — those
selections are threaded into the same `--only-connections`/`--only-jobs` scoping the CLI already
has, so unchecking a connection a job depends on will correctly make that job unsupported for that
apply call, exactly as it would from the CLI. The Results step can start a migrated job immediately
(via the existing `/api/jobs/{id}/start`), and either step can download the full plan/result as a
JSON file identical in shape to `--report-format json`'s output.

## Also available via `oc-cli`

Once [`oc-cli`](https://github.com/opencrawling/opencrawling/tree/main/oc-cli) (issue #86) is built
or installed (e.g. via its Homebrew tap), every subcommand here is also reachable as `oc mcf audit`/
`import`/`convert`/`list-mappers` — see `oc-cli`'s `McfCommand`, which registers this module's own
command classes (`AuditCommand`, `ImportCommand`, `ConvertCommand`, `ListMappersCommand`) directly,
unchanged. Nothing is reimplemented or kept in sync by hand; `oc-cli` depends on this module the same
way `oc-runtime` does.

Two known rough edges from this being a late addition to an already-established CLI, both
deliberate, not oversights:
- **No `~/.oc/config.json` fallback.** `oc connector`/`oc job` transparently read a saved server
  URL/API key from `oc config set`, via `oc-cli`'s own `CliConfigService`. `oc mcf`'s subcommands
  still need their own `--oc-url`/`--oc-api-key`/`--oc-bearer-token` (or env vars) every time —
  `CliConfigService` lives in `oc-cli` itself, and this module can't depend on it without creating a
  dependency cycle (`oc-cli` already depends on `oc-mcf-migrator`). Fixing this properly would mean
  moving that config mechanism somewhere both can depend on, like `oc-java-client-sdk` — a bigger,
  cross-module call for whoever owns `oc-cli` to make, not something to do unilaterally here.
- **`--oc-url`/`--oc-api-key` instead of the sibling commands' bare `--url`/`--api-key`.** Kept
  deliberately, not renamed to match: every other `oc-cli` command only ever talks to one system
  (OpenCrawling), so a bare `--url` is unambiguous there. This tool talks to two (`--mcf-url` for the
  *source* ManifoldCF instance, `--oc-url` for the *target*) — collapsing either to a bare `--url`
  would make it genuinely unclear which system a flag targets.

## Build

Requires **Java 25**.

**As part of the full platform** (what `oc-runtime` needs to build at all, since it depends on
this module): just build from the repo root like any other module — no separate step for this one:

```bash
cd /path/to/opencrawling/opencrawling
mvn install
# or, to build only what oc-runtime needs without the rest of the reactor:
mvn -pl oc-runtime -am install
```

Maven's reactor resolves `oc-mcf-migrator` (and its own dependency, `oc-java-client-sdk`) from the
`<modules>` list and builds them in the right order automatically — neither needs its own manual
`mvn install` first.

**Standalone**, if you only want this module/CLI and not the rest of the platform:

```bash
cd /path/to/opencrawling/opencrawling
mvn install -pl oc-java-client-sdk -am -DskipTests   # this module's one real dependency

cd oc-mcf-migrator
mvn clean package
```

Either way produces two jars in `target/`: a plain, unshaded library jar
(`oc-mcf-migrator-<version>.jar` — what `oc-runtime` depends on normally) and a self-contained fat
jar for standalone CLI use (`oc-mcf-migrator-<version>-cli.jar`).

## Usage

```bash
# Discover what this build currently supports:
java -jar target/oc-mcf-migrator-1.0.0-SNAPSHOT-cli.jar list-mappers

# audit: report only, writes nothing:
java -jar target/oc-mcf-migrator-1.0.0-SNAPSHOT-cli.jar audit \
  --mcf-url http://localhost:8345/mcf-api-service/json \
  --oc-url http://localhost:8080

# import: write the supported half directly to OpenCrawling:
java -jar target/oc-mcf-migrator-1.0.0-SNAPSHOT-cli.jar import \
  --mcf-url http://localhost:8345/mcf-api-service/json \
  --oc-url http://localhost:8080

# convert: emit OIS-format (ois/v1alpha1) job files, no API call at all:
java -jar target/oc-mcf-migrator-1.0.0-SNAPSHOT-cli.jar convert \
  --mcf-url http://localhost:8345/mcf-api-service/json \
  --output-dir ./ois-jobs

# convert straight from ManifoldCF's own native XML export, no live API at all:
java -jar target/oc-mcf-migrator-1.0.0-SNAPSHOT-cli.jar convert \
  --mcf-export-file ./mcf-export.xml --output-dir ./ois-jobs

# Any subcommand: JSON or HTML report instead of Markdown:
java -jar target/oc-mcf-migrator-1.0.0-SNAPSHOT-cli.jar audit \
  --mcf-url http://localhost:8345/mcf-api-service/json --report-format html

# Any subcommand: plan against a previously-saved snapshot instead of a live ManifoldCF API:
java -jar target/oc-mcf-migrator-1.0.0-SNAPSHOT-cli.jar audit \
  --mcf-input-dir ./mcf-snapshot

# Manually map a connection with no auto-mapper to a connector you already created by hand:
java -jar target/oc-mcf-migrator-1.0.0-SNAPSHOT-cli.jar import \
  --mcf-url http://localhost:8345/mcf-api-service/json --oc-url http://localhost:8080 \
  --map-connector "Solr_Output=Qdrant_Vector_Store"
```

Key flags shared by all three subcommands (see `<command> --help` for the full list):
`--mcf-user`/`--mcf-password` (prefer env `MANIFOLDCF_PASSWORD` over the flag to avoid
shell-history leakage), `--mcf-input-dir`/`--mcf-export-file` (read a saved snapshot or a native
XML export instead of a live API — `--mcf-export-file` takes priority over `--mcf-input-dir`, which
takes priority over `--mcf-url`), `--default-embedding-dimensions` (default 384 — override if your
embedder produces a different dimension; used for both migrated Vespa and OpenSearch output
connectors), `--only-connections`/`--only-jobs` (name filters), `--map-connector` (manual
source-name→target-name redirects, repeatable/comma-separated). `audit`/`import`/`convert` also
share `--report-file`, `--report-format` (`markdown`, the default, `json`, or `html`), and
`--fail-on-skip` (CI-friendly strict exit code). `import` additionally has `--oc-api-key`/
`--oc-bearer-token`. `convert` additionally has `--output-dir` (required) and `--output-format`
(`yaml`, the default, or `json`).

Exit codes: `0` clean; `1` connectivity/usage error; `2` completed with skips, only under
`--fail-on-skip`; `3` (`import` only) at least one write failure.

## Currently supported connectors

Run `list-mappers` for the authoritative list. As of this version:

| ManifoldCF class | OpenCrawling target |
|---|---|
| `org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector` | `org.opencrawling.filesystem.FileSystemRepositoryConnector` |
| `org.apache.manifoldcf.agents.output.vespa.VespaOutputConnector` | `org.opencrawling.vespa.VespaOutputConnector` |
| `org.apache.manifoldcf.agents.output.elasticsearch.ElasticSearchConnector` | `org.opencrawling.opensearch2.OpenSearch2OutputConnector` — carries a `RUNTIME_RISK` note: OpenCrawling's admin-UI config keys for this connector aren't the same property scheme its Spring beans actually read, so the target connector needs manual `application.yml` configuration before it indexes anything |

Everything else (Alfresco, M-Files, Solr, BFSI output, all authority connections, ...) is reported
as unsupported today, either because OpenCrawling has no equivalent connector yet, or because the
equivalent-named connector solves a materially different problem (e.g. OpenCrawling's Alfresco
connector talks to Alfresco's stock REST API v1 with Basic auth, not a custom webscript AMP, and
never extracts ACLs). If you've already created a suitable connector by hand in OpenCrawling for
one of these, `--map-connector` lets you redirect to it manually instead of waiting on a real
mapper — see "What this actually does" above.

**Transformation connections (Content Limiter, Metadata Adjuster, regex mappers, ...) are a
distinct case, not just "no mapper built yet."** Checked directly: OpenCrawling has a real
`TransformationConnector` SPI (`oc-core`'s `connector` package, with a genuine archetype and
`ServiceLoader` registration) and a `transformationConnector` field on `JobRequest` — but that field
is actually an **embedding-model selector** at runtime (`JobOrchestrator` looks it up only to read
an `engine`/model config for `EmbeddingModelFactory`, e.g. `Ollama_Embedding_Default`), not a
resolver for an arbitrary named document-transform connector. The only concrete transform that
actually runs in a job today is a hardcoded Mustache-narrativization special case
(`NarrativizationConfig`, a separate field entirely) — nothing resembling ManifoldCF's
content-limiting/metadata-adjustment/regex-mapping semantics. So this is genuinely blocked
upstream, the same category as authority/ACL — not a mapper this tool is simply missing.

## Extending: adding support for a new ManifoldCF connector

No existing code needs to change:

1. Implement `org.opencrawling.migrator.mcf.mapping.ConnectorMapper`. `supports(String)`
   must be an exact class-name match. `map(...)` returns a `ConnectorMappingResult` targeting a
   connector that must already exist in OpenCrawling — a mapper only translates configuration, it
   can't invent a target connector.
2. List its fully-qualified class name in a
   `META-INF/services/org.opencrawling.migrator.mcf.mapping.ConnectorMapper` file — either
   added to this module directly, or shipped in a standalone jar.
3. Recompile, or drop that jar on the classpath alongside this tool's jar
   (`java -cp oc-mcf-migrator.jar:my-connector-mapper.jar org.opencrawling.migrator.mcf.Main audit ...`).
   `ConnectorMapperRegistry` discovers it automatically via `ServiceLoader` — the same mechanism
   OpenCrawling itself uses for its own connector plugins (see `oc-connector-archetypes`).

If your new target connector also needs an OIS-style short type identifier for `convert`'s output
(e.g. `"vespa"`, `"opensearch2"`), add it to the small maps at the top of
`org.opencrawling.migrator.mcf.ois.OisJobRenderer` — otherwise `convert` falls back to the full
class name and adds a note explaining why.

## Known limitations (of the target, not this tool)

Verified directly against OpenCrawling's current reference implementation source, and repeated in
every generated report's "Known target-system limitations" section:

- **Narrow dynamic connector resolution.** At job-start time, only repository connectors whose
  class name contains "Alfresco" or "Iceberg", and output connectors whose class name contains
  "Qdrant" or "Vespa", are actually resolved dynamically — everything else silently falls back to
  default beans. A migrated job outside those four may be created successfully but not run against
  the connector you expect. Every connector/job combination outside this list gets a `RUNTIME_RISK`
  note in the report rather than being silently reported as a clean migration — this is what the
  OpenSearch2 mapper's caveat above is an instance of.
- **No scheduler.** Jobs only run via a manual/API `start` call. ManifoldCF's schedule, hopcount,
  recrawl-interval and reseed settings have no target and are always dropped (in `audit`/`import`'s
  reports). `convert`'s OIS output makes a best effort: a job with exactly one schedule record and a
  single specific hour/minute (and no month/day-of-month restriction) gets a real translated cron
  expression; anything more complex (multiple records, a duration window, more than one hour/minute
  value) falls back to a defaulted daily crontab with an explanatory note — see `CronTranslator`.
  Either way, OpenCrawling has no scheduler to actually honor it.
- **No filesystem filtering.** `FileSystemRepositoryConnector` scans every file under its root
  unconditionally. Any ManifoldCF include/exclude filters are dropped — flagged in reports as
  `SCOPE_CHANGE`, a step above ordinary `DROPPED`, since it changes what actually gets crawled.

## Relationship to opencrawling/opencrawling#96

[Issue #96](https://github.com/opencrawling/opencrawling/issues/96) is the formal upstream feature
request this tool implements a first cut of. It matches the issue's core idea — read ManifoldCF's
live REST API or native export, translate connections/jobs, produce an audit report, optionally
provision directly into a running `oc-runtime` or export OIS-format files, and its "Phase 2"
suggestion of a GUI wizard inside `oc-admin-ui` (see the Web UI section above) — closely now,
including its exact naming, command structure, and every specific technical detail it called out:

| Issue #96 proposes | This tool does | Notes |
|---|---|---|
| Module `oc-mcf-migrator`, package `org.opencrawling.migrator.mcf` | ✅ Matched, and it's a real reactor module now too (see "Build" above) — not just a matching name on a module built and installed separately | Renamed once the CLI/report/DTO shape had stabilized through live verification, then physically moved into this repo and added to the root `pom.xml`'s `<modules>`, rather than churning either the name or the module's home repeatedly during development |
| Three commands: `oc mcf convert` (file↔file), `oc mcf import` (live API), `oc mcf audit` (report only), under `oc-cli` | ✅ Fully matched, `oc-cli` prefix included — `oc mcf convert`/`import`/`audit` all work today | Once `oc-cli` (issue #86) landed in the reactor, this module's existing independent subcommand classes registered into it directly (`oc-cli`'s `McfCommand`) with zero changes — see "Also available via `oc-cli`" above. Still usable standalone too (`oc-mcf-migrator-cli.jar audit ...`), same classes either way |
| `convert` reads ManifoldCF's native XML export file | ✅ Matched — `--mcf-export-file` | Most experimental piece here: built on the (well-founded but unverified against a real export) assumption that ManifoldCF's XML and JSON share the same node-type vocabulary — see `McfXmlToJsonAdapter`'s javadoc |
| Apply-by-default with an opt-in `--dry-run` | N/A — resolved by the command split above, not chosen either way | With `audit` (never writes) and `import` (always writes) as separate commands, there's no single command left whose default write-behavior could diverge from the issue's suggestion |
| `--map-output-connector "Solr_Output=Qdrant_Vector_Store"` manual override | ✅ Matched (generalized to `--map-connector`, since the same need applies to repository connections too, not just output) | Nothing is created for the source side; jobs referencing it get the target name substituted in and are treated as supported |
| `--report-format html` | ✅ Matched — a self-contained, single-file HTML report, inline CSS only | Same content as the Markdown/JSON reports |
| OIS-format YAML/JSON config file output | ✅ Matched — `convert` writes one real `ois/v1alpha1` file per supported job (validated against the actual schema at [opencrawling/open-ingestion-standard](https://github.com/opencrawling/open-ingestion-standard)), YAML by default or JSON via `--output-format json` | `spec.pipeline` is deliberately omitted (ManifoldCF has no per-job text-extraction/chunking/embedding-provider data to translate); `spec.schedule` gets a real translated cron for the simple case (see "Known limitations" above) and a defaulted, explained one otherwise |
| Structured JSON audit report with a `compatibility_score_percentage` and per-warning `recommended_action` | ✅ Matched — `--report-format json` (CLI) and every `/api/mcf-migration/*` response (API) return the same shape: a `summary.compatibilityScorePercentage` and a `recommendedAction` on every connection/job/field note | Implemented via a shared `MigrationReportData` builder so the CLI and the web UI can never drift on this shape |
| Dependency on `org.opencrawling:oc-core` and its (proposed) `org.opencrawling.core.config.JobConfig` | Not adopted — this tool depends on `oc-java-client-sdk` instead | Checked directly: `oc-core` is a server-runtime library (claim-check storage, connector SPI, Kafka messaging DTOs, ACL abstractions for the crawler backend) with nothing client-facing, and `JobConfig` doesn't exist anywhere in this codebase. The issue's own proposed interface sketch referencing it appears to be illustrative pseudocode rather than grounded in real code — adding the dependency would mean importing something with no genuine use here |
| Dedicated `AuthorityConnectorMapper` / ACL-SID translation | Authority connections are extracted and always reported unsupported (no mapper) | No mapper was built because OpenCrawling's own Alfresco connector doesn't extract or use ACLs yet either (verified in its source) — translating ACL config with no consumer downstream would be translation theater |
| CMIS/SharePoint/Web/HDFS/JDBC repository connectors, Solr output connector | Not adopted — no OpenCrawling target exists for any of them | Confirmed by enumerating every `oc-*-repository-connector`/`oc-*-output-connector` module in this repo; `--map-connector` is the workaround if you've built one of these by hand |
| Translation Matrix: Transformation Connection (Metadata Adjuster, Tika Extractor) → OIS Pipeline Transformations & Auto-Narrativization | Not adopted — no real mapping target exists | Checked directly, not assumed: see "Known limitations" above — `transformationConnector` is an embedding-model selector at runtime, not a resolver for an arbitrary named document-transform connector; the only concrete transform that actually runs is a hardcoded Mustache-narrativization special case unrelated to content-limiting/metadata-adjustment semantics |
| Document ACLs (`allow_token_read`/`deny_token_read`) → OIS ACL Schema (`security_allowed_read`/`security_denied_read`) | Out of scope for this tool regardless of upstream state | This is a per-document, crawl-time behavior, not connector *configuration* — even once real authority/ACL support exists upstream, it'd be enforced by the crawl pipeline itself, not something a connector-config migration tool sets once and forgets |
| Community Discussion Q2: direct database extraction (reading MCF's Postgres/MySQL/Derby schema directly) | Not built, and deliberately left open rather than decided against | The issue poses this as a discussion question, not a requirement; live REST API, a saved JSON snapshot, and now `--mcf-export-file`'s native XML cover the sources the issue explicitly asks for — direct DB access would be a much larger scope decision (schema coupling to a specific MCF version/DB backend) worth its own discussion, not something to quietly add or skip |

## Testing

- Unit tests cover the ManifoldCF JSON parsing (including both encodings ManifoldCF's own
  `Configuration#toJSON()` produces — see `McfJsonNodes`) and its own real schedule-record parsing,
  the native XML export adapter (against a hand-built fixture mirroring this project's existing
  JSON fixtures), all three connector mappers, the manual `--map-connector` override end-to-end
  (plan, apply-skip, and job-name substitution), the job-resolution algorithm (including the
  `RUNTIME_RISK` dynamic-resolution check), the cron translator (simple case and every complex case
  that must fall back), secret redaction, all three report renderers (including HTML-escaping), the
  OIS job renderer (schedule translation/omission notes, secret redaction in OIS output, and
  YAML/JSON round-tripping), the file-based source, and the shared report DTOs.
- `MigrationEngineAcceptanceTest` runs the full extract→plan→apply pipeline offline against
  fixtures in `src/test/resources/fixtures/mcf/` modeled on this project's own real ManifoldCF
  configuration (4 repository connections, 5 output connections, 1 transformation, 5 jobs) —
  asserting the exact expected outcome: 4 of 10 connections migrate (including both Elasticsearch
  connections via the OpenSearch2 mapper), 1 of 5 jobs migrates, with the specific blocking
  connector named for each of the other four. Named `*Test`, not `*IT`, deliberately — Surefire's
  default `mvn test` include patterns silently skip `*IT.java` files without a configured
  `maven-failsafe-plugin`, which this project doesn't have.
- Live verification additionally confirmed: a real `convert`-generated OIS YAML file validates
  against the actual `job.schema.json` from opencrawling/open-ingestion-standard (via Python's
  `jsonschema` library); `audit`/`import`/`convert` all work end-to-end against a fixture-replay
  ManifoldCF stand-in and a live `oc-runtime`, including `--map-connector` turning a
  previously-unsupported connection and its dependent job into supported ones; `convert` against
  `--mcf-export-file` and `--report-format html` together; and a from-scratch `mvn install` from
  the repo root (with this module's `~/.m2` artifact deliberately deleted first) builds all 21
  reactor modules with no separate manual step.
- Run everything: `mvn test`.
