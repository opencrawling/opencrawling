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

import org.opencrawling.runtime.api.PersistenceHelper;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The key of the keyed digests that go into ETags of resources with secrets (connectors). Made once, at random, and
 * kept in the runtime's data directory next to the connectors it protects, so tags stay the same across restarts.
 */
final class EtagKey {

    private static final String FILE = "api-v1-etag-key.json";

    private EtagKey() {}

    private static final class Holder {
        static final byte[] KEY = load();

        private static byte[] load() {
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            String stored = PersistenceHelper.loadObject(FILE, String.class, Base64.getEncoder().encodeToString(fresh));
            try {
                byte[] key = Base64.getDecoder().decode(stored);
                if (key.length >= 32) {
                    return key;
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                // an unreadable key file: use a fresh key, and keep it
            }
            PersistenceHelper.save(FILE, Base64.getEncoder().encodeToString(fresh));
            return fresh;
        }
    }

    static byte[] get() {
        return Holder.KEY.clone();
    }
}
