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
package org.opencrawling.runtime.api.v1;

import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** With opencrawling.api.require-if-match, a change of a versioned resource must name the version it changes. */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE", "opencrawling.api.require-if-match=true"})
class PlatformApiV1RequireIfMatchHttpTest {

    @LocalServerPort
    int port;

    @Test
    void aChangeWithoutIfMatchIsRefusedWith428AndOneWithItGoesThrough() {
        PlatformApiTestSupport.Http http = new PlatformApiTestSupport.Http(port);
        String name = "ifmatch-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = http.send("POST", "/api/v1/jobs", "{\"name\":\"" + name + "\",\"repositoryConnector\":"
                + "\"FileSystem_Local\",\"outputConnector\":\"PGVector_Output\",\"path\":\"/tmp/x\"}");
        assertThat(created.statusCode()).as("creating needs no version").isEqualTo(201);
        String id = created.headers().firstValue("Location").orElseThrow().replace("/api/v1/jobs/", "");
        String etag = created.headers().firstValue("ETag").orElseThrow();

        HttpResponse<String> blind = http.send("PATCH", "/api/v1/jobs/" + id, "{\"path\":\"/tmp/y\"}",
                "Content-Type", "application/merge-patch+json");
        assertThat(blind.statusCode()).isEqualTo(428);
        assertThat(blind.body()).contains("#problem-precondition-required");
        assertThat(http.send("DELETE", "/api/v1/jobs/" + id, null).statusCode()).isEqualTo(428);
        assertThat(http.send("PUT", "/api/v1/settings/system", "{}").statusCode()).isEqualTo(428);

        HttpResponse<String> conditional = http.send("PATCH", "/api/v1/jobs/" + id, "{\"path\":\"/tmp/y\"}",
                "Content-Type", "application/merge-patch+json", "If-Match", etag);
        assertThat(conditional.statusCode()).as(conditional.body()).isEqualTo(200);
        assertThat(http.send("POST", "/api/v1/jobs/" + id + ":pause", null).statusCode()).as("actions are not changes of the version")
                .isNotEqualTo(428);
        // The pause changed the job's state, and with it its version: the delete names the current one.
        String current = http.send("GET", "/api/v1/jobs/" + id, null).headers().firstValue("ETag").orElseThrow();
        assertThat(current).isNotEqualTo(conditional.headers().firstValue("ETag").orElseThrow());
        assertThat(http.send("DELETE", "/api/v1/jobs/" + id, null, "If-Match", current).statusCode()).isEqualTo(204);
    }
}
