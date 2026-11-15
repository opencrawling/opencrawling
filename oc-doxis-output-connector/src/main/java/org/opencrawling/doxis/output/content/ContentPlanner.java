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
import org.opencrawling.doxis.output.DoxisConstants;
import org.opencrawling.doxis.client.ContentBody;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Chooses, per document, how its binary reaches Doxis. Only file metadata is inspected (size via {@code stat} for
 * {@code file:} URIs, size/hash from OIS metadata); content is never read here, so multi-terabyte files cost nothing to plan.
 */
public class ContentPlanner {

    private static final Logger log = LoggerFactory.getLogger(ContentPlanner.class);

    private final DoxisOutputProperties.Content config;
    private final LocatorResolver locatorResolver;
    private final ContentLinkResolver contentLinkResolver;
    private final boolean contentLinksAvailable;

    public ContentPlanner(DoxisOutputProperties.Content config, LocatorResolver locatorResolver) {
        this(config, locatorResolver, null, false);
    }

    /**
     * @param contentLinksAvailable whether a {@link ContentLinkWriter} is configured; without one, {@code AUTO} never plans a
     *                              content link and an explicit {@code CONTENT_LINK} strategy fails
     */
    public ContentPlanner(DoxisOutputProperties.Content config, LocatorResolver locatorResolver,
                          ContentLinkResolver contentLinkResolver, boolean contentLinksAvailable) {
        this.config = config;
        this.locatorResolver = locatorResolver;
        this.contentLinkResolver = contentLinkResolver;
        this.contentLinksAvailable = contentLinksAvailable && contentLinkResolver != null;
    }

    public ContentPlan plan(RepositoryDocument document) {
        Map<String, List<String>> metadata = document.metadata() != null ? document.metadata() : Map.of();
        String fileName = fileName(document, metadata);
        String mimeType = mimeType(metadata, fileName);
        Optional<Path> localFile = localFile(document.uri());
        Long length = length(metadata, localFile);
        String sha256 = first(metadata, DoxisConstants.META_SHA256);
        if (sha256 == null) {
            sha256 = first(metadata, DoxisConstants.META_HASH_VALUE);
        }
        Optional<String> locator = locatorResolver.resolve(document);
        Optional<ContentLinkResolver.Target> link = contentLinkResolver != null ? contentLinkResolver.resolve(document) : Optional.empty();

        ContentStrategy strategy = switch (config.strategy()) {
            case UPLOAD -> {
                if (length != null && length > config.uploadMaxBytes()) {
                    throw new IllegalStateException("Document " + document.id() + " is " + length
                            + " bytes, above content.upload-max-bytes (" + config.uploadMaxBytes() + ") for strategy UPLOAD");
                }
                yield ContentStrategy.UPLOAD;
            }
            case PREDEFINED_LOCATOR -> {
                if (locator.isEmpty()) {
                    throw new IllegalStateException("No predefined locator resolvable for document " + document.id()
                            + " (uri " + document.uri() + "); set metadata '" + "locator.metadata-key" + "' or locator.uri-prefix");
                }
                yield ContentStrategy.PREDEFINED_LOCATOR;
            }
            case REFERENCE_ONLY -> ContentStrategy.REFERENCE_ONLY;
            case CONTENT_LINK -> {
                if (!contentLinksAvailable) {
                    throw new IllegalStateException("Content strategy CONTENT_LINK requires content-link.client-lib-dir "
                            + "(oc-doxis-blueline-content-link + SER Doxis client jars)");
                }
                if (link.isEmpty()) {
                    throw new IllegalStateException("No content link resolvable for document " + document.id() + " (uri "
                            + document.uri() + "); set content-link.uri-prefix/link-prefix or metadata 'doxisContentLink'");
                }
                yield ContentStrategy.CONTENT_LINK;
            }
            case AUTO -> {
                if (locator.isPresent()) {
                    yield ContentStrategy.PREDEFINED_LOCATOR;
                }
                if (length == null || length <= config.uploadMaxBytes()) {
                    yield ContentStrategy.UPLOAD;
                }
                if (contentLinksAvailable && link.isPresent()) {
                    log.info("Document {} is {} bytes (> {}), linking it in place: {}", document.id(), length,
                            config.uploadMaxBytes(), link.get().link());
                    yield ContentStrategy.CONTENT_LINK;
                }
                log.info("Document {} is {} bytes (> {}), using fallback strategy {}.", document.id(), length,
                        config.uploadMaxBytes(), config.fallback());
                yield config.fallback();
            }
        };

        if (strategy == ContentStrategy.UPLOAD && length == null && document.contentStream() == null && localFile.isEmpty()) {
            log.info("Document {} has no readable content, archiving it as reference only.", document.id());
            strategy = ContentStrategy.REFERENCE_ONLY;
        }

        ContentBody body = null;
        if (strategy == ContentStrategy.UPLOAD) {
            body = uploadBody(document, fileName, mimeType, localFile);
        } else {
            closeQuietly(document.contentStream());
        }
        return new ContentPlan(strategy, fileName, mimeType, length, sha256,
                strategy == ContentStrategy.PREDEFINED_LOCATOR ? locator.orElseThrow() : null, document.uri(), body,
                strategy == ContentStrategy.CONTENT_LINK ? link.orElseThrow() : null);
    }

    private ContentBody uploadBody(RepositoryDocument document, String fileName, String mimeType, Optional<Path> localFile) {
        if (localFile.isPresent()) {
            // Reopenable: lets the client retry after throttling / session expiry without buffering the file.
            closeQuietly(document.contentStream());
            Path path = localFile.get();
            Supplier<InputStream> supplier = () -> {
                try {
                    return Files.newInputStream(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
            return new ContentBody(fileName, mimeType, supplier, true);
        }
        InputStream stream = document.contentStream();
        return new ContentBody(fileName, mimeType, () -> stream, false);
    }

    private static Optional<Path> localFile(String uri) {
        if (uri == null || !uri.startsWith("file:")) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(URI.create(uri));
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Long length(Map<String, List<String>> metadata, Optional<Path> localFile) {
        for (String key : List.of(DoxisConstants.META_SIZE, DoxisConstants.META_CONTENT_LENGTH)) {
            String value = first(metadata, key);
            if (value != null) {
                try {
                    return Long.parseLong(value.strip());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        if (localFile.isPresent()) {
            try {
                return Files.size(localFile.get());
            } catch (IOException ignored) {
                // unknown
            }
        }
        return null;
    }

    private static String fileName(RepositoryDocument document, Map<String, List<String>> metadata) {
        String name = first(metadata, DoxisConstants.META_NAME);
        if (name != null) {
            return name;
        }
        String uri = document.uri();
        if (uri != null) {
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1) {
                return java.net.URLDecoder.decode(path.substring(slash + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return document.id();
    }

    private static String mimeType(Map<String, List<String>> metadata, String fileName) {
        String mimeType = first(metadata, DoxisConstants.META_MIME_TYPE);
        if (mimeType == null && fileName != null) {
            mimeType = URLConnection.guessContentTypeFromName(fileName);
        }
        return mimeType != null ? mimeType : DoxisConstants.DEFAULT_MIME_TYPE;
    }

    private static String first(Map<String, List<String>> metadata, String key) {
        List<String> values = metadata.get(key);
        if (values == null || values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) {
            return null;
        }
        return values.getFirst();
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
