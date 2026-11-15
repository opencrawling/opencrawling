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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Keeps idempotency keys in Redis, so every runtime instance behind a load balancer sees the same keys and they
 * survive a restart.
 *
 * <p>Each key is one Redis string under {@code oc:api:idempotency:<sha-256 of scope and key>}, so callers, paths and
 * keys never appear in Redis. The first request writes a marker with {@code SET NX}, which only one instance can win;
 * the marker expires after {@code inProgressTimeout}, so a runtime that dies mid-request does not block the key for
 * the whole retention. A successful answer replaces the marker and is kept for {@code retention}.
 *
 * <p>When Redis cannot be reached, {@link #begin} throws {@link UnavailableException} and the request is refused
 * rather than run without protection against a duplicate.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    static final String PREFIX = "oc:api:idempotency:";

    /** What is stored: the body's fingerprint, and once done, the answer. */
    record Stored(String fingerprint, Integer status, Map<String, List<String>> headers, byte[] body) {}

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration retention;
    private final Duration inProgressTimeout;

    public RedisIdempotencyStore(StringRedisTemplate redis, JsonMapper json, Duration retention,
                                 Duration inProgressTimeout) {
        this.redis = redis;
        this.json = json;
        this.retention = retention;
        this.inProgressTimeout = inProgressTimeout;
    }

    @Override
    public Begin begin(String scope, String key, String fingerprint) {
        String id = id(scope, key);
        try {
            // Two attempts: the entry can expire between a failed SET NX and the GET.
            for (int attempt = 0; attempt < 2; attempt++) {
                String marker = json.writeValueAsString(new Stored(fingerprint, null, null, null));
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(id, marker, inProgressTimeout))) {
                    return new Begin.Proceed();
                }
                String value = redis.opsForValue().get(id);
                if (value == null) {
                    continue;
                }
                Stored stored = json.readValue(value, Stored.class);
                if (!fingerprint.equals(stored.fingerprint())) {
                    return new Begin.Mismatch();
                }
                if (stored.status() == null) {
                    return new Begin.InProgress();
                }
                return new Begin.Replay(new StoredResponse(stored.status(),
                        stored.headers() != null ? stored.headers() : Map.of(),
                        stored.body() != null ? stored.body() : new byte[0]));
            }
            return new Begin.InProgress();
        } catch (RuntimeException e) {
            throw new UnavailableException("The idempotency store (Redis) cannot be reached", e);
        }
    }

    @Override
    public void complete(String scope, String key, String fingerprint, StoredResponse response) {
        try {
            redis.opsForValue().set(id(scope, key), json.writeValueAsString(
                    new Stored(fingerprint, response.status(), response.headers(), response.body())), retention);
        } catch (RuntimeException e) {
            // The request succeeded; only a retry's replay is lost. The marker expires after the in-progress timeout.
            log.warn("Could not remember an idempotent answer in Redis: {}", e.toString());
        }
    }

    @Override
    public void abandon(String scope, String key) {
        try {
            redis.delete(id(scope, key));
        } catch (RuntimeException e) {
            log.warn("Could not release an idempotency key in Redis (it expires on its own): {}", e.toString());
        }
    }

    static String id(String scope, String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((scope + "\n" + key).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
