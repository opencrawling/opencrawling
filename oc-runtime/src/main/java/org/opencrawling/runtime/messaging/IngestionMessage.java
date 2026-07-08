package org.opencrawling.runtime.messaging;

import java.util.List;
import java.util.Map;

/**
 * Message payload sent to Kafka to trigger vector store processing.
 * Follows the Claim Check pattern: carries metadata and URI, consumer pulls content.
 */
public record IngestionMessage(
    String documentId,
    String uri,
    Map<String, List<String>> metadata,
    String acl,
    String lastModified
) {}
