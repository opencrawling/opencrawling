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
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.AuthorizationUnavailableException;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.core.security.SecurityConfig;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.openfga.OpenFGAContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the tuple writer and the provider against a real OpenFGA server.
 */
@Testcontainers
class OpenFgaAuthorizationIT {

    static final String IMAGE = "openfga/openfga:v1.22.0";

    @Container
    static final OpenFGAContainer OPENFGA = new OpenFGAContainer(IMAGE);

    static OpenFgaProperties properties;
    static OpenFgaClient client;
    static OpenFgaBootstrap.Target target;
    static OpenFgaTupleWriter writer;
    static OpenFgaAuthorizationProvider provider;

    @BeforeAll
    static void connect() throws Exception {
        properties = new OpenFgaProperties(OPENFGA.getHttpEndpoint(), null, null, "it-store", true, null,
                Duration.ofSeconds(10), 50, 4);
        client = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.apiUrl()));
        target = OpenFgaBootstrap.configure(client, properties);
        writer = new OpenFgaTupleWriter(client, properties);
        provider = new OpenFgaAuthorizationProvider(client, properties);
    }

    private static EffectiveAcl rules(PermissionRule... rules) {
        return EffectiveAcl.resolve(new SecurityConfig(false, List.of(rules)), null);
    }

    private static PermissionRule grant(String identity) {
        return new PermissionRule(identity, "group", identity, "read");
    }

    private static PermissionRule deny(String identity) {
        return new PermissionRule(identity, "user", identity, "deny");
    }

    private static boolean canRead(String documentId, String principal, String... roles) {
        return provider.canRead(Caller.asserted(principal, List.of(roles)), new ResourceRef(documentId, Map.of()));
    }

    @Test
    void bootstrapReusesTheStoreAndAMatchingModel() throws Exception {
        OpenFgaClient again = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.apiUrl()));
        assertThat(OpenFgaBootstrap.configure(again, properties)).isEqualTo(target);
    }

    @Test
    void grantsRolesPrefixesPublicAndDenials() throws Exception {
        writer.sync("it-grants", rules(grant("group:Finance"), grant("user:carol"), deny("mallory")));

        assertThat(canRead("it-grants", "alice", "finance")).isTrue();      // role matches the stripped grant
        assertThat(canRead("it-grants", "alice", "GROUP:FINANCE")).isTrue(); // role matches the raw grant
        assertThat(canRead("it-grants", "carol")).isTrue();                  // principal matches stripped user:carol
        assertThat(canRead("it-grants", "bob", "hr")).isFalse();
        assertThat(canRead("it-grants", null, "finance")).isTrue();          // anonymous caller with a role
        assertThat(canRead("it-grants", "mallory", "finance")).isFalse();    // deny wins

        writer.sync("it-public", rules(new PermissionRule("GROUP_EVERYONE", "public", "Everyone", "read"), deny("mallory")));
        assertThat(canRead("it-public", null)).isTrue();
        assertThat(canRead("it-public", "anyone")).isTrue();
        assertThat(canRead("it-public", "mallory")).isFalse();
    }

    @Test
    void permissionChangesTakeEffectWithoutReindexing() throws Exception {
        writer.sync("it-change", rules(grant("finance")));
        assertThat(canRead("it-change", "alice", "finance")).isTrue();

        writer.sync("it-change", rules(grant("hr")));
        assertThat(canRead("it-change", "alice", "finance")).isFalse();
        assertThat(canRead("it-change", "bob", "hr")).isTrue();

        // Replaying the same permissions changes nothing.
        assertThat(writer.sync("it-change", rules(grant("hr"))).isEmpty()).isTrue();
    }

    @Test
    void deletedDocumentsBecomeUnreadable() throws Exception {
        writer.sync("it-delete", EffectiveAcl.resolve(SecurityConfig.createPublic(), null));
        assertThat(canRead("it-delete", "anyone")).isTrue();

        writer.delete("it-delete");
        assertThat(writer.read("it-delete")).isEmpty();
        assertThat(canRead("it-delete", "anyone")).isFalse();
        writer.delete("it-delete"); // idempotent
    }

    @Test
    void largePermissionSetsAreWrittenInSeveralRequests() throws Exception {
        List<PermissionRule> rules = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            rules.add(grant("team-" + i));
        }
        writer.sync("it-large", EffectiveAcl.resolve(new SecurityConfig(false, rules), null));
        assertThat(writer.read("it-large")).hasSize(240);
        assertThat(canRead("it-large", "x", "team-119")).isTrue();

        rules.replaceAll(rule -> grant(rule.identity().replace("team", "squad")));
        writer.sync("it-large", EffectiveAcl.resolve(new SecurityConfig(false, rules), null));
        assertThat(canRead("it-large", "x", "team-119")).isFalse();
        assertThat(canRead("it-large", "x", "squad-0")).isTrue();
    }

    @Test
    void batchesManyDocumentsAndKeepsCandidateOrder() throws Exception {
        List<ResourceRef> refs = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            String id = "it-batch-" + i;
            writer.sync(id, rules(grant(i % 2 == 0 ? "even" : "odd")));
            refs.add(new ResourceRef(id, Map.of()));
        }
        refs.add(new ResourceRef("it-batch-0", Map.of())); // a duplicate document is checked once

        boolean[] decisions = provider.canRead(Caller.asserted("alice", List.of("even")), refs);

        for (int i = 0; i < 130; i++) {
            assertThat(decisions[i]).as("document %d", i).isEqualTo(i % 2 == 0);
        }
        assertThat(decisions[130]).isTrue();
    }

    @Test
    void anUnreachableServerFailsClosed() throws Exception {
        OpenFgaProperties down = new OpenFgaProperties("http://127.0.0.1:9", target.storeId(),
                target.modelId(), null, false, null, Duration.ofSeconds(2), 50, 1);
        OpenFgaClient unreachable = new OpenFgaClient(new ClientConfiguration().apiUrl(down.apiUrl())
                .storeId(down.storeId()).authorizationModelId(down.modelId()).maxRetries(0));
        OpenFgaAuthorizationProvider failing = new OpenFgaAuthorizationProvider(unreachable, down);

        assertThatThrownBy(() -> failing.canRead(Caller.asserted("alice", List.of()),
                List.of(new ResourceRef("it-grants", Map.of()))))
                .isInstanceOf(AuthorizationUnavailableException.class);
    }
}
