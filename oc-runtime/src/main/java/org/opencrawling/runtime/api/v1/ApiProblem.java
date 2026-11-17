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

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An error answered as an RFC 9457 problem detail. Thrown by the v1 controllers and written by
 * {@link ApiV1ExceptionResolver}.
 *
 * @param slug the last part of the problem {@code type} URI, for example {@code not-found}
 */
public class ApiProblem extends RuntimeException {

    /** One invalid input value. */
    public record FieldError(String field, String message) {}

    private final HttpStatus status;
    private final String slug;
    private final String title;
    private final Map<String, Object> extensions = new LinkedHashMap<>();
    private final HttpHeaders headers = new HttpHeaders();

    /**
     * Every problem type the Platform API answers with. Each one is documented, under its own anchor, in the guide's
     * table of problem types ({@link ApiV1#PROBLEM_TYPE_BASE}); a test keeps the two the same, and a problem with a type
     * not listed here cannot be made.
     */
    public static final java.util.Set<String> TYPES = java.util.Set.of(
            "validation-failed", "malformed-request", "bad-request", "job-invalid", "invalid-query",
            "unauthorized", "forbidden",
            "not-found", "no-such-endpoint", "export-not-available",
            "method-not-allowed", "not-acceptable", "unsupported-media-type",
            "job-already-running", "job-not-running", "job-not-startable", "connector-exists", "connector-in-use", "idempotency-in-progress",
            "idempotency-answer-unavailable", "conflict", "precondition-failed", "precondition-required", "body-too-large", "idempotency-key-reused",
            "too-many-runs", "too-many-requests", "upstream-error", "unavailable", "idempotency-unavailable",
            "internal-error", "error");

    public ApiProblem(HttpStatus status, String slug, String title, String detail) {
        super(detail);
        if (!TYPES.contains(slug)) {
            throw new IllegalArgumentException("Problem type '" + slug + "' is not in ApiProblem.TYPES (and the guide)");
        }
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public ApiProblem with(String name, Object value) {
        extensions.put(name, value);
        return this;
    }

    public ApiProblem header(String name, String value) {
        headers.set(name, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return getMessage();
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public static ApiProblem notFound(String what, String id) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "not-found", "Not found",
                what + " '" + id + "' does not exist or is not visible to you.");
    }

    public static ApiProblem badRequest(String detail) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", detail);
    }

    public static ApiProblem invalid(List<FieldError> errors) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "The request has " + errors.size() + " invalid value" + (errors.size() == 1 ? "" : "s") + ".")
                .with("errors", errors);
    }

    public static ApiProblem invalid(String field, String message) {
        return invalid(List.of(new FieldError(field, message)));
    }

    public static ApiProblem forbidden(String detail) {
        return new ApiProblem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", detail);
    }

    public static ApiProblem conflict(String slug, String detail) {
        return new ApiProblem(HttpStatus.CONFLICT, slug, "Conflict", detail);
    }

    public static ApiProblem preconditionFailed() {
        return new ApiProblem(HttpStatus.PRECONDITION_FAILED, "precondition-failed", "Precondition failed",
                "The resource changed since you read it (If-Match does not match its ETag). Read it again and retry.");
    }

    public static ApiProblem tooManyRequests(String detail) {
        return new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "too-many-runs", "Too many runs", detail);
    }

    public static ApiProblem unavailable(String detail) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "Service unavailable", detail);
    }

    /** For a status a delegated controller returned without a reason. */
    public static ApiProblem fromStatus(int status, String detail) {
        HttpStatus resolved = HttpStatus.resolve(status);
        if (resolved == null) {
            resolved = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String slug = switch (resolved) {
            case BAD_REQUEST -> "bad-request";
            case FORBIDDEN -> "forbidden";
            case NOT_FOUND -> "not-found";
            case CONFLICT -> "conflict";
            case TOO_MANY_REQUESTS -> "too-many-requests";
            case SERVICE_UNAVAILABLE -> "unavailable";
            default -> resolved.is5xxServerError() ? "internal-error" : "error";
        };
        return new ApiProblem(resolved, slug, resolved.getReasonPhrase(), detail);
    }
}
