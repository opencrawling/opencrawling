# Zammad REST API v1 Mock (`zammad-mock`)

A lightweight, standard-library-only Python 3 emulator of the **Zammad REST API v1** (`/api/v1/...`) used to run deterministic over-HTTP connector tests (`scripts/test-zammad-connector.sh`) and full decoupled pipeline integration tests (`scripts/test-zammad-decoupled.sh`) without spinning up Zammad's full multi-container Rails + PostgreSQL + Redis + Memcached + Elasticsearch stack.

## Implemented Endpoints

### Zammad REST API v1 (authenticated)
- `GET /api/v1/users/me` and `GET /api/v1/users/{id}`
- `GET /api/v1/groups`, `GET /api/v1/roles`, `GET /api/v1/ticket_states`, `GET /api/v1/ticket_priorities`
- `GET /api/v1/organizations/{id}` (exposes `shared: true|false`)
- `GET /api/v1/tickets?page=...&per_page=...&expand=true`
- `GET /api/v1/tickets/search?query=...&sort_by=updated_at&order_by=asc&page=...&per_page=...&expand=true` (returns Zammad's `assets.Ticket` envelope; supports `*`, `updated_at:>=...`, `group.name:"..."`, `group_id:...`)
- `GET /api/v1/tickets/{id}?expand=true`
- `GET /api/v1/ticket_articles/by_ticket/{ticketId}`
- `GET /api/v1/tags?object=Ticket&o_id={ticketId}`
- `GET /api/v1/ticket_attachment/{ticketId}/{articleId}/{attachmentId}`
- `GET /api/v1/knowledge_bases/init`
- `GET /api/v1/knowledge_bases/{kbId}/answers/{answerId}?include_contents=true`

Supports HTTP Access Token (`Authorization: Token token=...`), OAuth2 Bearer (`Authorization: Bearer ...`), Basic Auth, and tracks `X-On-Behalf-Of`.

### Mock Control Endpoints (unauthenticated)
- `GET /mock/stats` — dataset counts, expected document count, request counters, `authFailures`, `lastOnBehalfOf`
- `GET /mock/tickets` — current tickets list
- `POST /mock/reset` — reset dataset and counters to the deterministic seed
- `POST /mock/tickets` — create a ticket (with optional `public_body`, `internal_body`, `tags`)
- `POST /mock/tickets/{id}/update` — update ticket attributes, update `public_body`, optionally `remove_internal` and `clear_attachments`
- `POST /mock/tickets/{id}/delete` — hard-delete a ticket
- `POST /mock/kb/{answerId}/archive` — archive a Knowledge Base answer
- `POST /mock/kb/{answerId}/update` — update a Knowledge Base answer's title/body
- `POST /mock/faults` — queue transient HTTP errors (e.g. `429` with `Retry-After`) to test client retries

## Running Locally

```bash
ZAMMAD_PORT=18390 python3 oc-zammad-repository-connector/zammad-mock/zammad_mock.py
```
