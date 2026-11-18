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
package org.opencrawling.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opencrawling.sdk.http.HttpTransport;
import org.opencrawling.sdk.models.SystemSettings;
import org.opencrawling.sdk.models.SystemStatus;

import java.util.List;
import java.util.Map;

/**
 * Default implementation of SystemClient using HttpTransport.
 */
public class DefaultSystemClient implements SystemClient {

    private final HttpTransport transport;

    public DefaultSystemClient(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public SystemStatus getStatus() {
        return transport.execute("GET", "/api/system/status", null, SystemStatus.class);
    }

    @Override
    public List<Map<String, Object>> getThroughput() {
        return transport.execute("GET", "/api/system/throughput", null, new TypeReference<List<Map<String, Object>>>() {});
    }

    @Override
    public List<String> getLogs() {
        return transport.execute("GET", "/api/system/logs", null, new TypeReference<List<String>>() {});
    }

    @Override
    public SystemSettings getSettings() {
        return transport.execute("GET", "/api/system/settings", null, SystemSettings.class);
    }

    /**
     * Sends the runtime's current settings with this model's fields changed. The model has only some of the
     * runtime's settings (chunking and embedding); sending it alone would reset the others (text extraction,
     * claim-check store) to their defaults.
     */
    @Override
    public void updateSettings(SystemSettings settings) {
        ObjectNode current = transport.execute("GET", "/api/system/settings", null, ObjectNode.class);
        ObjectNode merged = current != null ? current : transport.getObjectMapper().createObjectNode();
        ObjectNode changes = transport.getObjectMapper().valueToTree(settings);
        changes.properties().forEach(field -> merged.set(field.getKey(), field.getValue()));
        transport.executeVoid("POST", "/api/system/settings", merged);
    }
}
