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
package org.opencrawling.sdk.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * An access token, when it expires, and when to stop using it: 30 seconds before expiry so it never runs out on its
 * way to the server, or halfway through for tokens that live a minute or less.
 */
record ExpiringToken(String value, Instant expiresAt, Instant renewAt) {

    static final Duration EARLY = Duration.ofSeconds(30);

    /** A token from a response that said it lasts {@code expiresIn} seconds (0: unknown, treated as one minute). */
    static ExpiringToken of(String value, long expiresIn, Clock clock) {
        long seconds = expiresIn > 0 ? expiresIn : 60;
        Instant now = clock.instant();
        long early = Math.min(EARLY.toSeconds(), seconds / 2);
        return new ExpiringToken(value, now.plusSeconds(seconds), now.plusSeconds(seconds - early));
    }

    /** A saved token whose lifetime is no longer known. */
    static ExpiringToken saved(String value, Instant expiresAt) {
        return new ExpiringToken(value, expiresAt, expiresAt.minus(EARLY));
    }

    boolean usable(Clock clock) {
        return value != null && clock.instant().isBefore(renewAt);
    }
}
