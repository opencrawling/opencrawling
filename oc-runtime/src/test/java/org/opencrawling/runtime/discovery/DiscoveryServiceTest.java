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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.discovery.CountAccuracy;
import org.opencrawling.core.connector.discovery.DiscoveryException;
import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.filesystem.FileSystemRepositoryConnector;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.connector.ConnectorResolutionException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DiscoveryServiceTest {

    private static final ConnectorDTO FAKE = new ConnectorDTO("Fake_Repo", "A fake repository", "repository",
            FakeDiscoverableConnector.class.getName(), 1, Map.of("password", "s3cret", "endpoint", "http://internal:8080"));
    private static final ConnectorDTO FILES = new ConnectorDTO("Files", "File system", "repository",
            FileSystemRepositoryConnector.class.getName(), 1, Map.of());
    private static final ConnectorDTO OUTPUT = new ConnectorDTO("Fake_Output", "Not a repository", "output",
            FakeDiscoverableConnector.class.getName(), 1, Map.of());

    private final FakeDiscoverableConnector connector = new FakeDiscoverableConnector();
    private final DiscoveryProperties properties = new DiscoveryProperties();
    private DiscoveryService service;

    private static Caller verified(String principal, String... groups) {
        return new Caller(principal, List.of(groups), null, true, Map.of());
    }

    private static List<String> names(List<DiscoveryService.NodeView> nodes) {
        return nodes.stream().map(DiscoveryService.NodeView::name).toList();
    }

    @BeforeEach
    void setUp() {
        service = new DiscoveryService(() -> List.of(FAKE, FILES, OUTPUT), dto -> connector, properties);
    }

    @Test
    void theCallerSeesWhatTheSourceAclLetsItRead() {
        DiscoveryService.TreeView tree = service.explore(verified("alice", "finance"), "Fake_Repo", null, null, null, null);

        assertThat(names(tree.nodes())).containsExactly(
                "finance-folder", "finance-report", "alice-notes", "public-guide", "finance-but-not-bob");
        assertThat(names(service.explore(verified("carol"), "Fake_Repo", null, null, null, null).nodes()))
                .containsExactly("public-guide");
    }

    @Test
    void anExplicitDenyWinsAndAnUnknownAclIsNeverShown() {
        List<String> bob = names(service.explore(verified("bob", "finance"), "Fake_Repo", null, null, null, null).nodes());

        assertThat(bob).contains("finance-report").doesNotContain("finance-but-not-bob", "unknown-acl");
    }

    @Test
    void usersAndGroupsAreNotMixedUp() {
        // As for search (#224): "group:finance" is not the user finance, and "user:alice" is not a group alice.
        assertThat(names(service.explore(verified("finance"), "Fake_Repo", null, null, null, null).nodes()))
                .containsExactly("public-guide");
        assertThat(names(service.explore(verified("carol", "alice"), "Fake_Repo", null, null, null, null).nodes()))
                .containsExactly("public-guide");
    }

    @Test
    void theFilterIsTheMetadataRulesOnTheSourceAcl() {
        boolean[] visible = service.filterFor(verified("alice", "finance")).visible(FakeDiscoverableConnector.NODES);

        assertThat(visible).containsExactly(true, true, true, true, true, false);
        assertThat(service.filterFor(verified("alice")).showsEverything()).isFalse();
    }

    @Test
    void resultsCarryNoAcl() {
        assertThat(Arrays.stream(DiscoveryService.NodeView.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("security", "acl", "permissions");
    }

    @Test
    void hiddenCountsAreWithheldUnlessRevealed() {
        assertThat(service.explore(verified("carol"), "Fake_Repo", null, null, null, null).hiddenCount()).isNull();

        properties.setRevealHiddenCounts(true);
        assertThat(service.explore(verified("carol"), "Fake_Repo", null, null, null, null).hiddenCount()).isEqualTo(5);
        assertThat(service.dryRun(verified("carol"), "Fake_Repo", null, null, null).hiddenCount()).isEqualTo(5);
    }

    @Test
    void aCountThatWasNotCheckedItemByItemIsWithheldUnlessRevealed() {
        connector.dryRunAccuracy = CountAccuracy.ESTIMATE;

        DiscoveryService.DryRunView withheld = service.dryRun(verified("carol"), "Fake_Repo", "SELECT x", null, null);
        assertThat(withheld.matchCount()).isNull();
        assertThat(withheld.countAccuracy()).isNull();
        assertThat(withheld.note()).isEqualTo(DiscoveryService.QUERY_COUNT_WITHHELD);
        assertThat(names(withheld.samples())).containsExactly("public-guide");

        properties.setRevealHiddenCounts(true);
        DiscoveryService.DryRunView revealed = service.dryRun(verified("carol"), "Fake_Repo", "SELECT x", null, null);
        assertThat(revealed.matchCount()).isEqualTo(6);
        assertThat(revealed.countAccuracy()).isEqualTo("ESTIMATE");
        assertThat(revealed.note()).isEqualTo(DiscoveryService.QUERY_COUNT_ESTIMATE);
    }

    @Test
    void anExactCountIsTheCallers() {
        DiscoveryService.DryRunView view = service.dryRun(verified("alice", "finance"), "Fake_Repo", "/x", null, null);

        assertThat(view.matchCount()).isEqualTo(5);
        assertThat(view.countAccuracy()).isEqualTo("EXACT");
        assertThat(view.note()).isNull();
    }

    @Test
    void requestsGetTheConfiguredLimitsAndCannotExceedThem() {
        properties.setMaxDepth(4);
        properties.setMaxNodes(50);
        properties.setTimeout(Duration.ofSeconds(7));

        service.explore(verified("alice"), "Fake_Repo", " /Finance ", null, null, null);
        assertThat(connector.lastExplore.maxDepth()).isEqualTo(4);
        assertThat(connector.lastExplore.maxNodes()).isEqualTo(50);
        assertThat(connector.lastExplore.includeDocuments()).isTrue();
        assertThat(connector.lastExplore.timeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(connector.lastExplore.start()).isEqualTo("/Finance");

        service.explore(verified("alice"), "Fake_Repo", null, 99, 99_999, false);
        assertThat(connector.lastExplore.maxDepth()).isEqualTo(4);
        assertThat(connector.lastExplore.maxNodes()).isEqualTo(50);
        assertThat(connector.lastExplore.includeDocuments()).isFalse();

        service.explore(verified("alice"), "Fake_Repo", null, -3, 0, null);
        assertThat(connector.lastExplore.maxDepth()).isEqualTo(1);
        assertThat(connector.lastExplore.maxNodes()).isEqualTo(1);

        service.dryRun(verified("alice"), "Fake_Repo", null, 500, 10_000_000);
        assertThat(connector.lastDryRun.sampleSize()).isEqualTo(10);
        assertThat(connector.lastDryRun.maxScan()).isEqualTo(10_000);
    }

    @Test
    void configuredLimitsAreHeldUnderTheHardCaps() {
        properties.setMaxDepth(1_000);
        properties.setMaxNodes(1_000_000);
        properties.setSampleSize(1_000);
        properties.setMaxScan(Integer.MAX_VALUE);
        properties.setTimeout(Duration.ofHours(1));

        service.explore(verified("alice"), "Fake_Repo", null, null, null, null);
        service.dryRun(verified("alice"), "Fake_Repo", null, null, null);

        assertThat(connector.lastExplore.maxDepth()).isEqualTo(DiscoveryProperties.MAX_DEPTH_CAP);
        assertThat(connector.lastExplore.maxNodes()).isEqualTo(DiscoveryProperties.MAX_NODES_CAP);
        assertThat(connector.lastExplore.timeout()).isEqualTo(DiscoveryProperties.TIMEOUT_CAP);
        assertThat(connector.lastDryRun.sampleSize()).isEqualTo(DiscoveryProperties.SAMPLE_SIZE_CAP);
        assertThat(connector.lastDryRun.maxScan()).isEqualTo(DiscoveryProperties.MAX_SCAN_CAP);
    }

    @Test
    void anAssertedCallerIsRefusedUnlessAllowed() {
        Caller asserted = Caller.asserted("alice", List.of("finance"));

        DiscoveryApiException refused = catchThrowableOfType(DiscoveryApiException.class,
                () -> service.explore(asserted, "Fake_Repo", null, null, null, null));
        assertThat(refused.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(connector.lastExplore).isNull();
        assertThatThrownBy(() -> service.listConnectors(asserted)).isInstanceOf(DiscoveryApiException.class);
        assertThatThrownBy(() -> service.listConnectors(null)).isInstanceOf(DiscoveryApiException.class);

        properties.setAllowAssertedIdentity(true);
        assertThat(service.explore(asserted, "Fake_Repo", null, null, null, null).nodes()).isNotEmpty();
    }

    @Test
    void onlyRegisteredRepositoryConnectorsThatSupportDiscoveryAreListed() {
        assertThat(service.listConnectors(verified("alice")))
                .containsExactly(new DiscoveryService.ConnectorInfo("Fake_Repo", "A fake repository"));
    }

    @Test
    void unknownAndUnsupportedConnectorsAreRefused() {
        assertThat(status(() -> service.explore(verified("alice"), "Nope", null, null, null, null))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(() -> service.explore(verified("alice"), "Fake_Output", null, null, null, null))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(() -> service.explore(verified("alice"), "Files", null, null, null, null))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(() -> service.explore(verified("alice"), "fake_repo", null, null, null, null))).isNull();
    }

    @Test
    void aConnectorThatCannotBeBuiltIsAConflictWithoutItsDetails() {
        service = new DiscoveryService(() -> List.of(FAKE), dto -> {
            throw new ConnectorResolutionException("bad endpoint http://internal:8080 password s3cret");
        }, properties);

        DiscoveryApiException e = catchThrowableOfType(DiscoveryApiException.class,
                () -> service.explore(verified("alice"), "Fake_Repo", null, null, null, null));

        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getMessage()).doesNotContain("internal", "s3cret");
    }

    @Test
    void connectorErrorsMapToStatusesAndRepositoryDetailsStayInTheLog() {
        connector.failure = () -> new IllegalArgumentException("No CMIS folder '/x' in this connector's scope.");
        DiscoveryApiException badRequest = catchThrowableOfType(DiscoveryApiException.class,
                () -> service.explore(verified("alice"), "Fake_Repo", "/x", null, null, null));
        assertThat(badRequest.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRequest.getMessage()).isEqualTo("No CMIS folder '/x' in this connector's scope.");

        connector.failure = () -> new DiscoveryException("HTTP 500 from http://internal:8080/alfresco", null);
        DiscoveryApiException gateway = catchThrowableOfType(DiscoveryApiException.class,
                () -> service.dryRun(verified("alice"), "Fake_Repo", null, null, null));
        assertThat(gateway.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(gateway.getMessage()).doesNotContain("internal");

        connector.failure = () -> new AuthorizationUnavailableException("provider down");
        assertThat(status(() -> service.explore(verified("alice"), "Fake_Repo", null, null, null, null)))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(connector.disconnected).isTrue();
    }

    @Test
    void theConnectorIsDisconnectedAfterEachRequest() {
        service.explore(verified("alice"), "Fake_Repo", null, null, null, null);

        assertThat(connector.disconnected).isTrue();
    }

    private static HttpStatus status(Runnable call) {
        try {
            call.run();
            return null;
        } catch (DiscoveryApiException e) {
            return e.status();
        }
    }
}
