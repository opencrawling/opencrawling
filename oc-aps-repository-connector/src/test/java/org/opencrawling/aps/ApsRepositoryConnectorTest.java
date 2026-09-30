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
package org.opencrawling.aps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.opencrawling.aps.mcp.ApsMcpClient;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;

import static org.junit.jupiter.api.Assertions.*;

class ApsRepositoryConnectorTest {

    private HttpClient mockHttpClient;
    private ApsRepositoryConnector connector;

    @BeforeEach
    void setUp() throws Exception {
        mockHttpClient = mock(HttpClient.class);
        connector = new ApsRepositoryConnector(
                "http://localhost:8080/activiti-app/api/enterprise",
                "admin@app.activiti.com",
                "admin",
                100,
                "",
                true,
                true,
                "",
                "all"
        );
        connector.setHttpClient(mockHttpClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connector != null) {
            connector.disconnect();
        }
    }

    @Test
    void testConnectSuccessViaProfile() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"id\":1,\"email\":\"admin@app.activiti.com\"}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        assertDoesNotThrow(() -> connector.connect());
    }

    @Test
    void testConnectSuccessViaQueryFallback() throws Exception {
        HttpResponse<String> mockProfileResponse = mock(HttpResponse.class);
        when(mockProfileResponse.statusCode()).thenReturn(404);
        when(mockProfileResponse.body()).thenReturn("Not Found");

        HttpResponse<String> mockQueryResponse = mock(HttpResponse.class);
        when(mockQueryResponse.statusCode()).thenReturn(200);
        when(mockQueryResponse.body()).thenReturn("{\"total\":0,\"data\":[]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    if (req.uri().toString().contains("/profile")) {
                        return mockProfileResponse;
                    }
                    return mockQueryResponse;
                });

        assertDoesNotThrow(() -> connector.connect());
    }

    @Test
    void testConnectFailure() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(401);
        when(mockResponse.body()).thenReturn("Unauthorized");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        Exception exception = assertThrows(IOException.class, () -> connector.connect());
        assertTrue(exception.getMessage().contains("Status code: 401") || exception.getMessage().contains("Profile status: 401"));
    }

    @Test
    void testScanSuccessWithVariablesAndTasks() throws Exception {
        String processInstancesJson = """
            {
              "total": 1,
              "start": 0,
              "sort": "startTime",
              "order": "asc",
              "size": 1,
              "data": [
                {
                  "id": "proc-10042",
                  "processDefinitionId": "loanApplication:3:9023",
                  "processDefinitionKey": "loanApplication",
                  "processDefinitionName": "Loan Application Workflow",
                  "businessKey": "LOAN-2026-9481",
                  "startedBy": {
                    "id": 10,
                    "firstName": "John",
                    "lastName": "Doe",
                    "email": "john.doe@example.com"
                  },
                  "started": "2026-09-28T14:30:00.000Z",
                  "ended": "2026-09-28T15:00:00.000Z",
                  "durationInMillis": 1800000,
                  "tenantId": "enterprise-tenant-1"
                }
              ]
            }
            """;

        String variablesJson = """
            [
              {
                "name": "applicantName",
                "type": "string",
                "value": "Acme Corp"
              },
              {
                "name": "requestedAmount",
                "type": "double",
                "value": "250000.0"
              }
            ]
            """;

        String tasksJson = """
            [
              {
                "id": "task-5001",
                "name": "Underwriter Review",
                "assignee": {
                  "id": 20,
                  "firstName": "Alice",
                  "lastName": "Underwriter",
                  "email": "alice.underwriter@example.com"
                },
                "candidateGroups": ["group:risk-analysts", "group:credit-committee"],
                "candidateUsers": ["reviewer.bob@example.com"],
                "status": "OPEN"
              }
            ]
            """;

        HttpResponse<String> mockInstancesResponse = mock(HttpResponse.class);
        when(mockInstancesResponse.statusCode()).thenReturn(200);
        when(mockInstancesResponse.body()).thenReturn(processInstancesJson);

        HttpResponse<String> mockVariablesResponse = mock(HttpResponse.class);
        when(mockVariablesResponse.statusCode()).thenReturn(200);
        when(mockVariablesResponse.body()).thenReturn(variablesJson);

        HttpResponse<String> mockTasksResponse = mock(HttpResponse.class);
        when(mockTasksResponse.statusCode()).thenReturn(200);
        when(mockTasksResponse.body()).thenReturn(tasksJson);

        HttpResponse<String> mockEmptyContentResponse = mock(HttpResponse.class);
        when(mockEmptyContentResponse.statusCode()).thenReturn(200);
        when(mockEmptyContentResponse.body()).thenReturn("{\"size\":0,\"total\":0,\"data\":[]}");

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        when(mockHttpClient.send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    String uri = req.uri().toString();
                    if (uri.contains("/content")) {
                        return mockEmptyContentResponse;
                    }
                    if (uri.contains("/variables")) {
                        return mockVariablesResponse;
                    }
                    if (uri.contains("/tasks")) {
                        return mockTasksResponse;
                    }
                    return mockInstancesResponse;
                });

        List<RepositoryDocument> docs = connector.scan("loanApplication").collectList().block();

        assertNotNull(docs);
        assertEquals(1, docs.size());

        RepositoryDocument doc = docs.get(0);
        assertEquals("proc-10042", doc.id());
        assertEquals("aps://process-instances/proc-10042", doc.uri());
        assertEquals(List.of("loanApplication:3:9023"), doc.metadata().get("processDefinitionId"));
        assertEquals(List.of("loanApplication"), doc.metadata().get("processDefinitionKey"));
        assertEquals(List.of("Loan Application Workflow"), doc.metadata().get("processDefinitionName"));
        assertEquals(List.of("LOAN-2026-9481"), doc.metadata().get("businessKey"));
        assertEquals(List.of("john.doe@example.com"), doc.metadata().get("startUserId"));
        assertEquals(List.of("enterprise-tenant-1"), doc.metadata().get("tenantId"));
        assertEquals(List.of("Acme Corp"), doc.metadata().get("aps_var_applicantName"));
        assertEquals(List.of("250000.0"), doc.metadata().get("aps_var_requestedAmount"));
        assertEquals(List.of("alice.underwriter@example.com"), doc.metadata().get("aps_task_Underwriter Review_assignee"));

        // Verify ACLs & Zero-Trust Security
        assertNotNull(doc.security());
        assertFalse(doc.security().inheritanceEnabled());
        List<PermissionRule> rules = doc.security().permissions();
        assertTrue(rules.stream().anyMatch(r -> "john.doe@example.com".equals(r.identity())));
        assertTrue(rules.stream().anyMatch(r -> "alice.underwriter@example.com".equals(r.identity())));
        assertTrue(rules.stream().anyMatch(r -> "group:risk-analysts".equals(r.identity()) && "group".equals(r.identityType())));
        assertTrue(rules.stream().anyMatch(r -> "reviewer.bob@example.com".equals(r.identity())));

        // Verify ACL string
        assertTrue(doc.acl().contains("john.doe@example.com"));
        assertTrue(doc.acl().contains("alice.underwriter@example.com"));
        assertTrue(doc.acl().contains("group:risk-analysts"));

        // Verify content JSON stream
        InputStream stream = doc.contentStream();
        assertNotNull(stream);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        String contentJsonStr = baos.toString(StandardCharsets.UTF_8);

        assertTrue(contentJsonStr.contains("\"id\":\"proc-10042\""));
        assertTrue(contentJsonStr.contains("\"applicantName\":\"Acme Corp\""));
        assertTrue(contentJsonStr.contains("\"Underwriter Review\""));
    }

    @Test
    void testScanWithMcpDiscovery() throws Exception {
        ApsRepositoryConnector mcpConnector = new ApsRepositoryConnector(
                "http://localhost:8080/activiti-app/api/enterprise",
                "admin@app.activiti.com",
                "admin",
                100,
                "",
                false,
                false,
                "",
                "all",
                true,
                "http://localhost:8080/activiti-app/mcp"
        );
        mcpConnector.setHttpClient(mockHttpClient);

        ApsMcpClient mockMcp = mock(ApsMcpClient.class);
        when(mockMcp.discoverProcessDefinitionKeys()).thenReturn(List.of("discoveredWorkflow"));
        mcpConnector.setMcpClient(mockMcp);

        String processInstancesJson = """
            {
              "total": 1,
              "start": 0,
              "size": 1,
              "data": [
                {
                  "id": "proc-9999",
                  "processDefinitionKey": "discoveredWorkflow",
                  "startTime": "2026-09-29T10:00:00.000Z"
                }
              ]
            }
            """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(processInstancesJson);

        HttpResponse<String> mockEmptyContent = mock(HttpResponse.class);
        when(mockEmptyContent.statusCode()).thenReturn(200);
        when(mockEmptyContent.body()).thenReturn("{\"size\":0,\"total\":0,\"data\":[]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    if (req.uri().toString().contains("/content")) {
                        return mockEmptyContent;
                    }
                    return mockResponse;
                });

        List<RepositoryDocument> docs = mcpConnector.scan("").collectList().block();

        assertNotNull(docs);
        assertEquals(1, docs.size());
        assertEquals("proc-9999", docs.get(0).id());
        assertEquals(List.of("discoveredWorkflow"), docs.get(0).metadata().get("processDefinitionKey"));
    }

    @Test
    void testScanSuccessWithWorkflowAttachments() throws Exception {
        String processInstancesJson = """
            {
              "total": 1,
              "start": 0,
              "sort": "startTime",
              "order": "asc",
              "size": 1,
              "data": [
                {
                  "id": "proc-10042",
                  "processDefinitionId": "loanApplication:3:9023",
                  "processDefinitionKey": "loanApplication",
                  "processDefinitionName": "Loan Application Workflow",
                  "businessKey": "LOAN-2026-9481",
                  "startedBy": {
                    "id": 10,
                    "firstName": "John",
                    "lastName": "Doe",
                    "email": "john.doe@example.com"
                  },
                  "started": "2026-09-28T14:30:00.000Z",
                  "ended": "2026-09-28T15:00:00.000Z",
                  "durationInMillis": 1800000,
                  "tenantId": "enterprise-tenant-1"
                }
              ]
            }
            """;

        String variablesJson = """
            [
              {
                "name": "applicantName",
                "type": "string",
                "value": "Acme Corp"
              }
            ]
            """;

        String tasksJson = """
            [
              {
                "id": "task-5001",
                "name": "Underwriter Review",
                "assignee": {
                  "id": 20,
                  "firstName": "Alice",
                  "lastName": "Underwriter",
                  "email": "alice.underwriter@example.com"
                },
                "candidateGroups": ["group:risk-analysts"],
                "status": "OPEN"
              }
            ]
            """;

        String processAttachmentsJson = """
            {
              "size": 1,
              "total": 1,
              "start": 0,
              "data": [
                {
                  "id": 1001,
                  "name": "invoice-10042.pdf",
                  "created": "2026-09-28T14:35:00.000Z",
                  "createdBy": {
                    "id": 10,
                    "firstName": "John",
                    "lastName": "Doe",
                    "email": "john.doe@example.com"
                  },
                  "relatedContent": true,
                  "contentAvailable": true,
                  "mimeType": "application/pdf",
                  "simpleType": "pdf",
                  "size": 15200
                }
              ]
            }
            """;

        String taskAttachmentsJson = """
            {
              "size": 1,
              "total": 1,
              "start": 0,
              "data": [
                {
                  "id": 1002,
                  "name": "audit-notes.txt",
                  "created": "2026-09-28T14:40:00.000Z",
                  "createdBy": {
                    "id": 20,
                    "firstName": "Alice",
                    "lastName": "Underwriter",
                    "email": "alice.underwriter@example.com"
                  },
                  "relatedContent": false,
                  "field": "reviewNotes",
                  "contentAvailable": true,
                  "mimeType": "text/plain",
                  "simpleType": "content",
                  "size": 450
                }
              ]
            }
            """;

        HttpResponse<String> mockInstancesResponse = mock(HttpResponse.class);
        when(mockInstancesResponse.statusCode()).thenReturn(200);
        when(mockInstancesResponse.body()).thenReturn(processInstancesJson);

        HttpResponse<String> mockVariablesResponse = mock(HttpResponse.class);
        when(mockVariablesResponse.statusCode()).thenReturn(200);
        when(mockVariablesResponse.body()).thenReturn(variablesJson);

        HttpResponse<String> mockTasksResponse = mock(HttpResponse.class);
        when(mockTasksResponse.statusCode()).thenReturn(200);
        when(mockTasksResponse.body()).thenReturn(tasksJson);

        HttpResponse<String> mockProcAttResponse = mock(HttpResponse.class);
        when(mockProcAttResponse.statusCode()).thenReturn(200);
        when(mockProcAttResponse.body()).thenReturn(processAttachmentsJson);

        HttpResponse<String> mockTaskAttResponse = mock(HttpResponse.class);
        when(mockTaskAttResponse.statusCode()).thenReturn(200);
        when(mockTaskAttResponse.body()).thenReturn(taskAttachmentsJson);

        HttpResponse<InputStream> mockRawPdfResponse = mock(HttpResponse.class);
        when(mockRawPdfResponse.statusCode()).thenReturn(200);
        when(mockRawPdfResponse.body()).thenAnswer(inv -> new ByteArrayInputStream("%PDF-1.4 Mock PDF Content".getBytes(StandardCharsets.UTF_8)));

        HttpResponse<InputStream> mockRawTxtResponse = mock(HttpResponse.class);
        when(mockRawTxtResponse.statusCode()).thenReturn(200);
        when(mockRawTxtResponse.body()).thenAnswer(inv -> new ByteArrayInputStream("Mock Review Notes Content".getBytes(StandardCharsets.UTF_8)));

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    String uri = req.uri().toString();
                    if (uri.contains("/content/1001/raw")) {
                        return mockRawPdfResponse;
                    }
                    if (uri.contains("/content/1002/raw")) {
                        return mockRawTxtResponse;
                    }
                    if (uri.contains("/process-instances/proc-10042/content")) {
                        return mockProcAttResponse;
                    }
                    if (uri.contains("/tasks/task-5001/content")) {
                        return mockTaskAttResponse;
                    }
                    if (uri.contains("/variables")) {
                        return mockVariablesResponse;
                    }
                    if (uri.contains("/tasks")) {
                        return mockTasksResponse;
                    }
                    return mockInstancesResponse;
                });

        List<RepositoryDocument> docs = connector.scan("loanApplication").collectList().block();

        assertNotNull(docs);
        assertEquals(3, docs.size(), "Should emit 1 process instance doc + 2 attachment docs");

        // 1. Verify Process Instance Document
        RepositoryDocument procDoc = docs.stream().filter(d -> d.id().equals("proc-10042")).findFirst().orElseThrow();
        assertEquals("aps://process-instances/proc-10042", procDoc.uri());
        assertEquals(List.of("2"), procDoc.metadata().get("aps_attachment_count"));
        assertTrue(procDoc.metadata().get("aps_attachment_names").contains("invoice-10042.pdf"));
        assertTrue(procDoc.metadata().get("aps_attachment_names").contains("audit-notes.txt"));
        assertEquals(List.of("invoice-10042.pdf"), procDoc.metadata().get("aps_attachment_1001_name"));
        assertEquals(List.of("application/pdf"), procDoc.metadata().get("aps_attachment_1001_mimeType"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        procDoc.contentStream().transferTo(baos);
        String procJson = baos.toString(StandardCharsets.UTF_8);
        assertTrue(procJson.contains("\"attachments\":["));
        assertTrue(procJson.contains("\"invoice-10042.pdf\""));
        assertTrue(procJson.contains("\"audit-notes.txt\""));

        // 2. Verify PDF Attachment Document
        RepositoryDocument pdfDoc = docs.stream().filter(d -> d.id().equals("proc-10042-content-1001")).findFirst().orElseThrow();
        assertEquals("aps://process-instances/proc-10042/content/1001", pdfDoc.uri());
        assertEquals(List.of("invoice-10042.pdf"), pdfDoc.metadata().get("name"));
        assertEquals(List.of("application/pdf"), pdfDoc.metadata().get("mimeType"));
        assertEquals(List.of("15200"), pdfDoc.metadata().get("size"));
        assertEquals(List.of("proc-10042"), pdfDoc.metadata().get("processInstanceId"));
        assertEquals(List.of("aps://process-instances/proc-10042"), pdfDoc.metadata().get("parentUri"));
        assertEquals(List.of("1001"), pdfDoc.metadata().get("contentId"));
        assertEquals(List.of("true"), pdfDoc.metadata().get("isRelatedContent"));
        assertEquals(List.of("john.doe@example.com"), pdfDoc.metadata().get("createdBy"));
        assertEquals(List.of("Acme Corp"), pdfDoc.metadata().get("aps_var_applicantName"));

        // Verify ACL security inheritance
        assertTrue(pdfDoc.acl().contains("john.doe@example.com"));
        assertTrue(pdfDoc.acl().contains("alice.underwriter@example.com"));
        assertTrue(pdfDoc.acl().contains("group:risk-analysts"));

        // Verify content stream
        ByteArrayOutputStream pdfBaos = new ByteArrayOutputStream();
        pdfDoc.contentStream().transferTo(pdfBaos);
        assertEquals("%PDF-1.4 Mock PDF Content", pdfBaos.toString(StandardCharsets.UTF_8));

        // 3. Verify Text Attachment Document (from task)
        RepositoryDocument txtDoc = docs.stream().filter(d -> d.id().equals("proc-10042-content-1002")).findFirst().orElseThrow();
        assertEquals("aps://process-instances/proc-10042/content/1002", txtDoc.uri());
        assertEquals(List.of("audit-notes.txt"), txtDoc.metadata().get("name"));
        assertEquals(List.of("text/plain"), txtDoc.metadata().get("mimeType"));
        assertEquals(List.of("reviewNotes"), txtDoc.metadata().get("formField"));
        assertEquals(List.of("task-5001"), txtDoc.metadata().get("taskId"));
        assertEquals(List.of("Underwriter Review"), txtDoc.metadata().get("taskName"));
        assertEquals(List.of("false"), txtDoc.metadata().get("isRelatedContent"));
        assertEquals(List.of("alice.underwriter@example.com"), txtDoc.metadata().get("createdBy"));
        assertEquals(List.of("Acme Corp"), txtDoc.metadata().get("aps_var_applicantName"));

        ByteArrayOutputStream txtBaos = new ByteArrayOutputStream();
        txtDoc.contentStream().transferTo(txtBaos);
        assertEquals("Mock Review Notes Content", txtBaos.toString(StandardCharsets.UTF_8));
    }

    @Test
    void testScanWithIncludeAttachmentsDisabled() throws Exception {
        ApsRepositoryConnector noAttConnector = new ApsRepositoryConnector(
                "http://localhost:8080/activiti-app/api/enterprise",
                "admin@app.activiti.com",
                "admin",
                100,
                "",
                true,
                true,
                false,
                "",
                "all"
        );
        noAttConnector.setHttpClient(mockHttpClient);
        assertFalse(noAttConnector.isIncludeAttachments());

        String processInstancesJson = """
            {
              "total": 1,
              "start": 0,
              "size": 1,
              "data": [
                {
                  "id": "proc-10042",
                  "processDefinitionKey": "loanApplication"
                }
              ]
            }
            """;

        HttpResponse<String> mockInstancesResponse = mock(HttpResponse.class);
        when(mockInstancesResponse.statusCode()).thenReturn(200);
        when(mockInstancesResponse.body()).thenReturn(processInstancesJson);

        HttpResponse<String> mockEmptyResponse = mock(HttpResponse.class);
        when(mockEmptyResponse.statusCode()).thenReturn(200);
        when(mockEmptyResponse.body()).thenReturn("[]");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    String uri = req.uri().toString();
                    if (uri.contains("/variables") || uri.contains("/tasks")) {
                        return mockEmptyResponse;
                    }
                    return mockInstancesResponse;
                });

        List<RepositoryDocument> docs = noAttConnector.scan("loanApplication").collectList().block();

        assertNotNull(docs);
        assertEquals(1, docs.size(), "Only process instance doc should be emitted when includeAttachments is false");
        assertEquals("proc-10042", docs.get(0).id());
        assertNull(docs.get(0).metadata().get("aps_attachment_count"));
    }

    @Test
    void testScanAttachmentDownloadFailureHandledGracefully() throws Exception {
        String processInstancesJson = """
            {
              "total": 1,
              "start": 0,
              "size": 1,
              "data": [
                {
                  "id": "proc-10042",
                  "processDefinitionKey": "loanApplication"
                }
              ]
            }
            """;

        String attachmentsJson = """
            {
              "size": 1,
              "total": 1,
              "start": 0,
              "data": [
                {
                  "id": 9999,
                  "name": "corrupt-file.pdf",
                  "mimeType": "application/pdf",
                  "size": 100
                }
              ]
            }
            """;

        HttpResponse<String> mockInstancesResponse = mock(HttpResponse.class);
        when(mockInstancesResponse.statusCode()).thenReturn(200);
        when(mockInstancesResponse.body()).thenReturn(processInstancesJson);

        HttpResponse<String> mockEmptyResponse = mock(HttpResponse.class);
        when(mockEmptyResponse.statusCode()).thenReturn(200);
        when(mockEmptyResponse.body()).thenReturn("[]");

        HttpResponse<String> mockAttResponse = mock(HttpResponse.class);
        when(mockAttResponse.statusCode()).thenReturn(200);
        when(mockAttResponse.body()).thenReturn(attachmentsJson);

        HttpResponse<InputStream> mockRawNotFound = mock(HttpResponse.class);
        when(mockRawNotFound.statusCode()).thenReturn(404);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    String uri = req.uri().toString();
                    if (uri.contains("/content/9999/raw")) {
                        return mockRawNotFound;
                    }
                    if (uri.contains("/content")) {
                        return mockAttResponse;
                    }
                    if (uri.contains("/variables") || uri.contains("/tasks")) {
                        return mockEmptyResponse;
                    }
                    return mockInstancesResponse;
                });

        List<RepositoryDocument> docs = connector.scan("loanApplication").collectList().block();

        assertNotNull(docs);
        assertEquals(1, docs.size(), "Failed attachment download should not terminate stream; process doc should still be emitted");
        assertEquals("proc-10042", docs.get(0).id());
    }
}
