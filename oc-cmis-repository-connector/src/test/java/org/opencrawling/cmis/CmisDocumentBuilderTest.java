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
package org.opencrawling.cmis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class CmisDocumentBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testBuildDocumentVerboseProperties() throws Exception {
        String json = """
            {
              "properties": {
                "cmis:objectId": { "value": "doc-101" },
                "cmis:name": { "value": "Architecture_Charter.pdf" },
                "cmis:baseTypeId": { "value": "cmis:document" },
                "cmis:objectTypeId": { "value": "cmis:document" },
                "cmis:createdBy": { "value": "piergiorgio.lucidi" },
                "cmis:creationDate": { "value": "2026-08-14T09:15:00Z" },
                "cmis:lastModifiedBy": { "value": "piergiorgio.lucidi" },
                "cmis:lastModificationDate": { "value": "2026-09-29T16:45:00Z" },
                "cmis:versionLabel": { "value": "2.1" },
                "cmis:isLatestVersion": { "value": true },
                "cmis:isLatestMajorVersion": { "value": true },
                "cmis:contentStreamLength": { "value": 4194304 },
                "cmis:contentStreamMimeType": { "value": "application/pdf" },
                "cmis:contentStreamFileName": { "value": "Architecture_Charter.pdf" },
                "cmis:path": { "value": "/Sites/engineering/Architecture_Charter.pdf" },
                "cmis:secondaryObjectTypeIds": { "values": ["P:custom:aspect"] },
                "custom:projectCode": { "value": "PRJ-2026-AI" },
                "custom:classification": { "value": "Confidential" }
              },
              "acl": {
                "isExact": true,
                "aces": [
                  {
                    "principal": { "principalId": "piergiorgio.lucidi" },
                    "permissions": ["cmis:all"]
                  }
                ]
              }
            }
            """;

        JsonNode objNode = mapper.readTree(json);
        InputStream stream = new ByteArrayInputStream("PDF-BYTE-CONTENT".getBytes(StandardCharsets.UTF_8));

        RepositoryDocument doc = CmisDocumentBuilder.buildDocument("repo-01", objNode, stream, true);

        assertThat(doc.id()).isEqualTo("cmis://repo-01/documents/doc-101");
        assertThat(doc.uri()).isEqualTo("cmis://repo-01/Sites/engineering/Architecture_Charter.pdf");
        assertThat(doc.action()).isEqualTo(DocumentAction.UPSERT);
        assertThat(doc.contentStream()).isNotNull();

        // Metadata assertions
        assertThat(doc.metadata().get("name")).containsExactly("Architecture_Charter.pdf");
        assertThat(doc.metadata().get("mimeType")).containsExactly("application/pdf");
        assertThat(doc.metadata().get("cmis.objectId")).containsExactly("doc-101");
        assertThat(doc.metadata().get("cmis.versionLabel")).containsExactly("2.1");
        assertThat(doc.metadata().get("cmis.isLatestMajorVersion")).containsExactly("true");
        assertThat(doc.metadata().get("cmis.secondaryObjectTypeIds")).containsExactly("P:custom:aspect");
        assertThat(doc.metadata().get("cmis_prop_custom_projectCode")).containsExactly("PRJ-2026-AI");
        assertThat(doc.metadata().get("cmis_prop_custom_classification")).containsExactly("Confidential");

        // Security assertions
        assertThat(doc.security().permissions()).hasSize(1);
        assertThat(doc.security().permissions().get(0).identity()).isEqualTo("piergiorgio.lucidi");
        assertThat(doc.security().permissions().get(0).access()).isEqualTo("admin");
    }

    @Test
    void testBuildDocumentSuccinctProperties() throws Exception {
        String json = """
            {
              "succinctProperties": {
                "cmis:objectId": "doc-202",
                "cmis:name": "Data_Catalog.json",
                "cmis:baseTypeId": "cmis:document",
                "cmis:contentStreamMimeType": "application/json",
                "cmis:lastModificationDate": 1710000000000,
                "custom:department": "DataOps"
              }
            }
            """;

        JsonNode objNode = mapper.readTree(json);
        RepositoryDocument doc = CmisDocumentBuilder.buildDocument("repo-02", objNode, null, false);

        assertThat(doc.id()).isEqualTo("cmis://repo-02/documents/doc-202");
        assertThat(doc.uri()).isEqualTo("cmis://repo-02/documents/doc-202");
        assertThat(doc.metadata().get("name")).containsExactly("Data_Catalog.json");
        assertThat(doc.metadata().get("mimeType")).containsExactly("application/json");
        assertThat(doc.metadata().get("cmis_prop_custom_department")).containsExactly("DataOps");
        assertThat(doc.security().permissions().get(0).identity()).isEqualTo("public");
    }

    @Test
    void testBuildTombstone() {
        RepositoryDocument tombstone = CmisDocumentBuilder.buildTombstone("repo-01", "doc-999");
        assertThat(tombstone.id()).isEqualTo("cmis://repo-01/documents/doc-999");
        assertThat(tombstone.action()).isEqualTo(DocumentAction.DELETE);
        assertThat(tombstone.contentStream()).isNull();
    }
}
