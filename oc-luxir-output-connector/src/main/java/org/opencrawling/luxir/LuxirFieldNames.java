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
package org.opencrawling.luxir;

import java.util.Collection;

/**
 * Maps OpenCrawling metadata keys to Luxir field names.
 *
 * <p>Luxir accepts a field name only if it starts with a letter, holds nothing but letters, digits and
 * underscores, has no {@code __} (that separates variants) and is at most 127 bytes long. Metadata keys such as
 * {@code cmis:objectId} or {@code ois:provenance.jobId} break those rules, and Luxir rejects the whole document.
 * Every other character becomes {@code _}, so {@code ois:provenance.jobId} is stored as
 * {@code ois_provenance_jobId_s}.
 */
public final class LuxirFieldNames {

    private static final int MAX_FIELD_NAME_BYTES = 127;

    private LuxirFieldNames() {}

    /**
     * The Luxir field for a metadata entry: the key made valid, with {@code _ss} for a multi-valued value and
     * {@code _s} otherwise. A key that already ends in {@code _s}, {@code _t} or {@code _ss} keeps its suffix.
     */
    public static String forMetadata(String key, Object value) {
        String name = sanitize(key);
        if (name.endsWith("_s") || name.endsWith("_t") || name.endsWith("_ss")) {
            return name;
        }
        boolean multiValued = value instanceof Collection<?> || (value != null && value.getClass().isArray());
        String suffix = multiValued ? "_ss" : "_s";
        int room = MAX_FIELD_NAME_BYTES - suffix.length();
        if (name.length() > room) {
            name = trimTrailingUnderscores(name.substring(0, room));
        }
        return name + suffix;
    }

    /** Replaces every character Luxir does not accept with a single {@code _}, and makes it start with a letter. */
    static String sanitize(String key) {
        StringBuilder out = new StringBuilder(key.length() + 2);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (valid) {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        String name = trimTrailingUnderscores(out.toString());
        // Only ASCII letters and digits are left, so this catches a leading digit or an empty name.
        if (name.isEmpty()) {
            name = "f";
        } else if (!Character.isLetter(name.charAt(0))) {
            name = "f_" + name;
        }
        if (name.length() > MAX_FIELD_NAME_BYTES) {
            name = trimTrailingUnderscores(name.substring(0, MAX_FIELD_NAME_BYTES));
        }
        return name;
    }

    private static String trimTrailingUnderscores(String name) {
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) == '_') {
            end--;
        }
        return name.substring(0, end);
    }
}
