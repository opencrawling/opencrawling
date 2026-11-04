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
package org.opencrawling.authorization.openfga;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Turns OpenCrawling documents and identities into OpenFGA object and user identifiers.
 *
 * <p>OpenFGA ids may not contain whitespace, {@code #}, {@code :} or {@code @} and an object is limited to
 * 256 characters, while repository identities are free text. The rules, applied identically when tuples are
 * written and when access is checked:
 * <ul>
 *   <li>A document is {@code document:<sha256 of its id>}: fixed length, no escaping.</li>
 *   <li>An identity is case-folded exactly as {@link String#equalsIgnoreCase} compares (each code point
 *       mapped to {@code toLowerCase(toUpperCase(c))}, so that for example {@code IŞIK} and {@code ışık} fold to
 *       the same key, as the metadata evaluator treats them) and every character
 *       outside {@code [a-z0-9._~-]} is percent-encoded as UTF-8; a result longer than
 *       {@link #MAX_ENCODED_LENGTH} becomes {@code %h<sha256>}, which no encoding can produce (encoded
 *       bytes are always {@code %} plus two upper-case hex digits).</li>
 *   <li>The metadata evaluator compares names without a leading {@code user:} (principal) or {@code group:}
 *       (role), and an ACL entry's own prefix decides what it can match: {@code user:x} only the principal,
 *       {@code group:x} only a role, a bare {@code x} either. An entry is therefore written to {@code u_<name>}
 *       (user entries), {@code g_<name>} (group entries) or both (bare entries); the caller's principal is a
 *       member of {@code u_<name>} and each role of {@code g_<name>}. A user never matches a group of the same
 *       name.</li>
 * </ul>
 */
public final class OpenFgaIdentifiers {

    public static final String DOCUMENT_TYPE = "document";
    public static final String GROUP_TYPE = "group";
    public static final String USER_TYPE = "user";
    public static final String MEMBER = "member";
    public static final String GRANTEE = "grantee";
    public static final String BLOCKED = "blocked";
    public static final String CAN_READ = "can_read";
    public static final String EVERYONE = USER_TYPE + ":*";
    public static final String ANONYMOUS_USER = USER_TYPE + ":anonymous";

    /** Longest encoded identity kept verbatim; longer ones are hashed. */
    public static final int MAX_ENCODED_LENGTH = 200;

    private static final Pattern USER_PREFIX = Pattern.compile("^(?i)user:");
    private static final Pattern GROUP_PREFIX = Pattern.compile("^(?i)group:");

    private OpenFgaIdentifiers() {
    }

    /** {@code document:<sha256(documentId)>}; surrounding whitespace is ignored, as on the read side. */
    public static String documentObject(String documentId) {
        return DOCUMENT_TYPE + ":" + sha256(documentId.trim());
    }

    /** The group holding users named {@code name}, e.g. {@code group:u_alice}. */
    public static String userNameGroup(String name) {
        return GROUP_TYPE + ":u_" + encode(name);
    }

    /** The group holding members of the group {@code name}, e.g. {@code group:g_finance}. */
    public static String groupNameGroup(String name) {
        return GROUP_TYPE + ":g_" + encode(name);
    }

    /**
     * The groups an ACL entry is written to: {@code user:x} to the user group of {@code x}, {@code group:x} to
     * the group group of {@code x}, and an entry without a prefix to both.
     */
    public static String[] aclEntryGroups(String identity) {
        String trimmed = identity.trim();
        if (USER_PREFIX.matcher(trimmed).lookingAt()) {
            return new String[] {userNameGroup(trimmed.substring(trimmed.indexOf(':') + 1))};
        }
        if (GROUP_PREFIX.matcher(trimmed).lookingAt()) {
            return new String[] {groupNameGroup(trimmed.substring(trimmed.indexOf(':') + 1))};
        }
        return new String[] {userNameGroup(trimmed), groupNameGroup(trimmed)};
    }

    /** The group a caller's principal is a member of: the user group of its name without {@code user:}. */
    public static String principalGroup(String principal) {
        return userNameGroup(USER_PREFIX.matcher(principal.trim()).replaceFirst(""));
    }

    /** The group a caller's role is a member of: the group group of its name without {@code group:}. */
    public static String roleGroup(String role) {
        return groupNameGroup(GROUP_PREFIX.matcher(role.trim()).replaceFirst(""));
    }

    /** The user a caller is checked as. Only a handle for its contextual memberships. */
    public static String userObject(String principal) {
        if (principal == null || principal.isBlank()) {
            return ANONYMOUS_USER;
        }
        return USER_TYPE + ":p_" + encode(principal.trim());
    }

    /** The {@code group:<id>#member} userset of a group object. */
    public static String members(String groupObject) {
        return groupObject + "#" + MEMBER;
    }

    static String encode(String identity) {
        String folded = fold(identity);
        StringBuilder out = new StringBuilder(folded.length());
        for (byte b : folded.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '~' || c == '-') {
                out.append(c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.length() > MAX_ENCODED_LENGTH ? "%h" + sha256(folded) : out.toString();
    }

    /**
     * Case folding with the same equivalence as {@link String#equalsIgnoreCase}: two strings fold to the same
     * value exactly when equalsIgnoreCase considers them equal. {@code String.toLowerCase} is not suitable: it is
     * locale- and context-sensitive and can change the length (for example {@code İ}, final sigma).
     */
    static String fold(String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> out.appendCodePoint(Character.toLowerCase(Character.toUpperCase(cp))));
        return out.toString();
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
