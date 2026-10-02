# OpenCrawling - Doxis Blueline Content Link (optional)

Zero-copy archiving of large or in-place binaries into **Doxis 4** for `oc-doxis-output-connector`.

The documents are created with an external **content link** (a UNC path or a URL) through the SER Doxis Java (Blueline) API, `IDocument.addContentLinkPartDocument()`. The binary is never transferred to, stored in or copied by Doxis. The Doxis CSB REST API 14.4.x has no content-link operation, which is why this module exists.

This module is **not part of the default build**. The SER Doxis client libraries are licensed by SER and are not distributed with OpenCrawling. Each installation builds and runs it against its own SER client jars.

## Feature Overview

1. **`BluelineContentLinkWriter`** implements the core `ContentLinkWriter` SPI, and is discovered through `META-INF/services`. It:
   - logs in once (ticket login, `ISERFactory.getCSBFactoryInstance()`);
   - creates the document in the configured repository and document class;
   - sets the typed descriptors;
   - adds the link and archives the document;
   - returns the REST document UUID.

   Calls are serialized, and an invalid session triggers one re-login.
2. **Isolated loading:** the core connector loads this jar and the SER client jars from `content-link.client-lib-dir` in a child-first class loader. Only the writer contract, SLF4J and the JDK are shared, so the client's own Spring, CXF and Jackson never clash with OpenCrawling's.
3. **Transport:** SOAP to `http://<csb-host>:<port>/sedna-transfer-service-xf/services/`, on the same port as the REST API (8080 by default). The host and port default to those of `base-url`.
4. **Everything else stays on REST:** after the writer creates the document, the core connector handles ACLs, verification and rollback, descriptor updates on re-crawl, and logical/physical deletes. A physical delete of a content-link document leaves the external file untouched (verified on CSB 14.4.1).

## Prerequisites in Doxis

- **The document class allows content links:** in Doxis Designer, open *DMS → Document classes →* your class *→ Object inspector* and set **Allow linking contents = True**. Otherwise the writer fails with a clear error.
- **For large / external files**, configure a dedicated class this way:
  - content indexing off;
  - **Creation of representations** = none;
  - thumbnails and other additional items off;
  - no ML model;
  - *File from file system → File deletion → Delete files* = **False**.
- **Linked files are opened by whoever opens the document.** The CSB does not stream linked content (REST `GET …/contentObjects/{id}` returns `SEDNA0104`). UNC shares must be reachable by the users' clients.

## Build & Install

```bash
# 1. Install the SER "blueline" API jar (from your SER Doxis client, e.g. a webCube WEB-INF/lib) into your Maven repository
mvn install:install-file -Dfile=/path/to/WEB-INF/lib/blueline-14.4.1-1.jar \
    -DgroupId=de.ser.doxis4.java-api -DartifactId=blueline -Dversion=14.4.1-1 -Dpackaging=jar -DgeneratePom=true

# 2. Build the module (JDK 25)
mvn -Pdoxis-blueline install -pl oc-doxis-blueline-content-link

# 3. Assemble the client directory: every SER client jar plus this module
mkdir -p /opt/doxis-client-lib
cp /path/to/WEB-INF/lib/*.jar /opt/doxis-client-lib/
cp oc-doxis-blueline-content-link/target/oc-doxis-blueline-content-link-1.0.0-SNAPSHOT.jar /opt/doxis-client-lib/
```

The client jars are Java 17 bytecode (14.4.1-1). Their Maven coordinates are `de.ser.doxis4.java-api:blueline`, `de.ser.doxis4.java-api:sednaclient-blueline` and `de.ser.doxis4.csb.client:sednaclient-api-nodep`.

## Configuration

```yaml
spring:
  opencrawling:
    output:
      doxis:
        content:
          strategy: AUTO                    # or CONTENT_LINK to link everything that resolves
          upload-max-bytes: 2147483648      # AUTO: files above this are linked instead of uploaded
        content-link:
          client-lib-dir: /opt/doxis-client-lib
          uri-prefix: "file:///mnt/archive/"          # crawled path prefix ...
          link-prefix: "\\\\fileserver\\archive\\"    # ... becomes this UNC prefix (forward slashes -> backslashes)
          link-type: UNC                    # or URL
          document-type: TX_LargeExternalDocument   # optional, defaults to the main document-type
```

An explicit `doxisContentLink` metadata value overrides the prefix mapping. The admin UI uses the keys `doxisContentLinkClientLibDir`, `doxisContentLinkUriPrefix`, `doxisContentLinkPrefix`, `doxisContentLinkType`, `doxisContentLinkDocumentType`, `doxisContentLinkCsbHost` and `doxisContentLinkCsbPort`.

## Testing & Execution

```bash
mvn -Pdoxis-blueline test -pl oc-doxis-blueline-content-link
```

The unit tests cover compound-id → UUID extraction, value typing and service registration. The full path was verified live on 2026-10-02 against the SER training CSB (14.4.1), `D_TEXTER` / `TX_MigratedDocument`, with a UNC link to `\\BN-PR-DEV\oc-link-test\test.txt`. A 5 TB `sizeInBytes` made `AUTO` link the file; then create, verify, a re-crawl updating descriptors, and logical plus physical delete all succeeded. The external file survived the deletes.

## Known Limitations

- New *versions* of a content-link document are not created. A re-crawl updates the descriptors and keeps the link. If the source file moves, delete the document and recreate it.
- Filing into a record (e-file) is not applied to content-link documents yet.
- Content-link creation goes through one Blueline session per writer, with calls serialized. Throughput tuning is still open with SER.
- Link and lifecycle semantics are based on our tests on 14.4.1; SER's confirmation is pending.
