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
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.security.CallerResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code Idempotency-Key} for Platform API {@code POST} requests (following the IETF httpapi idempotency-key draft):
 * the first request with a key runs; a retry with the same key and the same body gets the first answer again, with
 * {@code Idempotent-Replayed: true}; the same key with another body is refused ({@code 422}); a retry while the first
 * one still runs is refused ({@code 409}). Keys are scoped to the caller, the method and the path. Runs after Spring
 * Security, so the caller is known.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Headers of a successful answer that are replayed with it. */
    private static final List<String> REPLAYED_HEADERS = List.of("Content-Type", "Location", "ETag", "Link");

    private final IdempotencyStore store;
    private final JsonMapper json;
    private final Supplier<CallerResolver> callerResolver;

    /**
     * @param callerResolver the runtime's caller resolution, so keys are scoped to the same identity the rest of the
     *                       runtime uses (principal claims, tenant); null where there is none
     */
    public IdempotencyFilter(IdempotencyStore store, JsonMapper json, Supplier<CallerResolver> callerResolver) {
        this.store = store;
        this.json = json;
        this.callerResolver = callerResolver != null ? callerResolver : () -> null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || request.getHeader(ApiV1.IDEMPOTENCY_KEY) == null
                || !ApiV1.isPlatformRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(ApiV1.IDEMPOTENCY_KEY).strip();
        // The IETF draft sends the key as a Structured Field string ("..."); a bare token is accepted too.
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1);
        }
        if (request.getContentType() != null && request.getContentType().startsWith("multipart/")) {
            ProblemResponses.write(request, response, ApiProblem.invalid(ApiV1.IDEMPOTENCY_KEY,
                    "is not supported for uploads (multipart requests); send them without one"), json);
            return;
        }
        if (!KEY.matcher(key).matches()) {
            ProblemResponses.write(request, response, ApiProblem.invalid(ApiV1.IDEMPOTENCY_KEY,
                    "must be 1 to 200 letters, digits, '.', '_', ':' or '-'"), json);
            return;
        }
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        } catch (RequestSizeFilter.BodyTooLargeException e) {
            ProblemResponses.write(request, response, RequestSizeFilter.problem(e.maxBytes()), json);
            return;
        }
        if (body.length > MAX_BODY_BYTES) {
            ProblemResponses.write(request, response, new ApiProblem(HttpStatus.CONTENT_TOO_LARGE, "body-too-large",
                    "Request too large", "Requests with an Idempotency-Key may have at most 1 MB of body."), json);
            return;
        }
        String scope = caller() + " POST " + request.getRequestURI();
        String fingerprint = sha256(body);
        IdempotencyStore.Begin begin;
        try {
            begin = store.begin(scope, key, fingerprint);
        } catch (IdempotencyStore.UnavailableException e) {
            logger.warn("Refusing a request with an Idempotency-Key: " + e.getMessage(), e);
            response.setHeader("Retry-After", "5");
            ProblemResponses.write(request, response, new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE,
                    "idempotency-unavailable", "Idempotency keys unavailable",
                    "Requests with an Idempotency-Key cannot be checked right now, so this one was not run; "
                            + "retry later with the same key."), json);
            return;
        }
        switch (begin) {
            case IdempotencyStore.Begin.Replay replay -> {
                IdempotencyStore.StoredResponse stored = replay.response();
                response.setStatus(stored.status());
                stored.headers().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
                response.setHeader("Idempotent-Replayed", "true");
                response.getOutputStream().write(stored.body());
            }
            case IdempotencyStore.Begin.InProgress inProgress -> {
                response.setHeader("Retry-After", "1");
                ProblemResponses.write(request, response, ApiProblem.conflict("idempotency-in-progress",
                        "A request with this Idempotency-Key is still being processed; retry later."), json);
            }
            case IdempotencyStore.Begin.Mismatch mismatch -> ProblemResponses.write(request, response,
                    new ApiProblem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused", "Idempotency key reused",
                            "This Idempotency-Key was already used for a different request."), json);
            case IdempotencyStore.Begin.Proceed proceed -> {
                ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
                boolean completed = false;
                try {
                    chain.doFilter(new CachedBodyRequest(request, body), wrapped);
                    if (wrapped.getStatus() >= 200 && wrapped.getStatus() < 300) {
                        Map<String, List<String>> headers = new LinkedHashMap<>();
                        for (String name : REPLAYED_HEADERS) {
                            if (wrapped.getHeaders(name) != null && !wrapped.getHeaders(name).isEmpty()) {
                                headers.put(name, List.copyOf(wrapped.getHeaders(name)));
                            }
                        }
                        if (!headers.containsKey("Content-Type") && wrapped.getContentType() != null) {
                            headers.put("Content-Type", List.of(wrapped.getContentType()));
                        }
                        store.complete(scope, key, fingerprint, new IdempotencyStore.StoredResponse(wrapped.getStatus(), headers,
                                wrapped.getContentAsByteArray()));
                        completed = true;
                    }
                } finally {
                    if (!completed) {
                        store.abandon(scope, key);
                    }
                    wrapped.copyBodyToResponse();
                }
            }
        }
    }

    /**
     * Who sent the request: the verified caller as the runtime resolves it (its principal claims, for example
     * {@code preferred_username}, and tenant). A token's {@code sub} alone is not enough: tokens without one would all
     * share a scope.
     */
    private String caller() {
        CallerResolver resolver = callerResolver.get();
        Optional<Caller> verified = resolver == null ? Optional.empty() : resolver.verifiedCaller();
        if (verified.isPresent()) {
            return "caller:" + Objects.toString(verified.get().tenant(), "") + "/" + verified.get().principal();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && authentication.getName() != null
                ? "auth:" + authentication.getName() : "anonymous";
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lets the controller read a body the filter has already read. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
