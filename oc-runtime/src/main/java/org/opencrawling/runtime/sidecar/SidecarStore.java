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
package org.opencrawling.runtime.sidecar;

import java.util.List;

/**
 * Lists and reads the objects of one bucket, for the sidecar reader. Read-only: it never creates or changes anything.
 */
public interface SidecarStore extends AutoCloseable {

    /**
     * One page of keys, in key order.
     *
     * @param truncated whether more keys follow
     */
    record Page(List<String> keys, boolean truncated) {}

    /** The keys that start with {@code prefix} and sort after {@code startAfter} ({@code null}: from the start). */
    Page list(String prefix, String startAfter, int maxKeys) throws Exception;

    /**
     * Reads an object, up to {@code maxBytes + 1} bytes so that a caller can tell when it is larger.
     *
     * @return the bytes, or {@code null} when the object no longer exists
     */
    byte[] read(String key, int maxBytes) throws Exception;

    @Override
    default void close() {
    }
}
