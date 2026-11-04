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
package org.opencrawling.authorization.openfga;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.ResourceRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenFgaAuthorizationProviderTest {

    private final OpenFgaProperties properties = new OpenFgaProperties(null, "s", "m", null, false, null, null, null, null);

    @Test
    void callerMembershipsAreThePrincipalsUserGroupAndEachRolesGroupGroup() {
        List<ClientTupleKey> tuples = OpenFgaAuthorizationProvider.contextualTuples("user:p_alice",
                Caller.asserted("user:Alice", List.of("group:Finance", "finance")));

        assertThat(tuples).extracting(ClientTupleKey::getObject).containsExactly(
                "group:u_alice", "group:g_finance");
        assertThat(tuples).allSatisfy(t -> {
            assertThat(t.getUser()).isEqualTo("user:p_alice");
            assertThat(t.getRelation()).isEqualTo("member");
        });
    }

    @Test
    void tooManyGroupsFailClosed() {
        List<String> roles = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            roles.add("role-" + i);
        }
        // One tuple per identity: the principal plus 99 roles is exactly the limit.
        assertThat(OpenFgaAuthorizationProvider.contextualTuples("user:x", Caller.asserted("x", roles))).hasSize(100);
        roles.add("role-99");
        assertThatThrownBy(() -> OpenFgaAuthorizationProvider.contextualTuples("user:x", Caller.asserted("x", roles)))
                .isInstanceOf(AuthorizationUnavailableException.class);
    }

    @Test
    void candidatesWithoutADocumentIdAreDeniedWithoutAsking() {
        OpenFgaClient client = mock(OpenFgaClient.class);
        OpenFgaAuthorizationProvider provider = new OpenFgaAuthorizationProvider(client, properties);

        assertThat(provider.canRead(Caller.asserted("alice", List.of()),
                List.of(new ResourceRef(null, Map.of("acl", "public")), new ResourceRef(" ", Map.of()))))
                .containsExactly(false, false);
        verifyNoInteractions(client);
    }

    @Test
    void clientErrorsFailClosed() throws Exception {
        OpenFgaClient client = mock(OpenFgaClient.class);
        when(client.batchCheck(any(), any())).thenThrow(new IllegalStateException("connection refused"));
        OpenFgaAuthorizationProvider provider = new OpenFgaAuthorizationProvider(client, properties);

        assertThatThrownBy(() -> provider.canRead(Caller.asserted("alice", List.of()),
                List.of(new ResourceRef("d1", Map.of()))))
                .isInstanceOf(AuthorizationUnavailableException.class)
                .hasMessageContaining("access denied");
    }
}
