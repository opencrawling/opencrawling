/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

import java.util.List;

/**
 * Where the runtime keeps its definitions: jobs, connectors, system and transport settings (#315).
 *
 * <p>Names are the file names the runtime has always used ({@code jobs.json}, {@code connectors.json},
 * {@code settings.json}, ...). {@code opencrawling.control-plane.store} chooses the backend: {@code file} (the default,
 * JSON files in {@code data/}) or {@code jdbc} (the runtime's database, with versioned schema migrations), which
 * imports the existing files on its first start.
 */
public interface ControlPlaneStore {

    /** The list saved under {@code name}; when there is none yet, {@code defaults}, which are then saved. */
    <T> List<T> loadList(String name, Class<T> type, List<T> defaults);

    /** The object saved under {@code name}; when there is none yet, {@code defaultValue}, which is then saved. */
    <T> T loadObject(String name, Class<T> type, T defaultValue);

    /** Saves a list or an object under {@code name}, replacing what was there. */
    void save(String name, Object data);

    /** The JSON files in {@code data/}: the store used when no other is configured. */
    static ControlPlaneStore files() {
        return FileControlPlaneStore.INSTANCE;
    }
}
