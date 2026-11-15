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

import jakarta.servlet.http.HttpServletRequest;

/**
 * The OpenCrawling Platform API, version 1: every management operation over REST, under {@code /api/v1} (#313).
 *
 * <p>Conventions shared by all its endpoints:
 * <ul>
 *   <li>Errors are RFC 9457 problem details ({@code application/problem+json}), with a {@code type} URI under
 *       {@link #PROBLEM_TYPE_BASE}, the request id, and field errors for validation failures.</li>
 *   <li>Collections are paged with an opaque {@code cursor} and a {@code limit}; the next page is in
 *       {@code page.nextCursor} and in a {@code Link: <...>; rel="next"} header.</li>
 *   <li>Single resources carry an {@code ETag}; {@code If-None-Match} gives {@code 304}, and {@code If-Match} on a
 *       change gives {@code 412} when the resource changed meanwhile.</li>
 *   <li>Operations that are not create, read, update or delete are custom methods: {@code POST .../{id}:start}.</li>
 *   <li>A {@code POST} with an {@code Idempotency-Key} is done once; a retry with the same key gets the same answer.</li>
 *   <li>Every response has an {@code X-Request-Id}.</li>
 * </ul>
 * The legacy endpoints ({@code /api/jobs}, {@code /api/connectors}, ...) are unchanged; the v1 endpoints call the same
 * controllers, so the rules (validation, authorization, audit) are the same.
 */
public final class ApiV1 {

    public static final String BASE = "/api/v1";
    public static final String VERSION = "1.0.0";
    public static final String PROBLEM_TYPE_BASE = "https://opencrawling.org/problems/";
    public static final String REQUEST_ID = "X-Request-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String MERGE_PATCH_JSON = "application/merge-patch+json";
    public static final String OPENAPI_GROUP = "v1";

    /** Existing endpoints that live under {@code /api/v1} but keep their own conventions. */
    private static final String[] NOT_PLATFORM = {BASE + "/ingest/", BASE + "/admin/"};

    private ApiV1() {}

    /**
     * A URL under {@link #BASE} from path segments, each percent-encoded, so ids and names with spaces or other
     * reserved characters give a valid {@code Location}.
     */
    public static java.net.URI location(String... segments) {
        return org.springframework.web.util.UriComponentsBuilder.fromPath(BASE).pathSegment(segments).encode().build().toUri();
    }

    /** Whether the request is for the Platform API (and not one of the older endpoints that share the prefix). */
    public static boolean isPlatformRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (!(path.equals(BASE) || path.startsWith(BASE + "/"))) {
            return false;
        }
        for (String prefix : NOT_PLATFORM) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
