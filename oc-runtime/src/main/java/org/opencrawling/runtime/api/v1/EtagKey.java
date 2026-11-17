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

import org.opencrawling.runtime.controlplane.ControlPlaneStore;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The key of the keyed digests that go into ETags of resources with secrets (connectors). Made once, at random, and
 * kept in the control-plane store next to the connectors it protects (#315), so tags stay the same across restarts
 * and are the same in every runtime that shares the store. A file-based runtime's key file is imported with the rest.
 */
final class EtagKey {

    static final String NAME = "api-v1-etag-key.json";

    private static final Map<ControlPlaneStore, byte[]> KEYS = Collections.synchronizedMap(new WeakHashMap<>());

    private EtagKey() {}

    static byte[] get(ControlPlaneStore store) {
        byte[] key = KEYS.get(store);
        if (key == null) {
            // Loaded outside the map's lock: loading takes the store's lock, which a caller of get may already hold.
            key = load(store);
            byte[] earlier = KEYS.putIfAbsent(store, key);
            if (earlier != null) {
                key = earlier;
            }
        }
        return key.clone();
    }

    /** The stored key, or a new one saved under the store's lock, so two runtimes starting together keep the same. */
    private static byte[] load(ControlPlaneStore store) {
        return store.locked(() -> {
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            String stored = store.loadObject(NAME, String.class, Base64.getEncoder().encodeToString(fresh));
            try {
                byte[] key = Base64.getDecoder().decode(stored);
                if (key.length >= 32) {
                    return key;
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                // an unreadable key: use a fresh key, and keep it
            }
            store.save(NAME, Base64.getEncoder().encodeToString(fresh));
            return fresh;
        });
    }
}
