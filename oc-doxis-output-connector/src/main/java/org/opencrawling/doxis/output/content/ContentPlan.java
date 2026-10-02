/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.doxis.output.content;

import org.opencrawling.doxis.output.client.ContentBody;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;

/**
 * How one document's binary is handled: {@link ContentStrategy#UPLOAD} carries a streaming {@link #body()},
 * {@link ContentStrategy#PREDEFINED_LOCATOR} carries a {@link #locator()}, {@link ContentStrategy#CONTENT_LINK} carries a
 * {@link #contentLink()}, {@link ContentStrategy#REFERENCE_ONLY} carries
 * neither. {@code length} and {@code sha256} are null when unknown — the connector never reads content just to compute them.
 */
public record ContentPlan(
    ContentStrategy strategy,
    String fileName,
    String mimeType,
    Long length,
    String sha256,
    String locator,
    String sourceUri,
    ContentBody body,
    ContentLinkResolver.Target contentLink
) {
    public ContentPlan(ContentStrategy strategy, String fileName, String mimeType, Long length, String sha256, String locator,
                       String sourceUri, ContentBody body) {
        this(strategy, fileName, mimeType, length, sha256, locator, sourceUri, body, null);
    }

    public ContentPlan withMimeType(String newMimeType) {
        return new ContentPlan(strategy, fileName, newMimeType, length, sha256, locator, sourceUri, body, contentLink);
    }
}
