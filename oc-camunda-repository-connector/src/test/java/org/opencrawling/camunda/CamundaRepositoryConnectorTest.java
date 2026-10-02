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
package org.opencrawling.camunda;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.opencrawling.core.document.RepositoryDocument;

import reactor.test.StepVerifier;

class CamundaRepositoryConnectorTest {

    private CamundaRepositoryConnector connector;
    private HttpClient mockHttpClient;
    private HttpResponse<String> mockHttpResponse;

    @BeforeEach
    void setUp() {
        connector = new CamundaRepositoryConnector(
                "http://localhost:8080/engine-rest",
                "demo",
                "demo",
                10,
                "",
                true,
                "all",
                true
        );
        mockHttpClient = mock(HttpClient.class);
        mockHttpResponse = mock(HttpResponse.class);
        connector.setHttpClient(mockHttpClient);
    }

    @Test
    void testGetName() {
        assertEquals("CamundaConnector", connector.getName());
    }

    @Test
    void testConnectSuccess() throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn("[{\"id\":\"proc-1\"}]");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);

        connector.connect();
    }

    @Test
    void testConnectFailure() throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(401);
        when(mockHttpResponse.body()).thenReturn("Unauthorized");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);

        assertThrows(IOException.class, () -> connector.connect());
    }

    @Test
    void testScanProcessInstancesWithSecurityAndEntityReferences() throws Exception {
        String processInstancesJson = """
                [
                  {
                    "id": "proc-100",
                    "processDefinitionId": "order-process:1:10",
                    "processDefinitionKey": "order-process",
                    "businessKey": "ORD-99",
                    "startUserId": "user1",
                    "startTime": "2026-07-24T10:00:00.000+0000",
                    "endTime": "2026-07-24T10:05:00.000+0000",
                    "durationInMillis": 300000,
                    "tenantId": "tenant-alpha"
                  }
                ]
                """;

        String identityLinksJson = """
                [
                  {
                    "type": "assignee",
                    "userId": "approver_1",
                    "groupId": null,
                    "processInstanceId": "proc-100",
                    "operationType": "add"
                  },
                  {
                    "type": "candidate",
                    "userId": null,
                    "groupId": "finance-managers",
                    "processInstanceId": "proc-100",
                    "operationType": "add"
                  }
                ]
                """;

        String variablesJson = """
                [
                  {
                    "name": "amount",
                    "value": 1500
                  }
                ]
                """;

        HttpResponse<String> instancesResponse = mock(HttpResponse.class);
        when(instancesResponse.statusCode()).thenReturn(200);
        when(instancesResponse.body()).thenReturn(processInstancesJson, "[]");

        HttpResponse<String> linksResponse = mock(HttpResponse.class);
        when(linksResponse.statusCode()).thenReturn(200);
        when(linksResponse.body()).thenReturn(identityLinksJson);

        HttpResponse<String> variablesResponse = mock(HttpResponse.class);
        when(variablesResponse.statusCode()).thenReturn(200);
        when(variablesResponse.body()).thenReturn(variablesJson);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    String uri = req.uri().toString();
                    if (uri.contains("/history/identity-link-log")) {
                        return linksResponse;
                    }
                    if (uri.contains("/history/variable-instance")) {
                        return variablesResponse;
                    }
                    return instancesResponse;
                });

        StepVerifier.create(connector.scan("/"))
                .assertNext(doc -> {
                    assertNotNull(doc);
                    assertEquals("proc-100", doc.id());
                    assertEquals("camunda://process-instances/proc-100", doc.uri());
                    assertEquals(List.of("order-process"), doc.metadata().get("processDefinitionKey"));
                    assertEquals(List.of("1500"), doc.metadata().get("camunda_var_amount"));

                    // Verify Collocated DB Entity References
                    assertEquals(List.of("proc-100"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_PROC_INST_ID));
                    assertEquals(List.of("proc-100"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_SCOPE_ID));
                    assertEquals(List.of("processInstance"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_SCOPE_TYPE));
                    assertEquals(List.of("order-process"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_PROCESS_DEFINITION_KEY));
                    assertEquals(List.of("ORD-99"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_BUSINESS_KEY));
                    assertEquals(List.of("user1"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_START_USER_ID));
                    assertEquals(List.of("tenant-alpha"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_TENANT_ID));

                    // Verify Security & ACLs
                    assertEquals(List.of("user1", "approver_1"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_IDENTITY_USERS));
                    assertEquals(List.of("finance-managers"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_IDENTITY_GROUPS));
                    assertEquals("user1,approver_1,finance-managers", doc.acl());

                    assertNotNull(doc.security());
                    assertFalse(doc.security().inheritanceEnabled());
                    assertEquals(3, doc.security().permissions().size());

                    // Verify Content JSON
                    try {
                        InputStream stream = doc.contentStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        stream.transferTo(baos);
                        String contentJson = baos.toString(StandardCharsets.UTF_8);
                        assertTrue(contentJson.contains("\"camunda_proc_inst_id\":\"proc-100\""));
                        assertTrue(contentJson.contains("\"camunda_scope_id\":\"proc-100\""));
                        assertTrue(contentJson.contains("\"camunda_scope_type\":\"processInstance\""));
                        assertTrue(contentJson.contains("\"allowedUsers\":[\"user1\",\"approver_1\"]"));
                        assertTrue(contentJson.contains("\"allowedGroups\":[\"finance-managers\"]"));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void testScanWithIncludeAclsFalse() throws Exception {
        CamundaRepositoryConnector noAclConnector = new CamundaRepositoryConnector(
                "http://localhost:8080/engine-rest",
                "demo",
                "demo",
                10,
                "",
                false,
                "all",
                false
        );
        noAclConnector.setHttpClient(mockHttpClient);

        String processInstancesJson = """
                [
                  {
                    "id": "proc-200",
                    "processDefinitionKey": "sample-process",
                    "startUserId": "starter"
                  }
                ]
                """;

        HttpResponse<String> instancesResponse = mock(HttpResponse.class);
        when(instancesResponse.statusCode()).thenReturn(200);
        when(instancesResponse.body()).thenReturn(processInstancesJson, "[]");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(instancesResponse);

        StepVerifier.create(noAclConnector.scan("/"))
                .assertNext(doc -> {
                    assertNotNull(doc);
                    assertEquals("proc-200", doc.id());
                    assertNotNull(doc.security());
                    assertEquals(1, doc.security().permissions().size());
                    assertEquals("public", doc.security().permissions().get(0).identity());
                    assertEquals(List.of("proc-200"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_PROC_INST_ID));
                })
                .verifyComplete();
    }
}
