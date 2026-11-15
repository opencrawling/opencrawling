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
package org.opencrawling.doxis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalises descriptor values by their Doxis {@code attributeDataType} so typed descriptors arrive in OIS metadata in a
 * standard form:
 * <ul>
 *   <li>{@code DATE}: {@code 20230222} → {@code 2023-02-22};</li>
 *   <li>{@code DATETIME}: {@code 20230222143005} → {@code 2023-02-22T14:30:05}, ISO-8601 values unchanged;</li>
 *   <li>{@code INTEGER} / {@code LONGINTEGER}: canonical integer;</li>
 *   <li>{@code FLOATINGPOINT}: canonical decimal with a dot ({@code 750000,50} → {@code 750000.50});</li>
 *   <li>{@code BOOL}: {@code true} / {@code false} (also from {@code 1}/{@code 0}, {@code yes}/{@code no}).</li>
 * </ul>
 * Other types (strings, references, enumerations, …) and values that do not parse are returned unchanged.
 */
final class DoxisValues {

    private static final Pattern COMPACT_DATE = Pattern.compile("\\d{8}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern COMPACT_DATETIME = Pattern.compile("\\d{14}");

    private DoxisValues() {
    }

    static String normalize(String dataType, String raw) {
        if (raw == null || dataType == null) {
            return raw;
        }
        String value = raw.strip();
        return switch (dataType.toUpperCase(Locale.ROOT)) {
            case "DATE" -> date(value, raw);
            case "DATETIME" -> dateTime(value, raw);
            case "INTEGER", "LONGINTEGER" -> integer(value, raw);
            case "FLOATINGPOINT" -> decimal(value, raw);
            case "BOOL" -> bool(value, raw);
            default -> raw;
        };
    }

    private static String date(String value, String raw) {
        if (COMPACT_DATE.matcher(value).matches()) {
            return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6, 8);
        }
        if (value.length() >= 10 && ISO_DATE.matcher(value.substring(0, 10)).matches()) {
            return value.substring(0, 10);
        }
        return raw;
    }

    private static String dateTime(String value, String raw) {
        if (COMPACT_DATETIME.matcher(value).matches()) {
            return date(value.substring(0, 8), raw) + "T" + value.substring(8, 10) + ":" + value.substring(10, 12) + ":"
                    + value.substring(12, 14);
        }
        if (COMPACT_DATE.matcher(value).matches()) {
            return date(value, raw) + "T00:00:00";
        }
        try {
            OffsetDateTime.parse(value);
            return value;
        } catch (DateTimeParseException e) {
            return raw;
        }
    }

    private static String integer(String value, String raw) {
        try {
            return Long.toString(Long.parseLong(value.replace("+", "")));
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String decimal(String value, String raw) {
        String candidate = value.contains(".") ? value.replace(",", "") : value.replace(',', '.');
        try {
            return new BigDecimal(candidate).toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String bool(String value, String raw) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "j", "ja" -> "true";
            case "false", "0", "no", "n", "nein" -> "false";
            default -> raw;
        };
    }
}
