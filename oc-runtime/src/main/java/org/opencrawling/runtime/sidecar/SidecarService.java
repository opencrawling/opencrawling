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
package org.opencrawling.runtime.sidecar;

import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reads the OIS sidecars of registered Ozone and S3 output connectors, as far as the caller may read them.
 *
 * <ul>
 *   <li>Only connectors listed in {@code opencrawling.sidecars.connectors} are read, and only under the key prefix
 *   they write to. Only {@code *.ois.json} objects are read; the writers' tombstones and key index are skipped.</li>
 *   <li>Each sidecar is decided by the runtime's {@link DocumentAccessGate} on its document id and its
 *   {@code security}, exactly as search decides on the indexed document, in batches. A sidecar with no permissions
 *   is never returned. Sidecars that are not UPSERTs, not valid or too large are skipped.</li>
 *   <li>Answers never say how many sidecars were hidden, and the paging cursor is sealed, so the keys of hidden
 *   sidecars do not leak. Permissions are not returned.</li>
 *   <li>Every request is bounded: the sidecars it returns, the keys it lists and the bytes it returns.</li>
 * </ul>
 */
public class SidecarService {

    private static final Logger log = LoggerFactory.getLogger(SidecarService.class);

    static final int MAX_PREFIX_LENGTH = 1024;
    /** The most bytes of sidecars one answer returns. */
    static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_GROUPS = 50;
    private static final int MAX_BATCH = 100;
    private static final List<String> INTERNAL_PREFIXES = List.of(".tombstones/", ".opencrawling/");

    /** Opens a read-only store on a connector's bucket. */
    @FunctionalInterface
    public interface StoreOpener {
        SidecarStore open(ConnectorDTO connector) throws Exception;
    }

    /** A registered store whose sidecars may be read. */
    public record StoreInfo(String name, String description, String kind) {}

    /**
     * One sidecar: where it is, what document it describes, and that document's metadata. Permissions are left out.
     */
    public record SidecarView(String key, String documentId, String uri, String lastModified, String sourceType,
                              String originalUri, String originalPath, String filename, String mimeType,
                              Long contentLength, String checksumSha256, Map<String, List<String>> metadata) {}

    /** Totals over the sidecars of this page (only those the caller may read). */
    public record Aggregates(int count, long contentBytes, Map<String, Long> byMimeType, Map<String, Long> bySourceType) {}

    /**
     * A page of sidecars.
     *
     * @param nextCursor        pass it back for the next page; {@code null} when there is none
     * @param scanLimitReached  whether the page stopped at the listing bound rather than at {@code limit}
     */
    public record SidecarPage(String connector, String prefix, List<SidecarView> sidecars, Aggregates aggregates,
                              String nextCursor, boolean scanLimitReached) {}

    /** The sidecar JSON, as the Ozone and S3 migration writers store it; other fields are ignored. */
    record Sidecar(String id, String action, String uri, String lastModified, Source source, Content contentRef,
                   Map<String, List<String>> metadata, SecurityConfig security, String acl) {

        record Source(String type, String originalUri, String originalPath) {}

        record Content(String key, String filename, String mimeType, Long contentLength, String checksumSha256) {}
    }

    private record Candidate(String key, Sidecar sidecar, int bytes) {}

    private final SidecarProperties properties;
    private final Supplier<List<ConnectorDTO>> connectors;
    private final StoreOpener opener;
    private final DocumentAccessGate gate;
    private final SidecarCursor cursors;
    private final JsonMapper json = JsonMapper.builder().build();

    public SidecarService(SidecarProperties properties, Supplier<List<ConnectorDTO>> connectors, StoreOpener opener,
                          DocumentAccessGate gate, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.connectors = Objects.requireNonNull(connectors, "connectors");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.gate = Objects.requireNonNull(gate, "gate");
        String secret = properties.getCursorSecret();
        if (secret != null && !secret.isBlank() && secret.strip().length() < SidecarProperties.MIN_CURSOR_SECRET_LENGTH) {
            throw new IllegalArgumentException(SidecarProperties.PREFIX + ".cursor-secret must be at least "
                    + SidecarProperties.MIN_CURSOR_SECRET_LENGTH + " characters.");
        }
        this.cursors = new SidecarCursor(secret, properties.getCursorTtl(), clock);
    }

    /** The stores the caller may ask about: the listed Ozone and S3 connectors that are registered. */
    public List<StoreInfo> listStores(Caller caller) {
        requireCaller(caller);
        List<StoreInfo> stores = new ArrayList<>();
        for (ConnectorDTO connector : registered()) {
            if (listed(connector)) {
                stores.add(new StoreInfo(connector.name(), connector.description(),
                        org.opencrawling.runtime.connector.OutputKind.of(connector.className()).name().toLowerCase(java.util.Locale.ROOT)));
            }
        }
        return stores;
    }

