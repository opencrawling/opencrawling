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

    private CamundaRepositoryConnector legacyConnector;
    private CamundaRepositoryConnector camunda8Connector;
    private HttpClient mockHttpClient;
    private HttpResponse<String> mockHttpResponse;

    @BeforeEach
    void setUp() {
        legacyConnector = new CamundaRepositoryConnector(
                "http://localhost:8080/engine-rest",
                "demo",
                "demo",
                10,
                "",
                true,
                "all",
                true
        );
        camunda8Connector = new CamundaRepositoryConnector(
                "http://localhost:8080/v2",
                "basic",
                "demo",
                "demo",
                "",
                "",
                "",
                "zeebe-api",
                10,
                "",
                true,
                true,
                true,
                true,
                "all",
                ""
        );
        mockHttpClient = mock(HttpClient.class);
        mockHttpResponse = mock(HttpResponse.class);
        legacyConnector.setHttpClient(mockHttpClient);
        camunda8Connector.setHttpClient(mockHttpClient);
    }

    @Test
    void testGetName() {
        assertEquals("CamundaConnector", legacyConnector.getName());
        assertEquals("CamundaConnector", camunda8Connector.getName());
    }

    @Test
    void testLegacyConnectSuccess() throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn("[{\"id\":\"proc-1\"}]");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);

        legacyConnector.connect();
    }

    @Test
    void testLegacyConnectFailure() throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(401);
        when(mockHttpResponse.body()).thenReturn("Unauthorized");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);

        assertThrows(IOException.class, () -> legacyConnector.connect());
    }

    @Test
    void testCamunda8ConnectSuccess() throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn("{\"items\":[]}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);

        camunda8Connector.connect();
    }

    @Test
    void testCamunda8ConnectFailure() throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(403);
        when(mockHttpResponse.body()).thenReturn("Forbidden");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);

        assertThrows(IOException.class, () -> camunda8Connector.connect());
    }

    @Test
    void testOAuth2ClientCredentialsAuthentication() throws Exception {
        CamundaRepositoryConnector oauthConnector = new CamundaRepositoryConnector(
                "http://localhost:8080/v2",
                "oauth2",
                "",
                "",
                "http://localhost:18080/auth/token",
                "test-client-id",
                "test-client-secret",
                "zeebe-api",
                10,
                "",
                true,
                true,
                true,
                true,
                "all",
                ""
        );
        oauthConnector.setHttpClient(mockHttpClient);

        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"access_token\":\"mock-bearer-token-12345\",\"expires_in\":3600}");

        HttpResponse<String> pingResponse = mock(HttpResponse.class);
        when(pingResponse.statusCode()).thenReturn(200);
        when(pingResponse.body()).thenReturn("{\"items\":[]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    if (req.uri().toString().contains("/auth/token")) {
                        return tokenResponse;
                    }
                    assertTrue(req.headers().firstValue("Authorization").orElse("").contains("Bearer mock-bearer-token-12345"));
                    return pingResponse;
                });

        oauthConnector.connect();
    }

    @Test
    void testScanCamunda8ProcessInstancesWithTasksVariablesAndIncidents() throws Exception {
        String processInstancesJson = """
                {
                  "items": [
                    {
                      "processInstanceKey": 2251799813685257,
                      "processDefinitionKey": 2251799813685249,
                      "bpmnProcessId": "loan-approval-process",
                      "version": 2,
                      "startDate": "2026-07-24T10:00:00.000Z",
                      "endDate": "2026-07-24T10:05:00.000Z",
                      "state": "COMPLETED",
                      "tenantId": "tenant-enterprise-alpha"
                    }
                  ]
                }
                """;

        String userTasksJson = """
                {
                  "items": [
                    {
                      "userTaskKey": 2251799813685260,
                      "processInstanceKey": 2251799813685257,
                      "assignee": "senior_officer",
                      "candidateUsers": ["loan_officer_1", "loan_officer_2"],
                      "candidateGroups": ["loan-underwriters"],
                      "tenantId": "tenant-enterprise-alpha"
                    }
                  ]
                }
                """;

        String variablesJson = """
                {
                  "items": [
                    {
                      "name": "requestedAmount",
                      "value": 250000
                    },
                    {
                      "name": "riskRating",
                      "value": "LOW"
                    }
                  ]
                }
                """;

        String incidentsJson = """
                {
                  "items": []
                }
                """;

        HttpResponse<String> instancesResponse = mock(HttpResponse.class);
        when(instancesResponse.statusCode()).thenReturn(200);
        when(instancesResponse.body()).thenReturn(processInstancesJson, "{\"items\":[]}");

        HttpResponse<String> tasksResponse = mock(HttpResponse.class);
        when(tasksResponse.statusCode()).thenReturn(200);
        when(tasksResponse.body()).thenReturn(userTasksJson);

        HttpResponse<String> variablesResponse = mock(HttpResponse.class);
        when(variablesResponse.statusCode()).thenReturn(200);
        when(variablesResponse.body()).thenReturn(variablesJson);

        HttpResponse<String> incidentsResponse = mock(HttpResponse.class);
        when(incidentsResponse.statusCode()).thenReturn(200);
        when(incidentsResponse.body()).thenReturn(incidentsJson);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    String uri = req.uri().toString();
                    if (uri.contains("/user-tasks/search")) {
                        return tasksResponse;
                    }
                    if (uri.contains("/variables/search")) {
                        return variablesResponse;
                    }
                    if (uri.contains("/incidents/search")) {
                        return incidentsResponse;
                    }
                    return instancesResponse;
                });

        StepVerifier.create(camunda8Connector.scan("/"))
                .assertNext(doc -> {
                    assertNotNull(doc);
                    assertEquals("2251799813685257", doc.id());
                    assertEquals("camunda8://process-instances/2251799813685257", doc.uri());

                    // Camunda 8 Metadata Attributes
                    assertEquals(List.of("2251799813685257"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA8_PROCESS_INSTANCE_KEY));
                    assertEquals(List.of("loan-approval-process"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA8_BPMN_PROCESS_ID));
                    assertEquals(List.of("2"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA8_VERSION));
                    assertEquals(List.of("COMPLETED"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA8_STATE));
                    assertEquals(List.of("tenant-enterprise-alpha"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA8_TENANT_ID));
                    assertEquals(List.of("0"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA8_INCIDENT_COUNT));
                    assertEquals(List.of("250000"), doc.metadata().get("camunda8_var_requestedAmount"));
                    assertEquals(List.of("LOW"), doc.metadata().get("camunda8_var_riskRating"));

                    // Collocated DB and Entity References
                    assertEquals(List.of("2251799813685257"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_PROC_INST_ID));
                    assertEquals(List.of("2251799813685257"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_SCOPE_ID));
                    assertEquals(List.of("processInstance"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_SCOPE_TYPE));
                    assertEquals(List.of("loan-approval-process"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_PROCESS_DEFINITION_KEY));
                    assertEquals(List.of("tenant-enterprise-alpha"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_TENANT_ID));
                    assertEquals(List.of("250000"), doc.metadata().get("camunda_var_requestedAmount"));

                    // Verify Security & ACLs
                    assertEquals(List.of("senior_officer", "loan_officer_1", "loan_officer_2"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_IDENTITY_USERS));
                    assertEquals(List.of("loan-underwriters"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_IDENTITY_GROUPS));
                    assertEquals("senior_officer,loan_officer_1,loan_officer_2,loan-underwriters", doc.acl());

                    assertNotNull(doc.security());
                    assertFalse(doc.security().inheritanceEnabled());
                    assertEquals(4, doc.security().permissions().size());

                    // Verify Content JSON
                    try {
                        InputStream stream = doc.contentStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        stream.transferTo(baos);
                        String contentJson = baos.toString(StandardCharsets.UTF_8);
                        assertTrue(contentJson.contains("\"processInstanceKey\":\"2251799813685257\""));
                        assertTrue(contentJson.contains("\"bpmnProcessId\":\"loan-approval-process\""));
                        assertTrue(contentJson.contains("\"camunda_proc_inst_id\":\"2251799813685257\""));
                        assertTrue(contentJson.contains("\"allowedUsers\":[\"senior_officer\",\"loan_officer_1\",\"loan_officer_2\"]"));
                        assertTrue(contentJson.contains("\"allowedGroups\":[\"loan-underwriters\"]"));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void testLegacyScanProcessInstancesWithSecurityAndEntityReferences() throws Exception {
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

        StepVerifier.create(legacyConnector.scan("/"))
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
                "http://localhost:8080/v2",
                "basic",
                "demo",
                "demo",
                "",
                "",
                "",
                "zeebe-api",
                10,
                "",
                false,
                false,
                false,
                false,
                "all",
                ""
        );
        noAclConnector.setHttpClient(mockHttpClient);

        String processInstancesJson = """
                {
                  "items": [
                    {
                      "processInstanceKey": 99999,
                      "bpmnProcessId": "sample-process",
                      "initiator": "starter"
                    }
                  ]
                }
                """;

        HttpResponse<String> instancesResponse = mock(HttpResponse.class);
        when(instancesResponse.statusCode()).thenReturn(200);
        when(instancesResponse.body()).thenReturn(processInstancesJson, "{\"items\":[]}");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(instancesResponse);

        StepVerifier.create(noAclConnector.scan("/"))
                .assertNext(doc -> {
                    assertNotNull(doc);
                    assertEquals("99999", doc.id());
                    assertNotNull(doc.security());
                    assertEquals(1, doc.security().permissions().size());
                    assertEquals("public", doc.security().permissions().get(0).identity());
                    assertEquals(List.of("99999"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA_PROC_INST_ID));
                    assertEquals(List.of("99999"), doc.metadata().get(CamundaRepositoryConnector.FIELD_CAMUNDA8_PROCESS_INSTANCE_KEY));
                })
                .verifyComplete();
    }
}
