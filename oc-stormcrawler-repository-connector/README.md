# OpenCrawling - StormCrawler Repository Connector

The `oc-stormcrawler-repository-connector` module allows OpenCrawling to orchestrate, monitor, and stream web crawls using **Apache StormCrawler** and **Apache Storm Nimbus**.

## Features
- **Storm Nimbus REST API Management**: Interact with Nimbus to query cluster health (`/api/v1/cluster/summary`), retrieve topology states (`/api/v1/topology/summary`), activate, deactivate, or kill topologies.
- **Enterprise Web Crawling & Politeness**: Adheres to configurable crawl delays (`delayMs`), custom user agent headers, and URL inclusion/exclusion regex filters.
- **Full OIS Document Lifecycle**: Generates OIS `RepositoryDocument` instances with `action: UPSERT` for fetched web pages and `createTombstone` with `action: DELETE` when web pages are removed or return HTTP 404/410.
- **High Concurrency**: Parallelized crawling using **Java 25 Virtual Threads** and **StructuredTaskScope**.

## Configuration Properties

| Property key | Environment Variable | Default | Description |
|---|---|---|---|
| `spring.opencrawling.connector.stormcrawler.nimbus-host` | `STORMCRAWLER_NIMBUS_HOST` | `localhost` | Apache Storm Nimbus host |
| `spring.opencrawling.connector.stormcrawler.nimbus-port` | `STORMCRAWLER_NIMBUS_PORT` | `6627` | Nimbus Thrift service port |
| `spring.opencrawling.connector.stormcrawler.nimbus-rest-url` | `STORMCRAWLER_NIMBUS_REST_URL` | `http://localhost:8080` | Storm UI / Nimbus REST API base URL |
| `spring.opencrawling.connector.stormcrawler.topology-name` | `STORMCRAWLER_TOPOLOGY_NAME` | `opencrawling-web-crawler` | StormCrawler topology name |
| `spring.opencrawling.connector.stormcrawler.seeds` | `STORMCRAWLER_SEEDS` | `https://docs.example.com` | Comma-separated seed URLs to crawl |
| `spring.opencrawling.connector.stormcrawler.concurrency` | `STORMCRAWLER_CONCURRENCY` | `8` | Parallel virtual threads for crawling |
| `spring.opencrawling.connector.stormcrawler.delay-ms` | `STORMCRAWLER_DELAY_MS` | `1000` | Politeness delay between requests in milliseconds |
| `spring.opencrawling.connector.stormcrawler.ignore-robots-txt` | `STORMCRAWLER_IGNORE_ROBOTS_TXT` | `false` | Whether to ignore robots.txt rules |
| `spring.opencrawling.connector.stormcrawler.custom-user-agent` | `STORMCRAWLER_USER_AGENT` | `OpenCrawling-StormCrawler-Bot/1.0` | Custom User-Agent header |

## Example Spring Boot Configuration (`application.yml`)

```yaml
spring:
  opencrawling:
    connector:
      type: stormcrawler
      stormcrawler:
        nimbus-host: "localhost"
        nimbus-port: 6627
        nimbus-rest-url: "http://localhost:8080"
        topology-name: "opencrawling-web-crawler"
        concurrency: 8
        delay-ms: 1000
        seeds:
          - "https://docs.example.com"
          - "https://developer.example.com"
        custom-user-agent: "OpenCrawling-StormCrawler-Bot/1.0"
        exclude-patterns:
          - ".*\\.(pdf|zip|gz|exe)$"
```

## Docker Support

You can spin up a standalone Apache Storm cluster (Zookeeper, Nimbus, Supervisor, Storm UI) for testing:

```bash
docker compose -f oc-stormcrawler-repository-connector/docker/docker-compose.yml up -d
```

To spin up the entire decoupled OpenCrawling stack orchestrated with Apache Storm:

```bash
docker compose -f oc-stormcrawler-repository-connector/docker/docker-compose-decoupled-with-stormcrawler.yml up -d
```
