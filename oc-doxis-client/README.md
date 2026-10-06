# OpenCrawling - Doxis Client

The shared **Doxis 4 CSB REST client** for the OpenCrawling Doxis connectors: `oc-doxis-output-connector` (archiving, #128) and the Doxis repository connector (#122).

## Feature Overview

1. **`DoxisClient`** (`org.opencrawling.doxis.client`) is a Java `HttpClient`-based REST client for `…/restws/publicws/rest/api/v1`. It covers:
   - JWT login with role and logout;
   - CQL document and record search (the follow-up search result is always closed);
   - multipart document create and versioning, streamed;
   - version read-back;
   - descriptor updates;
   - document and record permissions;
   - e-file (record) create, primary parent, folder nodes;
   - logical and physical delete.
2. **Resilience:** a 401 triggers one re-login. HTTP `429` (honouring `Retry-After`), `502`, `503`, `504` and connection failures are retried with backoff, but only for requests whose body can be rebuilt. Errors surface as **`DoxisApiException`**, which carries the HTTP status and the CSB error code (e.g. `SECU0014`, `INSTANCE0207`).
3. **`DoxisSchema`** (`org.opencrawling.doxis.client.schema`) is a cached, read-only view of the server schema. It resolves:
   - document classes by name or uuid;
   - e-file classes (`schemaMetaType RECORD`);
   - descriptors by name, uuid or CQL short name, with data type and length;
   - users and groups;
   - allowed MIME types per class.

   No short names or lengths are hard-coded.
4. **`ContentBody`** is a streaming upload body (file, stream or bytes) with a known length.

## Usage

```xml
<dependency>
    <groupId>org.opencrawling</groupId>
    <artifactId>oc-doxis-client</artifactId>
    <version>${project.version}</version>
</dependency>
```

```java
DoxisClient client = new DoxisClient(baseUrl, "faststarter", "crawler", password, "admins", "OpenCrawling",
        Duration.ofSeconds(120), 3);
DoxisSchema schema = new DoxisSchema(client);
List<String> ids = client.searchDocumentIds("SELECT * FROM D_TEXTER WHERE OBJECTNUMBER2 = 'x'", false);
client.logout();   // technical sessions are licence-capped: always log out
```

## Testing

```bash
mvn test -pl oc-doxis-client
```

The tests run against `MockWebServer` with response fixtures modelled on a CSB 14.4.1 server (`src/test/resources/doxis-mock-responses/`). They cover the wire contract: paths, headers, multipart bodies, error codes, retry and re-login.
