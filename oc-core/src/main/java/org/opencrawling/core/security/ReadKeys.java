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
package org.opencrawling.core.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalized read keys that let a store pre-filter by exact match and still agree with the identity rule of the
 * metadata provider ({@code McpAclEvaluator}).
 *
 * <p>The rule compares names case-insensitively, ignores a leading {@code user:} on the caller's principal and
 * {@code group:} on its groups, and lets the ACL identity's own prefix decide the side: {@code user:x} matches a
 * principal only, {@code group:x} a group only, a bare {@code x} either, and {@code public} everyone. A store
 * cannot express that with exact matching on the raw identities, so the writer stores keys and the search asks
 * for the caller's keys:
 * <ul>
 *   <li>{@code user:Alice} is stored as {@code u:alice}; {@code group:Finance} as {@code g:finance};</li>
 *   <li>a bare {@code Finance} as both {@code u:finance} and {@code g:finance};</li>
 *   <li>{@code public} as {@code public};</li>
 *   <li>a caller asks for {@code public}, {@code u:<principal>} and {@code g:<group>} for each group.</li>
 * </ul>
 * A key from the document and a key from the caller are equal exactly when the rule matches the identity to the
 * caller. Case folding mirrors {@link String#equalsIgnoreCase(String)} code point by code point.
 */
public final class ReadKeys {

    /** The metadata field holding the keys of the identities that may read a document. */
    public static final String FIELD = "security_read_keys";

    private static final String PUBLIC = EffectiveAcl.PUBLIC;
    private static final String USER_KEY = "u:";
    private static final String GROUP_KEY = "g:";
    private static final Pattern USER_PREFIX = Pattern.compile("^(?i)user:");
    private static final Pattern GROUP_PREFIX = Pattern.compile("^(?i)group:");

    private ReadKeys() {
    }

    /**
     * The keys of a document's read grants.
     *
     * @param allowedRead the identities that may read the document, as {@link EffectiveAcl#allowedRead()}
     * @return the distinct keys, in the order of the identities
     */
    public static List<String> forAllowed(Collection<String> allowedRead) {
        Set<String> keys = new LinkedHashSet<>();
        if (allowedRead != null) {
            for (String identity : allowedRead) {
                if (identity == null) {
                    continue;
                }
                String trimmed = identity.trim();
                if (trimmed.isEmpty()) {
                    continue; // a blank identity names no one
                }
                if (PUBLIC.equalsIgnoreCase(trimmed)) {
                    keys.add(PUBLIC);
                } else if (USER_PREFIX.matcher(trimmed).lookingAt()) {
                    keys.add(USER_KEY + fold(afterPrefix(trimmed)));
                } else if (GROUP_PREFIX.matcher(trimmed).lookingAt()) {
                    keys.add(GROUP_KEY + fold(afterPrefix(trimmed)));
                } else {
                    keys.add(USER_KEY + fold(trimmed));
                    keys.add(GROUP_KEY + fold(trimmed));
                }
            }
        }
        return List.copyOf(keys);
    }

    /**
     * The keys a caller may read through: {@code public}, the principal and each group.
     *
     * @param principal the caller's principal, or {@code null}
     * @param groups    the caller's groups or roles, or {@code null}; blank entries are ignored
     * @return the distinct keys, {@code public} first
     */
    public static List<String> forCaller(String principal, Collection<String> groups) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(PUBLIC);
        if (principal != null && !principal.isBlank()) {
            keys.add(USER_KEY + fold(USER_PREFIX.matcher(principal.trim()).replaceFirst("")));
        }
        if (groups != null) {
            for (String group : groups) {
                if (group != null && !group.isBlank()) {
                    keys.add(GROUP_KEY + fold(GROUP_PREFIX.matcher(group.trim()).replaceFirst("")));
                }
            }
        }
        return List.copyOf(keys);
    }

    private static String afterPrefix(String identity) {
        return identity.substring(identity.indexOf(':') + 1);
    }

    /**
     * Folds case so that two strings fold to the same value exactly when {@link String#equalsIgnoreCase} holds.
     */
    static String fold(String value) {
        StringBuilder folded = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> folded.appendCodePoint(Character.toLowerCase(Character.toUpperCase(cp))));
        return folded.toString();
    }
}