    /**
     * A page of the sidecars under {@code prefix} that the caller may read, in key order.
     *
     * @param prefix a key prefix, relative to the prefix the connector writes under
     * @param cursor the previous page's {@code nextCursor}
     */
    public SidecarPage read(Caller caller, String connectorName, String prefix, Integer limit, String cursor) {
        requireCaller(caller);
        ConnectorDTO connector = connector(connectorName);
        SidecarStores.Layout layout = SidecarStores.layout(connector);
        String relative = checkedPrefix(prefix);
        String fullPrefix = layout.basePrefix() + relative;
        String callerKey = (caller.tenant() != null ? caller.tenant() : "") + "/" + caller.principal();
        String startAfter = null;
        if (cursor != null && !cursor.isBlank()) {
            SidecarCursor.Position position = cursors.open(cursor);
            if (position == null || !position.connector().equals(connector.name()) || !position.caller().equals(callerKey)
                    || !position.prefix().equals(fullPrefix)) {
                throw new SidecarException(HttpStatus.BAD_REQUEST,
                        "The cursor is not valid for this request (or has expired); start again without it.");
            }
            startAfter = position.startAfter();
        }
        int wanted = properties.limit(limit);
        int maxScan = properties.effectiveMaxScan();
        int maxBytes = properties.effectiveMaxSidecarBytes();

        List<SidecarView> found = new ArrayList<>();
        long returnedBytes = 0;
        int scanned = 0;
        String last = startAfter;
        boolean more = true;
        boolean scanLimitReached = false;
        try (SidecarStore store = opener.open(connector)) {
            pages:
            while (found.size() < wanted && returnedBytes < MAX_RESPONSE_BYTES) {
                int pageSize = Math.min(1000, maxScan - scanned);
                if (pageSize <= 0) {
                    scanLimitReached = true;
                    break;
                }
                SidecarStore.Page page = store.list(fullPrefix, last, pageSize);
                if (page.keys().isEmpty()) {
                    more = false;
                    break;
                }
                scanned += page.keys().size();
                List<String> keys = page.keys();
                int index = 0;
                while (index < keys.size()) {
                    // A batch of keys: read the sidecars among them, then decide them together.
                    int batchSize = Math.min(MAX_BATCH, Math.max(16, (wanted - found.size()) * 2));
                    int end = Math.min(keys.size(), index + batchSize);
                    List<String> batch = keys.subList(index, end);
                    Map<String, Candidate> candidates = new HashMap<>();
                    List<Candidate> toDecide = new ArrayList<>();
                    for (String key : batch) {
                        Candidate candidate = candidate(store, key, layout.suffix(), maxBytes);
                        if (candidate != null) {
                            candidates.put(key, candidate);
                            toDecide.add(candidate);
                        }
                    }
                    List<Candidate> readable = gate.filter(caller, toDecide, SidecarService::resourceRef);
                    java.util.Set<String> readableKeys = new java.util.HashSet<>();
                    readable.forEach(candidate -> readableKeys.add(candidate.key()));
                    for (String key : batch) {
                        last = key;
                        if (readableKeys.contains(key)) {
                            Candidate candidate = candidates.get(key);
                            found.add(view(candidate));
                            returnedBytes += candidate.bytes();
                            if (found.size() >= wanted || returnedBytes >= MAX_RESPONSE_BYTES) {
                                boolean restOfPage = !key.equals(keys.getLast());
                                more = restOfPage || page.truncated();
                                break pages;
                            }
                        }
                    }
                    index = end;
                }
                if (!page.truncated()) {
                    more = false;
                    break;
                }
            }
        } catch (AuthorizationUnavailableException e) {
            log.warn("Sidecars: access could not be decided for '{}' on '{}': {}", caller.principal(), connector.name(), e.getMessage());
            throw new SidecarException(HttpStatus.SERVICE_UNAVAILABLE, "Access could not be decided; nothing is returned.");
        } catch (SidecarException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Sidecars: the store of '{}' could not be read", connector.name(), e);
            throw new SidecarException(HttpStatus.BAD_GATEWAY, "The sidecar store could not be read.");
        }
        String next = more && last != null
                ? cursors.seal(new SidecarCursor.Position(connector.name(), callerKey, fullPrefix, last)) : null;
        log.info("Sidecars: '{}' read {} sidecar(s) of '{}' under '{}' ({} key(s) listed{}).", caller.principal(),
                found.size(), connector.name(), fullPrefix, scanned, next != null ? ", more follow" : "");
        return new SidecarPage(connector.name(), relative, List.copyOf(found), aggregates(found), next,
                scanLimitReached && next != null);
    }

    /** The sidecar under {@code key}, or {@code null} when it is not one, or not one that may be returned. */
    private Candidate candidate(SidecarStore store, String key, String suffix, int maxBytes) throws Exception {
        if (key == null || !key.endsWith(suffix) || INTERNAL_PREFIXES.stream().anyMatch(key::startsWith)) {
            return null;
        }
        byte[] bytes = store.read(key, maxBytes);
        if (bytes == null) {
            return null;
        }
        if (bytes.length > maxBytes) {
            log.warn("Sidecars: skipping '{}', larger than {} bytes.", key, maxBytes);
            return null;
        }
        Sidecar sidecar;
        try {
            sidecar = json.readValue(bytes, Sidecar.class);
        } catch (JacksonException e) {
            log.warn("Sidecars: skipping '{}', not a valid OIS sidecar.", key);
            return null;
        }
        if (sidecar == null || sidecar.id() == null || sidecar.id().isBlank()
                || (sidecar.action() != null && !"UPSERT".equalsIgnoreCase(sidecar.action()))) {
            return null;
        }
        // No permissions at all: never returned, whatever the authorization mode.
        if (sidecar.security() == null && (sidecar.acl() == null || sidecar.acl().isBlank())) {
            return null;
        }
        return new Candidate(key, sidecar, bytes.length);
    }

