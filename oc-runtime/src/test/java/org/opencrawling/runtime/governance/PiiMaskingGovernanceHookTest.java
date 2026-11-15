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
package org.opencrawling.runtime.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.governance.GovernanceDecision;
import org.opencrawling.core.governance.GovernanceDecision.Outcome;
import org.opencrawling.core.governance.GovernanceSubject;
import org.opencrawling.runtime.governance.GovernanceProperties.Pii.Type;

class PiiMaskingGovernanceHookTest {

    private final PiiMaskingGovernanceHook all = new PiiMaskingGovernanceHook(EnumSet.allOf(Type.class), true);

    private static GovernanceSubject subject(String text, Map<String, List<String>> metadata) {
        return new GovernanceSubject("doc", "file:///a", text, metadata, null);
    }

    private String masked(String text) {
        GovernanceDecision decision = all.evaluate(subject(text, Map.of()));
        return decision.outcome() == Outcome.SANITIZE ? decision.text() : text;
    }

    @Test
    void eMailAddressesAreMasked() {
        assertThat(masked("Mail jane.doe+hr@mail.example.co.uk or bob@example.com."))
                .isEqualTo("Mail [REDACTED:EMAIL] or [REDACTED:EMAIL].");
        assertThat(masked("Not an address: @example.com, user@, a@b")).isEqualTo("Not an address: @example.com, user@, a@b");
    }

    @Test
    void cardNumbersAreMaskedOnlyWhenTheirCheckDigitIsRight() {
        assertThat(masked("Card 4111 1111 1111 1111 and 5500-0000-0000-0004.")).isEqualTo("Card [REDACTED:PAYMENT_CARD] and [REDACTED:PAYMENT_CARD].");
        assertThat(masked("Order 4111111111111112 shipped")).isEqualTo("Order 4111111111111112 shipped");
        assertThat(masked("Phone 2125551234, year 2026")).isEqualTo("Phone 2125551234, year 2026");
    }

    @Test
    void ibansAreMaskedOnlyWhenTheirCheckDigitsAreRight() {
        assertThat(masked("Pay DE89 3704 0044 0532 0130 00 now")).isEqualTo("Pay [REDACTED:IBAN] now");
        assertThat(masked("IBAN GB82WEST12345698765432.")).isEqualTo("IBAN [REDACTED:IBAN].");
        // A following group of capitals is not part of the IBAN.
        assertThat(masked("GB82 WEST 1234 5698 7654 32 THE END")).isEqualTo("[REDACTED:IBAN] THE END");
        assertThat(masked("Ref DE89 3704 0044 0532 0130 01")).isEqualTo("Ref DE89 3704 0044 0532 0130 01");
    }

    @Test
    void metadataIsMaskedButNotThePermissionKeys() {
        Map<String, List<String>> metadata = Map.of("owner", List.of("bob@example.com"),
                "acl", List.of("bob@example.com"), "security_allowed_read", List.of("bob@example.com"));

        GovernanceDecision decision = all.evaluate(subject("no personal data here", metadata));

        assertThat(decision.outcome()).isEqualTo(Outcome.SANITIZE);
        assertThat(decision.text()).isEqualTo("no personal data here");
        assertThat(decision.metadata()).containsEntry("owner", List.of("[REDACTED:EMAIL]"))
                .containsEntry("acl", List.of("bob@example.com"))
                .containsEntry("security_allowed_read", List.of("bob@example.com"));
        assertThat(decision.audit()).containsExactlyEntriesOf(Map.of("masked.email", "1"));
    }

    @Test
    void metadataMaskingCanBeTurnedOff() {
        PiiMaskingGovernanceHook textOnly = new PiiMaskingGovernanceHook(EnumSet.allOf(Type.class), false);

        assertThat(textOnly.evaluate(subject("clean", Map.of("owner", List.of("bob@example.com"))))).isEqualTo(GovernanceDecision.approve());
    }

    @Test
    void onlyTheConfiguredTypesAreMasked() {
        PiiMaskingGovernanceHook cards = new PiiMaskingGovernanceHook(EnumSet.of(Type.PAYMENT_CARD), true);

        GovernanceDecision decision = cards.evaluate(subject("bob@example.com 4111111111111111", Map.of()));

        assertThat(decision.text()).isEqualTo("bob@example.com [REDACTED:PAYMENT_CARD]");
        assertThat(decision.audit()).containsExactlyEntriesOf(Map.of("masked.payment_card", "1"));
    }

    @Test
    void nothingFoundIsAnApproval() {
        assertThat(all.evaluate(subject("Quarterly figures: 12% growth.", Map.of("title", List.of("Q3")))))
                .isEqualTo(GovernanceDecision.approve());
        assertThat(all.name()).isEqualTo("pii-masking");
    }

    @Test
    void hostileInputDoesNotStallTheMatcher() {
        String hostile = "a".repeat(200_000) + "@" + "b-".repeat(100_000) + " " + "1 ".repeat(100_000) + "DE89".repeat(50_000);
        long start = System.nanoTime();
        all.evaluate(subject(hostile, Map.of()));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void theCheckDigitAlgorithms() {
        assertThat(PiiMaskingGovernanceHook.validCard("378282246310005")).isTrue();
        assertThat(PiiMaskingGovernanceHook.validCard("378282246310006")).isFalse();
        assertThat(PiiMaskingGovernanceHook.validIban("NL91ABNA0417164300")).isTrue();
        assertThat(PiiMaskingGovernanceHook.validIban("NL91ABNA0417164301")).isFalse();
    }
}
