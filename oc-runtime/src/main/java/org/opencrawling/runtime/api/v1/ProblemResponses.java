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
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes an {@link ApiProblem} as {@code application/problem+json} (RFC 9457). */
final class ProblemResponses {

    static final String PROBLEM_JSON = "application/problem+json";

    private ProblemResponses() {}

    static Map<String, Object> body(HttpServletRequest request, ApiProblem problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", ApiV1.PROBLEM_TYPE_BASE + problem.slug());
        body.put("title", problem.title());
        body.put("status", problem.status().value());
        if (problem.detail() != null) {
            body.put("detail", problem.detail());
        }
        body.put("instance", request.getRequestURI());
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        problem.extensions().forEach(body::putIfAbsent);
        return body;
    }

    static void write(HttpServletRequest request, HttpServletResponse response, ApiProblem problem, JsonMapper json)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(problem.status().value());
        problem.headers().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body(request, problem));
    }
}
