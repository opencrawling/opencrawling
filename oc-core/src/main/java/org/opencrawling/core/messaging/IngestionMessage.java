/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.core.messaging;

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
    String lastModified,
    String transformationConnector,
    String transformationEngine,
    Map<String, String> transformationConfig
) {}
