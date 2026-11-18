# OpenCrawling - Zammad Repository Connector (`oc-zammad-repository-connector`)

The **Zammad Repository Connector** (`oc-zammad-repository-connector`) crawls **Zammad Helpdesk & ITSM** instances (self-hosted and Zammad Cloud) via the official **Zammad REST API v1** (`/api/v1/...`), ingesting support tickets, public and internal conversation articles, binary ticket attachments, tags, and Knowledge Base localized answers into **OpenCrawling** using the **Open Ingestion Standard (OIS)**.

## Key Features

- **Strict Public vs. Internal Agent Note Isolation**:
  - `SPLIT_PUBLIC_AND_INTERNAL` (default): Emits distinct OIS documents for public customer threads (`zammad://<host>/tickets/<id>/public`) and confidential internal agent notes (`zammad://<host>/tickets/<id>/internal`), ensuring internal notes and their attachments are restricted exclusively to Zammad group agents (`zammad-group-<groupName>`).
  - `PER_ARTICLE`: Emits one OIS document per ticket article (`zammad://<host>/tickets/<id>/articles/<articleId>`).
  - `PUBLIC_ONLY`: Ingests only customer-visible articles (`internal: false`).
- **Incremental `SEARCH_DELTA` Crawling & Tombstone Lifecycle**:
  - Queries `GET /api/v1/tickets/search?query=updated_at:>=...&sort_by=updated_at&order_by=asc` and persists watermarks, emitted OIS document IDs, and ACL fingerprints in `ZammadCrawlState`, emitting `DocumentAction.DELETE` tombstones when tickets, articles, attachments, or Knowledge Base answers are removed or excluded.
- **Zero-Trust Security (`ZammadSecurityMapper`)**:
  - Maps Zammad group permissions (`zammad-group-<groupName>`), customer email principals (`user:<customerEmail>`), shared organizations (`zammad-org-<orgName>` when `organization.shared == true`), and Knowledge Base category roles (`zammad-role-<roleName>`) into OIS `SecurityConfig`, failing closed when permissions cannot be resolved.
- **Pre-Crawl Discovery & On-Demand Fetching**:
  - Implements `DiscoverableRepositoryConnector` (`explore`, `dryRun`, `crawlStart`) and `DocumentFetchingRepositoryConnector` (`fetchDocument`) with page-by-page `VisibilityFilter` enforcement.

## Configuration (`application.yml`)

```yaml
spring:
  opencrawling:
    connector:
      type: zammad
      zammad:
        base-url: "https://support.acme.com"
        auth-type: "token"          # token | oauth2 | basic
        access-token: "${ZAMMAD_ACCESS_TOKEN}"
        on-behalf-of: ""
        group-names: []
        ticket-states: []
        include-tickets: true
        include-internal-articles: true
        article-granularity: "SPLIT_PUBLIC_AND_INTERNAL"
        include-attachments: true
        include-knowledge-base: true
        include-tags: true
        crawl-mode: "search_delta"  # search_delta | full
        state-file: "data/zammad-state"
        max-attachment-size-bytes: 52428800
        include-acls: true
        include-customer-in-public-acl: true
        include-shared-organization-in-public-acl: true
        allow-public-kb: false
        page-size: 50
        parallelism: 4
        max-retries: 5
        initial-backoff-ms: 1000
        timeout-seconds: 30
```
