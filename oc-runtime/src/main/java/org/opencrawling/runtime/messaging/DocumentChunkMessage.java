package org.opencrawling.runtime.messaging;

import java.util.Map;

public record DocumentChunkMessage(
    String documentId,
    String chunkId,
    String text,
    Map<String, Object> metadata
) {}
