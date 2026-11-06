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
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.runtime.discovery.FakeDiscoverableConnector;
import org.opencrawling.runtime.orchestrator.JobRunRegistry;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BPM events over real HTTP in {@code jwt} mode with {@code protect-api}: a source's HMAC signature or Basic token
 * reaches the endpoint past the JWT chain, a bearer token with the event scope works too, and nothing else does.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, BpmEventHttpTest.Events.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "opencrawling.security.identity.mode=JWT",
                "opencrawling.security.identity.protect-api=true",
                "opencrawling.events.bpm.enabled=true",
                "opencrawling.events.bpm.sources[0].name=flowable",
                "opencrawling.events.bpm.sources[0].hmac-secret=" + BpmEventHttpTest.HMAC_SECRET,
                "opencrawling.events.bpm.sources[1].name=aps",
                "opencrawling.events.bpm.sources[1].token=" + BpmEventHttpTest.TOKEN,
                "opencrawling.events.bpm.routes[0].name=signed",
                "opencrawling.events.bpm.routes[0].process=contract",
                "opencrawling.events.bpm.routes[0].transition=signed",
                "opencrawling.events.bpm.routes[0].connector=Fake_Repo",
                "spring.security.oauth2.resourceserver.jwt.audiences=" + IdentityTestApplication.AUDIENCE
        })
class BpmEventHttpTest {

    static final String HMAC_SECRET = "flowable-signing-secret-0123456789abcdef";
    static final String TOKEN = "aps-static-token-0123456789abcdefghijklmn";
    static final List<String> CRAWLS = Collections.synchronizedList(new ArrayList<>());

    @Configuration
    @EnableConfigurationProperties({BpmEventProperties.class, DynamicJobProperties.class})
    @Import(BpmEventController.class)
    static class Events {
        @Bean
        DynamicJobService dynamicJobService(DynamicJobProperties properties) {
            DynamicJobService.CrawlRunner runner = (connector, start, jobId, jobName, executionId, stop, provenance, chunking) -> {
                CRAWLS.add(start + " via " + provenance.triggerSource() + " by " + provenance.triggeredBy());
                return true;
            };
            return new DynamicJobService(() -> List.of(DynamicJobServiceTest.FAKE), dto -> new FakeDiscoverableConnector(),
                    new JobRunRegistry(0), runner, executionId -> 1L, properties, Clock.systemUTC());
        }

        @Bean
        BpmEventAuthenticator bpmEventAuthenticator(BpmEventProperties properties) {
            properties.validate();
            return new BpmEventAuthenticator(properties, Clock.systemUTC());
        }

        @Bean
        BpmEventService bpmEventService(DynamicJobService jobs, BpmEventProperties properties) {
            return new BpmEventService(jobs, properties, Clock.systemUTC());
        }
    }

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void publicKey(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> "file:" + IdentityTestApplication.PUBLIC_KEY_FILE.toAbsolutePath());
    }

    static String body(String eventId, String transition, String document) {
        return "{\"eventId\":\"" + eventId + "\",\"engine\":\"flowable\",\"processDefinitionKey\":\"contract\","
                + "\"processInstanceId\":4711,\"activityId\":\"" + transition + "\",\"documentId\":\"" + document + "\"}";
    }

    private HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> signed(String body, String secret) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        return post("/api/events/bpm/document-transition", body,
                "X-OpenCrawling-Source", "flowable", "X-OpenCrawling-Timestamp", timestamp,
                "X-OpenCrawling-Signature", BpmEventAuthenticator.sign(secret, timestamp, body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void awaitCrawl(String crawl) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!CRAWLS.contains(crawl) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(CRAWLS).contains(crawl);
    }

    @Test
    void aSignedEventReachesTheEndpointWithoutABearerToken() throws Exception {
        HttpResponse<String> response = signed(body("h-1", "signed", "public-guide"), HMAC_SECRET);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("\"status\":\"ACCEPTED\"", "\"route\":\"signed\"");
        awaitCrawl("public-guide via " + ExecutionProvenance.SOURCE_BPM + " by bpm:flowable");

        HttpResponse<String> again = signed(body("h-1", "signed", "public-guide"), HMAC_SECRET);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.body()).contains("\"duplicate\":true");
    }

    @Test
    void aWrongSignatureIsRefused() throws Exception {
        HttpResponse<String> response = signed(body("h-2", "signed", "public-guide"), "not-the-secret-but-long-enough-0123456789");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains(BpmEventAuthenticator.NOT_ACCEPTED);
    }

    @Test
    void aSourcesStaticTokenWorksOverBasicAuth() throws Exception {
        HttpResponse<String> accepted = post("/api/events/bpm/document-transition", body("h-3", "signed", "finance-report"),
                "Authorization", BpmEventAuthenticatorTest.basic("aps", TOKEN));
        HttpResponse<String> wrong = post("/api/events/bpm/document-transition", body("h-4", "signed", "finance-report"),
                "Authorization", BpmEventAuthenticatorTest.basic("aps", TOKEN + "x"));

        assertThat(accepted.statusCode()).isEqualTo(202);
        awaitCrawl("finance-report via " + ExecutionProvenance.SOURCE_BPM + " by bpm:aps");
        assertThat(wrong.statusCode()).isEqualTo(401);
    }

    @Test
    void aBearerTokenWithTheEventScopeWorks() throws Exception {
        String token = IdentityTestApplication.validToken(Map.of("preferred_username", "svc-flowable", "scope", "oc:events:write"));
        String noScope = IdentityTestApplication.validToken(Map.of("preferred_username", "svc-flowable", "scope", "openid"));

        assertThat(post("/api/events/bpm/document-transition", body("h-5", "signed", "alice-notes"),
                "Authorization", "Bearer " + token).statusCode()).isEqualTo(202);
        awaitCrawl("alice-notes via " + ExecutionProvenance.SOURCE_BPM + " by svc-flowable");
        assertThat(post("/api/events/bpm/document-transition", body("h-6", "signed", "alice-notes"),
                "Authorization", "Bearer " + noScope).statusCode()).isEqualTo(403);
    }

    @Test
    void withoutCredentialsNothingGetsThroughAndBasicOpensOnlyTheEventPath() throws Exception {
        assertThat(post("/api/events/bpm/document-transition", body("h-7", "signed", "public-guide")).statusCode()).isEqualTo(401);
        assertThat(post("/api/jobs/dynamic", "{}", "Authorization", BpmEventAuthenticatorTest.basic("aps", TOKEN)).statusCode())
                .isEqualTo(401);
        assertThat(post("/api/jobs/dynamic", "{}", "X-OpenCrawling-Signature", "sha256=00").statusCode()).isEqualTo(401);
    }

    @Test
    void anEventNoRouteMatchesIsIgnored() throws Exception {
        HttpResponse<String> response = signed(body("h-8", "drafted", "public-guide"), HMAC_SECRET);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"IGNORED\"");
    }
}
