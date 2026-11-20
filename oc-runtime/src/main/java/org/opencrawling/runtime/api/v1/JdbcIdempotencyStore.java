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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps idempotency keys in the control-plane database ({@code oc_idempotency_key}, migration V4), so every runtime on
 * that database sees the same keys and they survive a restart, without Redis.
 *
 * <p>It behaves as {@link RedisIdempotencyStore} does:
 * <ul>
 *   <li>A row's id is the SHA-256 of the scope and key, as in Redis, so callers, paths and keys never reach the
 *       database.</li>
 *   <li>The first request inserts a row without a status. The primary key lets only one runtime win, with no lock.</li>
 *   <li>That marker expires after {@code inProgressTimeout}, so a runtime that dies mid-request does not block the key
 *       for the whole retention; {@link #hold} renews it while the request runs.</li>
 *   <li>A successful answer replaces the marker and is kept for {@code retention}.</li>
 *   <li>When the database cannot be reached, {@link #begin} throws {@link UnavailableException} and the request is
 *       refused rather than run without protection against a duplicate.</li>
 * </ul>
 *
 * <p>Expired rows are ignored at once and deleted at most once per {@code sweepEvery}, by whichever runtime begins a
 * request next.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcIdempotencyStore.class);
    static final String TABLE = "oc_idempotency_key";

    /** What {@code answer} holds: the headers and body of the answer to replay. */
    record Answer(Map<String, List<String>> headers, byte[] body) {
    }

    private record Row(String fingerprint, Integer status, String answer) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Duration retention;
    private final Duration inProgressTimeout;
    private final Duration sweepEvery;
    private final Clock clock;
    private final AtomicReference<Instant> lastSweep = new AtomicReference<>(Instant.MIN);

    public JdbcIdempotencyStore(JdbcTemplate jdbc, JsonMapper json, Duration retention, Duration inProgressTimeout,
                                Duration sweepEvery, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.retention = retention;
        this.inProgressTimeout = inProgressTimeout;
        this.sweepEvery = sweepEvery;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public JdbcIdempotencyStore(JdbcTemplate jdbc, JsonMapper json, Duration retention, Duration inProgressTimeout) {
        this(jdbc, json, retention, inProgressTimeout, Duration.ofMinutes(5), null);
    }

    @Override
    public Begin begin(String scope, String key, String fingerprint) {
        String id = id(scope, key);
        try {
            Instant now = clock.instant();
            sweep(now);
            // Two attempts: the row can expire, or be abandoned, between a refused insert and the read.
            for (int attempt = 0; attempt < 2; attempt++) {
                jdbc.update("DELETE FROM " + TABLE + " WHERE id = ? AND expires_at <= ?", id, timestamp(now));
                try {
                    jdbc.update("INSERT INTO " + TABLE + " (id, fingerprint, status, answer, expires_at) VALUES (?, ?, NULL, NULL, ?)",
                            id, fingerprint, timestamp(now.plus(inProgressTimeout)));
                    return new Begin.Proceed();
                } catch (DuplicateKeyException taken) {
                    // another request holds the key: read how it went
                }
                Row row = read(id, now);
                if (row == null) {
                    continue;
                }
                if (!fingerprint.equals(row.fingerprint())) {
                    return new Begin.Mismatch();
                }
                if (row.status() == null) {
                    return new Begin.InProgress();
                }
                if (row.answer() == null) {
                    return new Begin.AnswerNotKept(row.status());
                }
                Answer answer = json.readValue(row.answer(), Answer.class);
                return new Begin.Replay(new StoredResponse(row.status(),
                        answer.headers() != null ? answer.headers() : Map.of(),
                        answer.body() != null ? answer.body() : new byte[0]));
            }
            return new Begin.InProgress();
        } catch (RuntimeException e) {
            throw new UnavailableException("The idempotency store (database) cannot be reached", e);
        }
    }

    @Override
    public void complete(String scope, String key, String fingerprint, StoredResponse response) {
        write(scope, key, fingerprint, response.status(),
                json.writeValueAsString(new Answer(response.headers(), response.body())));
    }

    @Override
    public void completeWithoutAnswer(String scope, String key, String fingerprint, int status) {
        write(scope, key, fingerprint, status, null);
    }

    /**
     * Keeps the outcome for the retention, trying a few times: the request already ran, so losing its outcome would let
     * a retry, once the in-progress marker expires, run it again. If the database stays unreachable, that risk is
     * logged. The marker may have expired, or been taken over, while the request ran; the outcome replaces it all the
     * same, as {@link RedisIdempotencyStore} does.
     */
    private void write(String scope, String key, String fingerprint, int status, String answer) {
        String id = id(scope, key);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Timestamp expires = timestamp(clock.instant().plus(retention));
                int updated = jdbc.update("UPDATE " + TABLE + " SET fingerprint = ?, status = ?, answer = ?, expires_at = ? WHERE id = ?",
                        fingerprint, status, answer, expires, id);
                if (updated == 0) {
                    jdbc.update("INSERT INTO " + TABLE + " (id, fingerprint, status, answer, expires_at) VALUES (?, ?, ?, ?, ?)",
                            id, fingerprint, status, answer, expires);
                }
                return;
            } catch (DuplicateKeyException inserted) {
                last = inserted; // a new marker appeared between the update and the insert: update it next time
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
        log.error("Could not keep the outcome of a request with an Idempotency-Key in the database; once its in-progress "
                + "marker expires ({}), a retry with the same key would run it again: {}", inProgressTimeout,
                last != null ? last.toString() : "interrupted");
    }

    /**
     * Renews the in-progress marker while the request runs, so a request longer than the in-progress timeout keeps its
     * key; the marker still expires soon after a runtime that dies mid-request. Only a row without a status is renewed,
     * so a renewal can never cut a finished request's retention short.
     */
    @Override
    public Hold hold(String scope, String key, String fingerprint) {
        String id = id(scope, key);
        Duration every = inProgressTimeout.dividedBy(3).isZero() ? Duration.ofMillis(100) : inProgressTimeout.dividedBy(3);
        Thread renewer = Thread.ofVirtual().name("idempotency-hold").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(every);
                    int renewed = jdbc.update("UPDATE " + TABLE + " SET expires_at = ? WHERE id = ? AND fingerprint = ? AND status IS NULL",
                            timestamp(clock.instant().plus(inProgressTimeout)), id, fingerprint);
                    if (renewed == 0) {
                        return; // finished, abandoned or expired: nothing to hold
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    log.debug("Could not renew an idempotency key's in-progress marker: {}", e.toString());
                }
            }
        });
        return () -> {
            renewer.interrupt();
            try {
                renewer.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Override
    public void abandon(String scope, String key) {
        try {
            jdbc.update("DELETE FROM " + TABLE + " WHERE id = ?", id(scope, key));
        } catch (RuntimeException e) {
            log.warn("Could not release an idempotency key in the database (it expires on its own): {}", e.toString());
        }
    }

    /** Deletes every expired row; returns how many. */
    int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM " + TABLE + " WHERE expires_at <= ?", timestamp(now));
    }

    private void sweep(Instant now) {
        Instant last = lastSweep.get();
        if (now.isBefore(last.plus(sweepEvery)) || !lastSweep.compareAndSet(last, now)) {
            return;
        }
        try {
            int deleted = deleteExpired(now);
            if (deleted > 0) {
                log.debug("Deleted {} expired idempotency keys", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("Could not delete expired idempotency keys (tried again later): {}", e.toString());
        }
    }

    private Row read(String id, Instant now) {
        List<Row> rows = jdbc.query("SELECT fingerprint, status, answer FROM " + TABLE + " WHERE id = ? AND expires_at > ?",
                (rs, n) -> new Row(rs.getString("fingerprint"), (Integer) rs.getObject("status"), rs.getString("answer")),
                id, timestamp(now));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The SHA-256 of the scope and key, in hex: the Redis key without its prefix. */
    static String id(String scope, String key) {
        return RedisIdempotencyStore.id(scope, key).substring(RedisIdempotencyStore.PREFIX.length());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
