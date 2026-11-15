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

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class BpmEventAuthenticatorTest {

    static final String HMAC_SECRET = "flowable-signing-secret-0123456789abcdef";
    static final String TOKEN = "aps-static-token-0123456789abcdefghijklmn";
    static final Instant NOW = Instant.parse("2026-11-06T15:00:00Z");
    static final byte[] BODY = "{\"eventId\":\"e1\"}".getBytes(StandardCharsets.UTF_8);

    private final BpmEventAuthenticator authenticator = new BpmEventAuthenticator(properties(), Clock.fixed(NOW, ZoneOffset.UTC));

    static BpmEventProperties properties() {
        BpmEventProperties properties = new BpmEventProperties();
        BpmEventProperties.Source flowable = new BpmEventProperties.Source();
        flowable.setName("flowable");
        flowable.setHmacSecret(HMAC_SECRET);
        BpmEventProperties.Source aps = new BpmEventProperties.Source();
        aps.setName("aps");
        aps.setToken(TOKEN);
        properties.setSources(List.of(flowable, aps));
        return properties;
    }

    static String now() {
        return String.valueOf(NOW.getEpochSecond());
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private String refusal(Runnable call) {
        DynamicJobException e = catchThrowableOfType(DynamicJobException.class, call::run);
        assertThat(e).isNotNull();
        assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        return e.getMessage();
    }

    @Test
    void aSignatureOverTheTimestampAndBodyIsAccepted() {
        String signature = BpmEventAuthenticator.sign(HMAC_SECRET, now(), BODY);

        assertThat(signature).startsWith("sha256=").hasSize(7 + 64);
        assertThat(authenticator.verifySignature("flowable", now(), signature, BODY)).isEqualTo("flowable");
        assertThat(authenticator.verifySignature("flowable", now(), signature.toUpperCase().replace("SHA256=", "sha256="), BODY))
                .isEqualTo("flowable");
    }

    @Test
    void anyChangeToTheSignedRequestIsRefusedAlike() {
        String signature = BpmEventAuthenticator.sign(HMAC_SECRET, now(), BODY);
        String later = String.valueOf(NOW.getEpochSecond() + 1);

        assertThat(List.of(
                refusal(() -> authenticator.verifySignature("flowable", now(), signature, "{\"eventId\":\"e2\"}".getBytes(StandardCharsets.UTF_8))),
                refusal(() -> authenticator.verifySignature("flowable", later, signature, BODY)),
                refusal(() -> authenticator.verifySignature("flowable", now(), BpmEventAuthenticator.sign("another-secret-that-is-long-enough-0123", now(), BODY), BODY)),
                refusal(() -> authenticator.verifySignature("nobody", now(), signature, BODY)),
                refusal(() -> authenticator.verifySignature("aps", now(), BpmEventAuthenticator.sign(TOKEN, now(), BODY), BODY)),
                refusal(() -> authenticator.verifySignature("flowable", null, signature, BODY)),
                refusal(() -> authenticator.verifySignature("flowable", "soon", signature, BODY)),
                refusal(() -> authenticator.verifySignature(null, now(), signature, BODY))))
                .containsOnly(BpmEventAuthenticator.NOT_ACCEPTED);
    }

    @Test
    void aSignedRequestOutsideTheClockWindowIsRefusedSoItCannotBeReplayedLater() {
        String old = String.valueOf(NOW.minus(Duration.ofMinutes(6)).getEpochSecond());
        String future = String.valueOf(NOW.plus(Duration.ofMinutes(6)).getEpochSecond());
        String recent = String.valueOf(NOW.minus(Duration.ofMinutes(4)).getEpochSecond());

        assertThat(refusal(() -> authenticator.verifySignature("flowable", old, BpmEventAuthenticator.sign(HMAC_SECRET, old, BODY), BODY)))
                .contains("timestamp");
        assertThat(refusal(() -> authenticator.verifySignature("flowable", future, BpmEventAuthenticator.sign(HMAC_SECRET, future, BODY), BODY)))
                .contains("timestamp");
        assertThat(authenticator.verifySignature("flowable", recent, BpmEventAuthenticator.sign(HMAC_SECRET, recent, BODY), BODY))
                .isEqualTo("flowable");
    }

    @Test
    void aSourcesStaticTokenIsAcceptedOverBasicAuth() {
        assertThat(authenticator.verifyBasic(basic("aps", TOKEN))).isEqualTo("aps");
        assertThat(authenticator.verifyBasic("basic " + basic("aps", TOKEN).substring(6))).isEqualTo("aps");
    }

    @Test
    void wrongOrUnusableBasicCredentialsAreRefusedAlike() {
        assertThat(List.of(
                refusal(() -> authenticator.verifyBasic(basic("aps", TOKEN + "x"))),
                refusal(() -> authenticator.verifyBasic(basic("aps", ""))),
                refusal(() -> authenticator.verifyBasic(basic("nobody", TOKEN))),
                refusal(() -> authenticator.verifyBasic(basic("flowable", HMAC_SECRET))),
                refusal(() -> authenticator.verifyBasic("Basic not-base64!")),
                refusal(() -> authenticator.verifyBasic("Basic " + Base64.getEncoder().encodeToString("aps".getBytes(StandardCharsets.UTF_8)))),
                refusal(() -> authenticator.verifyBasic("Bearer " + TOKEN)),
                refusal(() -> authenticator.verifyBasic(null))))
                .containsOnly(BpmEventAuthenticator.NOT_ACCEPTED);
    }

    @Test
    void incompleteSourcesOrRoutesAndShortSecretsStopStartup() {
        BpmEventProperties shortSecret = properties();
        shortSecret.getSources().get(0).setHmacSecret("too-short");
        assertThatThrownBy(shortSecret::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32");

        BpmEventProperties noSecret = properties();
        noSecret.getSources().get(1).setToken(null);
        assertThatThrownBy(noSecret::validate).hasMessageContaining("needs an hmac-secret or a token");

        BpmEventProperties twice = properties();
        twice.getSources().get(1).setName("flowable");
        assertThatThrownBy(twice::validate).hasMessageContaining("used twice");

        BpmEventProperties colon = properties();
        colon.getSources().get(1).setName("a:b");
        assertThatThrownBy(colon::validate).hasMessageContaining("without ':'");

        BpmEventProperties route = properties();
        BpmEventProperties.Route incomplete = new BpmEventProperties.Route();
        incomplete.setName("signed");
        incomplete.setProcess("contract");
        incomplete.setTransition("signed");
        route.setRoutes(List.of(incomplete));
        assertThatThrownBy(route::validate).hasMessageContaining("route 'signed'");

        properties().validate();
    }
}
