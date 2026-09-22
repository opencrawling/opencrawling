# Migration Guide

OpenCrawling ships a ManifoldCF migration tool, **`oc-mcf-migrator`**, that translates an existing
Apache ManifoldCF crawler configuration — repository/output/transformation connections and jobs —
into OpenCrawling. It implements a first cut of
[issue #96](https://github.com/opencrawling/opencrawling/issues/96)'s proposal, and lives as a real
module in this repository's own Maven reactor (`oc-mcf-migrator/`) — no separate clone or build step
needed beyond the normal `mvn install` from the repo root.

Anything with no direct connector-level mapping is skipped and clearly reported, never guessed or
silently dropped.

---

## 🧭 Three ways to run it

| Command | What it does | Writes to OpenCrawling? |
| :--- | :--- | :--- |
| **`audit`** | Extract + map + report | Never |
| **`import`** | Extract + map + write the supported half directly | Always |
| **`convert`** | Extract + map + write each supported job as an [OIS](https://github.com/opencrawling/open-ingestion-standard)-format (`ois/v1alpha1`) YAML/JSON file | Never (pure file-to-file) |

All three share the same extract → map engine, so a connection or job is skipped for the exact same
reason no matter which one you run.

```bash
# Discover what this build currently supports:
java -jar oc-mcf-migrator-cli.jar list-mappers

# audit: report only
java -jar oc-mcf-migrator-cli.jar audit \
  --mcf-url http://localhost:8345/mcf-api-service/json --oc-url http://localhost:8080

# import: write the supported half directly to OpenCrawling
java -jar oc-mcf-migrator-cli.jar import \
  --mcf-url http://localhost:8345/mcf-api-service/json --oc-url http://localhost:8080

# convert: emit OIS-format job files, no API call at all
java -jar oc-mcf-migrator-cli.jar convert \
  --mcf-url http://localhost:8345/mcf-api-service/json --output-dir ./ois-jobs
```

## 🌐 Or use the web wizard

The same engine backs a visual migration wizard inside `oc-admin-ui` — open the admin dashboard and
pick **ManifoldCF Migration** from the sidebar. It walks Configure → Plan & Review → Apply → Results,
lets you uncheck individual connections/jobs before writing, and can download the full plan/result
as JSON.

## 📥 Three ways to read your ManifoldCF configuration

* **Live REST API** (default) — `--mcf-url` (plus `--mcf-user`/`--mcf-password` if required).
* **A saved JSON snapshot** — `--mcf-input-dir <dir>`, expecting the five files a live API would
  have returned (`repositoryconnections[.json]`, `outputconnections[.json]`,
  `transformationconnections[.json]`, `authorityconnections[.json]`, `jobs[.json]`).
* **ManifoldCF's own native XML export** — `--mcf-export-file <file>`, from ManifoldCF's
  `ExportConfiguration` tool. This is the most experimental of the three: it hasn't been
  independently verified against a real export file, only against the same node-type vocabulary
  confirmed via the live JSON API. Spot-check the result before trusting it in production.

## 🔧 Manually mapping what can't be auto-detected

Not every ManifoldCF connector has an OpenCrawling equivalent yet (see "Known gaps" below). If
you've already created a suitable connector by hand in OpenCrawling, `--map-connector` lets you
redirect a specific ManifoldCF connection to it, bypassing automatic class-based mapping entirely:

```bash
--map-connector "Solr_Output=Qdrant_Vector_Store"
```

The source connection isn't created — nothing is written for it — but any job referencing it is
treated as supported, with the target name substituted in.

## 📄 Report formats

`audit`/`import`/`convert` all produce the same report, in your choice of `--report-format`:
`markdown` (default), `json` (a `compatibilityScorePercentage` plus a `recommendedAction` per
warning — the same shape the web wizard's API returns), or `html` (a self-contained, single-file
report you can open directly in a browser).

## 🚧 Known gaps (verified against OpenCrawling's own source, not assumed)

* **No CMIS, SharePoint, Web, HDFS, JDBC, or Solr connectors exist in OpenCrawling yet** — there is
  nothing to map ManifoldCF's equivalents into. `--map-connector` is the workaround if you've built
  one by hand.
* **No authority/ACL enforcement exists yet** — `oc-admin-ui`'s "Authority Connector" field is
  cosmetic today; ManifoldCF's Active Directory/LDAP security mappings have nowhere real to migrate to.
* **Dynamic connector resolution at job-start is narrow** — only repository connectors named like
  Alfresco/Iceberg and output connectors named like Qdrant/Vespa are actually resolved dynamically;
  everything else may fall back to a default bean. Migrated connectors/jobs outside that list get a
  `RUNTIME_RISK` note in every report.

See the module's own README (`oc-mcf-migrator/README.md`) for the full, up-to-date breakdown against
issue #96's original proposal.
