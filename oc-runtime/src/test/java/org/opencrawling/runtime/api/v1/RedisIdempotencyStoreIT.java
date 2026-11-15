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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Redis idempotency store against a real Redis: the contract, expiry by Redis itself, and two runtime instances
 * racing for the same key, of which exactly one may run the request.
 */
@Testcontainers
class RedisIdempotencyStoreIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis/redis-stack:7.4.0-v8"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate template;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connections.afterPropertiesSet();
        connections.start();
        template = new StringRedisTemplate(connections);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    private static RedisIdempotencyStore store(Duration retention, Duration inProgressTimeout) {
        return new RedisIdempotencyStore(template, JsonMapper.builder().build(), retention, inProgressTimeout);
    }

    @Test
    void followsTheContractOnARealRedis() {
        IdempotencyStoreTest.followsTheContract(store(Duration.ofHours(24), Duration.ofMinutes(10)));
    }

    @Test
    void redisExpiresTheEntries() throws InterruptedException {
        RedisIdempotencyStore store = store(Duration.ofSeconds(2), Duration.ofSeconds(1));

        assertThat(store.begin("expiry", "k", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        Thread.sleep(1500);
        assertThat(store.begin("expiry", "k", "f")).as("the marker of a crashed request expired")
                .isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        IdempotencyStore.StoredResponse ok = new IdempotencyStore.StoredResponse(201, Map.of(), "{}".getBytes());
        store.complete("expiry", "k", "f", ok);
        assertThat(store.begin("expiry", "k", "f")).isEqualTo(new IdempotencyStore.Begin.Replay(ok));
        Thread.sleep(2500);
        assertThat(store.begin("expiry", "k", "f")).as("the answer expired after the retention")
                .isInstanceOf(IdempotencyStore.Begin.Proceed.class);
    }

    @Test
    void ofManyInstancesRacingForTheSameKeyExactlyOneRunsTheRequest() throws Exception {
        // Each "instance" is its own store object sharing only Redis, as runtimes behind a load balancer would.
        List<Callable<IdempotencyStore.Begin>> instances = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            RedisIdempotencyStore instance = store(Duration.ofHours(1), Duration.ofMinutes(1));
            instances.add(() -> instance.begin("race POST /api/v1/jobs/1:start", "run-1", "f"));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            long proceeded = 0;
            for (Future<IdempotencyStore.Begin> result : pool.invokeAll(instances)) {
                IdempotencyStore.Begin begin = result.get();
                if (begin instanceof IdempotencyStore.Begin.Proceed) {
                    proceeded++;
                } else {
                    assertThat(begin).isInstanceOf(IdempotencyStore.Begin.InProgress.class);
                }
            }
            assertThat(proceeded).isEqualTo(1);
        }
    }

    @Test
    void aHeldKeyOutlivesTheInProgressTimeoutOnARealRedis() throws InterruptedException {
        RedisIdempotencyStore store = store(Duration.ofMinutes(1), Duration.ofSeconds(1));
        assertThat(store.begin("hold", "k", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        try (IdempotencyStore.Hold held = store.hold("hold", "k", "f")) {
            Thread.sleep(2500);
            assertThat(store.begin("hold", "k", "f")).isInstanceOf(IdempotencyStore.Begin.InProgress.class);
        }
        Thread.sleep(1500);
        assertThat(store.begin("hold", "k", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
    }
}
