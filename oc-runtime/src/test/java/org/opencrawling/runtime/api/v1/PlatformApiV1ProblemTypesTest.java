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
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The problem types the runtime can answer with are exactly the ones the guide documents, each under the anchor its
 * type URI leads to (RFC 9457 3.1.1).
 */
class PlatformApiV1ProblemTypesTest {

    private static final Path GUIDE = Path.of("..", "docs", "Platform-API.md");

    @Test
    void everyProblemTypeIsDocumentedUnderItsAnchorAndNothingElseIs() throws IOException {
        Matcher anchors = Pattern.compile("<a id=\"problem-([a-z-]+)\"></a>`([a-z-]+)`").matcher(Files.readString(GUIDE));
        Set<String> documented = new TreeSet<>();
        while (anchors.find()) {
            assertThat(anchors.group(2)).as("the row's name matches its anchor").isEqualTo(anchors.group(1));
            documented.add(anchors.group(1));
        }
        assertThat(documented).as("problem types in the guide").containsExactlyInAnyOrderElementsOf(ApiProblem.TYPES);
    }

    @Test
    void aTypeUriLeadsToItsDocumentationAndAnUnlistedTypeCannotBeMade() {
        ApiProblem problem = ApiProblem.notFound("Job", "1");
        assertThat(ApiV1.PROBLEM_TYPE_BASE + problem.slug())
                .isEqualTo("https://github.com/opencrawling/opencrawling/wiki/Platform-API#problem-not-found");
        assertThatThrownBy(() -> new ApiProblem(HttpStatus.BAD_REQUEST, "made-up", "Made up", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ApiProblem.TYPES");
        for (int status : new int[] {400, 403, 404, 409, 418, 429, 500, 503}) {
            assertThat(ApiProblem.TYPES).as("fromStatus(" + status + ")").contains(ApiProblem.fromStatus(status, "x").slug());
        }
    }

    @Test
    void curlSamplesQuoteBodiesForTheShell() {
        assertThat(ApiV1Documentation.shellQuoted("{\"reason\":\"the team's call\"}"))
                .isEqualTo("{\"reason\":\"the team'\\''s call\"}");
    }
}
