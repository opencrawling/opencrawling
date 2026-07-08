package org.opencrawling.core.document;

import java.io.InputStream;
import java.util.Map;
import java.util.List;
import java.time.Instant;

public record RepositoryDocument(
    String id,
    String uri,
    InputStream contentStream,
    Map<String, List<String>> metadata,
    String acl,
    Instant lastModified
) {}
