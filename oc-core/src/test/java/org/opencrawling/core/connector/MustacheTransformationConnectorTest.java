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

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import static org.junit.jupiter.api.Assertions.*;

public class MustacheTransformationConnectorTest {

    @Test
    public void testTransformation() throws Exception {
        String template = "On {{date}}, region {{region}} sold products: {{#products}}{{.}} {{/products}}with amount ${{amount}}.";
        MustacheTransformationConnector connector = new MustacheTransformationConnector(template);

        Map<String, List<String>> metadata = Map.of(
            "date", List.of("2026-07-23"),
            "region", List.of("EU"),
            "amount", List.of("45000"),
            "products", List.of("Laptop", "Monitor")
        );

        RepositoryDocument doc = new RepositoryDocument(
            "123",
            "file:///test.json",
            InputStream.nullInputStream(),
            metadata,
            "public",
            SecurityConfig.createPublic(),
            Instant.now()
        );

        RepositoryDocument result = connector.transform(doc).blockFirst();
        assertNotNull(result);
        
        String renderedText = new String(result.contentStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("On 2026-07-23, region EU sold products: Laptop Monitor with amount $45000.", renderedText);
    }
}
