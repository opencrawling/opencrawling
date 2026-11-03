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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opencrawling.core.security.AuthorizationProvider;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.RelationshipAuthorizationProvider;
import org.opencrawling.core.security.ResourceRef;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationConfigurationTest {

    private static final Caller ALICE = Caller.asserted("alice", List.of());
    /** Readable by alice according to its stored ACL. */
    private static final ResourceRef ALICE_DOC = new ResourceRef("d1", Map.of("acl", "alice"));
    /** Not readable by alice according to its stored ACL. */
    private static final ResourceRef BOB_DOC = new ResourceRef("d2", Map.of("acl", "bob"));

    /** A relationship provider that allows only d2, the opposite of the metadata. */
    private static final class OnlyD2 implements RelationshipAuthorizationProvider {
        boolean fail;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean[] canRead(Caller caller, List<ResourceRef> candidates) {
            if (fail) {
                throw new IllegalStateException("down");
            }
            boolean[] decisions = new boolean[candidates.size()];
            for (int i = 0; i < decisions.length; i++) {
                decisions[i] = "d2".equals(candidates.get(i).documentId());
            }
            return decisions;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata", " Metadata ", ""})
    void metadataModeSelectsTheMetadataProvider(String mode) {
        assertThat(AuthorizationConfiguration.select(mode, new OnlyD2())).isInstanceOf(MetadataAuthorizationProvider.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"shadow", "intersection", "openfga", "OpenFGA"})
    void relationshipModesStopStartupWithoutTheOpenFgaModule(String mode) {
        assertThatThrownBy(() -> AuthorizationConfiguration.select(mode, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenFGA authorization module");
    }

    @Test
    void unknownModeStopsStartup() {
        assertThatThrownBy(() -> AuthorizationConfiguration.select("permissive", new OnlyD2()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown opencrawling.security.authorization.mode 'permissive'");
    }

    @Test
    void openfgaModeLetsTheRelationshipProviderDecideAlone() {
        AuthorizationProvider provider = AuthorizationConfiguration.select("openfga", new OnlyD2());
        assertThat(provider.canRead(ALICE, List.of(ALICE_DOC, BOB_DOC))).containsExactly(false, true);
    }

    @Test
    void intersectionModeNeedsBoth() {
        AuthorizationProvider provider = AuthorizationConfiguration.select("intersection", new OnlyD2());
        assertThat(provider.canRead(ALICE, List.of(ALICE_DOC, BOB_DOC))).containsExactly(false, false);
    }

    @Test
    void intersectionModeFailsClosedWhenTheRelationshipProviderFails() {
        OnlyD2 relationship = new OnlyD2();
        relationship.fail = true;
        AuthorizationProvider provider = AuthorizationConfiguration.select("intersection", relationship);
        assertThatThrownBy(() -> provider.canRead(ALICE, List.of(ALICE_DOC))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shadowModeKeepsMetadataDecisionsAndCountsDisagreements() {
        OnlyD2 relationship = new OnlyD2();
        ShadowAuthorizationProvider provider =
                (ShadowAuthorizationProvider) AuthorizationConfiguration.select("shadow", relationship);

        assertThat(provider.canRead(ALICE, List.of(ALICE_DOC, BOB_DOC))).containsExactly(true, false);
        assertThat(provider.mismatches()).isEqualTo(2);

        relationship.fail = true;
        assertThat(provider.canRead(ALICE, List.of(ALICE_DOC))).containsExactly(true);
        assertThat(provider.errors()).isEqualTo(1);
    }
}
