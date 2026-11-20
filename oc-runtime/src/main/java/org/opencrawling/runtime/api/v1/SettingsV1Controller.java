/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
package org.opencrawling.runtime.api.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencrawling.core.transport.TransportSettingsDTO;
import org.opencrawling.runtime.api.SystemController;
import org.opencrawling.runtime.api.TransportSettingsController;
import org.opencrawling.runtime.transport.TestGrpcResponseDTO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runtime-wide settings: crawling and extraction defaults, and the internal transport. Admins only. */
@RestController
@RequestMapping(ApiV1.BASE + "/settings")
@Tag(name = "Settings", description = "Runtime-wide settings (admins only)")
public class SettingsV1Controller {

    private final SystemController system;
    private final TransportSettingsController transport;
    private final JsonMapper json;

    public SettingsV1Controller(SystemController system, TransportSettingsController transport, JsonMapper json) {
        this.system = system;
        this.transport = transport;
        this.json = json;
    }

    @Operation(operationId = "getSystemSettings", summary = "Get the crawling and extraction settings",
            description = "Chunking defaults and text extraction (Tika) settings in effect. The ETag identifies this version; send it as If-Match when replacing them, or as If-None-Match to get 304 when nothing changed.")
    @ApiResponse(responseCode = "200", description = "The settings", headers = @Header(name = "ETag", description = "Version"))
    @GetMapping("/system")
    public ResponseEntity<SystemController.SystemSettingsDTO> getSystem(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        SystemController.SystemSettingsDTO settings = system.getSettings();
        String etag = Etags.of(settings, json);
        if (Etags.notModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(settings);
    }

    @Operation(operationId = "replaceSystemSettings", summary = "Replace the crawling and extraction settings",
            description = "Values are validated (for example the chunk size). Supports If-Match.")
    @ApiResponse(responseCode = "200", description = "The settings now in effect", headers = @Header(name = "ETag", description = "New version"))
    @PutMapping("/system")
    public ResponseEntity<SystemController.SystemSettingsDTO> replaceSystem(
            @RequestBody SystemController.SystemSettingsDTO settings,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        // The legacy controller's own checks, reported per field.
        List<ApiProblem.FieldError> errors = new ArrayList<>();
        SystemController.invalidSettings(settings).forEach((field, message) -> errors.add(new ApiProblem.FieldError(field, message)));
        if (!errors.isEmpty()) {
            throw ApiProblem.invalid(errors);
        }
        // Check and write under the store's lock, shared with every runtime that uses the same store.
        return system.locked(() -> {
            Etags.requireMatch(ifMatch, Etags.of(system.getSettings(), json));
            JobsV1Controller.requireOk(system.updateSettings(settings), "Settings", "system");
            SystemController.SystemSettingsDTO now = system.getSettings();
            return ResponseEntity.ok().eTag(Etags.of(now, json)).body(now);
        });
    }

    @Operation(operationId = "getTransportSettings", summary = "Get the internal transport settings",
            description = "How the runtime's services talk to each other (gRPC or REST): mode, gRPC port, limits, keep-alive and TLS. The ETag identifies this version; send it as If-Match when replacing them, or as If-None-Match to get 304 when nothing changed.")
    @ApiResponse(responseCode = "200", description = "The settings", headers = @Header(name = "ETag", description = "Version"))
    @GetMapping("/transport")
    public ResponseEntity<TransportSettingsDTO> getTransport(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        TransportSettingsDTO settings = currentTransport();
        String etag = Etags.of(settings, json);
        if (Etags.notModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(settings);
    }

    @Operation(operationId = "replaceTransportSettings", summary = "Replace the internal transport settings",
            description = "Switches between gRPC and REST for the runtime's internal calls, and sets gRPC port, limits, keep-alive and TLS. Takes effect at once. Supports If-Match.")
    @ApiResponse(responseCode = "200", description = "The settings now in effect", headers = @Header(name = "ETag", description = "New version"))
    @PutMapping("/transport")
    public ResponseEntity<TransportSettingsDTO> replaceTransport(@RequestBody TransportSettingsDTO settings,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        // The store's lock (the one SystemController and the transport manager share), around check and write.
        return system.locked(() -> {
            Etags.requireMatch(ifMatch, Etags.of(currentTransport(), json));
            ResponseEntity<TransportSettingsDTO> result = transport.updateTransportSettingsPut(settings);
            JobsV1Controller.requireOk(result, "Settings", "transport");
            TransportSettingsDTO now = currentTransport();
            return ResponseEntity.ok().eTag(Etags.of(now, json)).body(now);
        });
    }

    /** The settings document inside the legacy answer, which also carries a status summary. */
    private TransportSettingsDTO currentTransport() {
        Map<String, Object> legacy = transport.getTransportSettings().getBody();
        if (legacy == null || !(legacy.get("settings") instanceof TransportSettingsDTO settings)) {
            throw new IllegalStateException("The transport settings are not available");
        }
        return settings;
    }

    @Operation(operationId = "testGrpcTransport", summary = "Test the gRPC transport",
            description = "Tries a gRPC connection, to the configured target or to the host and port sent.")
    @ApiResponse(responseCode = "200", description = "The test's result")
    @PostMapping("/transport:test-grpc")
    public TestGrpcResponseDTO testGrpc(@RequestBody(required = false) Map<String, Object> target) {
        ResponseEntity<TestGrpcResponseDTO> result = transport.testGrpcConnection(target);
        JobsV1Controller.requireOk(result, "Settings", "transport");
        return result.getBody();
    }
}
