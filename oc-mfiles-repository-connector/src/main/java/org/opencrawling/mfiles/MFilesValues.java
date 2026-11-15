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
package org.opencrawling.mfiles;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Normalises MFWS {@code TypedValue}s by their {@code MFDataType}: Text (1), Integer (2), Floating (3), Date (5), Time (6),
 * Timestamp (7), Boolean (8), Lookup (9), MultiSelectLookup (10), Integer64 (11), FILETIME (12), MultiLineText (13).
 *
 * <p>Dates become {@code yyyy-MM-dd}, timestamps ISO-8601 instants, numbers plain decimals, booleans {@code true}/{@code false};
 * lookups give their display values (the item ids are kept separately). Anything else falls back to the server's
 * {@code DisplayValue}.
 */
final class MFilesValues {

    static final int TEXT = 1;
    static final int INTEGER = 2;
    static final int FLOATING = 3;
    static final int DATE = 5;
    static final int TIME = 6;
    static final int TIMESTAMP = 7;
    static final int BOOLEAN = 8;
    static final int LOOKUP = 9;
    static final int MULTI_SELECT_LOOKUP = 10;
    static final int INTEGER64 = 11;
    static final int MULTI_LINE_TEXT = 13;

    /** A normalised property value: the values to index and, for lookups, the referenced item ids. */
    record Value(List<String> values, List<String> lookupIds) {
        boolean isEmpty() {
            return values.isEmpty();
        }
    }

    private MFilesValues() {
    }

    static Value normalize(JsonNode typedValue) {
        if (typedValue == null || typedValue.isMissingNode() || typedValue.isNull()
                || !typedValue.path("HasValue").asBoolean(true)) {
            return new Value(List.of(), List.of());
        }
        int dataType = typedValue.path("DataType").asInt(0);
        JsonNode value = typedValue.path("Value");
        String display = text(typedValue.path("DisplayValue"));
        switch (dataType) {
            case LOOKUP -> {
                return lookups(List.of(typedValue.path("Lookup")), display);
            }
            case MULTI_SELECT_LOOKUP -> {
                List<JsonNode> lookups = new ArrayList<>();
                typedValue.path("Lookups").forEach(lookups::add);
                return lookups(lookups, display);
            }
            case DATE -> {
                return single(date(text(value)), display);
            }
            case TIMESTAMP -> {
                return single(timestamp(text(value)), display);
            }
            case INTEGER, INTEGER64, FLOATING -> {
                return single(number(value), display);
            }
            case BOOLEAN -> {
                String raw = text(value);
                return single(raw == null ? null : Boolean.toString(Boolean.parseBoolean(raw.toLowerCase(Locale.ROOT))), display);
            }
            case TEXT, MULTI_LINE_TEXT, TIME -> {
                return single(text(value), display);
            }
            default -> {
                return single(null, display);
            }
        }
    }

    private static Value lookups(List<JsonNode> lookups, String display) {
        List<String> values = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (JsonNode lookup : lookups) {
            if (lookup == null || lookup.isMissingNode() || lookup.isNull() || lookup.path("Deleted").asBoolean(false)) {
                continue;
            }
            String name = text(lookup.path("DisplayValue"));
            if (name != null && !name.isBlank()) {
                values.add(name);
            }
            if (lookup.has("Item")) {
                ids.add(lookup.path("Item").asText());
            }
        }
        if (values.isEmpty() && display != null && !display.isBlank()) {
            values.add(display);
        }
        return new Value(List.copyOf(values), List.copyOf(ids));
    }

    private static Value single(String normalized, String display) {
        String v = normalized != null && !normalized.isBlank() ? normalized : display;
        return v == null || v.isBlank() ? new Value(List.of(), List.of()) : new Value(List.of(v), List.of());
    }

    /** {@code 2026-05-01T00:00:00Z} or {@code 2026-05-01} → {@code 2026-05-01}. */
    static String date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.length() >= 10 ? raw.substring(0, 10) : raw).toString();
        } catch (DateTimeParseException e) {
            return raw;
        }
    }

    /** An ISO timestamp with or without offset → an ISO-8601 instant ({@code Z}); a value without offset is taken as UTC. */
    static String timestamp(String raw) {
        return parseInstant(raw).map(Instant::toString).orElse(raw);
    }

    static Optional<Instant> parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(raw).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(Instant.parse(raw));
            } catch (DateTimeParseException e2) {
                try {
                    return Optional.of(LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC));
                } catch (DateTimeParseException ignored) {
                    return Optional.empty();
                }
            }
        }
    }

    private static String number(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue().stripTrailingZeros().toPlainString();
        }
        String raw = text(value);
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.strip()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
