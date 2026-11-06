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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SidecarCursorTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final SidecarCursor.Position POSITION =
            new SidecarCursor.Position("Lake_S3", "acme/alice", "migration/v1/", "migration/v1/secret-board/minutes.ois.json");

    private static SidecarCursor cursor(String secret, Instant at) {
        return new SidecarCursor(secret, Duration.ofHours(1), Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test
    void aCursorOpensWhereItWasSealedAndHidesItsContent() {
        SidecarCursor codec = cursor("", NOW);
        String sealed = codec.seal(POSITION);

        assertThat(codec.open(sealed)).isEqualTo(POSITION);
        assertThat(sealed).doesNotContain("secret").doesNotContain("alice");
        // Sealed twice, the same position gives different cursors (a fresh IV each time).
        assertThat(codec.seal(POSITION)).isNotEqualTo(sealed);
    }

    @Test
    void changedForeignOrExpiredCursorsDoNotOpen() {
        SidecarCursor codec = cursor("", NOW);
        String sealed = codec.seal(POSITION);
        char last = sealed.charAt(10);
        String changed = sealed.substring(0, 10) + (last == 'A' ? 'B' : 'A') + sealed.substring(11);

        assertThat(codec.open(changed)).isNull();
        assertThat(codec.open("")).isNull();
        assertThat(codec.open("%%%")).isNull();
        assertThat(codec.open("AAAA")).isNull();
        assertThat(codec.open("A".repeat(9000))).isNull();
        assertThat(cursor("", NOW).open(sealed)).isNull();
        assertThat(cursor("", NOW.plusSeconds(10)).open(sealed)).isNull();
        SidecarCursor later = new SidecarCursor("", Duration.ofHours(1), Clock.fixed(NOW.plusSeconds(3601), ZoneOffset.UTC));
        assertThat(later.open(sealed)).isNull();
    }

    @Test
    void theSameSecretOpensAcrossInstancesAndExpiryCounts() {
        String secret = "a-cursor-secret-shared-by-every-instance-0123";
        String sealed = cursor(secret, NOW).seal(POSITION);

        assertThat(cursor(secret, NOW.plusSeconds(3599)).open(sealed)).isEqualTo(POSITION);
        assertThat(cursor(secret, NOW.plusSeconds(3601)).open(sealed)).isNull();
        assertThat(cursor(secret, NOW.minusSeconds(5)).open(sealed)).isNull();
        assertThat(cursor(secret + "x", NOW).open(sealed)).isNull();
    }
}
