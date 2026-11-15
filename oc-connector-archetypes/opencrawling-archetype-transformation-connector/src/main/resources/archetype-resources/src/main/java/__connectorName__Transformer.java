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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.opencrawling.core.document.RepositoryDocument;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class ${connectorName}Transformer {

    private final ${connectorName}Configuration configuration;

    public ${connectorName}Transformer(${connectorName}Configuration configuration) {
        this.configuration = configuration;
    }

    public Flux<RepositoryDocument> transformDocument(RepositoryDocument document) {
        Map<String, List<String>> updatedMetadata = new HashMap<>(document.metadata());
        updatedMetadata.put("transformedBy", List.of("${connectorName}"));

        RepositoryDocument transformedDoc = new RepositoryDocument(
            document.id(),
            document.uri(),
            document.contentStream(),
            updatedMetadata,
            document.acl(),
            document.security(),
            document.lastModified(),
            document.action()
        );

        return Flux.just(transformedDoc);
    }
}
