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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDBC idempotency store on a real database with the real migrations: the contract every store keeps, expiry,
 * a runtime that dies mid-request, a long request that holds its key, and several runtimes (store objects that share
 * only the database) racing for one key.
 */
abstract class JdbcIdempotencyStoreContract {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A clock the test moves; the database itself is never asked for the time. */
    static final class MovableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-18T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** A fresh, migrated database. */
    abstract JdbcTemplate freshDatabase();

    private JdbcTemplate jdbc;
    private final MovableClock clock = new MovableClock();

    @BeforeEach
    void database() {
        jdbc = freshDatabase();
    }

    private JdbcIdempotencyStore runtime(Duration retention, Duration inProgressTimeout) {
        return new JdbcIdempotencyStore(jdbc, JSON, retention, inProgressTimeout, Duration.ofMinutes(5), clock);
    }

    @Test
    void followsTheContract() {
        IdempotencyStoreTest.followsTheContract(runtime(Duration.ofHours(24), Duration.ofMinutes(10)));
    }

    @Test
    void anAnswerIsReplayedByAnotherRuntimeAndAfterARestart() {
        IdempotencyStore.StoredResponse ok = new IdempotencyStore.StoredResponse(201,
                Map.of("Location", List.of("/api/v1/jobs/7")), new byte[] {0, 1, (byte) 0xff, '"', '\\'});
        JdbcIdempotencyStore a = runtime(Duration.ofHours(24), Duration.ofMinutes(10));
        assertThat(a.begin("alice POST /api/v1/jobs", "k", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        assertThat(runtime(Duration.ofHours(24), Duration.ofMinutes(10)).begin("alice POST /api/v1/jobs", "k", "f"))
                .as("running, seen from another runtime").isInstanceOf(IdempotencyStore.Begin.InProgress.class);
        a.complete("alice POST /api/v1/jobs", "k", "f", ok);
        assertThat(runtime(Duration.ofHours(24), Duration.ofMinutes(10)).begin("alice POST /api/v1/jobs", "k", "f"))
                .as("replayed by a restarted runtime, bytes intact").isEqualTo(new IdempotencyStore.Begin.Replay(ok));
    }

    @Test
    void aMarkerExpiresSoACrashedRuntimeDoesNotBlockTheKeyButAnAnswerIsKeptForTheRetention() {
        JdbcIdempotencyStore store = runtime(Duration.ofHours(24), Duration.ofMinutes(10));

        assertThat(store.begin("alice", "k", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        clock.advance(Duration.ofMinutes(11)); // the runtime died without completing or abandoning
        assertThat(store.begin("alice", "k", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);

        IdempotencyStore.StoredResponse ok = new IdempotencyStore.StoredResponse(202, Map.of(), new byte[] {1, 2});
        clock.advance(Duration.ofMinutes(11)); // a long request: its marker expired before it finished
        store.complete("alice", "k", "f", ok);
        clock.advance(Duration.ofHours(23));
        assertThat(store.begin("alice", "k", "f")).as("the answer outlives the marker")
                .isEqualTo(new IdempotencyStore.Begin.Replay(ok));
        clock.advance(Duration.ofHours(2));
        assertThat(store.begin("alice", "k", "other")).as("and expires after the retention")
                .isInstanceOf(IdempotencyStore.Begin.Proceed.class);
    }

    @Test
    void anAnswerIsKeptEvenWhenItsExpiredMarkerWasAlreadyDeleted() {
        JdbcIdempotencyStore store = runtime(Duration.ofHours(24), Duration.ofMinutes(10));
        assertThat(store.begin("alice", "slow", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        clock.advance(Duration.ofMinutes(11));
        assertThat(store.deleteExpired(clock.instant())).as("a sweep removed the marker mid-request").isEqualTo(1);
        IdempotencyStore.StoredResponse ok = new IdempotencyStore.StoredResponse(201, Map.of(), "{}".getBytes());
        store.complete("alice", "slow", "f", ok);
        assertThat(store.begin("alice", "slow", "f")).as("a retry is answered, not run again")
                .isEqualTo(new IdempotencyStore.Begin.Replay(ok));
    }

    @Test
    void aRunningRequestKeepsItsKeyBeyondTheInProgressTimeout() throws Exception {
        JdbcIdempotencyStore store = runtime(Duration.ofHours(1), Duration.ofMillis(300));
        assertThat(store.begin("alice", "long", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        try (IdempotencyStore.Hold held = store.hold("alice", "long", "f")) {
            for (int i = 0; i < 10; i++) {
                Thread.sleep(100);
                clock.advance(Duration.ofMillis(100));
            }
            assertThat(store.begin("alice", "long", "f")).as("held: still running after 1 s, though the timeout is 300 ms")
                    .isInstanceOf(IdempotencyStore.Begin.InProgress.class);
        }
        clock.advance(Duration.ofSeconds(1));
        assertThat(store.begin("alice", "long", "f")).as("no longer held: it expires")
                .isInstanceOf(IdempotencyStore.Begin.Proceed.class);
    }

    @Test
    void aRenewalNeverCutsAFinishedRequestsRetention() throws Exception {
        JdbcIdempotencyStore store = runtime(Duration.ofHours(24), Duration.ofMillis(30));
        assertThat(store.begin("alice", "k", "f")).isInstanceOf(IdempotencyStore.Begin.Proceed.class);
        IdempotencyStore.Hold held = store.hold("alice", "k", "f");
        Thread.sleep(100); // the renewer is running
        store.complete("alice", "k", "f", new IdempotencyStore.StoredResponse(201, Map.of(), "{}".getBytes()));
        Thread.sleep(100); // renewals that would follow must leave the finished row alone
        held.close();
        clock.advance(Duration.ofHours(23));
        assertThat(store.begin("alice", "k", "f")).as("the answer lives for the retention")
                .isInstanceOf(IdempotencyStore.Begin.Replay.class);
        held.close(); // closing twice is harmless
    }

    @Test
    void ofManyRuntimesRacingForTheSameKeyExactlyOneRunsTheRequest() throws Exception {
        for (int round = 0; round < 5; round++) {
            String key = "run-" + round;
            List<Callable<IdempotencyStore.Begin>> runtimes = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                JdbcIdempotencyStore runtime = runtime(Duration.ofHours(1), Duration.ofMinutes(1));
                runtimes.add(() -> runtime.begin("race POST /api/v1/jobs/1:start", key, "f"));
            }
            try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
                long proceeded = 0;
                for (Future<IdempotencyStore.Begin> result : pool.invokeAll(runtimes)) {
                    IdempotencyStore.Begin begin = result.get();
                    if (begin instanceof IdempotencyStore.Begin.Proceed) {
                        proceeded++;
                    } else {
                        assertThat(begin).isInstanceOf(IdempotencyStore.Begin.InProgress.class);
                    }
                }
                assertThat(proceeded).as("round %d", round).isEqualTo(1);
            }
        }
    }

    @Test
    void expiredKeysAreDeletedAndTheTableRevealsNeitherCallerNorPathNorKey() {
        JdbcIdempotencyStore store = runtime(Duration.ofHours(1), Duration.ofMinutes(1));
        store.begin("alice@example.org POST /api/v1/jobs", "secret-key", "f");
        store.complete("alice@example.org POST /api/v1/jobs", "secret-key", "f",
                new IdempotencyStore.StoredResponse(201, Map.of(), "{}".getBytes()));
        store.begin("bob POST /api/v1/jobs", "running", "f");

        List<String> rows = jdbc.queryForList("SELECT id || ' ' || fingerprint || ' ' || COALESCE(answer, '') FROM "
                + JdbcIdempotencyStore.TABLE, String.class);
        assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row)
                .doesNotContain("alice").doesNotContain("bob").doesNotContain("jobs").doesNotContain("secret-key"));

        clock.advance(Duration.ofMinutes(2));
        assertThat(store.deleteExpired(clock.instant())).as("the running request's marker").isEqualTo(1);
        clock.advance(Duration.ofHours(1));
        assertThat(store.deleteExpired(clock.instant())).as("the answer, after the retention").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + JdbcIdempotencyStore.TABLE, Integer.class)).isZero();
    }

    @Test
    void beginningARequestSweepsExpiredKeysAtMostOncePerInterval() {
        JdbcIdempotencyStore store = runtime(Duration.ofMinutes(1), Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) {
            store.begin("alice", "old-" + i, "f"); // the first one sweeps (nothing yet) and starts the 5-minute interval
        }
        clock.advance(Duration.ofMinutes(6));
        store.begin("alice", "new-1", "f"); // the interval is over: this begin sweeps
        assertThat(count()).as("the three expired keys are gone").isEqualTo(1);

        store.begin("alice", "short", "f");
        clock.advance(Duration.ofMinutes(2));
        store.begin("alice", "new-2", "f"); // within the sweep interval of the last sweep: no sweep yet
        assertThat(count()).isEqualTo(3);
        clock.advance(Duration.ofMinutes(4));
        store.begin("alice", "new-3", "f");
        assertThat(count()).as("swept again after the interval").isEqualTo(1);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + JdbcIdempotencyStore.TABLE, Integer.class);
    }
}
