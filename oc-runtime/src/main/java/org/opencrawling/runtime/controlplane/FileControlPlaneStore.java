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
package org.opencrawling.runtime.controlplane;

import org.opencrawling.runtime.api.PersistenceHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * JSON files in {@code data/}, one per name: the runtime's behaviour before #315, and still the default. One runtime
 * owns the directory, so {@link #locked} is a lock of this runtime only. Reads are cached while the file is unchanged.
 */
final class FileControlPlaneStore implements ControlPlaneStore {

    static final FileControlPlaneStore INSTANCE = new FileControlPlaneStore();

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** A file's content as read, with the file's state when it was read. */
    private record Cached(long modified, long length, Object value) {
    }

    private FileControlPlaneStore() {
    }

    @Override
    public <T> List<T> loadList(String name, Class<T> type, List<T> defaults) {
        @SuppressWarnings("unchecked")
        List<T> list = (List<T>) cached(name, type, () -> PersistenceHelper.loadList(name, type, defaults));
        return new ArrayList<>(list);
    }

    @Override
    public <T> T loadObject(String name, Class<T> type, T defaultValue) {
        return type.cast(cached(name, type, () -> PersistenceHelper.loadObject(name, type, defaultValue)));
    }

    // Package-private for FileControlPlaneStoreTest, which replaces the file during a read.
    Object cached(String name, Class<?> type, Supplier<Object> read) {
        String key = name + "|" + type.getName();
        File file = new File(PersistenceHelper.DATA_DIR, name);
        Cached cached = cache.get(key);
        if (cached != null && file.isFile() && file.lastModified() == cached.modified() && file.length() == cached.length()) {
            return cached.value();
        }
        // The file's state is taken before it is read: a file replaced during the read is then cached under its
        // earlier state and read again next time, never kept as the content of the newer file.
        boolean exists = file.isFile();
        long modified = file.lastModified();
        long length = file.length();
        Object value = read.get();
        if (exists) {
            cache.put(key, new Cached(modified, length, value));
        }
        return value;
    }

    @Override
    public void save(String name, Object data) {
        lock.lock();
        try {
            cache.keySet().removeIf(key -> key.startsWith(name + "|"));
            PersistenceHelper.save(name, data);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <R> R locked(Supplier<R> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
