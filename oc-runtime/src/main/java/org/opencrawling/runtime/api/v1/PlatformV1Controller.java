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
package org.opencrawling.runtime.api.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencrawling.runtime.api.SystemController;
import org.opencrawling.runtime.security.AuthController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The API's entry point: what it offers, its version, the runtime's health, and who the caller is. */
@RestController
@RequestMapping(ApiV1.BASE)
@Tag(name = "Platform", description = "Entry point, version, health and the current caller")
public class PlatformV1Controller {

    private final SystemController system;
    private final ObjectProvider<AuthController> auth;
    private final Environment environment;

    public PlatformV1Controller(SystemController system, ObjectProvider<AuthController> auth, Environment environment) {
        this.system = system;
        this.auth = auth;
        this.environment = environment;
    }

    @Schema(description = "What the API offers on this installation")
    public record ApiIndex(
            @Schema(example = "OpenCrawling Platform API") String name,
            @Schema(example = ApiV1.VERSION) String apiVersion,
            @Schema(description = "Collection and resource URLs") Map<String, String> links,
            @Schema(description = "Optional features and whether they are enabled here") Map<String, Boolean> features,
            @Schema(description = "Where the API is described") Map<String, String> documentation) {}

    @Schema(description = "API and runtime versions")
    public record VersionInfo(@Schema(example = ApiV1.VERSION) String apiVersion,
                              @Schema(description = "Version of the runtime build, if known") String runtimeVersion) {}

    @Schema(description = "Health of the runtime and its dependencies")
    public record Health(@Schema(description = "HEALTHY or another state", example = "HEALTHY") String status,
                         @Schema(description = "Each dependency: UP or DOWN") Map<String, String> components) {}

    @Operation(operationId = "getApiIndex", summary = "Entry point",
            description = "Links to every collection, the optional features enabled on this installation, and the API description.")
    @ApiResponse(responseCode = "200", description = "The index")
    @GetMapping
    public ApiIndex index() {
        // Each readable collection and resource, under its own name.
        Map<String, String> links = new LinkedHashMap<>();
        links.put("jobs", ApiV1.BASE + "/jobs");
        links.put("jobRuns", ApiV1.BASE + "/job-runs");
        links.put("executions", ApiV1.BASE + "/executions");
        links.put("connectors", ApiV1.BASE + "/connectors");
        links.put("systemSettings", ApiV1.BASE + "/settings/system");
        links.put("transportSettings", ApiV1.BASE + "/settings/transport");
        links.put("systemStatus", ApiV1.BASE + "/system/status");
        links.put("systemThroughput", ApiV1.BASE + "/system/throughput");
        links.put("systemLogs", ApiV1.BASE + "/system/logs");
        links.put("metrics", ApiV1.BASE + "/observability/metrics");
        links.put("me", ApiV1.BASE + "/me");
        Map<String, Boolean> features = new LinkedHashMap<>();
        if (enabled("opencrawling.discovery.enabled")) {
            links.put("discovery", ApiV1.BASE + "/discovery/connectors");
        }
        if (enabled("opencrawling.jobs.dynamic.enabled")) {
            links.put("dynamicJobs", ApiV1.BASE + "/dynamic-jobs");
        }
        if (enabled("opencrawling.sidecars.enabled")) {
            links.put("sidecarStores", ApiV1.BASE + "/sidecar-stores");
        }
        features.put("discovery", enabled("opencrawling.discovery.enabled"));
        features.put("dynamicJobs", enabled("opencrawling.jobs.dynamic.enabled"));
        features.put("sidecars", enabled("opencrawling.sidecars.enabled"));
        features.put("managementAuthorization", enabled("opencrawling.security.management.enabled"));
        return new ApiIndex("OpenCrawling Platform API", ApiV1.VERSION, links, features, Map.of(
                "openapi", "/v3/api-docs/" + ApiV1.OPENAPI_GROUP,
                "swaggerUi", "/swagger-ui.html",
                "guide", "https://github.com/opencrawling/opencrawling/wiki/Platform-API"));
    }

    /** The same with a trailing slash; not listed separately in the API description. */
    @Operation(hidden = true)
    @GetMapping("/")
    public ApiIndex indexWithSlash() {
        return index();
    }

    @Operation(operationId = "getVersion", summary = "API and runtime versions",
            description = "The Platform API version (follows semantic versioning; breaking changes get a new /api/vN) and the runtime build's version.")
    @ApiResponse(responseCode = "200", description = "The versions")
    @GetMapping("/version")
    public VersionInfo version() {
        return new VersionInfo(ApiV1.VERSION, RUNTIME_VERSION);
    }

    @Operation(operationId = "getHealth", summary = "Health of the runtime",
            description = "200 when the runtime is healthy, 503 otherwise; the body says which dependency is down.")
    @ApiResponse(responseCode = "200", description = "Healthy")
    @ApiResponse(responseCode = "503", description = "Not healthy")
    @GetMapping("/health")
    public ResponseEntity<Health> health() {
        Map<String, String> status = new LinkedHashMap<>(system.getSystemStatus());
        String overall = status.remove("system");
        Health body = new Health(overall == null ? "UNKNOWN" : overall, status);
        return ResponseEntity.status("HEALTHY".equals(overall) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @Operation(operationId = "getCurrentCaller", summary = "Who the caller is",
            description = "The verified caller (principal, groups, tenant) and what it may do on the management plane.")
    @ApiResponse(responseCode = "200", description = "The caller")
    @GetMapping("/me")
    public AuthController.Me me() {
        AuthController controller = auth.getIfAvailable();
        if (controller == null) {
            throw ApiProblem.unavailable("This runtime has no web security configuration.");
        }
        return controller.me();
    }

    private boolean enabled(String property) {
        return environment.getProperty(property, Boolean.class, false);
    }

    /** The build's version: from the jar's manifest, or else from the Maven properties packaged in the jar. */
    private static final String RUNTIME_VERSION = runtimeVersion();

    private static String runtimeVersion() {
        String version = PlatformV1Controller.class.getPackage().getImplementationVersion();
        if (version != null) {
            return version;
        }
        try (java.io.InputStream in = PlatformV1Controller.class.getResourceAsStream(
                "/META-INF/maven/org.opencrawling/oc-runtime/pom.properties")) {
            if (in != null) {
                java.util.Properties properties = new java.util.Properties();
                properties.load(in);
                return properties.getProperty("version");
            }
        } catch (java.io.IOException ignored) {
            // unknown: reported as null
        }
        return null;
    }
}
