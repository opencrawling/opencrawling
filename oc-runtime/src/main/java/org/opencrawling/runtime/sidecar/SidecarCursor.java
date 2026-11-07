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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Sealed paging cursors. A cursor holds the last key a page looked at, which may be a sidecar the caller may not
 * read, so it is encrypted (AES-GCM) rather than merely encoded. It is bound to the connector, the prefix and the
 * caller, and expires.
 */
final class SidecarCursor {

    /** What a cursor carries. */
    record Position(String connector, String caller, String prefix, String startAfter) {}

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_CURSOR_LENGTH = 8192;
    private static final char SEPARATOR = '\u0000';

    private final SecretKeySpec key;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    SidecarCursor(String secret, Duration ttl, Clock clock) {
        this.key = new SecretKeySpec(keyBytes(secret), "AES");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A 256-bit key from the secret, or a random one when there is none. */
    private static byte[] keyBytes(String secret) {
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            return random;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    ("opencrawling-sidecar-cursor\u0000" + secret.strip()).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    String seal(Position position) {
        String plain = clock.instant().getEpochSecond() + String.valueOf(SEPARATOR) + position.connector() + SEPARATOR
                + position.caller() + SEPARATOR + position.prefix() + SEPARATOR + position.startAfter();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    /**
     * The position a cursor holds.
     *
     * @return {@code null} when the cursor was not sealed here, was changed, or has expired
     */
    Position open(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor.strip());
            if (bytes.length <= IV_BYTES) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            String plain = new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
            List<String> parts = split(plain);
            if (parts.size() != 5) {
                return null;
            }
            long issued = Long.parseLong(parts.get(0));
            long age = clock.instant().getEpochSecond() - issued;
            if (age < 0 || age > ttl.toSeconds()) {
                return null;
            }
            return new Position(parts.get(1), parts.get(2), parts.get(3), parts.get(4));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return null;
        }
    }

    private static List<String> split(String value) {
        List<String> parts = new ArrayList<>(5);
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == SEPARATOR) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }
}
