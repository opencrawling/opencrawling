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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.Caller;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallerResolverTest {

    private final CallerResolver verified = CallerResolver.verified(new JwtCallerMapper(new IdentityProperties().getJwt()));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "RS256"), claims);
    }

    static void authenticate(Map<String, Object> claims) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(claims), List.of()));
    }

    @Test
    void noneModeUsesTheAssertedArguments() {
        Caller caller = CallerResolver.asserted().resolve("alice", " finance , ,legal");

        assertThat(caller.principal()).isEqualTo("alice");
        assertThat(caller.groups()).containsExactly("finance", "legal");
        assertThat(caller.authenticated()).isFalse();
    }

    @Test
    void noneModeIgnoresAnyAuthenticationInTheContext() {
        authenticate(Map.of("preferred_username", "token-user"));

        assertThat(CallerResolver.asserted().resolve("alice", null).principal()).isEqualTo("alice");
    }

    @Test
    void jwtModeTakesTheIdentityFromTheTokenAndIgnoresTheArguments() {
        authenticate(Map.of("preferred_username", "bob", "groups", List.of("hr")));

        Caller caller = verified.resolve("admin", "finance,executives");

        assertThat(caller.principal()).isEqualTo("bob");
        assertThat(caller.groups()).containsExactly("hr");
        assertThat(caller.authenticated()).isTrue();
    }

    @Test
    void jwtModeRefusesACallWithoutAuthentication() {
        assertThatThrownBy(() -> verified.resolve("alice", "finance"))
                .isInstanceOf(VerifiedCallerRequiredException.class);
    }

    @Test
    void jwtModeRefusesAnAuthenticationThatIsNotAValidatedToken() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "pw", "finance"));

        assertThatThrownBy(() -> verified.resolve("alice", "finance"))
                .isInstanceOf(VerifiedCallerRequiredException.class);
    }

    @Test
    void jwtModeRefusesAnUnauthenticatedToken() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt(Map.of("sub", "alice")));
        token.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThatThrownBy(() -> verified.resolve("alice", null))
                .isInstanceOf(VerifiedCallerRequiredException.class);
    }
}
