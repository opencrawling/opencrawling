# OpenCrawling - Zammad Repository Connector (`oc-zammad-repository-connector`)

The **Zammad Repository Connector** (`oc-zammad-repository-connector`) crawls **Zammad Helpdesk & ITSM** instances (self-hosted and Zammad Cloud) via the official **Zammad REST API v1** (`/api/v1/...`), ingesting support tickets, public and internal conversation articles, binary ticket attachments, tags, and Knowledge Base localized answers into **OpenCrawling** using the **Open Ingestion Standard (OIS)**.

## Key Features

- **Strict Public vs. Internal Agent Note Isolation**:
  - `SPLIT_PUBLIC_AND_INTERNAL` (default): Emits distinct OIS documents for public customer threads (`zammad://<host>/tickets/<id>/public`) and confidential internal agent notes (`zammad://<host>/tickets/<id>/internal`), ensuring internal notes and their attachments are restricted exclusively to Zammad group agents (`zammad-group-<groupName>`).
  - `PER_ARTICLE`: Emits one OIS document per ticket article (`zammad://<host>/tickets/<id>/articles/<articleId>`).
  - `PUBLIC_ONLY`: Ingests only customer-visible articles (`internal: false`).
  - `TICKET`: Emits one OIS document per ticket (`zammad://<host>/tickets/<id>`) with the full conversation in order (public replies and internal notes), each article marked with its role (`data-role="customer|agent|internal|system"`), readable by the ticket's group only.
- **Article Filters**: system articles (trigger notifications, escalations) and automatic replies are left out by default (`excluded-article-senders: System`, `skip-auto-replies: true`); a ticket touched only by such an article is not re-indexed, thanks to a content hash per rendered document in the crawl state.
- **Ticket Selection Filters**: `created-after`, `included-tags` / `excluded-tags`, `excluded-title-patterns`, `priorities`, `organizations` and `base-query`, all compatible with incremental crawling and tombstones (tickets that stop matching are removed).
- **Privacy Controls**: `identity-rendering` (`email`, `name`, `id`, `none`), `customer-identity` (`email` or `id`), `participant-metadata` (`zammad.participants` for anonymization hooks) and `privacy-profile: strict` (no e-mail address in rendered headers, metadata or permission fields).
- **Periodic Full Scan**: `full-scan-interval` (e.g. `P1D`), optional `full-scan-window` and `full-scan-timezone`: an incremental job enumerates every ticket once per interval and tombstones deleted tickets, without a second job.
- **Incremental `SEARCH_DELTA` Crawling & Tombstone Lifecycle**:
  - Queries `GET /api/v1/tickets/search?query=updated_at:[<watermark> TO *]&sort_by=updated_at&order_by=asc` and persists watermarks, emitted OIS document IDs, and ACL fingerprints in `ZammadCrawlState`, emitting `DocumentAction.DELETE` tombstones when tickets, articles, attachments, or Knowledge Base answers are removed or excluded.
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
        # article filters (#412)
        excluded-article-senders: "System"
        excluded-article-types: ""
        skip-auto-replies: true
        article-role-labels: ""       # customer=Customer,agent=Agent,internal=Internal note,system=System
        # ticket selection (#413)
        created-after: ""             # 2024-01-01, 2024-01-01T00:00:00Z or P2Y
        included-tags: ""
        excluded-tags: ""             # spam, test
        excluded-title-patterns: ""   # one regular expression per line
        priorities: ""
        organizations: ""
        base-query: ""                # Zammad search syntax, combined with the watermark
        # privacy (#414)
        privacy-profile: "standard"   # standard | strict
        identity-rendering: "email"   # email | name | id | none
        customer-identity: "email"    # email | id
        participant-metadata: "names" # names | names-and-ids | none
        # periodic full scan (#415)
        full-scan-interval: ""        # e.g. P1D
        full-scan-window: ""          # e.g. 01:00-05:00
        full-scan-timezone: "UTC"
        page-size: 50
        parallelism: 4
        max-retries: 5
        initial-backoff-ms: 1000
        timeout-seconds: 30
```
