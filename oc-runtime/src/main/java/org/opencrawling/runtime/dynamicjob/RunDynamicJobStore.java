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
package org.opencrawling.runtime.dynamicjob;

import org.opencrawling.core.messaging.ChunkingSpec;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.DocumentRequest;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.Entry;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.JobRequest;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.Kind;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.Pending;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.Requester;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.StartView;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.State;
import org.opencrawling.runtime.orchestrator.RunStore;
import org.opencrawling.runtime.orchestrator.RunStore.RunEvent;
import org.opencrawling.runtime.orchestrator.RunStore.RunKind;
import org.opencrawling.runtime.orchestrator.RunStore.RunRecord;
import org.opencrawling.runtime.orchestrator.RunStore.RunState;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Dynamic jobs kept as runs (#316): each job is one run in the {@link RunStore}, of kind
 * {@link RunKind#DYNAMIC_FOLDER} or {@link RunKind#DYNAMIC_DOCUMENT}, from the moment it is asked for to its end. The
 * run's state, its history, when it was asked for and ended, and who owns it are the run's own; what the job asked for
 * (its connector, folder or document, chunking, the request waiting for approval) is the run's record
 * ({@link RunRecord#body}). With the {@code file} control-plane store that is this runtime's memory; with {@code jdbc}
 * it is the control-plane database ({@code oc_run}, {@code oc_run_step}), where every runtime sees the same jobs, after
 * a restart too.
 *
 * <p>A job's state follows its run: a job is filed as {@link RunState#QUEUED} (or {@link RunState#PENDING_APPROVAL})
 * and becomes {@link RunState#RUNNING} when the run registry claims its slot. The registry changes an active run
 * (pause, resume, stop, interruption); this store records the changes that only the job decides: approval, and the
 * end of a job that never started (rejected, expired, cancelled, failed). The dynamic-job API keeps its states:
 * {@code QUEUED} reads as {@link State#RUNNING}, as such a job did before.
 *
 * <p>A job waiting for approval keeps what is needed to check and start it once approved: the request and the
 * requester's verified identity (principal, groups, tenant and the token's mapped claims, such as its scopes). The token
 * itself is never kept. That identity is removed when the job is approved, rejected, cancelled or expires.
 */
final class RunDynamicJobStore implements DynamicJobStore {

    /** The idempotency keys of dynamic jobs, stored by a hash of owner and key. */
    interface Keys {
        Optional<Keyed> get(String owner, String key);

        void put(String owner, String key, Keyed keyed);

        void remove(String owner, String key);

        /** Forgets the keys that started any of {@code jobIds}. */
        void removeFor(Collection<String> jobIds);

        static Keys inMemory() {
            return new InMemoryKeys();
        }

        static Keys jdbc(JdbcTemplate jdbc) {
            return new JdbcKeys(jdbc);
        }
    }

    /** A job as stored in its run's record. */
    record StoredJob(String jobId, String executionId, String name, String connector, Kind kind, StartView start,
                     String owner, String principal, String triggerSource, Instant createdAt, ChunkingSpec chunking,
                     State state, boolean stopRequested, Instant finishedAt, Long documents, StoredPending pending,
                     String approvedBy, String note) {}

    /** A pending request; exactly one of {@code folder} and {@code document} is set. */
    record StoredPending(Caller caller, String owner, String principal, ExecutionProvenance provenance, String connector,
                         JobRequest folder, DocumentRequest document) {}

    /** A key's request; exactly one of {@code folder} and {@code document} is set. */
    record StoredRequest(JobRequest folder, DocumentRequest document) {}

    static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final RunStore runs;
    private final Keys keys;
    private final Supplier<String> runtimeId;
    private final Clock clock;

    /**
     * @param runs      the run registry's store
     * @param runtimeId this runtime's id, recorded in the history of the changes it makes
     */
    RunDynamicJobStore(RunStore runs, Keys keys, Supplier<String> runtimeId, Clock clock) {
        this.runs = runs;
        this.keys = keys;
        this.runtimeId = runtimeId;
        this.clock = clock;
    }

    @Override
    public <T> T locked(Supplier<T> work) {
        return runs.locked(work);
    }

    @Override
    public Optional<Entry> get(String jobId) {
        return run(jobId).map(this::entry);
    }

    @Override
    public void save(Entry job) {
        locked(() -> {
            String body = JSON.writeValueAsString(stored(job));
            RunRecord run = runs.get(job.executionId()).orElse(null);
            if (run == null) {
                RunState waiting = job.state() == State.PENDING_APPROVAL ? RunState.PENDING_APPROVAL : RunState.QUEUED;
                runs.request(RunRecord.waiting(job.executionId(), job.jobId(), null, waiting, kind(job.kind()),
                        job.owner(), job.createdAt(), body), runtimeId.get(), null);
                return null;
            }
            String approvedBefore = stored(run).approvedBy();
            runs.updateBody(job.executionId(), body);
            if (approvedBefore == null && job.approvedBy() != null) {
                runs.record(job.executionId(), RunEvent.APPROVED, runtimeId.get(), clock.instant(), job.approvedBy());
            }
            RunState target = runState(job.state());
            if (target.ended() && !run.state().ended()) {
                Instant at = job.finishedAt() != null ? job.finishedAt() : clock.instant();
                if (run.state().waiting()) {
                    runs.settle(job.executionId(), target, runtimeId.get(), at, job.note());
                } else {
                    runs.finish(job.executionId(), target, runtimeId.get(), at, job.note());
                }
            }
            return null;
        });
    }

    @Override
    public void delete(String jobId) {
        locked(() -> {
            run(jobId).ifPresent(run -> runs.delete(run.executionId()));
            keys.removeFor(List.of(jobId));
            return null;
        });
    }

    @Override
    public List<Entry> list(String owner) {
        return runs.requested(owner).stream().map(this::entry).toList();
    }

    @Override
    public int count(String owner, State... states) {
        Set<RunState> wanted = EnumSet.noneOf(RunState.class);
        for (State state : states) {
            wanted.add(runState(state));
            if (state == State.RUNNING) {
                wanted.add(RunState.QUEUED);
            }
        }
        return runs.countRequested(owner, wanted);
    }

    @Override
    public List<Entry> unansweredBefore(Instant cutoff) {
        return runs.requestedBefore(RunState.PENDING_APPROVAL, cutoff).stream().map(this::entry)
                .filter(job -> job.approvedBy() == null).toList();
    }

    @Override
    public void evict(Instant cutoff, int maxEnded) {
        locked(() -> {
            keys.removeFor(runs.forgetEndedRequested(cutoff, maxEnded));
            return null;
        });
    }

    @Override
    public Optional<Keyed> key(String owner, String key) {
        return keys.get(owner, key);
    }

    @Override
    public void saveKey(String owner, String key, Keyed keyed) {
        keys.put(owner, key, keyed);
    }

    @Override
    public void deleteKey(String owner, String key) {
        keys.remove(owner, key);
    }

    @Override
    public List<String> ids() {
        return runs.requestedJobIds();
    }

    private Optional<RunRecord> run(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        return runs.list(jobId).stream().filter(run -> run.kind() != RunKind.JOB).findFirst();
    }

    // ---- Conversions ----

    /** The job a run holds: what it asked for from its record, its state and times from the run itself. */
    private Entry entry(RunRecord run) {
        StoredJob job = stored(run);
        Pending pending = job.pending() == null ? null : new Pending(job.pending().caller(),
                new Requester(job.pending().owner(), job.pending().principal(), job.pending().provenance()),
                job.pending().connector(), job.pending().folder() != null ? job.pending().folder() : job.pending().document());
        return new Entry(run.jobId(), run.executionId(), job.name(), job.connector(), job.kind(), job.start(), run.owner(),
                job.principal(), job.triggerSource(), run.createdAt(), job.chunking(), state(run.state()),
                run.stopRequested(), run.endedAt(), job.documents(), pending, job.approvedBy(), job.note());
    }

    private static StoredJob stored(RunRecord run) {
        return JSON.readValue(run.body(), StoredJob.class);
    }

    private static StoredJob stored(Entry job) {
        Pending pending = job.pending();
        StoredPending storedPending = pending == null ? null : new StoredPending(pending.caller(),
                pending.requester().owner(), pending.requester().principal(), pending.requester().provenance(),
                pending.connector(), pending.request() instanceof JobRequest folder ? folder : null,
                pending.request() instanceof DocumentRequest document ? document : null);
        return new StoredJob(job.jobId(), job.executionId(), job.name(), job.connector(), job.kind(), job.start(),
                job.owner(), job.principal(), job.triggerSource(), job.createdAt(), job.chunking(), job.state(),
                job.stopRequested(), job.finishedAt(), job.documents(), storedPending, job.approvedBy(), job.note());
    }

    private static RunKind kind(Kind kind) {
        return kind == Kind.DOCUMENT ? RunKind.DYNAMIC_DOCUMENT : RunKind.DYNAMIC_FOLDER;
    }

    /** A run's state as the dynamic-job API shows it: a queued job reads as running. */
    static State state(RunState state) {
        return state == RunState.QUEUED ? State.RUNNING : State.valueOf(state.name());
    }

    static RunState runState(State state) {
        return RunState.valueOf(state.name());
    }

    /** The SHA-256 of the owner and key, in hex, so neither is stored. */
    static String keyId(String owner, String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((owner + "\n" + key).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StoredRequest stored(Object request) {
        return new StoredRequest(request instanceof JobRequest folder ? folder : null,
                request instanceof DocumentRequest document ? document : null);
    }

    private static Object request(StoredRequest stored) {
        return stored.folder() != null ? stored.folder() : stored.document();
    }

    /** Keys in this runtime's memory. */
    private static final class InMemoryKeys implements Keys {

        private final Map<String, Keyed> keys = new HashMap<>();

        @Override
        public synchronized Optional<Keyed> get(String owner, String key) {
            return Optional.ofNullable(keys.get(keyId(owner, key)));
        }

        @Override
        public synchronized void put(String owner, String key, Keyed keyed) {
            keys.put(keyId(owner, key), keyed);
        }

        @Override
        public synchronized void remove(String owner, String key) {
            keys.remove(keyId(owner, key));
        }

        @Override
        public synchronized void removeFor(Collection<String> jobIds) {
            if (!jobIds.isEmpty()) {
                keys.values().removeIf(keyed -> jobIds.contains(keyed.jobId()));
            }
        }
    }

    /** Keys in the control-plane database ({@code oc_dynamic_job_key}, migration V6). */
    private static final class JdbcKeys implements Keys {

        static final String TABLE = "oc_dynamic_job_key";

        private final JdbcTemplate jdbc;

        JdbcKeys(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Optional<Keyed> get(String owner, String key) {
            return jdbc.query("SELECT job_id, request FROM " + TABLE + " WHERE id = ?",
                    (rs, n) -> new Keyed(rs.getString("job_id"), request(JSON.readValue(rs.getString("request"), StoredRequest.class))),
                    keyId(owner, key)).stream().findFirst();
        }

        @Override
        public void put(String owner, String key, Keyed keyed) {
            String id = keyId(owner, key);
            String request = JSON.writeValueAsString(stored(keyed.request()));
            if (jdbc.update("UPDATE " + TABLE + " SET job_id = ?, request = ? WHERE id = ?", keyed.jobId(), request, id) == 0) {
                jdbc.update("INSERT INTO " + TABLE + " (id, job_id, request) VALUES (?, ?, ?)", id, keyed.jobId(), request);
            }
        }

        @Override
        public void remove(String owner, String key) {
            jdbc.update("DELETE FROM " + TABLE + " WHERE id = ?", keyId(owner, key));
        }

        @Override
        public void removeFor(Collection<String> jobIds) {
            for (String jobId : jobIds) {
                jdbc.update("DELETE FROM " + TABLE + " WHERE job_id = ?", jobId);
            }
        }
    }
}
