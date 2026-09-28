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
package org.opencrawling.seatunnel.client;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeaTunnelRestClientTest {

    private MockWebServer server;
    private SeaTunnelRestClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String url = server.url("/").toString();
        client = new SeaTunnelRestClient(url, 5);
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.shutdown();
    }

    @Test
    void submitJob_successful() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"jobId\":\"123456789\",\"jobName\":\"test_job\"}"));

        String result = client.submitJob("test_job", "env { parallelism = 1 }");
        assertThat(result).contains("123456789");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/submit-job?jobName=test_job");
        assertThat(req.getHeader("Content-Type")).isEqualTo("text/plain");
        assertThat(req.getBody().readUtf8()).isEqualTo("env { parallelism = 1 }");
    }

    @Test
    void submitJob_failure() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        assertThatThrownBy(() -> client.submitJob("test_job", "bad_config"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void getJobs_successful() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("[{\"jobId\":\"123\",\"jobStatus\":\"RUNNING\"}]"));

        String jobs = client.getJobs();
        assertThat(jobs).contains("RUNNING");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/jobs");
    }

    @Test
    void getJobInfo_successful() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"jobId\":\"123\",\"metrics\":{}}"));

        String jobInfo = client.getJobInfo("123");
        assertThat(jobInfo).contains("123");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/job-info/123");
    }

    @Test
    void stopJob_successful() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"success\":true}"));

        boolean stopped = client.stopJob("123");
        assertThat(stopped).isTrue();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/stop-job");
        assertThat(req.getBody().readUtf8()).contains("123");
    }

    @Test
    void getOverview_successful() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"totalJobs\":5,\"runningJobs\":1}"));

        String overview = client.getOverview();
        assertThat(overview).contains("totalJobs");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/overview");
    }

    @Test
    void isReachable_returnsTrueWhenServerAnswers() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"OK\"}"));

        assertThat(client.isReachable()).isTrue();
    }

    @Test
    void isReachable_returnsFalseWhenServerDown() throws IOException {
        server.shutdown();
        assertThat(client.isReachable()).isFalse();
    }
}
