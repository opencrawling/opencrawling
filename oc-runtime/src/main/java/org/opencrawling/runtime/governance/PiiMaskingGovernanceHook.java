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

import org.opencrawling.core.governance.GovernanceDecision;
import org.opencrawling.core.governance.GovernanceSubject;
import org.opencrawling.core.governance.PreEmbeddingGovernanceHook;
import org.opencrawling.runtime.governance.GovernanceProperties.Pii.Type;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.ToIntFunction;

/**
 * The built-in {@value GovernanceProperties#PII_MASKING} hook: replaces e-mail addresses, payment card numbers and
 * IBANs with {@code [REDACTED:<TYPE>]}, in the text and, unless turned off, in the metadata values. Card numbers and
 * IBANs are masked only when their check digits are right, so ordinary long numbers stay.
 *
 * <p>It never rejects. It answers SANITIZE when it masked something and APPROVE otherwise; the audit trail holds
 * how many items of each type were masked, never the items themselves. It finds well-formed data only and is no
 * substitute for a dedicated governance service (the {@value GovernanceProperties#HTTP} hook).
 */
public final class PiiMaskingGovernanceHook implements PreEmbeddingGovernanceHook {

    // IBAN: country code, check digits, then 11 to 30 letters or digits, optionally in groups split by single spaces.
    private static final Pattern IBAN = Pattern.compile("(?<![A-Za-z0-9])[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]){11,30}(?![A-Za-z0-9])");
    // Card: 13 to 19 digits, optionally split by single spaces or dashes.
    private static final Pattern CARD = Pattern.compile("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])");
    // E-mail: possessive labels and a bounded number of them, so the matcher backtracks over whole labels at most.
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}+@(?:[A-Za-z0-9-]{1,63}+\\.){1,10}[A-Za-z]{2,24}+(?![A-Za-z0-9-])");

    private final Set<Type> types;
    private final boolean maskMetadata;

    public PiiMaskingGovernanceHook(Set<Type> types, boolean maskMetadata) {
        this.types = types == null || types.isEmpty() ? EnumSet.noneOf(Type.class) : EnumSet.copyOf(types);
        this.maskMetadata = maskMetadata;
    }

    @Override
    public String name() {
        return GovernanceProperties.PII_MASKING;
    }

    @Override
    public GovernanceDecision evaluate(GovernanceSubject subject) {
        Map<Type, Integer> counts = new EnumMap<>(Type.class);
        String text = mask(subject.text(), counts);
        Map<String, List<String>> metadata = subject.metadata();
        if (maskMetadata) {
            Map<String, List<String>> masked = new LinkedHashMap<>();
            subject.metadata().forEach((key, values) -> {
                if (GovernanceGate.isProtectedKey(key)) {
                    masked.put(key, values);
                } else {
                    List<String> maskedValues = new ArrayList<>(values.size());
                    for (String value : values) {
                        maskedValues.add(mask(value, counts));
                    }
                    masked.put(key, maskedValues);
                }
            });
            metadata = masked;
        }
        if (counts.isEmpty()) {
            return GovernanceDecision.approve();
        }
        Map<String, String> audit = new LinkedHashMap<>();
        counts.forEach((type, count) -> audit.put("masked." + type.name().toLowerCase(Locale.ROOT), String.valueOf(count)));
        return GovernanceDecision.sanitize(text, metadata, audit);
    }

    String mask(String value, Map<Type, Integer> counts) {
        String result = value;
        // IBANs first: their digits could otherwise pass as a card number.
        if (types.contains(Type.IBAN)) {
            result = replace(result, IBAN, Type.IBAN, PiiMaskingGovernanceHook::ibanLength, counts);
        }
        if (types.contains(Type.PAYMENT_CARD)) {
            result = replace(result, CARD, Type.PAYMENT_CARD, match -> validCard(match) ? match.length() : -1, counts);
        }
        if (types.contains(Type.EMAIL)) {
            result = replace(result, EMAIL, Type.EMAIL, String::length, counts);
        }
        return result;
    }

    /**
     * Replaces each match's valid part.
     *
     * @param validLength how much of a match, from its start, is the item; {@code -1} when none of it is
     */
    private static String replace(String value, Pattern pattern, Type type, ToIntFunction<String> validLength,
                                  Map<Type, Integer> counts) {
        Matcher matcher = pattern.matcher(value);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            int length = validLength.applyAsInt(matcher.group());
            if (length <= 0) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(value.length());
            }
            out.append(value, last, matcher.start()).append("[REDACTED:").append(type.name()).append(']');
            last = matcher.start() + length;
            counts.merge(type, 1, Integer::sum);
        }
        if (out == null) {
            return value;
        }
        return out.append(value, last, value.length()).toString();
    }

    /** The Luhn check, over the digits only. */
    static boolean validCard(String candidate) {
        String digits = candidate.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (twice) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            twice = !twice;
        }
        return sum % 10 == 0;
    }

    /**
     * The length of the IBAN a match starts with, or {@code -1}. A match can run into a following group of capitals
     * or digits ({@code GB82 WEST ... 32 THE}), so shorter prefixes ending at a space are tried too.
     */
    static int ibanLength(String match) {
        int end = match.length();
        while (end > 0) {
            if (validIban(match.substring(0, end))) {
                return end;
            }
            end = match.lastIndexOf(' ', end - 1);
        }
        return -1;
    }

    /** The ISO 13616 check: the rearranged number, letters as 10 to 35, is 1 modulo 97. */
    static boolean validIban(String candidate) {
        String iban = candidate.replace(" ", "");
        if (iban.length() < 15 || iban.length() > 34) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (c >= '0' && c <= '9') {
                numeric.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                numeric.append(c - 'A' + 10);
            } else {
                return false;
            }
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
