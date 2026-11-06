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

import org.opencrawling.core.connector.discovery.CountAccuracy;
import org.opencrawling.core.connector.discovery.DocumentFetchingRepositoryConnector;
import org.opencrawling.core.connector.discovery.FetchedDocument;
import org.opencrawling.core.connector.discovery.DiscoveryNode;
import org.opencrawling.core.connector.discovery.DiscoveryResult;
import org.opencrawling.core.connector.discovery.DryRunRequest;
import org.opencrawling.core.connector.discovery.DryRunResult;
import org.opencrawling.core.connector.discovery.ExploreRequest;
import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** A discoverable connector over a fixed list of nodes; it applies the filter it is given to all of them. */
public class FakeDiscoverableConnector implements DocumentFetchingRepositoryConnector {

    static final List<DiscoveryNode> NODES = List.of(
            node("finance-folder", DiscoveryNode.NodeType.FOLDER, rule("group:finance", "group", "read")),
            node("finance-report", DiscoveryNode.NodeType.DOCUMENT, rule("group:finance", "group", "read")),
            node("alice-notes", DiscoveryNode.NodeType.DOCUMENT, rule("user:alice", "user", "read")),
            node("public-guide", DiscoveryNode.NodeType.DOCUMENT, rule("public", "public", "read")),
            node("finance-but-not-bob", DiscoveryNode.NodeType.DOCUMENT,
                    rule("group:finance", "group", "read"), rule("user:bob", "user", "deny")),
            node("unknown-acl", DiscoveryNode.NodeType.DOCUMENT));

    ExploreRequest lastExplore;
    DryRunRequest lastDryRun;
    VisibilityFilter lastFilter;
    public volatile boolean disconnected;
    public Supplier<RuntimeException> failure;
    CountAccuracy dryRunAccuracy = CountAccuracy.EXACT;
    String lastCrawlStart;
    public volatile String scannedFrom;
    /** When set, {@link #scan} waits for it before completing, so a run stays in progress. */
    public volatile java.util.concurrent.CountDownLatch scanGate;

    /** The configured root: readable by everyone. */
    static final DiscoveryNode ROOT = new DiscoveryNode("fake://root", "root", null, "root", "/",
            DiscoveryNode.NodeType.FOLDER, 0, null, null, null, null,
            new SecurityConfig(true, List.of(rule("public", "public", "read"))));

    static DiscoveryNode node(String name, DiscoveryNode.NodeType type, PermissionRule... rules) {
        SecurityConfig security = rules.length == 0 ? null : new SecurityConfig(true, List.of(rules));
        return new DiscoveryNode("fake://" + name, name, "root", name, "/" + name, type, 1,
                type == DiscoveryNode.NodeType.DOCUMENT ? "text/plain" : null, 1L, null, null, security);
    }

    static PermissionRule rule(String identity, String type, String access) {
        return new PermissionRule(identity, type, identity, access);
    }

    @Override
    public DiscoveryResult explore(ExploreRequest request, VisibilityFilter filter) {
        lastExplore = request;
        lastFilter = filter;
        if (failure != null) {
            throw failure.get();
        }
        List<DiscoveryNode> visible = visible(filter);
        return new DiscoveryResult(visible, NODES.size() - visible.size(), null);
    }

    @Override
    public DryRunResult dryRun(DryRunRequest request, VisibilityFilter filter) {
        lastDryRun = request;
        lastFilter = filter;
        if (failure != null) {
            throw failure.get();
        }
        List<DiscoveryNode> visible = visible(filter);
        long hidden = NODES.size() - visible.size();
        return dryRunAccuracy == CountAccuracy.ESTIMATE
                ? new DryRunResult(NODES.size(), CountAccuracy.ESTIMATE, visible, null, null)
                : new DryRunResult(visible.size(), dryRunAccuracy, visible, hidden, null);
    }

    /**
     * A document of {@link #NODES} by name, if the filter shows it; a name starting with {@code deleted-} is one the
     * repository no longer has; anything else is refused as not found.
     */
    @Override
    public FetchedDocument fetchDocument(String documentId, VisibilityFilter filter) {
        lastFilter = filter;
        if (failure != null) {
            throw failure.get();
        }
        if (documentId.startsWith("deleted-")) {
            return new FetchedDocument.Gone("fake://" + documentId);
        }
        DiscoveryNode document = NODES.stream()
                .filter(node -> node.type() == DiscoveryNode.NodeType.DOCUMENT && node.name().equals(documentId))
                .findFirst().orElse(null);
        if (document == null || !VisibilityFilter.decide(filter, List.of(document))[0]) {
            throw new IllegalArgumentException("No fake document '" + documentId + "' in this connector's scope.");
        }
        return new FetchedDocument.Found(document, new RepositoryDocument(document.id(), document.id(),
                new java.io.ByteArrayInputStream("content".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.util.Map.of("name", List.of(document.name())), null, document.security(),
                java.time.Instant.parse("2026-11-06T10:00:00Z"), org.opencrawling.core.document.DocumentAction.UPSERT));
    }

    @Override
    public DiscoveryNode crawlStart(String reference, VisibilityFilter filter) {
        lastCrawlStart = reference;
        lastFilter = filter;
        if (failure != null) {
            throw failure.get();
        }
        DiscoveryNode folder = reference == null ? ROOT : NODES.stream()
                .filter(node -> node.type() == DiscoveryNode.NodeType.FOLDER && node.name().equals(reference))
                .findFirst().orElse(null);
        if (folder == null || !VisibilityFilter.decide(filter, List.of(folder))[0]) {
            throw new IllegalArgumentException("No fake folder '" + reference + "' in this connector's scope.");
        }
        return folder;
    }

    private static List<DiscoveryNode> visible(VisibilityFilter filter) {
        boolean[] decisions = VisibilityFilter.decide(filter, NODES);
        List<DiscoveryNode> visible = new ArrayList<>();
        for (int i = 0; i < decisions.length; i++) {
            if (decisions[i]) {
                visible.add(NODES.get(i));
            }
        }
        return visible;
    }

    @Override
    public Flux<RepositoryDocument> scan(String basePath) {
        scannedFrom = basePath;
        java.util.concurrent.CountDownLatch gate = scanGate;
        if (gate == null) {
            return Flux.empty();
        }
        return Flux.defer(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Flux.<RepositoryDocument>empty();
        });
    }

    @Override
    public String getName() {
        return "fake";
    }

    @Override
    public void connect() {
    }

    @Override
    public void disconnect() {
        disconnected = true;
    }
}
