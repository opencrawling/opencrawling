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
package org.opencrawling.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.JobController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Posts an OIS page to {@code /api/v1/ingest/ois/{jobId}} over HTTP, as the StormCrawler bolt does, and
 * checks that it reaches {@code vector_store_1024} through Kafka (test embeddings, 1024 dimensions), then
 * that a DELETE for the same id removes it.
 *
 * <p>Requires PostgreSQL and Kafka from {@code docker-compose.yml}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.kafka.consumer.group-id=${it.kafka.group:test-group-${random.uuid}}",
    "opencrawling.ingest.ois.token=" + OisIngestIT.TOKEN
})
@ActiveProfiles("test")
class OisIngestIT {

    static final String TOKEN = "ois-it-token";
    private static final long TIMEOUT_MS = 60_000;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JobController jobController;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private String documentId;

    @Test
    void postedPageIsIndexedAndItsDeleteRemovesIt() throws Exception {
        String jobId = jobController.getAllJobs().getFirst().id();
        documentId = "http://ois-it-" + UUID.randomUUID().toString().substring(0, 8) + "/page.html";
        String text = "ois it page text " + documentId;

        assertThat(post(jobId, null, upsert(text)).statusCode()).as("without credentials").isEqualTo(401);
        assertThat(post(jobId, null, "not json").statusCode()).as("malformed, without credentials").isEqualTo(401);
        assertThat(post(jobId, null, "").statusCode()).as("empty, without credentials").isEqualTo(401);
        assertThat(post(jobId, "Bearer " + TOKEN, upsert(text)).statusCode()).as("upsert").isEqualTo(202);
        awaitRows("after the upsert", count -> count > 0);
        assertThat(jdbc.queryForList("SELECT content FROM vector_store_1024 WHERE metadata->>'documentId' = ?",
                String.class, documentId)).anyMatch(content -> content.contains(text));

        assertThat(post(jobId, "Bearer " + TOKEN, delete()).statusCode()).as("delete").isEqualTo(202);
        awaitRows("after the delete", count -> count == 0);
    }

    @AfterEach
    void deleteThisRunsRows() {
        if (documentId != null) {
            jdbc.update("DELETE FROM vector_store_1024 WHERE metadata->>'documentId' = ?", documentId);
        }
    }

    private HttpResponse<String> post(String jobId, String authorization, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/ingest/ois/" + jobId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String upsert(String text) {
        return """
                {"id": "%s", "action": "UPSERT",
                 "content": {"mimeType": "text/html", "text": "%s"},
                 "metadata": {"title": "OIS IT page"},
                 "security": {"inheritanceEnabled": false,
                              "permissions": [{"identity": "ROLE_USER", "identityType": "role", "access": "read"}]}}
                """.formatted(documentId, text);
    }

    private String delete() {
        return """
                {"id": "%s", "action": "DELETE"}
                """.formatted(documentId);
    }

    private void awaitRows(String phase, IntPredicate expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int count;
        while (!expected.test(count = rows())) {
            if (System.currentTimeMillis() >= deadline) {
                fail("%s: %d rows for %s after %d ms", phase, count, documentId, TIMEOUT_MS);
            }
            Thread.sleep(500);
        }
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = ?",
                Integer.class, documentId);
    }
}
