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
package org.opencrawling.runtime.api.v1;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cursor paging and sorting of in-memory collections.
 *
 * <p>A cursor is opaque to clients. It holds the position and a fingerprint of the filters and sort it was made for,
 * so it cannot be reused with other ones ({@code 400}). Each response with a next page also sends
 * {@code Link: <...>; rel="next"} (RFC 8288).
 */
public final class Paging {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;
    private static final Pattern CURSOR = Pattern.compile("v1\\.(\\d{1,9})\\.([A-Za-z0-9_-]{12})");

    private Paging() {}

    /**
     * @param query the filters and sort that define the collection, in a stable form, for the cursor's fingerprint
     */
    public static <T> Page<T> page(List<T> items, Integer limit, String cursor, String query, HttpServletResponse response) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ApiProblem.invalid("limit", "must be between 1 and " + MAX_LIMIT);
        }
        String fingerprint = Etags.hash(query == null ? "" : query);
        int offset = cursor == null || cursor.isBlank() ? 0 : decode(cursor, fingerprint);
        int end = Math.min(items.size(), offset + size);
        List<T> pageItems = offset >= items.size() ? List.of() : List.copyOf(items.subList(offset, end));
        String next = end < items.size() ? encode(end, fingerprint) : null;
        if (next != null && response != null) {
            String url = ServletUriComponentsBuilder.fromCurrentRequest()
                    .replaceQueryParam("cursor", next).build().toUriString();
            response.addHeader("Link", "<" + url + ">; rel=\"next\"");
        }
        return new Page<>(pageItems, new Page.PageInfo(size, next, items.size()));
    }

    static String encode(int offset, String fingerprint) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1." + offset + "." + fingerprint).getBytes(StandardCharsets.US_ASCII));
    }

    static int decode(String cursor, String fingerprint) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("cursor", "is not a cursor from this API");
        }
        Matcher matcher = CURSOR.matcher(raw);
        if (!matcher.matches()) {
            throw ApiProblem.invalid("cursor", "is not a cursor from this API");
        }
        if (!matcher.group(2).equals(fingerprint)) {
            throw ApiProblem.invalid("cursor", "was made for other filters or another sort; start again without a cursor");
        }
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Sorts by a comma-separated list of fields, each optionally prefixed with {@code -} for descending, for example
     * {@code -lastRun,name}. Unknown fields are refused with {@code 400}.
     */
    public static <T> List<T> sort(List<T> items, String sort, Map<String, Comparator<T>> fields) {
        if (sort == null || sort.isBlank()) {
            return items;
        }
        Comparator<T> comparator = null;
        for (String part : sort.split(",")) {
            String field = part.trim();
            boolean descending = field.startsWith("-");
            if (descending || field.startsWith("+")) {
                field = field.substring(1);
            }
            Comparator<T> next = fields.get(field);
            if (next == null) {
                throw ApiProblem.invalid("sort", "unknown field '" + field + "'; use one of " + fields.keySet());
            }
            if (descending) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(comparator);
        return sorted;
    }

    /** A null-safe, case-insensitive comparator on a text field. */
    public static <T> Comparator<T> byText(java.util.function.Function<T, String> field) {
        return Comparator.comparing(field, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }
}
