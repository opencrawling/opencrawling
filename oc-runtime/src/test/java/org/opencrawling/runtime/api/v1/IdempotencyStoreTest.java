/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The idempotency stores keep the same contract; the Redis one also survives crashes and outages safely (the JDBC one: {@link JdbcIdempotencyStoreContract}). */
class IdempotencyStoreTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What every store must do. */
    static void followsTheContract(IdempotencyStore store) {
        IdempotencyStore.StoredResponse ok = new IdempotencyStore.StoredResponse(201,
                Map.of("Location", List.of("/api/v1/jobs/1"), "Content-Type", List.of("application/json")),
                "{\"id\":\"1\"}".getBytes());

        assertThat(store.begin("alice POST /api/v1/jobs", "k1", "f1")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        assertThat(store.begin("alice POST /api/v1/jobs", "k1", "f1")).isInstanceOf(IdempotencyStore.Begin.InProgress.class);
        assertThat(store.begin("alice POST /api/v1/jobs", "k1", "f2")).as("another body while running")
                .isInstanceOf(IdempotencyStore.Begin.Mismatch.class);
        store.complete("alice POST /api/v1/jobs", "k1", "f1", ok);
        assertThat(store.begin("alice POST /api/v1/jobs", "k1", "f1")).isEqualTo(new IdempotencyStore.Begin.Replay(ok));
        assertThat(store.begin("alice POST /api/v1/jobs", "k1", "f2")).isInstanceOf(IdempotencyStore.Begin.Mismatch.class);
        assertThat(store.begin("bob POST /api/v1/jobs", "k1", "f2")).as("scoped to the caller")
                .isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        assertThat(store.begin("alice POST /api/v1/connectors", "k1", "f2")).as("scoped to the path")
                .isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        store.abandon("bob POST /api/v1/jobs", "k1");
        assertThat(store.begin("bob POST /api/v1/jobs", "k1", "f3")).as("a failed request may be retried")
                .isInstanceOf(IdempotencyStore.Begin.Proceed.class);

        assertThat(store.begin("carol POST /api/v1/executions:query", "big", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        store.completeWithoutAnswer("carol POST /api/v1/executions:query", "big", "f", 200);
        assertThat(store.begin("carol POST /api/v1/executions:query", "big", "f")).as("succeeded, answer not kept: not run again")
                .isEqualTo(new IdempotencyStore.Begin.AnswerNotKept(200));
        assertThat(store.begin("carol POST /api/v1/executions:query", "big", "other")).isInstanceOf(IdempotencyStore.Begin.Mismatch.class);
    }

    @Test
    void theMemoryStoreNeverDropsARunningRequestAndKeepsEachCallerWithinItsShare() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(Duration.ofHours(1), 3, 2, null);
        assertThat(store.begin("alice POST /a", "1", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        assertThat(store.begin("alice POST /a", "2", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        assertThatThrownBy(() -> store.begin("alice POST /a", "3", "f")).as("alice's share is full of running requests")
                .isInstanceOf(IdempotencyStore.UnavailableException.class);
        store.complete("alice POST /a", "1", "f", new IdempotencyStore.StoredResponse(201, Map.of(), new byte[0]));
        assertThat(store.begin("alice POST /a", "3", "f")).as("her oldest finished key makes room")
                .isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        assertThat(store.begin("alice POST /a", "2", "f")).as("the running one was kept")
                .isInstanceOf(IdempotencyStore.Begin.InProgress.class);

        assertThat(store.begin("bob POST /a", "1", "f")).as("bob is not pushed out by alice").isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        assertThatThrownBy(() -> store.begin("carol POST /a", "1", "f")).as("all 3 running: refused, not dropped")
                .isInstanceOf(IdempotencyStore.UnavailableException.class);
        assertThat(InMemoryIdempotencyStore.callerOf("caller:acme/Jane Doe POST /api/v1/jobs/1:start"))
                .isEqualTo("caller:acme/Jane Doe");
    }

    @Test
    void aRunningRequestKeepsItsRedisKeyBeyondTheInProgressTimeout() throws Exception {
        FakeRedis redis = new FakeRedis();
        RedisIdempotencyStore store = redis.store(Duration.ofHours(1), Duration.ofMillis(300));
        assertThat(store.begin("alice", "long", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        try (IdempotencyStore.Hold held = store.hold("alice", "long", "f")) {
            for (int i = 0; i < 10; i++) {
                Thread.sleep(100);
                redis.advance(Duration.ofMillis(100));
            }
            assertThat(store.begin("alice", "long", "f")).as("held: still running after 1 s, though the timeout is 300 ms")
                    .isInstanceOf(IdempotencyStore.Begin.InProgress.class);
        }
        redis.advance(Duration.ofSeconds(1));
        assertThat(store.begin("alice", "long", "f")).as("no longer held: it expires").isInstanceOf(IdempotencyStore.Begin.Proceed.class);
    }

    @Test
    void closingAHoldWaitsForTheRenewerSoTheAnswerKeepsItsRetention() throws Exception {
        FakeRedis redis = new FakeRedis();
        RedisIdempotencyStore store = redis.store(Duration.ofHours(24), Duration.ofMillis(30));
        assertThat(store.begin("alice", "k", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        IdempotencyStore.Hold held = store.hold("alice", "k", "f");
        Thread.sleep(100); // the renewer is running
        held.close();
        store.complete("alice", "k", "f", new IdempotencyStore.StoredResponse(201, Map.of(), "{}".getBytes()));
        Thread.sleep(100); // a renewal after close would cut the lifetime back to 30 ms
        redis.advance(Duration.ofHours(23));
        assertThat(store.begin("alice", "k", "f")).as("the answer lives for the retention")
                .isInstanceOf(IdempotencyStore.Begin.Replay.class);
        held.close(); // closing twice is harmless
    }

    @Test
    void anAnswerTooLargeToKeepIsNotRunAgainAndTheQueryStringIsPartOfTheRequest() throws Exception {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        IdempotencyFilter filter = new IdempotencyFilter(store, JSON, null, 10);
        FilterChain bigAnswer = (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200);
            res.getOutputStream().write("a large answer, longer than ten bytes".getBytes());
        };
        MockHttpServletResponse first = send(filter, "/api/v1/executions:query", null, bigAnswer);
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(first.getContentAsString()).as("the caller gets the whole answer").contains("longer than ten bytes");

        MockHttpServletResponse retry = send(filter, "/api/v1/executions:query", null, (req, res) -> {
            throw new AssertionError("must not run again");
        });
        assertThat(retry.getStatus()).isEqualTo(409);
        assertThat(retry.getContentAsString()).contains("idempotency-answer-unavailable", "\"firstStatus\":200");

        MockHttpServletResponse otherQuery = send(filter, "/api/v1/executions:query", "dryRun=true", (req, res) -> {
            throw new AssertionError("must not run");
        });
        assertThat(otherQuery.getStatus()).as("same key and body, other query string: another request").isEqualTo(422);
    }

    private static MockHttpServletResponse send(IdempotencyFilter filter, String path, String query, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setQueryString(query);
        request.setContentType("application/json");
        request.setContent("{\"sql\":\"SELECT 1\"}".getBytes());
        request.addHeader(ApiV1.IDEMPOTENCY_KEY, "q-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void theInMemoryStoreFollowsTheContract() {
        followsTheContract(new InMemoryIdempotencyStore());
    }

    @Test
    void theRedisStoreFollowsTheContract() {
        followsTheContract(new FakeRedis().store(Duration.ofHours(24), Duration.ofMinutes(10)));
    }

    @Test
    void aRedisMarkerExpiresSoACrashedRuntimeDoesNotBlockTheKeyButAnAnswerIsKeptForTheRetention() {
        FakeRedis redis = new FakeRedis();
        RedisIdempotencyStore store = redis.store(Duration.ofHours(24), Duration.ofMinutes(10));

        assertThat(store.begin("alice", "k", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        redis.advance(Duration.ofMinutes(11)); // the runtime died without completing or abandoning
        assertThat(store.begin("alice", "k", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);

        IdempotencyStore.StoredResponse ok = new IdempotencyStore.StoredResponse(202, Map.of(), new byte[] {1, 2});
        redis.advance(Duration.ofMinutes(11)); // a long request: its marker expired before it finished
        store.complete("alice", "k", "f", ok);
        redis.advance(Duration.ofHours(23));
        assertThat(store.begin("alice", "k", "f")).as("the answer outlives the marker")
                .isEqualTo(new IdempotencyStore.Begin.Replay(ok));
        redis.advance(Duration.ofHours(2));
        assertThat(store.begin("alice", "k", "other")).as("and expires after the retention")
                .isInstanceOf(IdempotencyStore.Begin.Proceed.class);
    }

    @Test
    void redisKeysRevealNeitherTheCallerNorThePathNorTheKey() {
        FakeRedis redis = new FakeRedis();
        redis.store(Duration.ofHours(1), Duration.ofMinutes(1)).begin("alice@example.org POST /api/v1/jobs", "secret-key", "f");

        assertThat(redis.values).hasSize(1);
        String id = redis.values.keySet().iterator().next();
        assertThat(id).startsWith(RedisIdempotencyStore.PREFIX).doesNotContain("alice").doesNotContain("jobs")
                .doesNotContain("secret-key");
        assertThat(redis.values.get(id)).doesNotContain("alice").doesNotContain("secret-key");
    }

    @Test
    void anUnreachableRedisMakesTheStoreUnavailableButNeverFailsASucceededRequest() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.opsForValue()).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(template.delete(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));
        RedisIdempotencyStore store = new RedisIdempotencyStore(template, JSON, Duration.ofHours(1), Duration.ofMinutes(1));

        assertThatThrownBy(() -> store.begin("alice", "k", "f")).isInstanceOf(IdempotencyStore.UnavailableException.class);
        store.complete("alice", "k", "f", new IdempotencyStore.StoredResponse(200, Map.of(), new byte[0]));
        store.abandon("alice", "k");
    }

    @Test
    void whenTheStoreIsUnavailableTheRequestIsRefusedWith503AndNotRun() throws Exception {
        IdempotencyStore down = mock(IdempotencyStore.class);
        when(down.begin(anyString(), anyString(), anyString()))
                .thenThrow(new IdempotencyStore.UnavailableException("down", null));
        IdempotencyFilter filter = new IdempotencyFilter(down, JSON, null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/jobs/1:start");
        request.addHeader(ApiV1.IDEMPOTENCY_KEY, "run-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("idempotency-unavailable");
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    }

    @Test
    void theStoreIsChosenByConfigurationAndAMistakeStopsTheStartup() {
        ApiV1Configuration configuration = new ApiV1Configuration();
        ObjectProvider<StringRedisTemplate> none = provider(null);
        ObjectProvider<StringRedisTemplate> some = provider(mock(StringRedisTemplate.class));
        ObjectProvider<JdbcTemplate> noDatabase = provider(null);
        ObjectProvider<JdbcTemplate> database = provider(mock(JdbcTemplate.class));
        Duration day = Duration.ofHours(24);
        Duration tenMinutes = Duration.ofMinutes(10);

        assertThat(configuration.apiV1IdempotencyStore("memory", day, tenMinutes, 10, 10, none, noDatabase, JSON))
                .isInstanceOf(InMemoryIdempotencyStore.class);
        assertThat(configuration.apiV1IdempotencyStore(" Redis ", day, tenMinutes, 10, 10, some, noDatabase, JSON))
                .isInstanceOf(RedisIdempotencyStore.class);
        assertThat(configuration.apiV1IdempotencyStore("JDBC", day, tenMinutes, 10, 10, none, database, JSON))
                .isInstanceOf(JdbcIdempotencyStore.class);
        assertThatThrownBy(() -> configuration.apiV1IdempotencyStore("redis", day, tenMinutes, 10, 10, none, database, JSON))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.data.redis");
        assertThatThrownBy(() -> configuration.apiV1IdempotencyStore("jdbc", day, tenMinutes, 10, 10, some, noDatabase, JSON))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.datasource");
        assertThatThrownBy(() -> configuration.apiV1IdempotencyStore("mongo", day, tenMinutes, 10, 10, some, database, JSON))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'memory', 'redis' or 'jdbc'");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** Just enough of Redis: strings with an expiry, SET NX, SET with expiry, GET and DEL. */
    private static final class FakeRedis {
        final Map<String, String> values = new HashMap<>();
        final Map<String, Instant> expiries = new HashMap<>();
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-15T10:00:00Z"));

        void advance(Duration duration) {
            now.set(now.get().plus(duration));
        }

        private void expire() {
            expiries.entrySet().removeIf(e -> {
                boolean expired = !e.getValue().isAfter(now.get());
                if (expired) {
                    values.remove(e.getKey());
                }
                return expired;
            });
        }

        @SuppressWarnings("unchecked")
        RedisIdempotencyStore store(Duration retention, Duration inProgressTimeout) {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(ops);
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call -> {
                expire();
                String id = call.getArgument(0);
                if (values.containsKey(id)) {
                    return false;
                }
                values.put(id, call.getArgument(1));
                expiries.put(id, now.get().plus(call.<Duration>getArgument(2)));
                return true;
            });
            when(ops.get(anyString())).thenAnswer(call -> {
                expire();
                return values.get(call.<String>getArgument(0));
            });
            org.mockito.Mockito.doAnswer(call -> {
                values.put(call.getArgument(0), call.getArgument(1));
                expiries.put(call.getArgument(0), now.get().plus(call.<Duration>getArgument(2)));
                return null;
            }).when(ops).set(anyString(), anyString(), any(Duration.class));
            when(template.expire(anyString(), any(Duration.class))).thenAnswer(call -> {
                expire();
                String id = call.getArgument(0);
                if (!values.containsKey(id)) {
                    return false;
                }
                expiries.put(id, now.get().plus(call.<Duration>getArgument(1)));
                return true;
            });
            when(template.delete(anyString())).thenAnswer(call -> {
                expiries.remove(call.<String>getArgument(0));
                return values.remove(call.<String>getArgument(0)) != null;
            });
            return new RedisIdempotencyStore(template, JSON, retention, inProgressTimeout);
        }
    }
}
