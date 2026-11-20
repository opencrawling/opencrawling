/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencrawling.runtime.sidecar.SidecarController;
import org.opencrawling.runtime.sidecar.SidecarProperties;
import org.opencrawling.runtime.sidecar.SidecarService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OIS sidecars written by migrations, read back with their document's metadata, as the caller is allowed to see them.
 * Enabled with {@code opencrawling.sidecars.enabled}.
 */
@RestController
@RequestMapping(ApiV1.BASE + "/sidecar-stores")
@ConditionalOnProperty(name = SidecarProperties.PREFIX + ".enabled", havingValue = "true")
@Tag(name = "Sidecars", description = "OIS sidecars written by migrations")
public class SidecarsV1Controller {

    private final SidecarController sidecars;

    public SidecarsV1Controller(SidecarController sidecars) {
        this.sidecars = sidecars;
    }

    @Operation(operationId = "listSidecarStores", summary = "Stores with sidecars", description = "The migration outputs that write sidecars.")
    @ApiResponse(responseCode = "200", description = "The stores")
    @GetMapping
    public List<SidecarService.StoreInfo> stores() {
        return sidecars.stores(null, null);
    }

    @Operation(operationId = "listSidecars", summary = "Read sidecars from a store",
            description = "Sidecars the caller may see, with aggregates; page with nextCursor.")
    @ApiResponse(responseCode = "200", description = "A page of sidecars")
    @GetMapping("/{connector}/sidecars")
    public SidecarService.SidecarPage read(@PathVariable String connector,
                                           @Parameter(description = "Key prefix, relative to the connector's") @RequestParam(required = false) String prefix,
                                           @Parameter(description = "Page size") @RequestParam(required = false) Integer limit,
                                           @Parameter(description = "The previous page's nextCursor") @RequestParam(required = false) String cursor) {
        return sidecars.read(connector, prefix, limit, cursor, null, null);
    }
}
