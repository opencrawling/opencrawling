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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ReadKeysTest {

    @Test
    void anIdentitysPrefixDecidesItsKeys() {
        assertThat(ReadKeys.forAllowed(List.of("user:Alice", "GROUP:Finance", "Ops", " public ", "PUBLIC", " ")))
                .containsExactly("u:alice", "g:finance", "u:ops", "g:ops", "public");
    }

    @Test
    void aCallerAsksForPublicItsPrincipalAndItsGroups() {
        assertThat(ReadKeys.forCaller(" User:Alice ", Arrays.asList("group:Finance", "HR", " ", null)))
                .containsExactly("public", "u:alice", "g:finance", "g:hr");
        assertThat(ReadKeys.forCaller(null, null)).containsExactly("public");
        assertThat(ReadKeys.forCaller("  ", List.of())).containsExactly("public");
    }

    @Test
    void aUserAndAGroupWithTheSameNameNeverShareAKey() {
        assertThat(ReadKeys.forAllowed(List.of("group:finance")))
                .doesNotContainAnyElementsOf(ReadKeys.forCaller("finance", List.of()));
        assertThat(ReadKeys.forAllowed(List.of("user:alice")))
                .doesNotContainAnyElementsOf(ReadKeys.forCaller(null, List.of("alice")));
    }

    @Test
    void onlyTheLeadingPrefixCounts() {
        assertThat(ReadKeys.forAllowed(List.of("mfiles:user:12"))).containsExactly("u:mfiles:user:12", "g:mfiles:user:12");
        assertThat(ReadKeys.forAllowed(List.of("user:group:x"))).containsExactly("u:group:x");
    }

    @Test
    void foldingAgreesWithEqualsIgnoreCase() {
        String alphabet = "aAbBzZ09:_-.iIıİsSſßẞkKKσΣςµΜÿŸǅǄǆ" + new String(Character.toChars(0x10400)) + new String(Character.toChars(0x10428));
        int[] codePoints = alphabet.codePoints().toArray();
        Random random = new Random(224);
        for (int i = 0; i < 200_000; i++) {
            String a = randomString(random, codePoints);
            String b = randomString(random, codePoints);
            if (a.length() == b.length() || random.nextBoolean()) {
                assertThat(ReadKeys.fold(a).equals(ReadKeys.fold(b)))
                        .as("fold(%s) vs fold(%s)", a, b)
                        .isEqualTo(a.equalsIgnoreCase(b));
            }
        }
    }

    private static String randomString(Random random, int[] codePoints) {
        StringBuilder sb = new StringBuilder();
        int length = random.nextInt(3);
        for (int i = 0; i < length; i++) {
            sb.appendCodePoint(codePoints[random.nextInt(codePoints.length)]);
        }
        return sb.toString();
    }
}
