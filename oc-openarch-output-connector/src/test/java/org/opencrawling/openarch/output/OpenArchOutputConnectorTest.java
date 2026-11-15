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
package org.opencrawling.openarch.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.openarch.output.client.OpenArchClient;
import org.opencrawling.openarch.output.config.OpenArchOutputProperties;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class OpenArchOutputConnectorTest {

    private OpenArchClient mockClient;
    private OpenArchOutputProperties properties;
    private OpenArchOutputConnector connector;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    public void setup() throws Exception {
        mockClient = mock(OpenArchClient.class);
        properties = new OpenArchOutputProperties();
        properties.setEndpointUrl("http://localhost:8080");
        properties.setRepositoryId("default");
        properties.setRootFolderId("00000000-0000-0000-0000-000000000000");

        ObjectNode uploadRes = mapper.createObjectNode();
        uploadRes.put("id", "blob-uuid-1234");
        when(mockClient.uploadBlob(any(InputStream.class), anyString(), anyString())).thenReturn(uploadRes);

        ObjectNode docRes = mapper.createObjectNode();
        docRes.put("id", "doc-uuid-5678");
        when(mockClient.createOrUpdateDocument(anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(docRes);

        when(mockClient.ping(anyString())).thenReturn(true);

        connector = new OpenArchOutputConnector(mockClient, properties);
    }

    @Test
    public void testConnectorLifecycle() throws Exception {
        assertEquals("OpenArch_Output", connector.getName());
        connector.connect();
        verify(mockClient).ping("default");
        connector.disconnect();
    }

    @Test
    public void testSendDocument() {
        SecurityConfig securityConfig = new SecurityConfig(true, List.of(
                new PermissionRule("user:john", "user", "John", "READ"),
                new PermissionRule("group:engineers", "group", "Engineers", "WRITE")
        ));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-test-1",
                "file:///tmp/doc-test-1",
                new ByteArrayInputStream("Test file content for OpenArch".getBytes()),
                Map.of(
                        "mimeType", List.of("text/plain"),
                        "title", List.of("Test Title"),
                        "department", List.of("R&D")
                ),
                "READ:user:john",
                securityConfig,
                Instant.now()
        );

        StepVerifier.create(connector.send(doc))
                .verifyComplete();

        try {
            verify(mockClient).uploadBlob(any(InputStream.class), eq("doc-test-1"), eq("text/plain"));
            verify(mockClient).createOrUpdateDocument(eq("default"), eq("00000000-0000-0000-0000-000000000000"), anyMap(), eq(true));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
