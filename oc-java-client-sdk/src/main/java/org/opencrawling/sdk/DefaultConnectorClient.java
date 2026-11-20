/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import org.opencrawling.sdk.http.HttpTransport;
import org.opencrawling.sdk.models.ConnectionCheckResponse;
import org.opencrawling.sdk.models.ConnectorRequest;
import org.opencrawling.sdk.models.ConnectorResponse;

import java.util.List;

/**
 * Default implementation of ConnectorClient using HttpTransport.
 */
public class DefaultConnectorClient implements ConnectorClient {

    private final HttpTransport transport;

    public DefaultConnectorClient(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public List<ConnectorResponse> list(String type) {
        String reqType = type != null ? type : "repository";
        return transport.execute("GET", "/api/connectors/" + reqType, null, new TypeReference<List<ConnectorResponse>>() {});
    }

    @Override
    public void create(ConnectorRequest request) {
        transport.executeVoid("POST", "/api/connectors", request);
    }

    @Override
    public ConnectionCheckResponse checkConnection(ConnectorRequest request) {
        return transport.execute("POST", "/api/connectors/check", request, ConnectionCheckResponse.class);
    }

    @Override
    public void delete(String name) {
        transport.executeVoid("DELETE", "/api/connectors/" + name, null);
    }
}
