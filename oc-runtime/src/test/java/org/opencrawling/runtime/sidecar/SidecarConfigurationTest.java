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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.authorization.DocumentAccessGate;
import org.opencrawling.runtime.security.CallerResolver;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;

/** The reader exists only when enabled, its MCP tools only with the MCP server; its settings and stores. */
class SidecarConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SidecarConfiguration.class, SidecarController.class, SidecarMcpTools.class);

    @Test
    void offByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SidecarService.class);
            assertThat(context).doesNotHaveBean(SidecarController.class);
            assertThat(context).doesNotHaveBean(SidecarMcpTools.class);
        });
    }

    @Test
    void enablingItAddsTheServiceTheEndpointAndTheTools() {
        runner.withPropertyValues("opencrawling.sidecars.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SidecarService.class);
            assertThat(context).hasSingleBean(SidecarController.class);
            assertThat(context).hasSingleBean(SidecarMcpTools.class);
            SidecarProperties defaults = context.getBean(SidecarProperties.class);
            assertThat(defaults.getConnectors()).isEmpty();
            assertThat(defaults.getRequiredScope()).isEqualTo("oc:sidecars:read");
            assertThat(defaults.isAllowAssertedIdentity()).isFalse();
            assertThat(defaults.limit(null)).isEqualTo(50);
            assertThat(defaults.limit(10_000)).isEqualTo(200);
            assertThat(defaults.effectiveMaxScan()).isEqualTo(5000);
            assertThat(defaults.effectiveMaxSidecarBytes()).isEqualTo(1024 * 1024);
            assertThat(defaults.getCursorTtl()).isEqualTo(Duration.ofHours(1));
        });
        runner.withPropertyValues("opencrawling.sidecars.enabled=true", "opencrawling.mcp.server.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(SidecarController.class);
                    assertThat(context).doesNotHaveBean(SidecarMcpTools.class);
                });
    }

    @Test
    void settingsBindAndHardLimitsHold() {
        runner.withPropertyValues("opencrawling.sidecars.enabled=true",
                "opencrawling.sidecars.connectors=Lake_S3,Lake_Ozone",
                "opencrawling.sidecars.required-scope=lake:read",
                "opencrawling.sidecars.allow-asserted-identity=true",
                "opencrawling.sidecars.default-limit=20",
                "opencrawling.sidecars.max-limit=100000",
                "opencrawling.sidecars.max-scan=10000000",
                "opencrawling.sidecars.max-sidecar-bytes=1000000000",
                "opencrawling.sidecars.cursor-secret=a-cursor-secret-shared-by-every-instance-0123",
                "opencrawling.sidecars.cursor-ttl=10m").run(context -> {
            assertThat(context).hasNotFailed();
            SidecarProperties properties = context.getBean(SidecarProperties.class);
            assertThat(properties.getConnectors()).containsExactly("Lake_S3", "Lake_Ozone");
            assertThat(properties.getRequiredScope()).isEqualTo("lake:read");
            assertThat(properties.isAllowAssertedIdentity()).isTrue();
            assertThat(properties.limit(null)).isEqualTo(20);
            assertThat(properties.limit(100_000)).isEqualTo(SidecarProperties.HARD_MAX_LIMIT);
            assertThat(properties.effectiveMaxScan()).isEqualTo(SidecarProperties.HARD_MAX_SCAN);
            assertThat(properties.effectiveMaxSidecarBytes()).isEqualTo(SidecarProperties.HARD_MAX_SIDECAR_BYTES);
            assertThat(properties.getCursorTtl()).isEqualTo(Duration.ofMinutes(10));
        });
        // The values application.yml gives when the environment sets nothing.
        runner.withPropertyValues("opencrawling.sidecars.enabled=true", "opencrawling.sidecars.connectors=",
                "opencrawling.sidecars.cursor-secret=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SidecarProperties.class).getConnectors()).isEmpty();
        });
        runner.withPropertyValues("opencrawling.sidecars.enabled=true", "opencrawling.sidecars.cursor-secret=short")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("at least 32"));
    }

    @Test
    void theServiceUsesTheRuntimesAuthorizationGate() {
        DocumentAccessGate gate = new DocumentAccessGate(new org.opencrawling.runtime.authorization.MetadataAuthorizationProvider());
        runner.withBean(DocumentAccessGate.class, () -> gate).withPropertyValues("opencrawling.sidecars.enabled=true")
                .run(context -> assertThat(context.getBean(SidecarService.class))
                        .extracting("gate").isSameAs(gate));
    }

    @Test
    void storeLayoutsFollowTheConnectorsSettings() {
        ConnectorDTO s3 = new ConnectorDTO("Lake_S3", "", "output", "org.opencrawling.s3.S3OutputConnector", 1,
                Map.of("keyPrefix", "/lake/v2"));
        ConnectorDTO s3Default = new ConnectorDTO("Lake_S3", "", "output", "org.opencrawling.s3.S3OutputConnector", 1, null);
        ConnectorDTO ozone = new ConnectorDTO("Lake_Ozone", "", "output", "org.opencrawling.ozone.OzoneOutputConnector", 1, Map.of());
        ConnectorDTO vectors = new ConnectorDTO("PG", "", "output", "org.opencrawling.vector.VectorOutputConnector", 1, Map.of());

        assertThat(SidecarStores.layout(s3)).isEqualTo(new SidecarStores.Layout("lake/v2/", ".ois.json"));
        assertThat(SidecarStores.layout(s3Default)).isEqualTo(new SidecarStores.Layout("migration/v1/", ".ois.json"));
        assertThat(SidecarStores.layout(ozone)).isEqualTo(new SidecarStores.Layout("", ".ois.json"));
        assertThat(SidecarStores.supports(s3)).isTrue();
        assertThat(SidecarStores.supports(ozone)).isTrue();
        assertThat(SidecarStores.supports(vectors)).isFalse();
    }

    @Test
    void theControllerAndToolsTakeTheCallerFromTheResolverAndMapErrors() {
        SidecarProperties properties = new SidecarProperties();
        properties.setConnectors(List.of("Lake_S3"));
        SidecarServiceTest.MemoryStore store = new SidecarServiceTest.MemoryStore();
        store.put("migration/v1/a.ois.json", SidecarServiceTest.sidecar("a", "finance"));
        SidecarService service = new SidecarService(properties, () -> List.of(new ConnectorDTO("Lake_S3", "", "output",
                "org.opencrawling.s3.S3OutputConnector", 1, Map.of())), connector -> store,
                DocumentAccessGate.metadataDefault(), java.time.Clock.systemUTC());
        CallerResolver verified = CallerResolver.verified(new org.opencrawling.runtime.security.JwtCallerMapper(
                new org.opencrawling.runtime.security.IdentityProperties().getJwt()));

        SidecarController controller = new SidecarController(service);
        SidecarMcpTools tools = new SidecarMcpTools(service);
        // Without a verified caller (no resolver): asserted identities are refused by default.
        assertThat(controller.failed(catchSidecar(() -> controller.read("Lake_S3", null, null, null, "alice", "finance")))
                .getStatusCode().value()).isEqualTo(401);
        controller.setCallerResolver(verified);
        tools.setCallerResolver(verified);
        // A verified token without a user: 401 from the resolver.
        assertThat(catchSidecar(() -> controller.read("Lake_S3", null, null, null, null, null)).status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        org.springframework.security.oauth2.jwt.Jwt jwt = new org.springframework.security.oauth2.jwt.Jwt("t",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(60), Map.of("alg", "RS256"),
                Map.of("sub", "alice-id", "preferred_username", "alice", "scope", "openid oc:sidecars:read",
                        "realm_access", Map.of("roles", List.of("finance"))));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, List.of()));
        // Forged arguments are ignored: the token's identity is used.
        assertThat(controller.read("Lake_S3", null, null, null, "mallory", "hr").sidecars()).hasSize(1);
        assertThat(tools.readOisSidecars("Lake_S3", null, null, null, "mallory", "hr").sidecars()).hasSize(1);
        assertThat(tools.listSidecarStores(null, null)).extracting(SidecarService.StoreInfo::name).containsExactly("Lake_S3");
        assertThat(controller.failed(catchSidecar(() -> controller.read("Nope", null, null, null, null, null))).getBody())
                .containsEntry("error", "No sidecar store named 'Nope' is available.");
        assertThat(catchSidecar(() -> controller.read("Nope", null, null, null, null, null)).status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static SidecarException catchSidecar(Runnable call) {
        try {
            call.run();
        } catch (SidecarException e) {
            return e;
        }
        throw new AssertionError("expected a SidecarException");
    }
}
