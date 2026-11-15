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
package org.opencrawling.runtime.api.v1;

import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.api.v1.model.ConnectorResource;
import org.opencrawling.runtime.discovery.DiscoveryController;
import org.opencrawling.runtime.dynamicjob.DynamicJobController;
import org.opencrawling.runtime.dynamicjob.DynamicJobService;
import org.opencrawling.runtime.sidecar.SidecarController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformApiV1UnitTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void onlyPlatformApiRequestsGetItsConventions() {
        assertThat(ApiV1.isPlatformRequest(new MockHttpServletRequest("GET", "/api/v1"))).isTrue();
        assertThat(ApiV1.isPlatformRequest(new MockHttpServletRequest("GET", "/api/v1/jobs/1:start"))).isTrue();
        assertThat(ApiV1.isPlatformRequest(new MockHttpServletRequest("POST", "/api/v1/ingest/ois/job"))).isFalse();
        assertThat(ApiV1.isPlatformRequest(new MockHttpServletRequest("GET", "/api/v1/admin/settings/transport"))).isFalse();
        assertThat(ApiV1.isPlatformRequest(new MockHttpServletRequest("GET", "/api/v10/jobs"))).isFalse();
        assertThat(ApiV1.isPlatformRequest(new MockHttpServletRequest("GET", "/api/jobs"))).isFalse();
        MockHttpServletRequest withContext = new MockHttpServletRequest("GET", "/oc/api/v1/jobs");
        withContext.setContextPath("/oc");
        assertThat(ApiV1.isPlatformRequest(withContext)).isTrue();
    }

    @Test
    void cursorsArePositionsBoundToTheirQuery() {
        List<Integer> items = List.of(1, 2, 3, 4, 5);
        Page<Integer> first = Paging.page(items, 2, null, "q=a", null);
        assertThat(first.items()).containsExactly(1, 2);
        Page<Integer> second = Paging.page(items, 2, first.page().nextCursor(), "q=a", null);
        assertThat(second.items()).containsExactly(3, 4);
        Page<Integer> last = Paging.page(items, 2, second.page().nextCursor(), "q=a", null);
        assertThat(last.items()).containsExactly(5);
        assertThat(last.page().nextCursor()).isNull();
        assertThat(last.page().totalItems()).isEqualTo(5);

        assertThatThrownBy(() -> Paging.page(items, 2, first.page().nextCursor(), "q=b", null)).isInstanceOf(ApiProblem.class);
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("v1.2.AAAAAAAAAAAA".getBytes());
        assertThatThrownBy(() -> Paging.page(items, 2, forged, "q=a", null)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> Paging.page(items, 2, "%%%", "q=a", null)).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> Paging.page(items, 501, null, "", null)).isInstanceOf(ApiProblem.class);
        assertThat(Paging.page(items, 2, Paging.encode(10, Etags.hash("q=a")), "q=a", null).items()).isEmpty();
    }

    @Test
    void sortingTakesSeveralFieldsAndRefusesUnknownOnes() {
        record Row(String name, int size) {}
        List<Row> rows = List.of(new Row("b", 1), new Row("a", 2), new Row("a", 1));
        Map<String, Comparator<Row>> fields = Map.of("name", Paging.byText(Row::name), "size", Comparator.comparingInt(Row::size));
        assertThat(Paging.sort(rows, "name,-size", fields)).containsExactly(new Row("a", 2), new Row("a", 1), new Row("b", 1));
        assertThat(Paging.sort(rows, null, fields)).isSameAs(rows);
        assertThatThrownBy(() -> Paging.sort(rows, "colour", fields)).isInstanceOf(ApiProblem.class)
                .satisfies(e -> assertThat(((ApiProblem) e).slug()).isEqualTo("validation-failed"));
    }

    @Test
    void entityTagsFollowRfc9110Comparison() {
        String etag = Etags.of(Map.of("a", 1), json);
        assertThat(etag).startsWith("\"").endsWith("\"");
        assertThat(Etags.of(Map.of("a", 1), json)).isEqualTo(etag);
        assertThat(Etags.of(Map.of("a", 2), json)).isNotEqualTo(etag);
        assertThat(Etags.notModified("W/" + etag, etag)).as("weak comparison for If-None-Match").isTrue();
        assertThat(Etags.notModified("\"x\", " + etag, etag)).isTrue();
        assertThat(Etags.notModified("*", etag)).isTrue();
        assertThat(Etags.notModified(null, etag)).isFalse();
        Etags.requireMatch(null, etag);
        Etags.requireMatch("*", etag);
        Etags.requireMatch(etag, etag);
        Etags.requireMatch("W/" + etag, etag); // a weak copy, as a compressing proxy makes it, names the same version
        assertThatThrownBy(() -> Etags.requireMatch("W/\"other\"", etag)).as("another version, weak or not")
                .isInstanceOf(ApiProblem.class)
                .satisfies(e -> assertThat(((ApiProblem) e).status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
        assertThatThrownBy(() -> Etags.requireMatch("\"other\"", etag))
                .isInstanceOf(ApiProblem.class)
                .satisfies(e -> assertThat(((ApiProblem) e).status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
    }

    @Test
    void mergePatchFollowsRfc7396() {
        JsonNode target = json.readTree("{\"a\":\"b\",\"c\":{\"d\":\"e\",\"f\":\"g\"},\"list\":[1,2]}");
        JsonNode patch = json.readTree("{\"a\":\"z\",\"c\":{\"f\":null},\"list\":[3],\"new\":{\"x\":1}}");
        assertThat(JsonMergePatch.apply(target, patch)).isEqualTo(json.readTree(
                "{\"a\":\"z\",\"c\":{\"d\":\"e\"},\"list\":[3],\"new\":{\"x\":1}}"));
        assertThat(target.get("c").has("f")).as("the target is not changed").isTrue();
        assertThat(JsonMergePatch.apply(target, json.readTree("[1]"))).isEqualTo(json.readTree("[1]"));
        assertThat(JsonMergePatch.apply(json.readTree("\"text\""), json.readTree("{\"a\":null,\"b\":1}")))
                .isEqualTo(json.readTree("{\"b\":1}"));
    }

    @Test
    void idempotencyRemembersSuccessesForALimitedTimeAndSpace() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-14T10:00:00Z"));
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(Duration.ofHours(24), 2, clock);
        IdempotencyStore.StoredResponse ok = new IdempotencyStore.StoredResponse(201, Map.of(), "{}".getBytes());

        assertThat(store.begin("alice", "k1", "f1")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        assertThat(store.begin("alice", "k1", "f1")).isInstanceOf(IdempotencyStore.Begin.InProgress.class);
        store.complete("alice", "k1", "f1", ok);
        assertThat(store.begin("alice", "k1", "f1")).isEqualTo(new IdempotencyStore.Begin.Replay(ok));
        assertThat(store.begin("alice", "k1", "f2")).isInstanceOf(IdempotencyStore.Begin.Mismatch.class);
        assertThat(store.begin("bob", "k1", "f2")).as("scoped to the caller").isInstanceOf(IdempotencyStore.Begin.Proceed.class);

        store.abandon("bob", "k1");
        assertThat(store.begin("bob", "k1", "f3")).as("a failed request may be retried").isInstanceOf(IdempotencyStore.Begin.Proceed.class);

        assertThat(store.begin("carol", "k1", "f1")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        assertThat(store.size()).as("the oldest is evicted at the limit").isEqualTo(2);

        now.set(now.get().plus(Duration.ofHours(25)));
        assertThat(store.begin("carol", "k1", "other")).as("expired").isInstanceOf(IdempotencyStore.Begin.Proceed.class);
    }

    @Test
    void connectorSecretsAreMaskedAndTheMaskKeepsThem() {
        assertThat(ConnectorResource.isSecret("pgVectorPassword")).isTrue();
        assertThat(ConnectorResource.isSecret("apiKey")).isTrue();
        assertThat(ConnectorResource.isSecret("clientSecret")).isTrue();
        assertThat(ConnectorResource.isSecret("milvusToken")).isTrue();
        assertThat(ConnectorResource.isSecret("idpTokenUrl")).isFalse();
        assertThat(ConnectorResource.isSecret("keyPrefix")).isFalse();
        assertThat(ConnectorResource.isSecret("primaryKeyColumns")).isFalse();

        ConnectorResource masked = ConnectorResource.of(new ConnectorDTO("n", null, "repository", "x", 1,
                Map.of("password", "p", "user", "u", "token", "")));
        assertThat(masked.configuration()).containsEntry("password", ConnectorResource.MASK).containsEntry("user", "u")
                .as("an empty secret is shown as empty").containsEntry("token", "");
        assertThat(ConnectorResource.of(new ConnectorDTO("n", null, "repository", "x", 1, null)).configuration()).isNull();

        assertThat(ConnectorResource.unmask(Map.of("password", ConnectorResource.MASK, "user", "v"), Map.of("password", "p")))
                .containsEntry("password", "p").containsEntry("user", "v");
        assertThatThrownBy(() -> ConnectorResource.unmask(Map.of("apiKey", ConnectorResource.MASK), Map.of()))
                .isInstanceOf(ApiProblem.class);
        assertThat(ConnectorResource.unmask(null, Map.of())).isNull();
    }

    @Test
    void dynamicJobsPointAtTheirV1Resource() {
        DynamicJobController legacy = mock(DynamicJobController.class);
        DynamicJobService.Accepted accepted = new DynamicJobService.Accepted("dj-1", "exec-1", "/api/jobs/dynamic/dj-1",
                DynamicJobService.State.RUNNING, false);
        when(legacy.start(any(), eq("key"), eq("corr"))).thenAnswer(i -> ResponseEntity.accepted()
                .location(URI.create("/api/jobs/dynamic/dj-1")).body(accepted));
        when(legacy.document(any(), any(), any())).thenAnswer(i -> ResponseEntity.ok(Map.of("valid", true)));
        DynamicJobsV1Controller controller = new DynamicJobsV1Controller(legacy);

        ResponseEntity<?> started = controller.start(null, "key", "corr");
        assertThat(started.getStatusCode().value()).isEqualTo(202);
        assertThat(started.getHeaders().getLocation()).hasToString("/api/v1/dynamic-jobs/dj-1");
        assertThat(started.getBody()).as("the status URL points at the v1 resource too")
                .isEqualTo(new DynamicJobService.Accepted("dj-1", "exec-1", "/api/v1/dynamic-jobs/dj-1",
                        DynamicJobService.State.RUNNING, false));
        assertThat(controller.document(null, null, null).getBody()).isEqualTo(Map.of("valid", true));
        controller.approve("dj-1");
        verify(legacy).approve("dj-1");
    }

    @Test
    void discoveryAndSidecarsDelegateWithTheTokensCaller() {
        DiscoveryController discovery = mock(DiscoveryController.class);
        new DiscoveryV1Controller(discovery).tree("cmis", "/Sites", 2, 100, true);
        verify(discovery).tree("cmis", "/Sites", 2, 100, true, null, null);
        new DiscoveryV1Controller(discovery).dryRun("cmis", null);
        verify(discovery).dryRun("cmis", null, null, null);

        SidecarController sidecars = mock(SidecarController.class);
        new SidecarsV1Controller(sidecars).read("ozone", "p/", 10, "c1");
        verify(sidecars).read("ozone", "p/", 10, "c1", null, null);
    }

    @Test
    void featureExceptionsBecomeProblemsWithTheirStatus() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dynamic-jobs/x");
        assertThat(ApiV1ExceptionResolver.toProblem(new org.opencrawling.runtime.dynamicjob.DynamicJobException(
                HttpStatus.CONFLICT, "busy"), request).status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ApiV1ExceptionResolver.toProblem(new org.opencrawling.runtime.discovery.DiscoveryApiException(
                HttpStatus.UNAUTHORIZED, "token"), request).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ApiProblem upstream = ApiV1ExceptionResolver.toProblem(
                new org.opencrawling.migrator.mcf.mcf.client.ManifoldCFApiException("down"), request);
        assertThat(upstream.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(upstream.slug()).isEqualTo("upstream-error");
        ApiProblem unexpected = ApiV1ExceptionResolver.toProblem(new IllegalStateException("secret internals"), request);
        assertThat(unexpected.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(unexpected.detail()).doesNotContain("secret internals");
    }
}
