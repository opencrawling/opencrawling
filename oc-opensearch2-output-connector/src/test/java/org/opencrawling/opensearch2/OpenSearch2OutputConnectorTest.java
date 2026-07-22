/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.opensearch2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OpenSearch2OutputConnectorTest {

    private OpenSearch2OutputConnector connector;

    @Mock
    private OpenSearchClient client;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private BulkResponse bulkResponse;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        connector = new OpenSearch2OutputConnector(client, embeddingModel);
    }

    @Test
    void testGetName() {
        assertThat(connector.getName()).isEqualTo("OpenSearch2OutputConnector");
    }

    @Test
    void testSendDocument() throws Exception {
        String contentText = "This is a test content that is long enough to be indexed in the vector store.";
        ByteArrayInputStream bais = new ByteArrayInputStream(contentText.getBytes(StandardCharsets.UTF_8));

        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("mimeType", List.of("text/plain"));
        metadata.put("title", List.of("Test Title"));

        SecurityConfig securityConfig = new SecurityConfig(true, List.of(
                new PermissionRule("user1", "user", "User One", "read"),
                new PermissionRule("group1", "group", "Group One", "read"),
                new PermissionRule("baduser", "user", "Bad User", "deny")
        ));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-123",
                "file://tmp/doc-123.txt",
                bais,
                metadata,
                "acl-123",
                securityConfig,
                Instant.now()
        );

        when(embeddingModel.embed(any(org.springframework.ai.document.Document.class)))
                .thenReturn(new float[]{0.1f, 0.2f});

        when(client.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);

        connector.send(doc).block();

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client, times(1)).bulk(captor.capture());

        BulkRequest capturedReq = captor.getValue();
        assertThat(capturedReq.operations()).isNotEmpty();
    }
}
