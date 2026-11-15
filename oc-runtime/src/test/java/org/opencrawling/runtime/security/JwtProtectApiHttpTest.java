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

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.opencrawling.runtime.security.IdentityTestApplication.*;

/**
 * {@code jwt} mode with {@code protect-api}: {@code /api/**} needs a token too, except OIS ingest, which keeps its
 * own token.
 */
@SpringBootTest(classes = IdentityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=JWT",
                "opencrawling.security.identity.protect-api=true",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE,
                "spring.ai.mcp.server.protocol=STREAMABLE"
        })
class JwtProtectApiHttpTest {

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> "file:" + PUBLIC_KEY_FILE.toAbsolutePath());
    }

    @Test
    void theRestApiNeedsAToken() throws Exception {
        String token = "Bearer " + validToken(Map.of("preferred_username", "alice"));

        assertThat(send(port, "GET", "/api/ping", null).statusCode()).isEqualTo(401);
        assertThat(send(port, "POST", "/api/ping", null).statusCode()).isEqualTo(401);
        assertThat(send(port, "GET", "/api/ping", token).statusCode()).isEqualTo(200);
        assertThat(send(port, "POST", "/api/ping", token).statusCode()).isEqualTo(200);
    }

    @Test
    void oisIngestKeepsItsOwnToken() throws Exception {
        assertThat(send(port, "POST", "/api/v1/ingest/ois/job-1", "Bearer s3cret").statusCode()).isEqualTo(200);
    }

    @Test
    void theMcpEndpointStillNeedsAToken() throws Exception {
        assertThat(postInitialize(port, null).statusCode()).isEqualTo(401);
    }
}
