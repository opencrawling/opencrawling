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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Marks answers of the legacy management endpoints as deprecated, with their Platform API successor:
 * {@code Deprecation: @<seconds>} (RFC 9745) and {@code Link: <successor>; rel="successor-version"} (RFC 5829). The
 * endpoints behave exactly as before; only the headers are added.
 */
public class LegacyDeprecationFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return LegacyApi.successor(request.getRequestURI()).isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        LegacyApi.successor(request.getRequestURI()).ifPresent(successor -> {
            response.setHeader("Deprecation", LegacyApi.deprecationHeader());
            response.addHeader("Link", "<" + successor + ">; rel=\"successor-version\"");
        });
        chain.doFilter(request, response);
    }
}
