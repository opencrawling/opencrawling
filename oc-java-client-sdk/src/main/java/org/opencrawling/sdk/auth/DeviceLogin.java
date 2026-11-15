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

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Signs a user in with the OAuth 2.0 device authorization grant (RFC 8628): the user opens a URL in any browser and
 * confirms a code, and the program waits for the token. The program never sees the user's password.
 */
public class DeviceLogin {

    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    /** Waits between polls; tests replace it. */
    public interface Sleeper {
        void sleep(long seconds) throws InterruptedException;
    }

    private final OAuth2Client oauth;
    private final Clock clock;
    private final Sleeper sleeper;

    public DeviceLogin(OAuth2Client oauth, Clock clock, Sleeper sleeper) {
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.sleeper = sleeper != null ? sleeper : seconds -> Thread.sleep(seconds * 1000);
    }

    public OAuth2Client.DeviceAuthorization start(OAuth2Client.Endpoints endpoints, String clientId, String scope) {
        if (endpoints.deviceAuthorizationEndpoint() == null) {
            throw new OAuth2Exception(null, "The issuer " + endpoints.issuer()
                    + " does not offer the device authorization grant.");
        }
        return oauth.startDeviceAuthorization(endpoints.deviceAuthorizationEndpoint(), clientId, scope);
    }

    /**
     * Polls the token endpoint at the interval the provider asked for until the user confirms, declines, or the code
     * expires (RFC 8628, section 3.5): {@code authorization_pending} keeps waiting, {@code slow_down} adds five
     * seconds to the interval.
     */
    public RefreshTokenProvider.Tokens await(OAuth2Client.Endpoints endpoints, String clientId,
                                             OAuth2Client.DeviceAuthorization authorization) throws InterruptedException {
        Instant deadline = clock.instant().plusSeconds(authorization.expiresIn());
        long interval = authorization.interval();
        Map<String, String> grant = new LinkedHashMap<>();
        grant.put("grant_type", GRANT_TYPE);
        grant.put("device_code", authorization.deviceCode());
        while (clock.instant().isBefore(deadline)) {
            sleeper.sleep(interval);
            if (!clock.instant().isBefore(deadline)) {
                break; // the code expired while waiting
            }
            try {
                OAuth2Client.TokenResponse response = oauth.token(endpoints.tokenEndpoint(), clientId, null, grant);
                ExpiringToken access = ExpiringToken.of(response.accessToken(), response.expiresIn(), clock);
                return new RefreshTokenProvider.Tokens(access.value(), response.refreshToken(), access.expiresAt());
            } catch (OAuth2Exception e) {
                if ("authorization_pending".equals(e.error())) {
                    continue;
                }
                if ("slow_down".equals(e.error())) {
                    interval += 5;
                    continue;
                }
                if ("access_denied".equals(e.error())) {
                    throw new OAuth2Exception("access_denied", "The sign-in was declined.");
                }
                if ("expired_token".equals(e.error())) {
                    break;
                }
                throw e;
            }
        }
        throw new OAuth2Exception("expired_token", "The sign-in code expired before it was confirmed; try again.");
    }
}
