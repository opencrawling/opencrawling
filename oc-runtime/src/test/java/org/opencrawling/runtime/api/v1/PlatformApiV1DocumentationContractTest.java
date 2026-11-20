/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documentation contract of the Platform API: every operation, with the optional features on, is documented well
 * enough to be used from its OpenAPI description alone. A new endpoint without that documentation fails here.
 */
@SpringBootTest(classes = {IdentityTestApplication.class, PlatformApiTestSupport.PlatformApi.class,
        PlatformApiV1FeaturesHttpTest.Features.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE", "opencrawling.discovery.enabled=true",
                "opencrawling.jobs.dynamic.enabled=true", "opencrawling.sidecars.enabled=true"})
class PlatformApiV1DocumentationContractTest {

    @LocalServerPort
    int port;

    @Test
    void everyOperationIsDocumentedCompletely() {
        JsonNode doc = JsonMapper.builder().build().readTree(new PlatformApiTestSupport.Http(port).send("GET", "/v3/api-docs/v1", null).body());
        Set<String> tags = new HashSet<>();
        doc.get("tags").forEach(tag -> {
            assertThat(tag.path("description").asString()).as("tag " + tag.get("name") + " has a description").hasSizeGreaterThan(20);
            tags.add(tag.get("name").asString());
        });
        List<String> gaps = new ArrayList<>();
        doc.get("paths").properties().forEach(path -> path.getValue().properties().forEach(entry -> {
            String where = entry.getKey().toUpperCase() + " " + path.getKey();
            JsonNode op = entry.getValue();
            if (op.path("summary").asString("").isBlank()) gaps.add(where + ": no summary");
            if (op.path("description").asString("").length() < 30) gaps.add(where + ": description missing or too short");
            if (op.path("tags").isEmpty() || !tags.contains(op.path("tags").get(0).asString())) gaps.add(where + ": no described tag");
            op.path("parameters").forEach(parameter -> {
                if (parameter.path("description").asString("").isBlank()) {
                    gaps.add(where + ": parameter '" + parameter.path("name").asString() + "' has no description");
                }
            });
            JsonNode body = op.path("requestBody");
            if (!body.isMissingNode()) {
                body.path("content").properties().forEach(media -> {
                    JsonNode type = media.getValue();
                    boolean example = type.has("example") || type.has("examples") || type.path("schema").has("example");
                    if (!example) gaps.add(where + ": request body " + media.getKey() + " has no example");
                });
            }
            op.path("responses").properties().forEach(response -> {
                String code = response.getKey();
                if (response.getValue().path("description").asString("").isBlank()) gaps.add(where + " " + code + ": no description");
                if ((code.startsWith("4") || code.startsWith("5")) && !(where.equals("GET /api/v1/health") && code.equals("503"))) {
                    if (!response.getValue().path("content").has("application/problem+json")) {
                        gaps.add(where + " " + code + ": not a problem detail");
                    }
                } else if ((code.equals("200") || code.equals("201")) && response.getValue().path("content").has("application/json")) {
                    JsonNode schema = response.getValue().path("content").path("application/json").path("schema");
                    if (schema.isMissingNode() || schema.isEmpty()) gaps.add(where + " " + code + ": no response schema");
                }
            });
            if (!op.has("x-codeSamples") || op.path("x-codeSamples").isEmpty()) gaps.add(where + ": no code sample");
        }));
        assertThat(gaps).as("documentation gaps").isEmpty();
    }
}
