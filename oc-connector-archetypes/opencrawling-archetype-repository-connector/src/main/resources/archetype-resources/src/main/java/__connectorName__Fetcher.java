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
package ${package};

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ${connectorName}Fetcher {

    private final ${connectorName}Configuration configuration;

    public ${connectorName}Fetcher(${connectorName}Configuration configuration) {
        this.configuration = configuration;
    }

    public Flux<RepositoryDocument> fetchDocuments(String basePath) {
        return Flux.create(sink -> {
            try {
                // Implement scanning, rate-limiting, and document stream generation
                RepositoryDocument doc = new RepositoryDocument(
                    "doc-1",
                    basePath + "/doc-1",
                    new ByteArrayInputStream("Sample document content".getBytes(StandardCharsets.UTF_8)),
                    Map.of("contentType", List.of("text/plain"), "source", List.of(basePath)),
                    "public",
                    SecurityConfig.createPublic(),
                    Instant.now(),
                    DocumentAction.UPSERT
                );
                sink.next(doc);
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }
}
