/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.runtime.dynamicjob;

import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Checks the shared-secret credentials of a BPM event: an HMAC signature, or a source's static token over Basic auth.
 *
 * <p>Every failure gives the same answer, so a caller cannot tell an unknown source from a wrong secret. Secrets
 * are compared in constant time and never logged.
 */
final class BpmEventAuthenticator {

    static final String SOURCE_HEADER = "X-OpenCrawling-Source";
    static final String TIMESTAMP_HEADER = "X-OpenCrawling-Timestamp";
    static final String SIGNATURE_HEADER = "X-OpenCrawling-Signature";
    static final String SIGNATURE_PREFIX = "sha256=";
    static final String NOT_ACCEPTED = "The event's credentials were not accepted.";

    private final BpmEventProperties properties;
    private final Clock clock;

    BpmEventAuthenticator(BpmEventProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The HMAC-SHA256 signature of a request, as the {@value #SIGNATURE_HEADER} header carries it: {@code sha256=}
     * and the lowercase hex digest of {@code timestamp + "." + body}, keyed with the source's secret.
     */
    static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body);
            return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /**
     * Checks an HMAC-signed request.
     *
     * @param timestamp the {@value #TIMESTAMP_HEADER} header: seconds since the epoch
     * @return the source's name
     * @throws DynamicJobException {@code 401} when the source, signature or timestamp is not accepted
     */
    String verifySignature(String sourceName, String timestamp, String signature, byte[] body) {
        BpmEventProperties.Source source = source(sourceName);
        if (source == null || empty(source.getHmacSecret()) || timestamp == null || signature == null
                || !timestamp.matches("\\d{1,12}")) {
            throw notAccepted();
        }
        byte[] expected = sign(source.getHmacSecret(), timestamp, body).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, signature.strip().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw notAccepted();
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(Long.parseLong(timestamp)), clock.instant()).abs();
        if (skew.compareTo(properties.getMaxClockSkew()) > 0) {
            throw new DynamicJobException(HttpStatus.UNAUTHORIZED,
                    "The event's timestamp is outside the accepted window; send it with the current time.");
        }
        return source.getName().strip();
    }

    /**
     * Checks a request that sends a source's static token as the Basic auth password, with the source's name as
     * the username.
     *
     * @return the source's name
     * @throws DynamicJobException {@code 401} when the source or token is not accepted
     */
    String verifyBasic(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw notAccepted();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw notAccepted();
        }
        int colon = decoded.indexOf(':');
        BpmEventProperties.Source source = colon < 0 ? null : source(decoded.substring(0, colon));
        if (source == null || empty(source.getToken())) {
            throw notAccepted();
        }
        if (!MessageDigest.isEqual(source.getToken().getBytes(StandardCharsets.UTF_8),
                decoded.substring(colon + 1).getBytes(StandardCharsets.UTF_8))) {
            throw notAccepted();
        }
        return source.getName().strip();
    }

    private BpmEventProperties.Source source(String name) {
        if (name == null) {
            return null;
        }
        return properties.getSources().stream()
                .filter(source -> source.getName() != null && source.getName().strip().equals(name.strip()))
                .findFirst().orElse(null);
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    private static DynamicJobException notAccepted() {
        return new DynamicJobException(HttpStatus.UNAUTHORIZED, NOT_ACCEPTED);
    }
}
