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
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * With {@code opencrawling.api.require-if-match}, changes to versioned resources must say which version they change:
 * a {@code PUT}, {@code PATCH} or {@code DELETE} of a job, connector or settings document without {@code If-Match}
 * answers {@code 428 precondition-required} (RFC 6585), so no Platform API client can overwrite a change it has not
 * seen. The deprecated legacy endpoints are not covered.
 */
public class ConditionalRequestFilter extends OncePerRequestFilter {

    private static final Set<String> CHANGES = Set.of("PUT", "PATCH", "DELETE");
    /** The resources with ETags. */
    private static final Pattern VERSIONED = Pattern.compile(
            ApiV1.BASE + "/(jobs/[^/:]+|jobs/[^/:]+/schedule|connectors/[^/:]+|settings/system|settings/transport)");

    private final JsonMapper json;

    public ConditionalRequestFilter(JsonMapper json) {
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
        if ("PUT".equals(request.getMethod()) && path.matches(ApiV1.BASE + "/jobs/[^/:]+/schedule")) {
            // Creating an initial schedule via PUT has no prior schedule ETag; SchedulesV1Controller enforces If-Match when updating an existing schedule.
            return true;
        }
        return !CHANGES.contains(request.getMethod()) || !VERSIONED.matcher(path).matches()
                || request.getHeader("If-Match") != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ProblemResponses.write(request, response, new ApiProblem(HttpStatus.PRECONDITION_REQUIRED, "precondition-required",
                "Precondition required", "This runtime requires If-Match on changes: read the resource, then send its ETag "
                        + "as If-Match."), json);
    }
}
