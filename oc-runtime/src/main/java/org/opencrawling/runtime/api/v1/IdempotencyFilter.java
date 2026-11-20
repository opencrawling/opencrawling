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
    /** The default largest body of a request with a key; the runtime uses opencrawling.api.max-request-bytes. */
    static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Headers of a successful answer that are replayed with it. */
    private static final List<String> REPLAYED_HEADERS = List.of("Content-Type", "Location", "ETag", "Link");

    private final IdempotencyStore store;
    private final JsonMapper json;
    private final Supplier<CallerResolver> callerResolver;
    private final int maxStoredBytes;
    private final long maxBodyBytes;

    /**
     * @param callerResolver the runtime's caller resolution, so keys are scoped to the same identity the rest of the
     *                       runtime uses (principal claims, tenant); null where there is none
     */
    public IdempotencyFilter(IdempotencyStore store, JsonMapper json, Supplier<CallerResolver> callerResolver) {
        this(store, json, callerResolver, 256 * 1024, MAX_BODY_BYTES);
    }

    /**
     * @param maxStoredBytes the largest answer kept for replay; for a larger one only its status is kept, and a retry
     *                       learns that the request succeeded instead of running it again
     */
    public IdempotencyFilter(IdempotencyStore store, JsonMapper json, Supplier<CallerResolver> callerResolver,
                             int maxStoredBytes) {
        this(store, json, callerResolver, maxStoredBytes, MAX_BODY_BYTES);
    }

    /** @param maxBodyBytes the largest request body taken, the same as for every request (max-request-bytes) */
    public IdempotencyFilter(IdempotencyStore store, JsonMapper json, Supplier<CallerResolver> callerResolver,
                             int maxStoredBytes, long maxBodyBytes) {
        this.store = store;
        this.json = json;
        this.callerResolver = callerResolver != null ? callerResolver : () -> null;
        this.maxStoredBytes = maxStoredBytes;
        this.maxBodyBytes = maxBodyBytes;
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
        if (request.getContentType() != null
                && request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")) {
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
            body = request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBodyBytes + 1));
        } catch (RequestSizeFilter.BodyTooLargeException e) {
            ProblemResponses.write(request, response, RequestSizeFilter.problem(e.maxBytes()), json);
            return;
        }
        if (body.length > maxBodyBytes) {
            ProblemResponses.write(request, response, RequestSizeFilter.problem(maxBodyBytes), json);
            return;
        }
        String scope = caller() + " POST " + request.getRequestURI();
        // What makes it "the same request": the body, and the query string and media type it was sent with.
        String fingerprint = sha256((Objects.toString(request.getQueryString(), "") + "\n"
                + Objects.toString(request.getContentType(), "") + "\n").getBytes(StandardCharsets.UTF_8), body);
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
            case IdempotencyStore.Begin.AnswerNotKept notKept -> ProblemResponses.write(request, response,
                    new ApiProblem(HttpStatus.CONFLICT, "idempotency-answer-unavailable", "Answer not kept",
                            "The request with this Idempotency-Key already succeeded (status " + notKept.status()
                                    + "), but its answer was not kept. It was not run again; read the resource instead.")
                            .with("firstStatus", notKept.status()), json);
            case IdempotencyStore.Begin.Proceed proceed -> {
                ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
                boolean completed = false;
                try (IdempotencyStore.Hold held = store.hold(scope, key, fingerprint)) {
                    try {
                        chain.doFilter(new CachedBodyRequest(request, body), wrapped);
                    } finally {
                        held.close(); // stop renewing before the outcome is written
                    }
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
                        byte[] answer = wrapped.getContentAsByteArray();
                        if (answer.length > maxStoredBytes) {
                            store.completeWithoutAnswer(scope, key, fingerprint, wrapped.getStatus());
                        } else {
                            store.complete(scope, key, fingerprint,
                                    new IdempotencyStore.StoredResponse(wrapped.getStatus(), headers, answer));
                        }
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

    private static String sha256(byte[] context, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(context);
            return HexFormat.of().formatHex(digest.digest(body));
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
