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
package org.opencrawling.runtime.authorization;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.AuthorizationProvider;
import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.ResourceRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentAccessGateTest {

    private static final Caller ALICE = Caller.asserted("alice", List.of("finance"));

    /** Records each batch and allows the documents whose id starts with "ok". */
    private static final class RecordingProvider implements AuthorizationProvider {
        final List<List<ResourceRef>> batches = new ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
            batches.add(List.copyOf(candidates));
            boolean[] decisions = new boolean[candidates.size()];
            for (int i = 0; i < decisions.length; i++) {
                decisions[i] = candidates.get(i).documentId().startsWith("ok");
            }
            return decisions;
        }
    }

    @Test
    void filterKeepsAllowedCandidatesInOrderWithOneBatch() {
        RecordingProvider provider = new RecordingProvider();
        DocumentAccessGate gate = new DocumentAccessGate(provider);

        List<String> kept = gate.filter(ALICE, List.of("ok-3", "no-1", "ok-1", "no-2", "ok-2"),
                id -> new ResourceRef(id, Map.of()));

        assertThat(kept).containsExactly("ok-3", "ok-1", "ok-2");
        assertThat(provider.batches).hasSize(1);
        assertThat(provider.batches.get(0)).extracting(ResourceRef::documentId)
                .containsExactly("ok-3", "no-1", "ok-1", "no-2", "ok-2");
    }

    @Test
    void emptyCandidatesNeverReachTheProvider() {
        RecordingProvider provider = new RecordingProvider();
        assertThat(new DocumentAccessGate(provider).filter(ALICE, List.<String>of(), id -> null)).isEmpty();
        assertThat(provider.batches).isEmpty();
    }

    @Test
    void providerFailureDeniesTheWholeRequest() {
        AuthorizationProvider broken = new AuthorizationProvider() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
                throw new IllegalStateException("engine unreachable");
            }
        };
        DocumentAccessGate gate = new DocumentAccessGate(broken);

        assertThatThrownBy(() -> gate.filter(ALICE, List.of("ok-1"), id -> new ResourceRef(id, Map.of())))
                .isInstanceOf(AuthorizationUnavailableException.class)
                .hasMessageContaining("broken")
                .hasRootCauseMessage("engine unreachable");
        assertThatThrownBy(() -> gate.canRead(ALICE, new ResourceRef("ok-1", Map.of())))
                .isInstanceOf(AuthorizationUnavailableException.class);
    }

    @Test
    void malformedAnswerDeniesTheWholeRequest() {
        AuthorizationProvider shortAnswer = new AuthorizationProvider() {
            @Override
            public String name() {
                return "short";
            }

            @Override
            public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
                return new boolean[] {true};
            }
        };
        DocumentAccessGate gate = new DocumentAccessGate(shortAnswer);

        assertThatThrownBy(() -> gate.filter(ALICE, List.of("a", "b"), id -> new ResourceRef(id, Map.of())))
                .isInstanceOf(AuthorizationUnavailableException.class)
                .hasMessageContaining("1 decisions for 2 candidates");
    }

    /** A provider that, like OpenFGA, may allow documents the stored metadata does not show. */
    private static final class Relationship implements org.opencrawling.core.security.RelationshipAuthorizationProvider {
        @Override
        public String name() {
            return "rel";
        }

        @Override
        public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
            boolean[] decisions = new boolean[candidates.size()];
            decisions[0] = true;
            return decisions;
        }
    }

    @Test
    void metadataModeKeepsTheStoreWindowAndPrefilter() {
        DocumentAccessGate gate = DocumentAccessGate.metadataDefault();
        assertThat(gate.storePrefilterAllowed()).isTrue();
        assertThat(gate.candidateWindow(5, 20)).isEqualTo(20);
    }

    @Test
    void relationshipModesWidenTheWindowWithinTheBudgetAndSkipStorePrefilters() {
        DocumentAccessGate gate = new DocumentAccessGate(new Relationship(), 10, 120, null);
        assertThat(gate.storePrefilterAllowed()).isFalse();
        assertThat(gate.candidateWindow(5, 20)).isEqualTo(50);
        assertThat(gate.candidateWindow(50, 150)).isEqualTo(150);   // never below the store's own window
        assertThat(gate.candidateWindow(30, 20)).isEqualTo(120);    // capped by the budget
        assertThat(gate.candidateWindow(0, 20)).isEqualTo(20);
    }

    @Test
    void publishesMetrics() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        DocumentAccessGate gate = new DocumentAccessGate(new Relationship(), 10, 500, registry);

        gate.filter(ALICE, List.of("a", "b", "c"), id -> new ResourceRef(id, Map.of()));

        assertThat(registry.get("oc.authz.candidates").tag("provider", "rel").counter().count()).isEqualTo(3);
        assertThat(registry.get("oc.authz.denied").tag("provider", "rel").counter().count()).isEqualTo(2);
        assertThat(registry.get("oc.authz.check").timer().count()).isEqualTo(1);
        assertThat(registry.get("oc.authz.errors").counter().count()).isZero();
    }

    @Test
    void metadataDefaultDecidesFromStoredAcls() {
        DocumentAccessGate gate = DocumentAccessGate.metadataDefault();
        ResourceRef finance = new ResourceRef("d1", Map.of("acl", "finance"));
        ResourceRef hr = new ResourceRef("d2", Map.of("acl", "hr"));
        ResourceRef denied = new ResourceRef("d3", Map.of("acl", "finance", "security_denied_read", List.of("alice")));
        ResourceRef noAcl = new ResourceRef("d4", Map.of());

        assertThat(gate.filter(ALICE, List.of(finance, hr, denied, noAcl), ref -> ref)).containsExactly(finance);
        assertThat(gate.provider().name()).isEqualTo("metadata");
    }
}
