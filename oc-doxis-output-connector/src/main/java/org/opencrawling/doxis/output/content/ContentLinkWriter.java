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

import java.util.List;
import java.util.Map;

/**
 * Creates Doxis documents whose content is an external <em>content link</em> (URL or UNC path): the binary is never
 * transferred to, stored in or copied by Doxis. The Doxis CSB REST API (14.4.x) has no content-link operation, so
 * implementations use the SER Doxis Java (Blueline) API — see the optional {@code oc-doxis-blueline-content-link} module,
 * which is loaded at runtime from {@code content-link.client-lib-dir} because the SER client libraries cannot ship with
 * OpenCrawling.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader} and configured once via {@link #configure(Map)}.
 */
public interface ContentLinkWriter extends AutoCloseable {

    /** Link types supported by Doxis ({@code com.ser.blueline.ContentLinkType}). */
    enum LinkType { UNC, URL }

    /**
     * A descriptor value set: attribute definition UUID, Doxis data type ({@code STRING}, {@code DATE}, {@code INTEGER}, …) and
     * values already converted as for the REST API (dates as epoch millis, numbers as {@link Number}).
     */
    record Descriptor(String attributeDefinitionUuid, String dataType, List<Object> values) {
    }

    /**
     * A document to create: Doxis repository (database) short name, document class UUID, descriptors, and the link.
     */
    record Request(String repository, String documentTypeId, List<Descriptor> descriptors, LinkType linkType, String link) {
    }

    /**
     * Connection settings: {@code host}, {@code port}, {@code customer}, {@code username}, {@code password}.
     */
    void configure(Map<String, String> settings);

    /**
     * Creates and archives the document; returns its document UUID (as used by the REST API).
     */
    String createLinkedDocument(Request request) throws Exception;

    @Override
    void close();
}
