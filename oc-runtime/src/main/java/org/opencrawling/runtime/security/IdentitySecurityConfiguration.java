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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Web security for {@code opencrawling.security.identity.mode}.
 *
 * <ul>
 *   <li>{@code none} (default): one chain that lets every request through, with CSRF, login forms, sessions and
 *       Spring Security's response headers off, so the runtime behaves as it did before Spring Security was on the
 *       classpath.</li>
 *   <li>{@code jwt}: a resource-server chain that requires a valid bearer token on the MCP endpoints (and on
 *       {@code /api/**} with {@code protect-api}); every other request falls through to the open chain. The
 *       resource-server chain matches only those paths, so a bearer token meant for another endpoint (the OIS
 *       ingest token) is never parsed as a JWT.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentitySecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdentitySecurityConfiguration.class);

    static final String MODE_PROPERTY = IdentityProperties.PREFIX + ".mode";

    /** Has its own bearer token ({@code opencrawling.ingest.ois.token}); never under the JWT chain. */
    static final String OIS_INGEST_PATH = "/api/v1/ingest/ois/**";

    @Bean
    public CallerResolver callerResolver(IdentityProperties properties, Environment environment) {
        if (!properties.isJwt()) {
            log.info("Caller identity mode 'none': MCP tools use the identity asserted in their arguments.");
            return CallerResolver.asserted();
        }
        log.info("Caller identity mode 'jwt': MCP endpoints {} require a bearer token; tools use the token's identity "
                + "(principal claims {}, group claims {}).", mcpEndpoints(environment),
                properties.getJwt().getPrincipalClaims(), properties.getJwt().getGroupClaims());
        if (!properties.isProtectApi()) {
            log.warn("Caller identity mode 'jwt' without {}.protect-api: the REST API (/api/**) still accepts "
                    + "requests without a token.", IdentityProperties.PREFIX);
        }
        if (environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences", "").isBlank()) {
            log.warn("No spring.security.oauth2.resourceserver.jwt.audiences configured: any valid token from the "
                    + "issuer is accepted, including tokens issued for other applications.");
        }
        return CallerResolver.verified(new JwtCallerMapper(properties.getJwt()));
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = MODE_PROPERTY, havingValue = "jwt")
    public SecurityFilterChain verifiedCallerFilterChain(HttpSecurity http, IdentityProperties properties,
                                                         Environment environment,
                                                         ObjectProvider<JwtDecoder> jwtDecoder) throws Exception {
        if (jwtDecoder.getIfAvailable() == null) {
            throw new IllegalStateException(MODE_PROPERTY + "=jwt needs a token issuer: set "
                    + "spring.security.oauth2.resourceserver.jwt.issuer-uri or jwk-set-uri.");
        }
        http.securityMatcher(protectedRequests(properties, environment))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * The requests that need a token in {@code jwt} mode: the MCP endpoints, plus {@code /api/**} (except OIS
     * ingest) with {@code protect-api}.
     */
    static RequestMatcher protectedRequests(IdentityProperties properties, Environment environment) {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        List<RequestMatcher> matchers = new ArrayList<>();
        for (String endpoint : mcpEndpoints(environment)) {
            matchers.add(paths.matcher("/".equals(endpoint) ? "/**" : endpoint + "/**"));
        }
        if (properties.isProtectApi()) {
            matchers.add(new AndRequestMatcher(paths.matcher("/api/**"),
                    new NegatedRequestMatcher(paths.matcher(OIS_INGEST_PATH))));
        }
        return new OrRequestMatcher(matchers);
    }

    /**
     * The MCP endpoint paths for every transport Spring AI may serve (Streamable HTTP or stateless, and SSE), read
     * from Spring AI's own properties so that a renamed endpoint stays protected.
     */
    static Set<String> mcpEndpoints(Environment environment) {
        Set<String> endpoints = new LinkedHashSet<>();
        endpoints.add(normalize(environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp")));
        endpoints.add(normalize(environment.getProperty("spring.ai.mcp.server.sse-endpoint", "/sse")));
        endpoints.add(normalize(environment.getProperty("spring.ai.mcp.server.sse-message-endpoint", "/mcp/message")));
        return endpoints;
    }

    private static String normalize(String path) {
        String trimmed = path == null ? "" : path.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
