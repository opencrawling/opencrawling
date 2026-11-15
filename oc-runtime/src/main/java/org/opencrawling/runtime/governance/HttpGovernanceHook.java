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
package org.opencrawling.runtime.governance;

import org.opencrawling.core.governance.GovernanceDecision;
import org.opencrawling.core.governance.GovernanceSubject;
import org.opencrawling.core.governance.GovernanceUnavailableException;
import org.opencrawling.core.governance.PreEmbeddingGovernanceHook;
import org.opencrawling.core.security.SecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The built-in {@value GovernanceProperties#HTTP} hook: asks an external governance service about each document.
 *
 * <p>The request is a JSON {@code POST} of {@code documentId}, {@code uri}, {@code text}, {@code metadata} and
 * {@code security}, signed like the BPM events: {@value #TIMESTAMP_HEADER} holds the seconds since the epoch, and
 * {@value #SIGNATURE_HEADER} holds {@code sha256=} and the lowercase hex HMAC-SHA256 of {@code timestamp + "." + body},
 * keyed with the shared secret. The answer is JSON with {@code decision} ({@code APPROVE}, {@code SANITIZE} or
 * {@code REJECT}) and, as needed, {@code text}, {@code metadata}, {@code reason} and {@code audit}.
 *
 * <p>Network errors, timeouts, {@code 429} and {@code 5xx} are retried up to {@code max-attempts} in all, with
 * exponential backoff, so a slow service holds the consumer for a bounded time. Any other status, an answer that is
 * not valid, or too large, means no decision, and the document is not embedded.
 */
public final class HttpGovernanceHook implements PreEmbeddingGovernanceHook {

    private static final Logger log = LoggerFactory.getLogger(HttpGovernanceHook.class);

    static final String TIMESTAMP_HEADER = "X-OpenCrawling-Timestamp";
    static final String SIGNATURE_HEADER = "X-OpenCrawling-Signature";
    static final int MIN_SECRET_LENGTH = 32;

    /** Pauses between attempts; replaced in tests. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    record Request(String documentId, String uri, String text, Map<String, List<String>> metadata, SecurityConfig security) {}

    record Reply(String decision, String text, Map<String, List<String>> metadata, String reason, Map<String, String> audit) {}

    private final URI url;
    private final String secret;
    private final GovernanceProperties.Http settings;
    private final HttpClient client;
    private final Clock clock;
    private final Sleeper sleeper;
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpGovernanceHook(GovernanceProperties.Http settings) {
        this(settings, HttpClient.newBuilder().connectTimeout(settings.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC(), Thread::sleep);
    }

    HttpGovernanceHook(GovernanceProperties.Http settings, HttpClient client, Clock clock, Sleeper sleeper) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.url = checkedUrl(settings.getUrl());
        this.secret = checkedSecret(settings.getSecret());
        if (settings.getMaxAttempts() < 1) {
            throw new IllegalArgumentException("opencrawling.governance.http.max-attempts must be at least 1.");
        }
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    static URI checkedUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("opencrawling.governance.http.url is required for the http hook.");
        }
        URI uri;
        try {
            uri = URI.create(value.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("opencrawling.governance.http.url is not a valid URL.");
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            throw new IllegalArgumentException("opencrawling.governance.http.url must be an http or https URL.");
        }
        return uri;
    }

    static String checkedSecret(String value) {
        if (value == null || value.strip().length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("opencrawling.governance.http.secret must be at least "
                    + MIN_SECRET_LENGTH + " characters.");
        }
        return value.strip();
    }

    @Override
    public String name() {
        return GovernanceProperties.HTTP;
    }

    @Override
    public GovernanceDecision evaluate(GovernanceSubject subject) throws GovernanceUnavailableException {
        byte[] body;
        try {
            body = json.writeValueAsBytes(new Request(subject.documentId(), subject.uri(), subject.text(),
                    subject.metadata(), subject.security()));
        } catch (JacksonException e) {
            throw new GovernanceUnavailableException("the request could not be written", e);
        }
        Duration backoff = settings.getInitialBackoff();
        String lastProblem = "no attempt was made";
        for (int attempt = 1; attempt <= settings.getMaxAttempts(); attempt++) {
            if (attempt > 1) {
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GovernanceUnavailableException("interrupted while waiting to retry", e);
                }
                backoff = min(backoff.multipliedBy(2), settings.getMaxBackoff());
            }
            String timestamp = String.valueOf(clock.instant().getEpochSecond());
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(settings.getTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header(TIMESTAMP_HEADER, timestamp)
                    .header(SIGNATURE_HEADER, sign(secret, timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                lastProblem = "the service could not be reached (" + e.getClass().getSimpleName() + ")";
                log.warn("Governance service attempt {} of {} for document {} failed: {}", attempt,
                        settings.getMaxAttempts(), subject.documentId(), e.toString());
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GovernanceUnavailableException("interrupted while waiting for the service", e);
            }
            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                close(response);
                lastProblem = "the service answered " + status;
                log.warn("Governance service attempt {} of {} for document {} answered {}", attempt,
                        settings.getMaxAttempts(), subject.documentId(), status);
                continue;
            }
            if (status != 200) {
                close(response);
                throw new GovernanceUnavailableException("the service answered " + status);
            }
            return decision(read(response));
        }
        throw new GovernanceUnavailableException(lastProblem + " after " + settings.getMaxAttempts() + " attempt(s)");
    }

    private byte[] read(HttpResponse<InputStream> response) throws GovernanceUnavailableException {
        int max = settings.getMaxResponseBytes();
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(max + 1);
            if (bytes.length > max) {
                throw new GovernanceUnavailableException("the answer is larger than " + max + " bytes");
            }
            return bytes;
        } catch (IOException e) {
            throw new GovernanceUnavailableException("the answer could not be read", e);
        }
    }

    private GovernanceDecision decision(byte[] body) throws GovernanceUnavailableException {
        Reply reply;
        try {
            reply = json.readValue(body, Reply.class);
        } catch (JacksonException e) {
            throw new GovernanceUnavailableException("the answer is not valid JSON of the expected form");
        }
        if (reply == null || reply.decision() == null) {
            throw new GovernanceUnavailableException("the answer has no decision");
        }
        GovernanceDecision.Outcome outcome;
        try {
            outcome = GovernanceDecision.Outcome.valueOf(reply.decision().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new GovernanceUnavailableException("the answer's decision is not APPROVE, SANITIZE or REJECT");
        }
        Map<String, String> audit = reply.audit() != null ? withoutNulls(reply.audit()) : Map.of();
        return switch (outcome) {
            case APPROVE -> GovernanceDecision.approve(audit);
            case SANITIZE -> {
                if (reply.text() == null) {
                    throw new GovernanceUnavailableException("a SANITIZE answer has no text");
                }
                yield GovernanceDecision.sanitize(reply.text(), reply.metadata(), audit);
            }
            case REJECT -> GovernanceDecision.reject(reply.reason(), audit);
        };
    }

    private static Map<String, String> withoutNulls(Map<String, String> map) {
        Map<String, String> copy = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return copy;
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** {@code sha256=} and the lowercase hex HMAC-SHA256 of {@code timestamp + "." + body}. */
    static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    @Override
    public String toString() {
        return "HttpGovernanceHook[" + url.getHost() + "]";
    }
}
