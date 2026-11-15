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
package org.opencrawling.runtime.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * The API routes that change or expose the whole platform, in one list: connectors (with their credentials), system
 * and transport settings, logs, migrations, Vespa administration, raw report queries and global metrics. Only admins
 * may call them. Job creators may also list connectors (without their configuration) and use the narrativization
 * copilot, which they need to set up jobs.
 *
 * <p>Routes that act on one job, report or trace are checked by their controller against the job's access. Routes with
 * their own caller checks (discovery, dynamic jobs, sidecars, BPM events, OIS ingest) are not listed.
 */
public class ManagementAuthorizationInterceptor implements HandlerInterceptor {

    private record Rule(HttpMethod method, PathPattern pattern, boolean creatorsToo) {
        boolean matches(HttpMethod requestMethod, PathContainer path) {
            return (method == null || method.equals(requestMethod)) && pattern.matches(path);
        }
    }

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    /** Checked in order; the first match decides. */
    static final List<Rule> RULES = List.of(
            rule(HttpMethod.GET, "/api/connectors", true),
            rule(HttpMethod.GET, "/api/connectors/{type}", true),
            rule(null, "/api/connectors/**", false),
            rule(null, "/api/system/settings/**", false),
            rule(null, "/api/system/logs", false),
            rule(null, "/api/v1/admin/**", false),
            rule(null, "/api/mcf-migration/**", false),
            rule(null, "/api/vespa/**", false),
            rule(null, "/api/observability/metrics", false),
            rule(null, "/api/reports/query", false),
            rule(null, "/api/transformation/copilot/**", true));

    private final ManagementAuthorization authorization;

    public ManagementAuthorizationInterceptor(ManagementAuthorization authorization) {
        this.authorization = authorization;
    }

    private static Rule rule(HttpMethod method, String pattern, boolean creatorsToo) {
        return new Rule(method, PARSER.parse(pattern), creatorsToo);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!authorization.isEnabled()) {
            return true;
        }
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        // The path Spring routes on (decoded segments, no path parameters), so an encoded path cannot slip past.
        PathContainer path = (ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : ServletRequestPathUtils.parseAndCache(request)).pathWithinApplication();
        for (Rule rule : RULES) {
            if (rule.matches(method, path)) {
                boolean allowed = authorization.isAdmin() || (rule.creatorsToo() && authorization.canCreateJobs());
                if (!allowed) {
                    throw ManagementAuthorization.forbidden("Only members of the admin groups may do this.");
                }
                return true;
            }
        }
        return true;
    }
}
