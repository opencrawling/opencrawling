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
package org.opencrawling.core.connector;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.opencrawling.core.document.RepositoryDocument;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import reactor.core.publisher.Flux;

public class MustacheTransformationConnector implements TransformationConnector {

    private final String templateContent;
    private final Template compiledTemplate;

    public MustacheTransformationConnector(String templateContent) {
        this.templateContent = templateContent;
        this.compiledTemplate = Mustache.compiler().compile(templateContent);
    }

    @Override
    public String getName() {
        return "MustacheTransformationConnector";
    }

    @Override
    public void connect() throws Exception {
        // No-op
    }

    @Override
    public void disconnect() throws Exception {
        // No-op
    }

    @Override
    public Flux<RepositoryDocument> transform(RepositoryDocument document) throws Exception {
        Map<String, Object> context = flattenMetadata(document.metadata());
        String renderedText = compiledTemplate.execute(context);
        
        ByteArrayInputStream renderedStream = new ByteArrayInputStream(renderedText.getBytes(StandardCharsets.UTF_8));
        
        RepositoryDocument transformedDoc = new RepositoryDocument(
            document.id(),
            document.uri(),
            renderedStream,
            document.metadata(),
            document.acl(),
            document.security(),
            document.lastModified()
        );
        return Flux.just(transformedDoc);
    }

    private Map<String, Object> flattenMetadata(Map<String, List<String>> metadata) {
        Map<String, Object> flatMap = new HashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, List<String>> entry : metadata.entrySet()) {
                List<String> values = entry.getValue();
                if (values == null || values.isEmpty()) {
                    flatMap.put(entry.getKey(), "");
                } else if (values.size() == 1) {
                    flatMap.put(entry.getKey(), values.get(0));
                } else {
                    flatMap.put(entry.getKey(), values);
                }
            }
        }
        return flatMap;
    }

    public String getTemplateContent() {
        return templateContent;
    }
}
