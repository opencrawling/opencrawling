/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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

    /** What is stored: the body's fingerprint, and once done, the answer (or, if it was not kept, only its status). */
    record Stored(String fingerprint, Integer status, Map<String, List<String>> headers, byte[] body, Boolean answerNotKept) {
        Stored(String fingerprint, Integer status, Map<String, List<String>> headers, byte[] body) {
            this(fingerprint, status, headers, body, null);
        }
    }

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
                if (Boolean.TRUE.equals(stored.answerNotKept())) {
                    return new Begin.AnswerNotKept(stored.status());
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
        write(scope, key, new Stored(fingerprint, response.status(), response.headers(), response.body()));
    }

    @Override
    public void completeWithoutAnswer(String scope, String key, String fingerprint, int status) {
        write(scope, key, new Stored(fingerprint, status, null, null, true));
    }

    /**
     * Keeps the outcome for the retention, trying a few times: the request already ran, so losing its outcome would let
     * a retry, once the in-progress marker expires, run it again. If Redis stays unreachable, that risk is logged.
     */
    private void write(String scope, String key, Stored stored) {
        String value = json.writeValueAsString(stored);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                redis.opsForValue().set(id(scope, key), value, retention);
                return;
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("Could not keep the outcome of a request with an Idempotency-Key in Redis; once its in-progress "
                + "marker expires ({}), a retry with the same key would run it again: {}", inProgressTimeout,
                last != null ? last.toString() : "interrupted");
    }

    /**
     * Renews the in-progress marker while the request runs, so a request longer than the in-progress timeout keeps its
     * key; the marker still expires soon after a runtime that dies mid-request.
     */
    @Override
    public Hold hold(String scope, String key, String fingerprint) {
        String id = id(scope, key);
        Duration every = inProgressTimeout.dividedBy(3).isZero() ? Duration.ofMillis(100) : inProgressTimeout.dividedBy(3);
        Thread renewer = Thread.ofVirtual().name("idempotency-hold").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(every);
                    String value = redis.opsForValue().get(id);
                    if (value == null || json.readValue(value, Stored.class).status() != null) {
                        return; // finished, abandoned or expired: nothing to hold
                    }
                    redis.expire(id, inProgressTimeout);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    log.debug("Could not renew an idempotency key's in-progress marker: {}", e.toString());
                }
            }
        });
        // Closing stops the renewer and waits for it, so no renewal can land after the outcome is written (it would cut
        // the outcome's lifetime to the in-progress timeout).
        return () -> {
            renewer.interrupt();
            try {
                renewer.join(java.time.Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
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
