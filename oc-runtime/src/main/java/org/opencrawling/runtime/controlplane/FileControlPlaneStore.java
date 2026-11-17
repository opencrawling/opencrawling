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

import org.opencrawling.runtime.api.PersistenceHelper;

import java.util.List;

/** JSON files in {@code data/}, one per name: the runtime's behaviour before #315, and still the default. */
final class FileControlPlaneStore implements ControlPlaneStore {

    static final FileControlPlaneStore INSTANCE = new FileControlPlaneStore();

    private FileControlPlaneStore() {
    }

    @Override
    public <T> List<T> loadList(String name, Class<T> type, List<T> defaults) {
        return PersistenceHelper.loadList(name, type, defaults);
    }

    @Override
    public <T> T loadObject(String name, Class<T> type, T defaultValue) {
        return PersistenceHelper.loadObject(name, type, defaultValue);
    }

    @Override
    public void save(String name, Object data) {
        PersistenceHelper.save(name, data);
    }
}
