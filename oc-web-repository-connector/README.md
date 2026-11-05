# OpenCrawling Web Repository Connector

Crawls websites and intranets from seed URLs and sends each page into the OpenCrawling pipeline (claim check, Tika, chunking, embedding, vector store). This is Phase 1a of the [web crawler specification](../docs/Web-Crawler-Specification.md), which delivers [issue #38](https://github.com/OpenCrawling/opencrawling/issues/38).

## How it relates to the StormCrawler connector

OpenCrawling also has `oc-stormcrawler-repository-connector`, which drives a separate Apache Storm cluster. Use that one for large, continuous crawls. This connector runs inside OpenCrawling itself with no extra infrastructure, which suits documentation sites, intranets and other crawls of up to a few thousand pages per run.

## What it does

- Follows links from the seeds and from sitemaps (explicit, or the `Sitemap:` lines in robots.txt).
- Respects robots.txt (RFC 9309, via crawler-commons), including `Crawl-delay`, page-level `noindex`/`nofollow` and `X-Robots-Tag`.
- Waits at least `delayMs` between requests to the same host. All jobs in the process share this budget, and robots.txt is fetched once per host.
- Normalizes URLs (fragments, default ports, tracking parameters like `utm_*`, session IDs) and indexes each page once. It follows `rel=canonical` and skips pages with identical content.
- Crawls HTML, PDF and Office documents. HTML is parsed with jsoup only for links and metadata. Text extraction stays in Tika downstream.
- Blocks requests to internal networks (loopback, RFC 1918, link-local including `169.254.169.254`, and more) on every request and redirect hop, unless you allow specific hosts or ranges.

Crawl state is kept in memory for one run. Every run is a full crawl capped by `maxPages`. Resuming, incremental recrawls and deleting pages that disappear come in Phase 1b.

## Configuration

Set each setting in one of these places. The first one found wins:

1. the job's `crawlSpec` (JSON, through `POST /api/jobs`),
2. the connector configuration (the Admin UI connector form, or `connectors.json`),
3. a Spring property `spring.opencrawling.connector.web.<kebab-case-name>`, for example `spring.opencrawling.connector.web.max-depth=5`,
4. the default.

| Setting | Default | Description |
|---|---|---|
| `seeds` | the job path | Start URLs. The job path may list several, separated by spaces or new lines. |
| `maxDepth` | `3` | Maximum link hops from a seed (0 to 50) |
| `delayMs` | `1000` | Minimum time between requests to one host. A robots.txt `Crawl-delay` can raise it. |
| `stayOnDomain` | `true` | Stay on the seeds' registrable domain, subdomains included. `false` allows any host. |
| `scope` | | Overrides `stayOnDomain`: `HOST`, `DOMAIN`, `PREFIX` (the seed's directory) or `ANY` |
| `userAgent` | `OpenCrawlingBot/1.0 (+https://opencrawling.github.io)` | Must contain a contact URL or email address |
| `respectRobots` | `true` | `false` ignores robots.txt. Use it only for sites you own. Every run logs a warning. |
| `maxPages` | `1000` | Maximum page requests per run |
| `includePatterns` / `excludePatterns` | none | Regexes (RE2/J syntax) matched against the path and query. Excludes win. A seed that doesn't match is still crawled for its links but isn't indexed. |
| `sitemapUrls` | none | Extra sitemaps to read |
| `discoverSitemaps` | `true` | Read `Sitemap:` lines from the seeds' robots.txt |
| `maxContentBytes` | `52428800` | Larger responses are skipped |
| `acceptMimeTypes` | HTML, text, Markdown, PDF, Office, OpenDocument, RTF | Which responses are indexed |
| `allowPrivateNetworks` / `privateAllowlist` | `false` / none | Allow crawling internal hosts. The allowlist lists host names (`intranet.example.com`, `.example.internal`) or CIDR ranges (`10.20.0.0/16`). |
| `aclMode` | `public` | `public`, or `static` with `aclIdentity` (the user or group allowed to read the documents). Required when `allowPrivateNetworks` is on. `public` then also needs `confirmPublicExposure: true`. |
| `stripQueryParams` | | Extra query parameters to remove, added to `utm_*`, `fbclid`, `gclid`, `msclkid` and session IDs |
| `maxThreads` | `16` | Worker virtual threads per crawl |
| `connectTimeoutMs` / `readTimeoutMs` | `10000` / `30000` | HTTP timeouts |
| `maxRedirects` / `maxRetries` | `5` / `2` | Redirect hops per URL, and retries after 5xx, 429 or network errors |

Example job:

```json
{
  "name": "Product docs",
  "repositoryConnector": "Web_Crawler",
  "outputConnector": "PGVector_Output",
  "path": "https://docs.example.com/",
  "crawlSpec": {
    "maxDepth": 4,
    "maxPages": 5000,
    "includePatterns": ["^/docs/"],
    "excludePatterns": ["\\.(png|jpe?g|gif|svg|css|js)$"]
  }
}
```

Invalid settings are rejected when the job is saved (HTTP 400, with every problem listed).

## Document metadata

| Key | Example |
|---|---|
| `name`, `mimeType`, `source` | `features.html`, `text/html`, `web` |
| `title`, `description`, `language`, `keywords` | from the page |
| `http_status`, `http_server`, `http_etag`, `http_last_modified`, `http_content_length` | from the response |
| `web_host`, `web_canonical_url`, `web_depth`, `web_referrer`, `web_seed`, `web_anchor_text` | crawl details |
| `web_content_hash`, `web_fetched_at`, `jobId` | |

The document ID is `web:` followed by the SHA-256 of the job ID and the URL, so it stays stable across runs. The last-modified time comes from the `Last-Modified` header, then `<meta property="article:modified_time">`, then the fetch time.

## Testing

```bash
mvn test -pl oc-web-repository-connector
```

`WebRepositoryConnectorTest` crawls a small website served on `127.0.0.1` by the JDK's HTTP server. It covers robots.txt, sitemaps, depth, filters, redirect loops, canonical and duplicate pages, `noindex`, size limits, `maxPages`, SSRF blocking, and the politeness delay within one job and across two concurrent jobs.
