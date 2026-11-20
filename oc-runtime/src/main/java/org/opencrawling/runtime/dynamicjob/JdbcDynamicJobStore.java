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
package org.opencrawling.runtime.dynamicjob;

import org.opencrawling.core.messaging.ChunkingSpec;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.controlplane.ControlPlaneStore;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.DocumentRequest;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.Entry;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.JobRequest;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.Kind;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.Pending;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.Requester;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.StartView;
import org.opencrawling.runtime.dynamicjob.DynamicJobService.State;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Dynamic jobs in the control-plane database ({@code oc_dynamic_job}, {@code oc_dynamic_job_key}; migration V6), shared
 * by every runtime on it and kept across restarts. Changes that depend on what is stored take the control-plane
 * store's lock ({@link ControlPlaneStore#locked}), the one lock of every runtime on the database.
 *
 * <p>A job waiting for approval keeps what is needed to check and start it once approved: the request and the
 * requester's verified identity (principal, groups, tenant and the token's mapped claims, such as its scopes). The
 * token itself is never kept. That identity is removed when the job is approved, rejected, cancelled or expires.
 */
final class JdbcDynamicJobStore implements DynamicJobStore {

    static final String JOBS = "oc_dynamic_job";
    static final String KEYS = "oc_dynamic_job_key";

    /** A job as stored in {@code body}. */
    record StoredJob(String jobId, String executionId, String name, String connector, Kind kind, StartView start,
                     String owner, String principal, String triggerSource, Instant createdAt, ChunkingSpec chunking,
                     State state, boolean stopRequested, Instant finishedAt, Long documents, StoredPending pending,
                     String approvedBy, String note) {}

    /** A pending request; exactly one of {@code folder} and {@code document} is set. */
    record StoredPending(Caller caller, String owner, String principal, ExecutionProvenance provenance, String connector,
                         JobRequest folder, DocumentRequest document) {}

    /** A key's request; exactly one of {@code folder} and {@code document} is set. */
    record StoredRequest(JobRequest folder, DocumentRequest document) {}

    private final JdbcTemplate jdbc;
    private final ControlPlaneStore lock;
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    JdbcDynamicJobStore(JdbcTemplate jdbc, ControlPlaneStore lock) {
        this.jdbc = jdbc;
        this.lock = lock;
    }

    @Override
    public <T> T locked(Supplier<T> work) {
        return lock.locked(work);
    }

    @Override
    public Optional<Entry> get(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT body FROM " + JOBS + " WHERE job_id = ?", String.class, jobId).stream()
                .findFirst().map(this::entry);
    }

    @Override
    public void save(Entry job) {
        locked(() -> {
            String body = json.writeValueAsString(stored(job));
            int updated = jdbc.update("UPDATE " + JOBS + " SET owner = ?, state = ?, created_at = ?, finished_at = ?, body = ? WHERE job_id = ?",
                    job.owner(), job.state().name(), timestamp(job.createdAt()), timestamp(job.finishedAt()), body, job.jobId());
            if (updated == 0) {
                Long last = jdbc.queryForObject("SELECT MAX(position) FROM " + JOBS, Long.class);
                jdbc.update("INSERT INTO " + JOBS + " (job_id, position, owner, state, created_at, finished_at, body) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        job.jobId(), last == null ? 1 : last + 1, job.owner(), job.state().name(), timestamp(job.createdAt()),
                        timestamp(job.finishedAt()), body);
            }
            return null;
        });
    }

    @Override
    public void delete(String jobId) {
        locked(() -> {
            jdbc.update("DELETE FROM " + KEYS + " WHERE job_id = ?", jobId);
            jdbc.update("DELETE FROM " + JOBS + " WHERE job_id = ?", jobId);
            return null;
        });
    }

    @Override
    public List<Entry> list(String owner) {
        List<String> bodies = owner == null
                ? jdbc.queryForList("SELECT body FROM " + JOBS + " ORDER BY created_at DESC, position", String.class)
                : jdbc.queryForList("SELECT body FROM " + JOBS + " WHERE owner = ? ORDER BY created_at DESC, position", String.class, owner);
        return bodies.stream().map(this::entry).toList();
    }

