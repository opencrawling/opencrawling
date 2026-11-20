/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.runtime.scheduler;

import org.opencrawling.runtime.api.PersistenceHelper;
import org.opencrawling.runtime.controlplane.ControlPlaneStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-backed {@link ScheduleStore} (#197) storing {@code schedule-state.json} and {@code schedule-dispatches.json}
 * in the configured data directory for single-node restarts (so misfires are preserved accurately across boots).
 */
public final class FileScheduleStore implements ScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(FileScheduleStore.class);
    static final String STATE_FILE = "schedule-state.json";
    static final String DISPATCH_FILE = "schedule-dispatches.json";
    private static final int MAX_DISPATCHES = 10_000;

    private final ControlPlaneStore controlPlane;
    private final Path dataDir;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final ReentrantLock ioLock = new ReentrantLock();

    public FileScheduleStore(ControlPlaneStore controlPlane) {
        this(controlPlane, Path.of(PersistenceHelper.DATA_DIR));
    }

    public FileScheduleStore(ControlPlaneStore controlPlane, Path dataDir) {
        this.controlPlane = controlPlane != null ? controlPlane : ControlPlaneStore.files();
        this.dataDir = dataDir;
    }

    @Override
    public Optional<JobScheduleState> getState(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        ioLock.lock();
        try {
            return Optional.ofNullable(loadStateMap().get(jobId));
        } finally {
            ioLock.unlock();
        }
    }

    @Override
    public Map<String, JobScheduleState> getAllStates() {
        ioLock.lock();
        try {
            return new LinkedHashMap<>(loadStateMap());
        } finally {
            ioLock.unlock();
        }
    }

    @Override
    public void saveState(JobScheduleState state) {
        controlPlane.locked(() -> {
            ioLock.lock();
            try {
                Map<String, JobScheduleState> map = loadStateMap();
                map.put(state.jobId(), state);
                writeJson(STATE_FILE, map);
                return null;
            } finally {
                ioLock.unlock();
            }
        });
    }

    @Override
    public void deleteState(String jobId) {
        if (jobId == null) {
            return;
        }
        controlPlane.locked(() -> {
            ioLock.lock();
            try {
                Path file = dataDir.resolve(STATE_FILE);
                if (!Files.exists(file)) {
                    return null;
                }
                Map<String, JobScheduleState> map = loadStateMap();
                if (map.remove(jobId) != null) {
                    writeJson(STATE_FILE, map);
                }
                return null;
            } finally {
                ioLock.unlock();
            }
        });
    }

    @Override
    public List<JobScheduleState> findDue(Instant now, int limit) {
        int cap = Math.max(1, limit);
        return getAllStates().values().stream()
                .filter(s -> s.enabled() && s.nextFireAt() != null && !s.nextFireAt().isAfter(now))
                .sorted(Comparator.comparing(JobScheduleState::nextFireAt).thenComparing(JobScheduleState::jobId))
                .limit(cap)
                .toList();
    }

    @Override
    public void recordDispatch(ScheduleDispatchRecord dispatch) {
        controlPlane.locked(() -> {
            ioLock.lock();
            try {
                List<ScheduleDispatchRecord> list = loadDispatchList();
                list.add(dispatch);
                while (list.size() > MAX_DISPATCHES) {
                    list.removeFirst();
                }
                writeJson(DISPATCH_FILE, list);
                return null;
            } finally {
                ioLock.unlock();
            }
        });
    }

    @Override
    public List<ScheduleDispatchRecord> listDispatches(String jobId) {
        ioLock.lock();
        try {
            return loadDispatchList().stream()
                    .filter(d -> d.jobId().equals(jobId))
                    .sorted(Comparator.comparing(ScheduleDispatchRecord::dispatchedAt).reversed()
                            .thenComparing(Comparator.comparing(ScheduleDispatchRecord::id).reversed()))
                    .toList();
        } finally {
            ioLock.unlock();
        }
    }

    @Override
    public int countDispatchesSince(Instant since, List<DispatchOutcome> outcomes) {
        ioLock.lock();
        try {
            return (int) loadDispatchList().stream()
                    .filter(d -> !d.dispatchedAt().isBefore(since))
                    .filter(d -> outcomes == null || outcomes.isEmpty() || outcomes.contains(d.outcome()))
                    .count();
        } finally {
            ioLock.unlock();
        }
    }

    @Override
    public int deleteDispatchesBefore(Instant cutoff) {
        return controlPlane.locked(() -> {
            ioLock.lock();
            try {
                Path file = dataDir.resolve(DISPATCH_FILE);
                if (!Files.exists(file)) {
                    return 0;
                }
                List<ScheduleDispatchRecord> list = loadDispatchList();
                int before = list.size();
                list.removeIf(d -> d.dispatchedAt().isBefore(cutoff));
                int deleted = before - list.size();
                if (deleted > 0) {
                    writeJson(DISPATCH_FILE, list);
                }
                return deleted;
            } finally {
                ioLock.unlock();
            }
        });
    }

    private Map<String, JobScheduleState> loadStateMap() {
        Path file = dataDir.resolve(STATE_FILE);
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            JavaType type = mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, JobScheduleState.class);
            Map<String, JobScheduleState> loaded = mapper.readValue(file.toFile(), type);
            return loaded != null ? loaded : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private List<ScheduleDispatchRecord> loadDispatchList() {
        Path file = dataDir.resolve(DISPATCH_FILE);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            JavaType type = mapper.getTypeFactory().constructCollectionType(ArrayList.class, ScheduleDispatchRecord.class);
            List<ScheduleDispatchRecord> loaded = mapper.readValue(file.toFile(), type);
            return loaded != null ? loaded : new ArrayList<>();
        } catch (RuntimeException e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeJson(String filename, Object value) {
        try {
            Path target = dataDir.resolve(filename).toAbsolutePath();
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), "." + filename, ".tmp");
            try {
                mapper.writeValue(tmp.toFile(), value);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write schedule file " + filename + ": " + e.getMessage(), e);
        }
    }
}
