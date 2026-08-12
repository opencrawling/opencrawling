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
package org.opencrawling.runtime.api;

import java.util.Map;
import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.opencrawling.core.transport.TransportSettingsDTO;
import org.opencrawling.runtime.transport.InternalTransportManager;
import org.opencrawling.runtime.transport.TestGrpcResponseDTO;

@RestController
@RequestMapping
public class TransportSettingsController {

    private final InternalTransportManager transportManager;

    @Autowired
    public TransportSettingsController(InternalTransportManager transportManager) {
        this.transportManager = transportManager;
    }

    @GetMapping({"/api/v1/admin/settings/transport", "/api/system/settings/transport"})
    public ResponseEntity<Map<String, Object>> getTransportSettings() {
        TransportSettingsDTO settings = transportManager.getSettings();
        Map<String, Object> response = new HashMap<>();
        response.put("settings", settings);
        response.put("status", "UP");
        response.put("activeMode", settings.mode());
        response.put("grpcEnabled", settings.grpcEnabled());
        response.put("grpcPort", settings.grpcPort());
        response.put("fallbackToRest", settings.fallbackToRest());
        return ResponseEntity.ok(response);
    }

    @PutMapping({"/api/v1/admin/settings/transport", "/api/system/settings/transport"})
    public ResponseEntity<TransportSettingsDTO> updateTransportSettingsPut(@RequestBody TransportSettingsDTO newSettings) {
        transportManager.updateSettings(newSettings);
        return ResponseEntity.ok(transportManager.getSettings());
    }

    @PostMapping({"/api/v1/admin/settings/transport", "/api/system/settings/transport"})
    public ResponseEntity<TransportSettingsDTO> updateTransportSettingsPost(@RequestBody TransportSettingsDTO newSettings) {
        transportManager.updateSettings(newSettings);
        return ResponseEntity.ok(transportManager.getSettings());
    }

    @PostMapping({"/api/v1/admin/settings/transport/test-grpc", "/api/system/settings/transport/test-grpc"})
    public ResponseEntity<TestGrpcResponseDTO> testGrpcConnection(@RequestBody(required = false) Map<String, Object> payload) {
        String host = "127.0.0.1";
        Integer port = null;
        if (payload != null) {
            if (payload.containsKey("host") && payload.get("host") != null) {
                host = payload.get("host").toString();
            }
            if (payload.containsKey("port") && payload.get("port") != null) {
                try {
                    port = Integer.parseInt(payload.get("port").toString());
                } catch (Exception ignored) {}
            }
        }
        TestGrpcResponseDTO result = transportManager.testGrpcConnection(host, port);
        return ResponseEntity.ok(result);
    }
}
