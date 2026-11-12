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
package org.opencrawling.sdk.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.exceptions.OpenCrawlingApiException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenProvidersTest {

    private FakeIdentityProvider idp;
    private final FakeIdentityProvider.MutableClock clock = new FakeIdentityProvider.MutableClock();
    private OAuth2Client oauth;

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdentityProvider();
        oauth = new OAuth2Client();
    }

    @AfterEach
    void stop() {
        idp.close();
    }

    private ClientCredentialsTokenProvider clientCredentials() {
        return new ClientCredentialsTokenProvider(oauth, idp.base + "/token", "ci bot", "s3cret:&=", "jobs", null, clock);
    }

    private OpenCrawlingClient client(TokenProvider tokens) {
        return OpenCrawlingClient.builder().baseUrl(idp.base).tokenProvider(tokens).build();
    }

    @Test
    void clientCredentialsAuthenticateTheClientWithBasicAndAskForTheScope() {
        idp.respondAccessToken("t1", 300);
        assertThat(clientCredentials().accessToken()).isEqualTo("t1");

        FakeIdentityProvider.Request request = idp.requestsTo("/token").getFirst();
        assertThat(request.form()).containsEntry("grant_type", "client_credentials").containsEntry("scope", "jobs")
                .doesNotContainKey("client_id").doesNotContainKey("client_secret");
        String credentials = new String(Base64.getDecoder().decode(request.authorization().substring(6)), StandardCharsets.UTF_8);
        assertThat(credentials).as("RFC 6749 2.3.1: id and secret form-encoded").isEqualTo("ci+bot:s3cret%3A%26%3D");
    }

    @Test
    void theTokenIsReusedUntilShortlyBeforeItExpires() {
        ClientCredentialsTokenProvider tokens = clientCredentials();
        idp.respondAccessToken("t1", 300);
        idp.respondAccessToken("t2", 300);

        assertThat(tokens.accessToken()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(269));
        assertThat(tokens.accessToken()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(2));
        assertThat(tokens.accessToken()).as("within 30 s of expiry").isEqualTo("t2");
        assertThat(idp.requestsTo("/token")).hasSize(2);
    }

    @Test
    void shortLivedTokensAreRenewedHalfwayThrough() {
        ClientCredentialsTokenProvider tokens = clientCredentials();
        idp.respondAccessToken("t1", 20);
        idp.respondAccessToken("t2", 20);

        assertThat(tokens.accessToken()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(9));
        assertThat(tokens.accessToken()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(2));
        assertThat(tokens.accessToken()).isEqualTo("t2");
    }

    @Test
    void aRefusedTokenIsReplacedAndTheRequestRepeatedOnce() {
        idp.respondAccessToken("stale", 300);
        idp.respondAccessToken("fresh", 300);
        idp.acceptedApiToken = "fresh";

        assertThat(client(clientCredentials()).jobs().list()).isEmpty();
        assertThat(idp.requestsTo("/api/jobs")).extracting(FakeIdentityProvider.Request::authorization)
                .containsExactly("Bearer stale", "Bearer fresh");
    }

    @Test
    void aFixedTokenIsNotRetried() {
        idp.acceptedApiToken = "other";
        assertThatThrownBy(() -> client(TokenProvider.of("fixed")).jobs().list())
                .isInstanceOf(OpenCrawlingApiException.class);
        assertThat(idp.requestsTo("/api/jobs")).hasSize(1);
    }

    @Test
    void aTokenThatIsRefusedTwiceFailsWithoutLooping() {
        idp.respondAccessToken("a", 300);
        idp.respondAccessToken("b", 300);
        idp.acceptedApiToken = "never";

        assertThatThrownBy(() -> client(clientCredentials()).jobs().list()).isInstanceOf(OpenCrawlingApiException.class);
        assertThat(idp.requestsTo("/api/jobs")).hasSize(2);
    }

    @Test
    void refusedClientCredentialsNameTheErrorButNeverEchoTheResponse() {
        idp.respondToken(401, "{\"error\":\"invalid_client\",\"error_description\":\"bad secret\",\"access_token\":\"leak\"}");
        assertThatThrownBy(() -> clientCredentials().accessToken())
                .isInstanceOf(OAuth2Exception.class)
                .hasMessageContaining("invalid_client").hasMessageContaining("bad secret")
                .hasMessageNotContaining("leak")
                .satisfies(e -> assertThat(((OAuth2Exception) e).error()).isEqualTo("invalid_client"));
        assertThat(clientCredentials().toString()).doesNotContain("s3cret");
    }

    @Test
    void discoveryReadsTheEndpointsAndRejectsAnotherIssuer() {
        OAuth2Client.Endpoints endpoints = oauth.discover(idp.base + "/realms/test/");
        assertThat(endpoints.tokenEndpoint()).isEqualTo(idp.base + "/token");
        assertThat(endpoints.deviceAuthorizationEndpoint()).isEqualTo(idp.base + "/device");
        assertThat(endpoints.revocationEndpoint()).isEqualTo(idp.base + "/revoke");

        idp.discoveredIssuer = "https://evil.example/realms/test";
        assertThatThrownBy(() -> oauth.discover(idp.base + "/realms/test")).isInstanceOf(OAuth2Exception.class)
                .hasMessageContaining("another issuer");
    }

    @Test
    void refreshTokensRenewTheAccessTokenAndAreSaved() {
        List<RefreshTokenProvider.Tokens> saved = new ArrayList<>();
        RefreshTokenProvider tokens = new RefreshTokenProvider(oauth, idp.base + "/token", "opencrawling-cli",
                new RefreshTokenProvider.Tokens("a1", "r1", clock.instant().plusSeconds(300)), saved::add, clock);

        assertThat(tokens.accessToken()).isEqualTo("a1");
        assertThat(idp.requestsTo("/token")).isEmpty();

        clock.advance(Duration.ofSeconds(280));
        idp.respondToken(200, "{\"access_token\":\"a2\",\"refresh_token\":\"r2\",\"expires_in\":300}");
        assertThat(tokens.accessToken()).isEqualTo("a2");
        assertThat(idp.requestsTo("/token").getFirst().form()).containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "r1").containsEntry("client_id", "opencrawling-cli");
        assertThat(saved).containsExactly(new RefreshTokenProvider.Tokens("a2", "r2", clock.instant().plusSeconds(300)));

        clock.advance(Duration.ofSeconds(290));
        idp.respondAccessToken("a3", 300); // no new refresh token: keep the current one
        assertThat(tokens.accessToken()).isEqualTo("a3");
        assertThat(saved.getLast().refreshToken()).isEqualTo("r2");
    }

    @Test
    void anExpiredSessionAsksTheUserToSignInAgain() {
        RefreshTokenProvider tokens = new RefreshTokenProvider(oauth, idp.base + "/token", "opencrawling-cli",
                new RefreshTokenProvider.Tokens("a1", "r1", clock.instant().minusSeconds(1)), null, clock);
        idp.respondToken(400, "{\"error\":\"invalid_grant\"}");
        assertThatThrownBy(tokens::accessToken).isInstanceOf(OAuth2Exception.class)
                .satisfies(e -> assertThat(((OAuth2Exception) e).error()).isEqualTo("login_required"));

        RefreshTokenProvider noRefresh = new RefreshTokenProvider(oauth, idp.base + "/token", "opencrawling-cli",
                new RefreshTokenProvider.Tokens("a1", null, clock.instant().plusSeconds(300)), null, clock);
        assertThat(noRefresh.invalidate()).isFalse();
    }

    @Test
    void theDeviceLoginWaitsSlowsDownAndReturnsTheTokens() throws Exception {
        List<Long> waits = new ArrayList<>();
        DeviceLogin login = new DeviceLogin(oauth, clock, seconds -> {
            waits.add(seconds);
            clock.advance(Duration.ofSeconds(seconds));
        });
        OAuth2Client.Endpoints endpoints = oauth.discover(idp.base + "/realms/test");
        OAuth2Client.DeviceAuthorization authorization = login.start(endpoints, "opencrawling-cli", "openid");
        assertThat(authorization.userCode()).isEqualTo("ABCD-EFGH");
        assertThat(idp.requestsTo("/device").getFirst().form()).containsEntry("client_id", "opencrawling-cli")
                .containsEntry("scope", "openid");

        idp.respondToken(400, "{\"error\":\"authorization_pending\"}");
        idp.respondToken(400, "{\"error\":\"slow_down\"}");
        idp.respondToken(200, "{\"access_token\":\"a1\",\"refresh_token\":\"r1\",\"expires_in\":300}");
        RefreshTokenProvider.Tokens tokens = login.await(endpoints, "opencrawling-cli", authorization);

        assertThat(tokens.accessToken()).isEqualTo("a1");
        assertThat(tokens.refreshToken()).isEqualTo("r1");
        assertThat(waits).containsExactly(5L, 5L, 10L);
        assertThat(idp.requestsTo("/token").getFirst().form())
                .containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .containsEntry("device_code", "dc-1");
    }

    @Test
    void aDeclinedOrExpiredDeviceLoginFails() {
        DeviceLogin login = new DeviceLogin(oauth, clock, seconds -> clock.advance(Duration.ofSeconds(seconds)));
        OAuth2Client.Endpoints endpoints = oauth.discover(idp.base + "/realms/test");
        OAuth2Client.DeviceAuthorization authorization =
                new OAuth2Client.DeviceAuthorization("dc", "U", "v", null, 12, 5);

        idp.respondToken(400, "{\"error\":\"access_denied\"}");
        assertThatThrownBy(() -> login.await(endpoints, "c", authorization))
                .satisfies(e -> assertThat(((OAuth2Exception) e).error()).isEqualTo("access_denied"));

        idp.respondToken(400, "{\"error\":\"authorization_pending\"}");
        idp.respondToken(400, "{\"error\":\"authorization_pending\"}");
        assertThatThrownBy(() -> login.await(endpoints, "c", authorization))
                .satisfies(e -> assertThat(((OAuth2Exception) e).error()).isEqualTo("expired_token"));
    }

    @Test
    void aBearerTokenAndATokenProviderCannotBothBeSet() {
        assertThatThrownBy(() -> OpenCrawlingClient.builder().bearerToken("x").tokenProvider(TokenProvider.of("y")).build())
                .isInstanceOf(IllegalStateException.class);
        assertThat(TokenProvider.of("  ").accessToken()).isNull();
    }
}
