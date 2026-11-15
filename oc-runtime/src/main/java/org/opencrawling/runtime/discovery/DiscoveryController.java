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
package org.opencrawling.runtime.discovery;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.security.CallerResolver;
import org.opencrawling.runtime.security.VerifiedCallerRequiredException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST access to repository discovery. Identity works as for the MCP tools: in {@code jwt} mode the caller comes
 * from the request's validated token (so {@code protect-api} must be on), otherwise from the {@code userPrincipal}
 * and {@code userRoles} parameters, which discovery accepts only with {@code allow-asserted-identity}.
 */
@RestController
@RequestMapping("/api/discovery")
@ConditionalOnProperty(name = "opencrawling.discovery.enabled", havingValue = "true")
@Tag(name = "Discovery", description = "List registered repositories' trees and preview crawls, filtered by the caller's access")
public class DiscoveryController {

    private final DiscoveryService service;
    private CallerResolver callerResolver = CallerResolver.asserted();

    public DiscoveryController(DiscoveryService service) {
        this.service = service;
    }

    @Autowired(required = false)
    public void setCallerResolver(CallerResolver callerResolver) {
        if (callerResolver != null) {
            this.callerResolver = callerResolver;
        }
    }

    /** The body of a dry-run request. */
    public record DryRunBody(String scope, Integer sampleSize, Integer maxScan) {}

    @Operation(summary = "List connectors that support discovery")
    @ApiResponse(responseCode = "200", description = "Registered repository connectors that support discovery")
    @ApiResponse(responseCode = "401", description = "No verified caller")
    @GetMapping("/connectors")
    public List<DiscoveryService.ConnectorInfo> connectors(
            @RequestParam(required = false) String userPrincipal, @RequestParam(required = false) String userRoles) {
        return service.listConnectors(caller(callerResolver, userPrincipal, userRoles));
    }

    @Operation(summary = "List a repository's tree", description = "Breadth-first from a folder, showing only what the caller may read. No content is read.")
    @ApiResponse(responseCode = "200", description = "The visible part of the tree")
    @ApiResponse(responseCode = "400", description = "Unknown start folder, or the connector does not support discovery")
    @ApiResponse(responseCode = "404", description = "No such repository connector")
    @GetMapping("/{connector}/tree")
    public DiscoveryService.TreeView tree(
            @PathVariable String connector,
            @Parameter(description = "Folder id or path; the connector's root when absent") @RequestParam(required = false) String start,
            @RequestParam(required = false) Integer depth,
            @RequestParam(required = false) Integer maxNodes,
            @RequestParam(required = false) Boolean includeDocuments,
            @RequestParam(required = false) String userPrincipal,
            @RequestParam(required = false) String userRoles) {
        return service.explore(caller(callerResolver, userPrincipal, userRoles), connector, start, depth, maxNodes,
                includeDocuments);
    }

    @Operation(summary = "Preview a crawl", description = "Counts what a crawl of a folder or a read-only query would take that the caller may read, with samples. No content is read.")
    @ApiResponse(responseCode = "200", description = "The preview")
    @ApiResponse(responseCode = "400", description = "Unknown folder or not a read-only query")
    @ApiResponse(responseCode = "404", description = "No such repository connector")
    @PostMapping("/{connector}/dry-run")
    public DiscoveryService.DryRunView dryRun(
            @PathVariable String connector,
            @RequestBody(required = false) DryRunBody body,
            @RequestParam(required = false) String userPrincipal,
            @RequestParam(required = false) String userRoles) {
        DryRunBody request = body != null ? body : new DryRunBody(null, null, null);
        return service.dryRun(caller(callerResolver, userPrincipal, userRoles), connector, request.scope(),
                request.sampleSize(), request.maxScan());
    }

    @ExceptionHandler(DiscoveryApiException.class)
    ResponseEntity<Map<String, String>> failed(DiscoveryApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    /**
     * The caller of a discovery request: the token's in {@code jwt} mode, the asserted arguments otherwise.
     *
     * @throws DiscoveryApiException {@code 401} when callers are verified and the request carries no token
     */
    static Caller caller(CallerResolver resolver, String userPrincipal, String userRoles) {
        try {
            return resolver.resolve(userPrincipal, userRoles);
        } catch (VerifiedCallerRequiredException e) {
            throw new DiscoveryApiException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }
}
