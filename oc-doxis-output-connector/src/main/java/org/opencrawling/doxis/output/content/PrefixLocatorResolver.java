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

import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Default {@link LocatorResolver}:
 * <ol>
 *   <li>an explicit locator in the document metadata ({@code locator.metadata-key}, default {@code doxisLocator}) wins —
 *       for sources or staging steps that already know where the binary sits in the data store;</li>
 *   <li>otherwise, if the document URI starts with {@code locator.uri-prefix} (e.g. {@code file:///mnt/doxis-store/}), the
 *       URL-decoded remainder, prefixed with {@code locator.prefix}, becomes the locator — for content crawled in place from a
 *       share that is the Doxis file data store.</li>
 * </ol>
 */
public class PrefixLocatorResolver implements LocatorResolver {

    private final DoxisOutputProperties.Locator config;

    public PrefixLocatorResolver(DoxisOutputProperties.Locator config) {
        this.config = config;
    }

    @Override
    public Optional<String> resolve(RepositoryDocument document) {
        if (document.metadata() != null) {
            List<String> explicit = document.metadata().get(config.metadataKey());
            if (explicit != null && !explicit.isEmpty() && explicit.getFirst() != null && !explicit.getFirst().isBlank()) {
                return Optional.of(explicit.getFirst().strip());
            }
        }
        String uriPrefix = config.uriPrefix();
        String uri = document.uri();
        if (uriPrefix == null || uriPrefix.isBlank() || uri == null || !uri.startsWith(uriPrefix)) {
            return Optional.empty();
        }
        String remainder = URLDecoder.decode(uri.substring(uriPrefix.length()).replace("+", "%2B"), StandardCharsets.UTF_8);
        if (remainder.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(config.prefix() + remainder);
    }
}
