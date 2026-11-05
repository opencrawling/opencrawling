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
package org.opencrawling.runtime.discovery;

import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.connector.discovery.CountAccuracy;
import org.opencrawling.core.connector.discovery.DiscoverableRepositoryConnector;
import org.opencrawling.core.connector.discovery.DiscoveryException;
import org.opencrawling.core.connector.discovery.DiscoveryNode;
import org.opencrawling.core.connector.discovery.DiscoveryResult;
import org.opencrawling.core.connector.discovery.DryRunRequest;
import org.opencrawling.core.connector.discovery.DryRunResult;
import org.opencrawling.core.connector.discovery.ExploreRequest;
import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.authorization.MetadataAuthorizationProvider;
import org.opencrawling.runtime.connector.ConnectorResolutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Lists registered repositories' trees and previews crawls on behalf of a caller.
 *
 * <p>What the caller sees is decided with the metadata rules on the source repository's own ACL, through a
 * {@link DocumentAccessGate} built on {@link MetadataAuthorizationProvider}, whatever authorization mode the
 * runtime is configured with. A relationship provider such as OpenFGA only knows documents that have been indexed,
 * so it would hide every folder and every document not yet ingested. The cost: group memberships held only in
 * OpenFGA are not taken into account here, so a caller may see less than it may read, never more.
 *
 * <p>Results never include an item's ACL, and name nothing the caller cannot see.
 */
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    static final String QUERY_COUNT_WITHHELD = "A query's matches cannot be checked one by one, so no count is given; "
            + "only samples you may read are returned.";
    static final String QUERY_COUNT_ESTIMATE = "Counts every match, including documents you may not be able to read.";

    private final Supplier<List<ConnectorDTO>> connectors;
    private final Function<ConnectorDTO, RepositoryConnector> builder;
    private final DiscoveryProperties properties;
    private final DocumentAccessGate gate = new DocumentAccessGate(new MetadataAuthorizationProvider());

    /**
     * @param connectors the registered connectors
     * @param builder    builds a connector from its registration (the job API's {@code ConnectorFactory})
     */
    public DiscoveryService(Supplier<List<ConnectorDTO>> connectors, Function<ConnectorDTO, RepositoryConnector> builder,
                            DiscoveryProperties properties) {
        this.connectors = Objects.requireNonNull(connectors, "connectors");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** A connector that supports discovery. Its configuration (endpoints, credentials) is never returned. */
    public record ConnectorInfo(String name, String description) {}

    /** A discovered item as returned to a caller: no ACL. */
    public record NodeView(String id, String sourceId, String parentId, String name, String path, String type,
                           int depth, String mimeType, Long sizeBytes, Instant lastModified) {

        static NodeView of(DiscoveryNode node) {
            return new NodeView(node.id(), node.sourceId(), node.parentId(), node.name(), node.path(),
                    node.type().name(), node.depth(), node.mimeType(), node.sizeBytes(), node.lastModified());
        }
    }

    /**
     * @param hiddenCount how many items were hidden from the caller, or {@code null} unless
     *                    {@code reveal-hidden-counts} is on
     */
    public record TreeView(String connector, List<NodeView> nodes, Long hiddenCount, boolean truncated,
                           String truncationReason) {}

    /**
     * @param matchCount    how many matching documents the caller may read, or {@code null} when that cannot be
     *                      told (see {@code note})
     * @param countAccuracy {@code EXACT}, {@code LOWER_BOUND} or {@code ESTIMATE}, or {@code null} with no count
     */
    public record DryRunView(String connector, Long matchCount, String countAccuracy, List<NodeView> samples,
                             Long hiddenCount, boolean truncated, String truncationReason, String note) {}

    /**
     * Checks that the caller may use discovery at all.
     *
     * @throws DiscoveryApiException {@code 401} when the identity is only asserted and that is not allowed
     */
    public void requireCaller(Caller caller) {
        if (caller == null || (!caller.authenticated() && !properties.isAllowAssertedIdentity())) {
            throw new DiscoveryApiException(HttpStatus.UNAUTHORIZED, "Discovery needs a caller verified by token "
                    + "(opencrawling.security.identity.mode=jwt). For tests only, "
                    + DiscoveryProperties.PREFIX + ".allow-asserted-identity=true accepts asserted identities.");
        }
    }

    /** The registered repository connectors that support discovery. */
    public List<ConnectorInfo> listConnectors(Caller caller) {
        requireCaller(caller);
        return registered().stream().filter(DiscoveryService::discoverable)
                .map(connector -> new ConnectorInfo(connector.name(), connector.description())).toList();
    }

    /** Lists a registered repository's tree below a folder, as far as the caller may see it. */
    public TreeView explore(Caller caller, String connectorName, String start, Integer depth, Integer maxNodes,
                            Boolean includeDocuments) {
        requireCaller(caller);
        ExploreRequest request = new ExploreRequest(start,
                DiscoveryProperties.bounded(depth, 1, properties.depthLimit()),
                DiscoveryProperties.bounded(maxNodes, 1, properties.nodeLimit()),
                includeDocuments == null || includeDocuments, properties.timeoutLimit());
        return withConnector(connectorName, connector -> {
            DiscoveryResult result = connector.explore(request, filterFor(caller));
            log.info("Discovery: '{}' explored '{}' from '{}': {} visible, {} hidden{}.", caller.principal(),
                    connectorName, start == null ? "<root>" : start, result.nodes().size(), result.hiddenCount(),
                    result.truncated() ? ", truncated (" + result.truncationReason() + ")" : "");
            return new TreeView(connectorName, result.nodes().stream().map(NodeView::of).toList(),
                    properties.isRevealHiddenCounts() ? result.hiddenCount() : null, result.truncated(),
                    result.truncated() ? result.truncationReason().name() : null);
        });
    }

    /** Previews what a crawl of a scope would take, as far as the caller may read it. */
    public DryRunView dryRun(Caller caller, String connectorName, String scope, Integer sampleSize, Integer maxScan) {
        requireCaller(caller);
        DryRunRequest request = new DryRunRequest(scope,
                DiscoveryProperties.bounded(sampleSize, 0, properties.sampleLimit()),
                DiscoveryProperties.bounded(maxScan, 1, properties.scanLimit()), properties.timeoutLimit());
        return withConnector(connectorName, connector -> {
            DryRunResult result = connector.dryRun(request, filterFor(caller));
            log.info("Discovery: '{}' previewed '{}' scope '{}': {} matches ({}), {} samples{}.", caller.principal(),
                    connectorName, scope == null ? "<configured>" : scope, result.matchCount(),
                    result.countAccuracy(), result.samples().size(),
                    result.truncated() ? ", truncated (" + result.truncationReason() + ")" : "");
            boolean reveal = properties.isRevealHiddenCounts();
            boolean unchecked = result.countAccuracy() == CountAccuracy.ESTIMATE;
            Long count = unchecked && !reveal ? null : result.matchCount();
            String accuracy = count == null ? null : result.countAccuracy().name();
            String note = unchecked ? (reveal ? QUERY_COUNT_ESTIMATE : QUERY_COUNT_WITHHELD) : null;
            return new DryRunView(connectorName, count, accuracy,
                    result.samples().stream().map(NodeView::of).toList(),
                    reveal ? result.hiddenCount() : null, result.truncated(),
                    result.truncated() ? result.truncationReason().name() : null, note);
        });
    }

    /** Shows the nodes the caller may read by the metadata rules on their source ACL. */
    VisibilityFilter filterFor(Caller caller) {
        return nodes -> {
            List<Integer> readable = gate.filter(caller, IntStream.range(0, nodes.size()).boxed().toList(),
                    index -> nodes.get(index).toResourceRef());
            boolean[] visible = new boolean[nodes.size()];
            readable.forEach(index -> visible[index] = true);
            return visible;
        };
    }

    private List<ConnectorDTO> registered() {
        List<ConnectorDTO> all = connectors.get();
        return all == null ? List.of() : all.stream()
                .filter(connector -> connector != null && "repository".equalsIgnoreCase(connector.type())).toList();
    }

    static boolean discoverable(ConnectorDTO connector) {
        if (connector.className() == null || connector.className().isBlank()) {
            return false;
        }
        try {
            Class<?> type = Class.forName(connector.className(), false, DiscoveryService.class.getClassLoader());
            return DiscoverableRepositoryConnector.class.isAssignableFrom(type);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private <T> T withConnector(String connectorName, Function<DiscoverableRepositoryConnector, T> action) {
        ConnectorDTO registration = registered().stream()
                .filter(connector -> connector.name() != null && connector.name().equalsIgnoreCase(connectorName))
                .findFirst()
                .orElseThrow(() -> new DiscoveryApiException(HttpStatus.NOT_FOUND,
                        "No repository connector '" + connectorName + "'."));
        if (!discoverable(registration)) {
            throw new DiscoveryApiException(HttpStatus.BAD_REQUEST,
                    "Repository connector '" + connectorName + "' does not support discovery.");
        }
        RepositoryConnector built;
        try {
            built = builder.apply(registration);
        } catch (ConnectorResolutionException e) {
            log.warn("Discovery: connector '{}' could not be built: {}", connectorName, e.getMessage());
            throw new DiscoveryApiException(HttpStatus.CONFLICT,
                    "Repository connector '" + connectorName + "' is not configured correctly.");
        }
        if (!(built instanceof DiscoverableRepositoryConnector connector)) {
            throw new DiscoveryApiException(HttpStatus.BAD_REQUEST,
                    "Repository connector '" + connectorName + "' does not support discovery.");
        }
        try {
            return action.apply(connector);
        } catch (IllegalArgumentException e) {
            throw new DiscoveryApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AuthorizationUnavailableException e) {
            log.warn("Discovery: access could not be decided for '{}': {}", connectorName, e.getMessage());
            throw new DiscoveryApiException(HttpStatus.SERVICE_UNAVAILABLE, "Access could not be decided; nothing is returned.");
        } catch (DiscoveryException e) {
            // The repository's own error may name internal hosts; it is logged, not returned.
            log.warn("Discovery: repository '{}' failed: {}", connectorName, e.getMessage(), e);
            throw new DiscoveryApiException(HttpStatus.BAD_GATEWAY, "The repository could not be explored.");
        } finally {
            try {
                connector.disconnect();
            } catch (Exception e) {
                log.debug("Discovery: disconnecting '{}' failed: {}", connectorName, e.getMessage());
            }
        }
    }
}
