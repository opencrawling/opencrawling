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
package org.opencrawling.alfresco.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opencrawling.alfresco.output.client.AlfrescoRestClient;
import org.opencrawling.alfresco.output.config.AlfrescoOutputProperties;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.core.security.SecurityConfig;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlfrescoOutputConnectorTest {

    private AlfrescoOutputProperties properties;
    private AlfrescoRestClient mockClient;
    private AlfrescoOutputConnector connector;

    @BeforeEach
    void setUp() {
        properties = new AlfrescoOutputProperties();
        properties.setUrl("http://localhost:8080/alfresco");
        properties.setTargetParentNodeId("-root-");
        properties.setTargetPathPrefix("MigratedContent");
        properties.setPathStrategy("HIERARCHICAL");
        properties.setConflictStrategy("OVERWRITE");
        properties.setSyncPermissions(true);

        PipelineProperties pipelineProperties = new PipelineProperties();
        pipelineProperties.setMode(PipelineMode.MIGRATION);

        mockClient = Mockito.mock(AlfrescoRestClient.class);
        connector = new AlfrescoOutputConnector(
                properties,
                mockClient,
                pipelineProperties,
                new ObjectMapper()
        );
    }

    @Test
    void testGetName() {
        assertThat(connector.getName()).isEqualTo("AlfrescoOutputConnector");
    }

    @Test
    void testSend_rejectsRagMode() {
        connector.setPipelineMode(PipelineMode.RAG);

        RepositoryDocument doc = new RepositoryDocument(
                "doc-1",
                "http://example.com/doc.pdf",
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)),
                Map.of("filename", List.of("doc.pdf")),
                "public",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );

        assertThatThrownBy(() -> connector.send(doc).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AlfrescoOutputConnector only supports MIGRATION mode");
    }

    @Test
    void testSend_upsertMigrationModeSuccess() throws Exception {
        when(mockClient.ensureFolderPath(anyString(), anyString())).thenReturn("resolved-folder-id");
        when(mockClient.uploadNode(anyString(), anyString(), anyString(), anyMap(), anyList(), any(), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(new AlfrescoRestClient.NodeCreationResult("node-999", "contract.pdf", false));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-contract",
                "file:///contracts/2026/contract.pdf",
                new ByteArrayInputStream("PDF-BINARY-CONTENT".getBytes(StandardCharsets.UTF_8)),
                Map.of(
                        "filename", List.of("contract.pdf"),
                        "path", List.of("contracts/2026"),
                        "title", List.of("Annual Contract 2026")
                ),
                "GROUP_LEGAL",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );

        connector.send(doc).block();

        verify(mockClient).ensureFolderPath(eq("-root-"), contains("MigratedContent/contracts/2026"));
        verify(mockClient).uploadNode(
                eq("resolved-folder-id"),
                eq("contract.pdf"),
                eq("cm:content"),
                argThat(props -> "Annual Contract 2026".equals(props.get("cm:title"))),
                anyList(),
                any(),
                anyString(),
                eq(true),
                eq(false)
        );
        verify(mockClient).updatePermissions(eq("node-999"), any(), anyString(), eq("Consumer"), eq(true));
    }

    @Test
    void testSend_deleteAction() throws Exception {
        when(mockClient.ensureFolderPath(anyString(), anyString())).thenReturn("folder-id");
        when(mockClient.findChildNodeByName(anyString(), eq("old-doc.pdf"))).thenReturn("node-to-delete-123");
        when(mockClient.deleteNode(anyString(), anyBoolean())).thenReturn(true);

        RepositoryDocument doc = new RepositoryDocument(
                "doc-delete-1",
                "file:///archive/old-doc.pdf",
                null,
                Map.of("filename", List.of("old-doc.pdf"), "path", List.of("archive")),
                "public",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.DELETE
        );

        connector.send(doc).block();

        verify(mockClient).deleteNode(eq("node-to-delete-123"), eq(false));
    }
}
