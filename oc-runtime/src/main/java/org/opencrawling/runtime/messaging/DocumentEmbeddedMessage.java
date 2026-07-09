package org.opencrawling.runtime.messaging;

import java.util.Map;

public record DocumentEmbeddedMessage(
    String documentId,
    String chunkId,
    String text,
    Map<String, Object> metadata,
    float[] embedding
) {}