    private static ResourceRef resourceRef(Candidate candidate) {
        Sidecar sidecar = candidate.sidecar();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", sidecar.id());
        if (sidecar.security() != null) {
            metadata.put("security", sidecar.security());
        }
        if (sidecar.acl() != null && !sidecar.acl().isBlank()) {
            metadata.put("acl", sidecar.acl());
        }
        return new ResourceRef(sidecar.id(), metadata);
    }

    private static SidecarView view(Candidate candidate) {
        Sidecar sidecar = candidate.sidecar();
        Sidecar.Source source = sidecar.source();
        Sidecar.Content content = sidecar.contentRef();
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        if (sidecar.metadata() != null) {
            sidecar.metadata().forEach((key, values) -> {
                if (key != null && values != null && !isPermissionKey(key)) {
                    metadata.put(key, values);
                }
            });
        }
        return new SidecarView(candidate.key(), sidecar.id(), sidecar.uri(), sidecar.lastModified(),
                source != null ? source.type() : null, source != null ? source.originalUri() : null,
                source != null ? source.originalPath() : null, content != null ? content.filename() : null,
                content != null ? content.mimeType() : null, content != null ? content.contentLength() : null,
                content != null ? content.checksumSha256() : null, metadata);
    }

    /** Metadata keys that carry permissions; they are not returned. */
    static boolean isPermissionKey(String key) {
        return key.equals("acl") || key.equals("security") || key.equals("sys_acl") || key.startsWith("security_")
                || key.startsWith("acl_");
    }

    private static Aggregates aggregates(List<SidecarView> sidecars) {
        long bytes = 0;
        Map<String, Long> byMime = new LinkedHashMap<>();
        Map<String, Long> bySource = new LinkedHashMap<>();
        for (SidecarView sidecar : sidecars) {
            if (sidecar.contentLength() != null && sidecar.contentLength() > 0) {
                bytes += sidecar.contentLength();
            }
            count(byMime, sidecar.mimeType());
            count(bySource, sidecar.sourceType());
        }
        return new Aggregates(sidecars.size(), bytes, byMime, bySource);
    }

    private static void count(Map<String, Long> counts, String value) {
        String key = value != null && !value.isBlank() ? value : "unknown";
        if (counts.containsKey(key) || counts.size() < MAX_GROUPS) {
            counts.merge(key, 1L, Long::sum);
        } else {
            counts.merge("other", 1L, Long::sum);
        }
    }

    // ---- Checks ----

    private void requireCaller(Caller caller) {
        if (caller == null || (!caller.authenticated() && !properties.isAllowAssertedIdentity())) {
            throw new SidecarException(HttpStatus.UNAUTHORIZED, "Reading sidecars needs a caller verified by token "
                    + "(opencrawling.security.identity.mode=jwt; for the REST API also protect-api=true).");
        }
        if (caller.principal() == null || caller.principal().isBlank()) {
            throw new SidecarException(HttpStatus.FORBIDDEN, "The caller names no user.");
        }
        String scope = properties.getRequiredScope();
        if (caller.authenticated() && scope != null && !scope.isBlank()
                && !JwtCallerMapper.scopes(caller).contains(scope.strip())) {
            throw new SidecarException(HttpStatus.FORBIDDEN, "The token does not carry the scope '" + scope.strip() + "'.");
        }
    }

    private List<ConnectorDTO> registered() {
        List<ConnectorDTO> all = connectors.get();
        return all != null ? all : List.of();
    }

    private boolean listed(ConnectorDTO connector) {
        return connector != null && connector.name() != null && SidecarStores.supports(connector)
                && properties.getConnectors().stream().anyMatch(name -> name != null && name.strip().equals(connector.name()));
    }

    private ConnectorDTO connector(String name) {
        if (name == null || name.isBlank()) {
            throw new SidecarException(HttpStatus.BAD_REQUEST, "Name a registered Ozone or S3 output connector.");
        }
        return registered().stream().filter(connector -> name.strip().equals(connector.name()))
                .filter(this::listed).findFirst()
                .orElseThrow(() -> new SidecarException(HttpStatus.NOT_FOUND,
                        "No sidecar store named '" + name.strip() + "' is available."));
    }

    static String checkedPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String value = prefix.strip().replaceAll("^/+", "");
        if (value.length() > MAX_PREFIX_LENGTH || value.chars().anyMatch(Character::isISOControl)) {
            throw new SidecarException(HttpStatus.BAD_REQUEST, "The prefix may be at most " + MAX_PREFIX_LENGTH
                    + " characters, with no control characters.");
        }
        return value;
    }
}
