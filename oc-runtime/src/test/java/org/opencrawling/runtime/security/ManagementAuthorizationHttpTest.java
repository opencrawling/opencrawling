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
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.runtime.api.ConnectorController;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobVisibility;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.orchestrator.JobOrchestrator;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.service.ConnectorCheckerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.opencrawling.runtime.security.IdentityTestApplication.PUBLIC_KEY_FILE;
import static org.opencrawling.runtime.security.IdentityTestApplication.validToken;

/**
 * Management authorization over real HTTP: signed tokens, the security filter chain, Spring MVC routing and JSON.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, ManagementAuthorizationHttpTest.ManagementApi.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=JWT",
                "opencrawling.security.identity.protect-api=true",
                "opencrawling.security.management.enabled=true",
                "opencrawling.security.management.job-creator-groups=hr-team",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE,
                "spring.ai.mcp.server.protocol=STREAMABLE"
        })
class ManagementAuthorizationHttpTest {

    @Configuration
    @Import({JobController.class, ConnectorController.class, ManagementAuthorization.class, JobVisibility.class,
            ManagementAuthorizationWebConfig.class})
    static class ManagementApi {
        @Bean
        JobOrchestrator jobOrchestrator() {
            return mock(JobOrchestrator.class);
        }

        @Bean
        ConnectorFactory connectorFactory() {
            ConnectorFactory factory = mock(ConnectorFactory.class);
            when(factory.repository(any())).thenReturn(mock(RepositoryConnector.class));
            when(factory.output(any(), any())).thenReturn(mock(OutputConnector.class));
            return factory;
        }

        @Bean
        JobRunRegistry jobRunRegistry() {
            return new JobRunRegistry(0);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        ConnectorCheckerService connectorCheckerService() {
            return mock(ConnectorCheckerService.class);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JobController controller;

    private final String name = "http-authz-" + UUID.randomUUID();

    private static final String ADMIN = validToken(Map.of("preferred_username", "ops", "groups", List.of("opencrawling-admin")));
    private static final String HR = validToken(Map.of("preferred_username", "hr.lead", "groups", List.of("hr-team")));
    private static final String FINANCE = validToken(Map.of("preferred_username", "finance.lead", "groups", List.of("finance-team")));

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> "file:" + PUBLIC_KEY_FILE.toAbsolutePath());
    }

    @AfterEach
    void deleteJob() {
        String id = jobIdOrNull();
        if (id != null) {
            send("DELETE", "/api/jobs/" + id, ADMIN, null);
        }
    }

    private String jobIdOrNull() {
        HttpResponse<String> list = send("GET", "/api/jobs", ADMIN, null);
        int at = list.body().indexOf("\"name\":\"" + name + "\"");
        if (at < 0) {
            return null;
        }
        int idStart = list.body().lastIndexOf("\"id\":\"", at) + 6;
        return list.body().substring(idStart, list.body().indexOf('"', idStart));
    }

    private HttpResponse<String> send(String method, String path, String bearer, String json) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try {
            return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void jobsAreCreatedOwnedAndShownPerCaller() {
        String job = "{\"id\":\"new\",\"name\":\"" + name + "\",\"repositoryConnector\":\"r\",\"outputConnector\":\"o\","
                + "\"path\":\"/tmp\",\"pipelineMode\":\"rag\",\"access\":{\"owner\":\"someone.else\",\"viewerGroups\":[\"auditors\"]}}";

        assertThat(send("POST", "/api/jobs", null, job).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/api/jobs", FINANCE, job).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/jobs", HR, job).statusCode()).isEqualTo(201);

        String id = jobIdOrNull();
        HttpResponse<String> own = send("GET", "/api/jobs/" + id, HR, null);
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(own.body()).contains("\"owner\":\"hr.lead\"").contains("\"viewerGroups\":[\"auditors\"]");
        assertThat(send("GET", "/api/jobs", HR, null).body()).contains(name);
        assertThat(send("GET", "/api/jobs", FINANCE, null).body()).doesNotContain(name);
        assertThat(send("GET", "/api/jobs/" + id, FINANCE, null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/jobs/" + id + "/stop", FINANCE, null).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/api/jobs/" + id, FINANCE, null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/jobs/" + id + "/stop", HR, null).statusCode()).isEqualTo(200);
    }

    @Test
    void connectorsAreForAdminsAndCreatorsSeeThemWithoutConfiguration() {
        String connector = "{\"name\":\"Http_Test_" + UUID.randomUUID() + "\",\"type\":\"repository\",\"className\":\"x\"}";

        assertThat(send("POST", "/api/connectors", HR, connector).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/%63onnectors", HR, connector).statusCode()).as("encoded path").isEqualTo(403);
        assertThat(send("POST", "/api/connectors/check", FINANCE, connector).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/connectors", FINANCE, null).statusCode()).isEqualTo(403);

        HttpResponse<String> listed = send("GET", "/api/connectors", HR, null);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains("\"name\"").doesNotContain("\"configuration\":{");
        assertThat(send("GET", "/api/connectors", ADMIN, null).body()).contains("\"configuration\":{");
    }
}
