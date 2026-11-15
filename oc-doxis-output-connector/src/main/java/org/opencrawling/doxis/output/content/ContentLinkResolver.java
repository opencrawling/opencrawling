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
 * Derives the content link of a document that stays where it is:
 * <ol>
 *   <li>an explicit link in the metadata ({@code content-link.metadata-key}, default {@code doxisContentLink}) wins;</li>
 *   <li>otherwise a document URI under {@code content-link.uri-prefix} (e.g. {@code file:///mnt/archive/}) is rewritten to
 *       {@code content-link.link-prefix} + remainder (e.g. {@code \\fileserver\archive\}). For {@code UNC} links forward
 *       slashes in the remainder become backslashes.</li>
 * </ol>
 */
public class ContentLinkResolver {

    public record Target(ContentLinkWriter.LinkType type, String link) {
    }

    private final DoxisOutputProperties.ContentLink config;

    public ContentLinkResolver(DoxisOutputProperties.ContentLink config) {
        this.config = config;
    }

    public Optional<Target> resolve(RepositoryDocument document) {
        ContentLinkWriter.LinkType type = config.linkType();
        if (document.metadata() != null) {
            List<String> explicit = document.metadata().get(config.metadataKey());
            if (explicit != null && !explicit.isEmpty() && explicit.getFirst() != null && !explicit.getFirst().isBlank()) {
                return Optional.of(new Target(type, explicit.getFirst().strip()));
            }
        }
        String uri = document.uri();
        if (config.uriPrefix() == null || uri == null || !uri.startsWith(config.uriPrefix())) {
            return Optional.empty();
        }
        String remainder = URLDecoder.decode(uri.substring(config.uriPrefix().length()).replace("+", "%2B"), StandardCharsets.UTF_8);
        if (remainder.isBlank()) {
            return Optional.empty();
        }
        if (type == ContentLinkWriter.LinkType.UNC) {
            remainder = remainder.replace('/', '\\');
        }
        return Optional.of(new Target(type, config.linkPrefix() + remainder));
    }
}
