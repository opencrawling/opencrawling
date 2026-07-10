package org.opencrawling.runtime.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class McpVectorServerTest {

    private VectorStore vectorStore;
    private McpVectorServer mcpVectorServer;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        mcpVectorServer = new McpVectorServer(vectorStore);
    }

    @Test
    void testSecureVectorSearch_withPrincipalAndRoles() {
        // Arrange
        String query = "kubernetes deployment";
        String principal = "user@enterprise.com";
        String roles = "engineering,finance";

        List<Document> mockDocuments = List.of(
                new Document("1", "Kubernetes guide for engineering", Map.of("uri", "file:///doc1.txt", "acl", "engineering")),
                new Document("2", "Public deployment guide", Map.of("uri", "file:///doc2.txt", "acl", "public"))
        );

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(mockDocuments);

        // Act
        List<McpVectorServer.DocumentSearchResult> results = mcpVectorServer.secureVectorSearch(
                query, principal, roles, 5, 0.2
        );

        // Assert
        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo("1");
        assertThat(results.get(0).acl()).isEqualTo("engineering");
        assertThat(results.get(1).id()).isEqualTo("2");
        assertThat(results.get(1).acl()).isEqualTo("public");

        // Verify SearchRequest filter construction
        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());

        SearchRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.getQuery()).isEqualTo(query);
        assertThat(capturedRequest.getTopK()).isEqualTo(5);
        assertThat(capturedRequest.getSimilarityThreshold()).isEqualTo(0.2);
        
        // The filter expression should be constructed and not null
        assertThat(capturedRequest.getFilterExpression()).isNotNull();
        String filterString = capturedRequest.getFilterExpression().toString();
        
        // Assert that the filter expression string includes our security rules
        assertThat(filterString).contains("acl");
        assertThat(filterString).contains("public");
        assertThat(filterString).contains("user@enterprise.com");
        assertThat(filterString).contains("engineering");
        assertThat(filterString).contains("finance");
    }

    @Test
    void testGetDocumentContent_success() {
        // Arrange
        String docUri = "file:///secure-finance-report.pdf";
        String principal = "finance-manager@enterprise.com";

        Document mockDoc = new Document("99", "Q3 Financial Report details...", 
                Map.of("uri", docUri, "acl", "finance-manager@enterprise.com"));

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(mockDoc));

        // Act
        McpVectorServer.DocumentDetailsResult result = mcpVectorServer.getDocumentContent(
                docUri, principal, ""
        );

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("99");
        assertThat(result.content()).isEqualTo("Q3 Financial Report details...");
        assertThat(result.uri()).isEqualTo(docUri);

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());

        SearchRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.getFilterExpression()).isNotNull();
        String filterString = capturedRequest.getFilterExpression().toString();
        assertThat(filterString).contains(docUri);
        assertThat(filterString).contains("finance-manager@enterprise.com");
    }

    @Test
    void testGetDocumentContent_notFoundOrAccessDenied() {
        // Arrange
        String docUri = "file:///secure-finance-report.pdf";
        String principal = "unauthorized-user@enterprise.com";

        // Mock empty list (which happens when metadata filter blocks access or document doesn't exist)
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        // Act & Assert
        assertThatThrownBy(() -> mcpVectorServer.getDocumentContent(docUri, principal, ""))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Document not found or access denied.");
    }

    @Test
    void testListAccessibleSources() {
        // Arrange
        String principal = "user@enterprise.com";
        List<Document> mockDocs = List.of(
                new Document("1", "Text 1", Map.of("uri", "file:///doc1.txt", "acl", "public", "lastModified", "2026-07-10T12:00:00Z")),
                new Document("2", "Text 2", Map.of("uri", "file:///doc2.txt", "acl", "user@enterprise.com", "lastModified", "2026-07-10T12:05:00Z"))
        );

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(mockDocs);

        // Act
        List<Map<String, Object>> sources = mcpVectorServer.listAccessibleSources(principal, null);

        // Assert
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0)).containsEntry("id", "1");
        assertThat(sources.get(0)).containsEntry("uri", "file:///doc1.txt");
        assertThat(sources.get(0)).containsEntry("acl", "public");

        assertThat(sources.get(1)).containsEntry("id", "2");
        assertThat(sources.get(1)).containsEntry("uri", "file:///doc2.txt");
        assertThat(sources.get(1)).containsEntry("acl", "user@enterprise.com");
    }
}
