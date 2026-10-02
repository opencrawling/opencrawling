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
package org.opencrawling.flowable;

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
import org.opencrawling.core.document.RepositoryDocument;

import static org.junit.jupiter.api.Assertions.*;

class FlowableRepositoryConnectorTest {

    private HttpClient mockHttpClient;
    private FlowableRepositoryConnector connector;

    @BeforeEach
    void setUp() throws Exception {
        mockHttpClient = mock(HttpClient.class);
        connector = new FlowableRepositoryConnector(
                "http://localhost:8080/flowable-rest/service",
                "admin",
                "test",
                100,
                "",
                true,
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
    void testConnectSuccess() throws Exception {
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"total\":0,\"data\":[]}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

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
        assertTrue(exception.getMessage().contains("Status code: 401"));
    }

    @Test
    void testScanSuccessWithVariables() throws Exception {
        String processInstancesJson = """
            {
              "total": 1,
              "start": 0,
              "sort": "startTime",
              "order": "asc",
              "size": 1,
              "data": [
                {
                  "id": "proc-1234",
                  "processDefinitionId": "invoice-process:2:98765",
                  "processDefinitionKey": "invoice-process",
                  "businessKey": "INV-2026-001",
                  "startUserId": "finance_agent_1",
                  "startTime": "2026-07-23T08:00:00.000Z",
                  "endTime": "2026-07-23T08:15:00.000Z",
                  "durationInMillis": 900000
                }
              ]
            }
            """;

        String variablesJson = """
            {
              "total": 2,
              "data": [
                {
                  "variable": {
                    "name": "totalAmount",
                    "type": "double",
                    "value": "250.50"
                  }
                },
                {
                  "variable": {
                    "name": "customerName",
                    "type": "string",
                    "value": "ACME Corp"
                  }
                }
              ]
            }
            """;

        String identityLinksJson = """
            [
              {
                "type": "participant",
                "userId": "finance_agent_1",
                "groupId": null,
                "processInstanceId": "proc-1234"
              },
              {
                "type": "candidate",
                "userId": null,
                "groupId": "finance_managers",
                "processInstanceId": "proc-1234"
              }
            ]
            """;

        HttpResponse<String> mockInstancesResponse = mock(HttpResponse.class);
        when(mockInstancesResponse.statusCode()).thenReturn(200);
        when(mockInstancesResponse.body()).thenReturn(processInstancesJson);

        HttpResponse<String> mockVariablesResponse = mock(HttpResponse.class);
        when(mockVariablesResponse.statusCode()).thenReturn(200);
        when(mockVariablesResponse.body()).thenReturn(variablesJson);

        HttpResponse<String> mockIdentityLinksResponse = mock(HttpResponse.class);
        when(mockIdentityLinksResponse.statusCode()).thenReturn(200);
        when(mockIdentityLinksResponse.body()).thenReturn(identityLinksJson);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        when(mockHttpClient.send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    String uri = req.uri().toString();
                    if (uri.contains("/history/historic-variable-instances")) {
                        return mockVariablesResponse;
                    }
                    if (uri.contains("/identitylinks")) {
                        return mockIdentityLinksResponse;
                    }
                    return mockInstancesResponse;
                });

        List<RepositoryDocument> docs = connector.scan("invoice-process").collectList().block();

        assertNotNull(docs);
        assertEquals(1, docs.size());

        RepositoryDocument doc = docs.get(0);
        assertEquals("proc-1234", doc.id());
        assertEquals("flowable://process-instances/proc-1234", doc.uri());
        assertEquals(List.of("invoice-process:2:98765"), doc.metadata().get("processDefinitionId"));
        assertEquals(List.of("invoice-process"), doc.metadata().get("processDefinitionKey"));
        assertEquals(List.of("INV-2026-001"), doc.metadata().get("businessKey"));
        assertEquals(List.of("250.50"), doc.metadata().get("flowable_var_totalAmount"));
        assertEquals(List.of("ACME Corp"), doc.metadata().get("flowable_var_customerName"));

        // Entity references for collocated and distributed patterns
        assertEquals(List.of("proc-1234"), doc.metadata().get(FlowableRepositoryConnector.FIELD_FLOWABLE_PROC_INST_ID));
        assertEquals(List.of("proc-1234"), doc.metadata().get(FlowableRepositoryConnector.FIELD_FLOWABLE_SCOPE_ID));
        assertEquals(List.of("processInstance"), doc.metadata().get(FlowableRepositoryConnector.FIELD_FLOWABLE_SCOPE_TYPE));
        assertEquals(List.of("finance_agent_1"), doc.metadata().get(FlowableRepositoryConnector.FIELD_FLOWABLE_IDENTITY_USERS));
        assertEquals(List.of("finance_managers"), doc.metadata().get(FlowableRepositoryConnector.FIELD_FLOWABLE_IDENTITY_GROUPS));

        // SecurityConfig validation
        assertNotNull(doc.security());
        assertFalse(doc.security().inheritanceEnabled());
        assertEquals(2, doc.security().permissions().size());
        assertTrue(doc.security().permissions().stream().anyMatch(p -> p.identity().equals("finance_agent_1") && p.identityType().equals("user")));
        assertTrue(doc.security().permissions().stream().anyMatch(p -> p.identity().equals("finance_managers") && p.identityType().equals("group")));

        InputStream stream = doc.contentStream();
        assertNotNull(stream);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        String contentJsonStr = baos.toString(StandardCharsets.UTF_8);

        assertTrue(contentJsonStr.contains("\"id\":\"proc-1234\""));
        assertTrue(contentJsonStr.contains("\"flowable_proc_inst_id\":\"proc-1234\""));
        assertTrue(contentJsonStr.contains("\"totalAmount\":\"250.50\""));
        assertTrue(contentJsonStr.contains("\"customerName\":\"ACME Corp\""));
        assertTrue(contentJsonStr.contains("\"allowedUsers\":[\"finance_agent_1\"]"));
        assertTrue(contentJsonStr.contains("\"allowedGroups\":[\"finance_managers\"]"));
    }

    @Test
    void testScanWithAclsDisabled() throws Exception {
        FlowableRepositoryConnector connectorNoAcl = new FlowableRepositoryConnector(
                "http://localhost:8080/flowable-rest/service",
                "admin",
                "test",
                100,
                "",
                false,
                "all",
                false
        );
        connectorNoAcl.setHttpClient(mockHttpClient);

        String processInstancesJson = """
            {
              "total": 1,
              "data": [
                {
                  "id": "proc-9999",
                  "processDefinitionKey": "sample-process",
                  "startTime": "2026-07-23T08:00:00.000Z"
                }
              ]
            }
            """;

        HttpResponse<String> mockInstancesResponse = mock(HttpResponse.class);
        when(mockInstancesResponse.statusCode()).thenReturn(200);
        when(mockInstancesResponse.body()).thenReturn(processInstancesJson);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockInstancesResponse);

        List<RepositoryDocument> docs = connectorNoAcl.scan("sample-process").collectList().block();

        assertNotNull(docs);
        assertEquals(1, docs.size());

        RepositoryDocument doc = docs.get(0);
        assertEquals("proc-9999", doc.id());
        assertEquals("public", doc.acl());
        assertEquals(List.of("proc-9999"), doc.metadata().get(FlowableRepositoryConnector.FIELD_FLOWABLE_PROC_INST_ID));
        assertNull(doc.metadata().get(FlowableRepositoryConnector.FIELD_FLOWABLE_IDENTITY_USERS));
        assertEquals(1, doc.security().permissions().size());
        assertEquals("public", doc.security().permissions().get(0).identity());
    }
}
