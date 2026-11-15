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
package org.opencrawling.runtime.sidecar;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST access to OIS sidecars, filtered by the caller's access. */
@RestController
@RequestMapping("/api/sidecars")
@ConditionalOnProperty(name = SidecarProperties.PREFIX + ".enabled", havingValue = "true")
@Tag(name = "OIS Sidecars", description = "Read the OIS metadata sidecars of migrated content without reading the binaries")
public class SidecarController {

    private final SidecarService service;
    private CallerResolver callerResolver = CallerResolver.asserted();

    public SidecarController(SidecarService service) {
        this.service = service;
    }

    @Autowired(required = false)
    public void setCallerResolver(CallerResolver callerResolver) {
        if (callerResolver != null) {
            this.callerResolver = callerResolver;
        }
    }

    @Operation(summary = "List the sidecar stores", description = "The registered Ozone, S3, and Apache Iceberg output connectors whose sidecars may be read.")
    @GetMapping
    public List<SidecarService.StoreInfo> stores(@RequestParam(required = false) String userPrincipal,
                                                 @RequestParam(required = false) String userRoles) {
        return service.listStores(caller(callerResolver, userPrincipal, userRoles));
    }

    @Operation(summary = "Read sidecars", description = "A page of the *.ois.json sidecars under a key prefix that the "
            + "caller may read, in key order, with totals over the page. Binaries are not read and permissions are not returned.")
    @ApiResponse(responseCode = "200", description = "A page; pass nextCursor back for the next one")
    @ApiResponse(responseCode = "400", description = "Invalid prefix, or a cursor that is not valid for this request")
    @ApiResponse(responseCode = "401", description = "No verified caller")
    @ApiResponse(responseCode = "403", description = "The token names no user or lacks the required scope")
    @ApiResponse(responseCode = "404", description = "No such sidecar store")
    @ApiResponse(responseCode = "502", description = "The store could not be read")
    @ApiResponse(responseCode = "503", description = "Access could not be decided")
    @GetMapping("/{connector}")
    public SidecarService.SidecarPage read(
            @PathVariable String connector,
            @Parameter(description = "Key prefix, relative to the prefix the connector writes under") @RequestParam(required = false) String prefix,
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "The previous page's nextCursor") @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String userPrincipal,
            @RequestParam(required = false) String userRoles) {
        return service.read(caller(callerResolver, userPrincipal, userRoles), connector, prefix, limit, cursor);
    }

    @ExceptionHandler(SidecarException.class)
    ResponseEntity<Map<String, String>> failed(SidecarException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    static Caller caller(CallerResolver resolver, String userPrincipal, String userRoles) {
        try {
            return resolver.resolve(userPrincipal, userRoles);
        } catch (VerifiedCallerRequiredException e) {
            throw new SidecarException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }
}