    @Override
    public int count(String owner, State... states) {
        if (states.length == 0) {
            return 0;
        }
        String in = Arrays.stream(states).map(state -> "?").collect(Collectors.joining(", "));
        Object[] args = new Object[states.length + 1];
        args[0] = owner;
        for (int i = 0; i < states.length; i++) {
            args[i + 1] = states[i].name();
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + JOBS + " WHERE owner = ? AND state IN (" + in + ")",
                Integer.class, args);
        return count != null ? count : 0;
    }

    @Override
    public List<Entry> unansweredBefore(Instant cutoff) {
        return jdbc.queryForList("SELECT body FROM " + JOBS + " WHERE state = ? AND created_at < ? ORDER BY position",
                        String.class, State.PENDING_APPROVAL.name(), timestamp(cutoff)).stream()
                .map(this::entry).filter(job -> job.approvedBy() == null).toList();
    }

    @Override
    public void evict(Instant cutoff, int maxEnded) {
        locked(() -> {
            jdbc.update("DELETE FROM " + JOBS + " WHERE finished_at IS NOT NULL AND finished_at < ?", timestamp(cutoff));
            Integer ended = jdbc.queryForObject("SELECT COUNT(*) FROM " + JOBS + " WHERE finished_at IS NOT NULL", Integer.class);
            if (ended != null && ended > maxEnded) {
                List<String> oldest = jdbc.queryForList("SELECT job_id FROM " + JOBS
                        + " WHERE finished_at IS NOT NULL ORDER BY position", String.class);
                for (String jobId : oldest.subList(0, ended - maxEnded)) {
                    jdbc.update("DELETE FROM " + JOBS + " WHERE job_id = ?", jobId);
                }
            }
            jdbc.update("DELETE FROM " + KEYS + " WHERE job_id NOT IN (SELECT job_id FROM " + JOBS + ")");
            return null;
        });
    }

    @Override
    public Optional<Keyed> key(String owner, String key) {
        return jdbc.query("SELECT job_id, request FROM " + KEYS + " WHERE id = ?",
                (rs, n) -> new Keyed(rs.getString("job_id"), request(json.readValue(rs.getString("request"), StoredRequest.class))),
                keyId(owner, key)).stream().findFirst();
    }

    @Override
    public void saveKey(String owner, String key, Keyed keyed) {
        locked(() -> {
            String id = keyId(owner, key);
            String request = json.writeValueAsString(stored(keyed.request()));
            if (jdbc.update("UPDATE " + KEYS + " SET job_id = ?, request = ? WHERE id = ?", keyed.jobId(), request, id) == 0) {
                jdbc.update("INSERT INTO " + KEYS + " (id, job_id, request) VALUES (?, ?, ?)", id, keyed.jobId(), request);
            }
            return null;
        });
    }

    @Override
    public void deleteKey(String owner, String key) {
        jdbc.update("DELETE FROM " + KEYS + " WHERE id = ?", keyId(owner, key));
    }

    @Override
    public List<String> ids() {
        return jdbc.queryForList("SELECT job_id FROM " + JOBS + " ORDER BY position", String.class);
    }

    // ---- Conversions ----

    private Entry entry(String body) {
        StoredJob job = json.readValue(body, StoredJob.class);
        Pending pending = job.pending() == null ? null : new Pending(job.pending().caller(),
                new Requester(job.pending().owner(), job.pending().principal(), job.pending().provenance()),
                job.pending().connector(), job.pending().folder() != null ? job.pending().folder() : job.pending().document());
        return new Entry(job.jobId(), job.executionId(), job.name(), job.connector(), job.kind(), job.start(), job.owner(),
                job.principal(), job.triggerSource(), job.createdAt(), job.chunking(), job.state(), job.stopRequested(),
                job.finishedAt(), job.documents(), pending, job.approvedBy(), job.note());
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

    private static StoredRequest stored(Object request) {
        return new StoredRequest(request instanceof JobRequest folder ? folder : null,
                request instanceof DocumentRequest document ? document : null);
    }

    private static Object request(StoredRequest stored) {
        return stored.folder() != null ? stored.folder() : stored.document();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
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
}
