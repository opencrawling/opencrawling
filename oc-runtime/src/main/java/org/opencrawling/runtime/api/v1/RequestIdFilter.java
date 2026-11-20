/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every Platform API response an {@code X-Request-Id}: the caller's own, when it sent a well-formed one, or a
 * new one. Problem details carry it too, and it is in the log's MDC as {@code requestId} while the request runs.
 * Runs before Spring Security, so refused requests have one as well.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiV1.isPlatformRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(ApiV1.REQUEST_ID);
        String id = incoming != null && WELL_FORMED.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(ApiV1.REQUEST_ID, id);
        protect(request, response);
        MDC.put("requestId", id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }

    /**
     * Headers every Platform API answer gets, whatever the identity mode (OWASP REST Security Cheat Sheet):
     * <ul>
     *   <li>{@code Cache-Control}: answers are per caller and may hold secrets, so nothing shared may cache them. Reads
     *       are {@code private, no-cache}, so a client keeps them but revalidates with {@code If-None-Match} (RFC 9111);
     *       everything else is {@code no-store}.</li>
     *   <li>{@code Vary: Authorization}: an answer depends on who asks.</li>
     *   <li>{@code X-Content-Type-Options: nosniff} and a {@code Content-Security-Policy} that allows nothing, so a
     *       browser never treats an answer as a page.</li>
     * </ul>
     */
    static void protect(HttpServletRequest request, HttpServletResponse response) {
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        response.setHeader("Cache-Control", read ? "private, no-cache" : "no-store");
        response.addHeader("Vary", "Authorization");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    }

    /** The id of the current request, or null outside the Platform API. */
    public static String currentId(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        return id instanceof String value ? value : null;
    }
}
