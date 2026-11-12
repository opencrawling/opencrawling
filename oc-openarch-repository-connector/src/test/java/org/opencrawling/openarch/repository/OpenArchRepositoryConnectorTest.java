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
package org.opencrawling.openarch.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.openarch.repository.config.OpenArchRepositoryProperties;
import org.opencrawling.openarch.repository.model.OpenArchRepositoryClient;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OpenArchRepositoryConnectorTest {

    private OpenArchRepositoryClient mockClient;
    private OpenArchRepositoryProperties properties;
    private OpenArchRepositoryConnector connector;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    public void setup() throws Exception {
        mockClient = mock(OpenArchRepositoryClient.class);
        properties = new OpenArchRepositoryProperties();
        properties.setEndpointUrl("http://localhost:8080");
        properties.setRepositoryId("default");
        properties.setBatchSize(10);
        properties.setIncludeContentStream(true);
        properties.setIncludeAcls(true);

        when(mockClient.ping(anyString())).thenReturn(true);
        when(mockClient.downloadBlob(anyString(), anyString())).thenReturn(new ByteArrayInputStream("OpenArch test binary stream".getBytes()));

        connector = new OpenArchRepositoryConnector(properties, mockClient);
    }

    @Test
    public void testConnectorLifecycle() throws Exception {
        assertEquals("OpenArch_Repository", connector.getName());
        assertTrue(connector.supportsConcurrentProcessing());

        connector.connect();
        verify(mockClient).ping("default");
        connector.disconnect();

        ConnectorSchema schema = connector.getSchema("/");
        assertNotNull(schema);
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("sys_id")));
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("sys_acl")));
    }

    @Test
    public void testMapToRepositoryDocument() {
        ObjectNode docNode = mapper.createObjectNode();
        docNode.put("id", "doc-100");
        docNode.put("sys_name", "Invoice-2026.pdf");
        docNode.put("sys_title", "Invoice March 2026");
        docNode.put("sys_description", "Quarterly customer invoice");
        docNode.put("sys_modified", "2026-03-15T10:00:00Z");
        docNode.put("sys_primaryType", "SysFile");

        ArrayNode aclArray = mapper.createArrayNode();
        ObjectNode ace1 = mapper.createObjectNode();
        ace1.put("permission", "Read");
        ace1.put("granted", true);
        ObjectNode userObj = mapper.createObjectNode();
        userObj.put("username", "accounting_user");
        ace1.set("user", userObj);
        aclArray.add(ace1);

        docNode.set("sys_acl", aclArray);

        RepositoryDocument repoDoc = connector.mapToRepositoryDocument(docNode);
        assertNotNull(repoDoc);
        assertEquals("doc-100", repoDoc.id());
        assertEquals("openarch://default/doc-100", repoDoc.uri());
        assertEquals("Invoice March 2026", repoDoc.metadata().get("title").get(0));

        SecurityConfig sec = repoDoc.security();
        assertNotNull(sec);
        assertEquals(1, sec.permissions().size());
        assertEquals("accounting_user", sec.permissions().get(0).identity());
        assertEquals("read", sec.permissions().get(0).access());
    }

    @Test
    public void testScanFlux() throws Exception {
        ObjectNode queryResult = mapper.createObjectNode();
        ArrayNode documents = mapper.createArrayNode();

        ObjectNode d1 = mapper.createObjectNode();
        d1.put("id", "d1");
        d1.put("sys_name", "File1.txt");
        d1.put("sys_modified", "2026-01-01T12:00:00Z");
        documents.add(d1);

        ObjectNode d2 = mapper.createObjectNode();
        d2.put("id", "d2");
        d2.put("sys_name", "File2.txt");
        d2.put("sys_modified", "2026-01-02T12:00:00Z");
        documents.add(d2);

        queryResult.set("documents", documents);

        when(mockClient.query(eq("default"), anyString(), anyInt(), anyLong(), isNull())).thenReturn(queryResult);

        StepVerifier.create(connector.scan("/"))
                .expectNextMatches(doc -> doc.id().equals("d1") || doc.id().equals("d2"))
                .expectNextMatches(doc -> doc.id().equals("d1") || doc.id().equals("d2"))
                .verifyComplete();
    }

    @Test
    public void sourceInstanceIsTheConfiguredEndpointUrl() {
        assertEquals("http://localhost:8080", connector.sourceInstance());
    }
}
