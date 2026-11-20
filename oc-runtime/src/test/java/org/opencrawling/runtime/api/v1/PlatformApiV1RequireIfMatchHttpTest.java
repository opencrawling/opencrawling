/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
import org.opencrawling.migrator.mcf.oc.OpenCrawlingWriter;
import org.opencrawling.sdk.models.ConnectorRequest;
import org.opencrawling.sdk.models.JobRequest;
import org.opencrawling.runtime.security.IdentityTestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;
import java.util.Map;
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

        // Initial PUT /jobs/{id}/schedule needs no If-Match; once configured, updating or deleting without If-Match returns 428.
        HttpResponse<String> initialSched = http.send("PUT", "/api/v1/jobs/" + id + "/schedule",
                "{\"cron\":\"0 2 * * *\",\"timezone\":\"UTC\",\"enabled\":true}");
        assertThat(initialSched.statusCode()).as(initialSched.body()).isEqualTo(200);
        String schedEtag = initialSched.headers().firstValue("ETag").orElseThrow();
        assertThat(http.send("PUT", "/api/v1/jobs/" + id + "/schedule",
                "{\"cron\":\"0 3 * * *\",\"timezone\":\"UTC\",\"enabled\":true}").statusCode()).isEqualTo(428);
        assertThat(http.send("DELETE", "/api/v1/jobs/" + id + "/schedule", null).statusCode()).isEqualTo(428);
        assertThat(http.send("DELETE", "/api/v1/jobs/" + id + "/schedule", null, "If-Match", schedEtag).statusCode()).isEqualTo(204);

        assertThat(http.send("POST", "/api/v1/jobs/" + id + ":pause", null).statusCode()).as("actions are not changes of the version")
                .isNotEqualTo(428);
        // The pause changed the job's state, and with it its version: the delete names the current one.
        String current = http.send("GET", "/api/v1/jobs/" + id, null).headers().firstValue("ETag").orElseThrow();
        assertThat(current).isNotEqualTo(conditional.headers().firstValue("ETag").orElseThrow());
        assertThat(http.send("DELETE", "/api/v1/jobs/" + id, null, "If-Match", current).statusCode()).isEqualTo(204);
    }

    @Test
    void theMigratorRerunsAMigrationOnARuntimeThatRequiresIfMatch() {
        PlatformApiTestSupport.Http http = new PlatformApiTestSupport.Http(port);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String connector = "Mcf_" + tag;
        OpenCrawlingWriter writer = new OpenCrawlingWriter("http://localhost:" + port, null, null, 10);
        for (String basePath : new String[] {"/data/first", "/data/second"}) {
            writer.upsertConnector(new ConnectorRequest(connector, "migrated", "repository", "org.example.Repo", 0,
                    Map.of("basePath", basePath)));
            writer.upsertJob(new JobRequest(null, "mcf-" + tag, connector, "PGVector_Output", null, basePath,
                    null, null, 0, null, null, null, null, null));
        }
        assertThat(http.send("GET", "/api/v1/connectors/" + connector, null).body()).as("the re-run replaced the connector")
                .contains("/data/second");
        String jobs = http.send("GET", "/api/v1/jobs?q=mcf-" + tag, null).body();
        assertThat(jobs).as("the re-run replaced the job instead of adding one").contains("/data/second")
                .doesNotContain("/data/first");
        assertThat(jobs.split("\"name\":\"mcf-" + tag, -1)).hasSize(2);
    }
}
