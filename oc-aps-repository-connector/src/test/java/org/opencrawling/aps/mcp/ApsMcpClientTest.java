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
package org.opencrawling.aps.mcp;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.*;

class ApsMcpClientTest {

    private HttpClient mockHttpClient;
    private ApsMcpClient mcpClient;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(HttpClient.class);
        mcpClient = new ApsMcpClient("http://localhost:8080/activiti-app/mcp", "admin@app.activiti.com", "admin");
        mcpClient.setHttpClient(mockHttpClient);
    }

    @Test
    void testInitializeSuccess() throws Exception {
        String initResponseJson = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {
                  "tools": {}
                },
                "serverInfo": {
                  "name": "Alfresco-Process-Services-MCP-Server",
                  "version": "26.2.0"
                }
              }
            }
            """;

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(initResponseJson);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);

        mcpClient.initialize();
        assertTrue(mcpClient.isInitialized());
    }

    @Test
    void testListTools() throws Exception {
        String initResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}";
        String listToolsResponse = """
            {
              "jsonrpc": "2.0",
              "id": 2,
              "result": {
                "tools": [
                  {
                    "name": "list_process_definitions",
                    "description": "Lists all deployed workflow definitions in APS"
                  },
                  {
                    "name": "get_process_definition_schema",
                    "description": "Retrieves the variable schema for a workflow"
                  },
                  {
                    "name": "list_candidate_groups",
                    "description": "Lists organizational candidate groups"
                  }
                ]
              }
            }
            """;

        HttpResponse<String> mockInit = mock(HttpResponse.class);
        when(mockInit.statusCode()).thenReturn(200);
        when(mockInit.body()).thenReturn(initResponse);

        HttpResponse<String> mockTools = mock(HttpResponse.class);
        when(mockTools.statusCode()).thenReturn(200);
        when(mockTools.body()).thenReturn(listToolsResponse);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockInit, mockTools);

        mcpClient.initialize();
        List<String> tools = mcpClient.listTools();

        assertEquals(3, tools.size());
        assertTrue(tools.contains("list_process_definitions"));
        assertTrue(tools.contains("get_process_definition_schema"));
        assertTrue(tools.contains("list_candidate_groups"));
    }

    @Test
    void testDiscoverProcessDefinitionKeys() throws Exception {
        String initResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}";
        String toolCallResponse = """
            {
              "jsonrpc": "2.0",
              "id": 2,
              "result": {
                "content": [
                  {
                    "type": "text",
                    "text": "[{\\"key\\": \\"loanApplication\\", \\"name\\": \\"Loan Application\\"}, {\\"key\\": \\"vendorOnboarding\\", \\"name\\": \\"Vendor Onboarding\\"}]"
                  }
                ]
              }
            }
            """;

        HttpResponse<String> mockInit = mock(HttpResponse.class);
        when(mockInit.statusCode()).thenReturn(200);
        when(mockInit.body()).thenReturn(initResponse);

        HttpResponse<String> mockCall = mock(HttpResponse.class);
        when(mockCall.statusCode()).thenReturn(200);
        when(mockCall.body()).thenReturn(toolCallResponse);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockInit, mockCall);

        mcpClient.initialize();
        List<String> keys = mcpClient.discoverProcessDefinitionKeys();

        assertEquals(2, keys.size());
        assertTrue(keys.contains("loanApplication"));
        assertTrue(keys.contains("vendorOnboarding"));
    }

    @Test
    void testDiscoverVariableSchema() throws Exception {
        String initResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}";
        String schemaResponse = """
            {
              "jsonrpc": "2.0",
              "id": 2,
              "result": {
                "content": [
                  {
                    "type": "text",
                    "text": "{\\"variables\\": [{\\"name\\": \\"requestedAmount\\", \\"type\\": \\"double\\"}, {\\"name\\": \\"applicantName\\", \\"type\\": \\"string\\"}]}"
                  }
                ]
              }
            }
            """;

        HttpResponse<String> mockInit = mock(HttpResponse.class);
        when(mockInit.statusCode()).thenReturn(200);
        when(mockInit.body()).thenReturn(initResponse);

        HttpResponse<String> mockCall = mock(HttpResponse.class);
        when(mockCall.statusCode()).thenReturn(200);
        when(mockCall.body()).thenReturn(schemaResponse);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockInit, mockCall);

        mcpClient.initialize();
        Map<String, String> vars = mcpClient.discoverVariableSchema("loanApplication");

        assertEquals(2, vars.size());
        assertEquals("double", vars.get("requestedAmount"));
        assertEquals("string", vars.get("applicantName"));
    }
}
