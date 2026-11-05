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
package org.opencrawling.core.connector.discovery;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.core.security.SecurityConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryTypesTest {

    private static DiscoveryNode node(SecurityConfig security) {
        return new DiscoveryNode("cmis://repo/documents/d1", "d1", "f1", "budget.pdf", null,
                DiscoveryNode.NodeType.DOCUMENT, 1, "application/pdf", 10L, null, null, security);
    }

    @Test
    void aNodeIsDecidedOnByItsIndexedIdAndItsSourceAcl() {
        SecurityConfig security = new SecurityConfig(true, List.of(new PermissionRule("finance", "group", "finance", "read")));

        ResourceRef ref = node(security).toResourceRef();

        assertThat(ref.documentId()).isEqualTo("cmis://repo/documents/d1");
        assertThat(ref.metadata()).containsEntry("documentId", "cmis://repo/documents/d1").containsEntry("security", security);
    }

    @Test
    void aNodeWithoutAKnownAclCarriesNoAccessControlData() {
        assertThat(node(null).toResourceRef().metadata()).containsOnlyKeys("documentId");
    }

    @Test
    void requestsRejectBoundsThatCouldNotBeHonoured() {
        Duration timeout = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new ExploreRequest(null, 0, 10, true, timeout)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExploreRequest(null, 1, 0, true, timeout)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExploreRequest(null, 1, 10, true, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DryRunRequest(null, -1, 10, timeout)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DryRunRequest(null, 1, 0, timeout)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DryRunRequest(null, 1, 10, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void blankStartsAndScopesMeanTheConnectorsOwn() {
        assertThat(new ExploreRequest("  ", 1, 1, true, Duration.ofSeconds(1)).start()).isNull();
        assertThat(new ExploreRequest(" /Finance ", 1, 1, true, Duration.ofSeconds(1)).start()).isEqualTo("/Finance");
        assertThat(new DryRunRequest("", 1, 1, Duration.ofSeconds(1)).scope()).isNull();
    }

    @Test
    void resultsAreImmutableCopiesAndSayWhetherTheyWereTruncated() {
        List<DiscoveryNode> nodes = new ArrayList<>(List.of(node(null)));
        DiscoveryResult result = new DiscoveryResult(nodes, null);
        nodes.clear();

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.truncated()).isFalse();
        assertThat(new DiscoveryResult(null, TruncationReason.MAX_NODES).truncated()).isTrue();
        assertThat(new DryRunResult(3, CountAccuracy.LOWER_BOUND, null, TruncationReason.MAX_SCAN).samples()).isEmpty();
        assertThatThrownBy(() -> result.nodes().add(node(null))).isInstanceOf(UnsupportedOperationException.class);
    }
}
