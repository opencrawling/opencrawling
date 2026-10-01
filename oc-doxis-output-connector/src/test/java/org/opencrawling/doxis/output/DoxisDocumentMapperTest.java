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
package org.opencrawling.doxis.output;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;
import org.opencrawling.doxis.output.content.ContentPlan;
import org.opencrawling.doxis.output.schema.DoxisSchema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DoxisDocumentMapperTest {

    private DoxisSchema schema;

    @BeforeEach
    void setUp() throws Exception {
        DoxisClient client = mock(DoxisClient.class);
        when(client.listAttributeDefinitions()).thenReturn(Fixtures.list("attribute-definitions.json"));
        schema = new DoxisSchema(client);
    }

    private DoxisDocumentMapper mapper(String referenceAttribute, Map<String, String> mapping) {
        DoxisOutputProperties props = new DoxisOutputProperties(null, "faststarter", "Supervisor", "pw", "admins", "D_TEXTER",
                null, null, null, referenceAttribute, mapping, null, null, true, null, null, 0, 10);
        return new DoxisDocumentMapper(props, schema);
    }

    private static RepositoryDocument doc(String id, Map<String, List<String>> metadata) {
        return new RepositoryDocument(id, "file:///data/contracts/msa.pdf", null, metadata, "", SecurityConfig.createPublic(),
                Instant.parse("2026-09-30T08:00:00Z"));
    }

    private static ContentPlan plan(ContentStrategy strategy, Long length, String sha256, String locator) {
        return new ContentPlan(strategy, "msa.pdf", "application/pdf", length, sha256, locator, "file:///data/contracts/msa.pdf", null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attribute(List<Map<String, Object>> attributes, String uuid) {
        return attributes.stream().filter(a -> uuid.equals(a.get("attributeDefinitionUUID"))).findFirst().orElse(null);
    }

    @Test
    void documentParamsCarryTypeDescriptorsAndRegisteredLocator() throws Exception {
        Map<String, Object> params = mapper(null, Map.of()).documentParams(doc("doc-1", Map.of("title", List.of("MSA 2026"))),
                plan(ContentStrategy.PREDEFINED_LOCATOR, 5_000_000_000_000L, "9f86", "fs01/2026/msa.pdf"), "type-1");

        assertEquals("type-1", params.get("documentTypeUUID"));
        assertEquals("application/pdf", params.get("mimeTypeName"));
        assertEquals("msa.pdf", params.get("fullFileName"));
        assertEquals("pdf", params.get("fileExtension"));
        assertEquals(5_000_000_000_000L, params.get("contentLength"));
        assertEquals("SHA-256", params.get("hashAlgorithm"));
        assertEquals("9f86", params.get("hashValue"));
        assertEquals("fs01/2026/msa.pdf", params.get("predefinedLocator"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attributes = (List<Map<String, Object>>) params.get("attributes");
        assertEquals(List.of("doc-1"), attribute(attributes, "90c6196e-0829-4322-836e-93898b4e39be").get("values"));
        assertEquals("STRING", attribute(attributes, "90c6196e-0829-4322-836e-93898b4e39be").get("attributeDataType"));
        assertEquals(List.of("MSA 2026"), attribute(attributes, "55a1b1ce-1139-4f08-b9bc-89478d46079b").get("values"));
    }

    @Test
    void referenceOnlyOmitsContentDescriptionButRecordsUri() throws Exception {
        Map<String, Object> params = mapper("URL", Map.of()).documentParams(doc("doc-1", Map.of()),
                plan(ContentStrategy.REFERENCE_ONLY, 123L, "abc", null), "type-1");

        assertFalse(params.containsKey("contentLength"));
        assertFalse(params.containsKey("hashValue"));
        assertFalse(params.containsKey("predefinedLocator"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attributes = (List<Map<String, Object>>) params.get("attributes");
        assertEquals(List.of("file:///data/contracts/msa.pdf"), attribute(attributes, "996fcb9f-d7cb-4e18-b8b9-1b02e7fd68cb").get("values"));
    }

    @Test
    void attributeMappingConvertsTypesAndKeepsMultiValues() throws Exception {
        List<Map<String, Object>> attributes = mapper(null, Map.of("authors", "ObjectAuthors", "lastModified", "ObjectDate",
                "unknownKey", "DoesNotExist"))
                .attributes(doc("doc-1", Map.of("authors", List.of("Elena Weber", "Jane Doe"))), plan(ContentStrategy.UPLOAD, 1L, null, null));

        assertEquals(List.of("Elena Weber", "Jane Doe"), attribute(attributes, "f033bf16-1c83-4fb1-9b65-4f0b1dd4e825").get("values"));
        Map<String, Object> date = attribute(attributes, "d060ac48-2df2-466d-acac-f4ca82fd174d");
        assertEquals("DATE", date.get("attributeDataType"));
        assertEquals(List.of(Instant.parse("2026-09-30T08:00:00Z").toEpochMilli()), date.get("values"));
        assertEquals(4, attributes.size());
    }

    @Test
    void longExternalIdsAreHashedDeterministicallyToFitTheDescriptor() throws Exception {
        DoxisDocumentMapper mapper = mapper(null, Map.of());
        String longId = "/mnt/share/projects/2026/very/deep/folder/structure/with/a/long/file-name.pdf";

        String value = mapper.externalIdValue(longId);

        assertTrue(value.startsWith("h:"));
        assertEquals(50, value.length());
        assertEquals(value, mapper.externalIdValue(longId));
        assertEquals("short-id", mapper.externalIdValue("short-id"));
    }

    @Test
    void lookupStatementUsesShortNameAndEscapesQuotes() throws Exception {
        assertEquals("SELECT * FROM D_TEXTER WHERE OBJECTNUMBER = 'o''brien.pdf'",
                mapper(null, Map.of()).lookupStatement("D_TEXTER", "o'brien.pdf"));
    }
}
