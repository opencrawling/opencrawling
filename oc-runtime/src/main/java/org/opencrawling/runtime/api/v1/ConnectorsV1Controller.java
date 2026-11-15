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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.opencrawling.runtime.api.ConnectorController;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.v1.model.ConnectorInput;
import org.opencrawling.runtime.api.v1.model.ConnectorResource;
import org.opencrawling.runtime.service.ConnectorCheckerService.ConnectionCheckResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Connectors: the configured sources, outputs, transformations and authorities jobs use. Writing is for admins; job
 * creators may list them, without their configuration. Secret settings are always masked in responses.
 */
@RestController
@RequestMapping(ApiV1.BASE)
@Tag(name = "Connectors", description = "Configured repository, output, transformation and authority connectors")
public class ConnectorsV1Controller {

    private final ConnectorController connectors;
    private final JobController jobs;
    private final JsonMapper json;

    public ConnectorsV1Controller(ConnectorController connectors, JobController jobs, JsonMapper json) {
        this.connectors = connectors;
        this.jobs = jobs;
        this.json = json;
    }

    @Operation(operationId = "listConnectors", summary = "List connectors",
            description = "Configured connectors, filtered, sorted by name and paged. Job creators get them without configuration.")
    @ApiResponse(responseCode = "200", description = "A page of connectors")
    @GetMapping("/connectors")
    public Page<ConnectorResource> list(
            @Parameter(description = "Only connectors of this category: repository, output, transformation, authority") @RequestParam(required = false) String type,
            @Parameter(description = "Only connectors whose name or description contains this text") @RequestParam(required = false) String q,
            @Parameter(description = "Page size, 1 to 500 (default 50)") @RequestParam(required = false) Integer limit,
            @Parameter(description = "Cursor from page.nextCursor") @RequestParam(required = false) String cursor,
            HttpServletResponse response) {
        String text = q == null ? null : q.toLowerCase(Locale.ROOT);
        List<ConnectorResource> items = connectors.getAllConnectors().stream()
                .filter(c -> type == null || type.equalsIgnoreCase(c.type()))
                .filter(c -> text == null || (c.name() != null && c.name().toLowerCase(Locale.ROOT).contains(text))
                        || (c.description() != null && c.description().toLowerCase(Locale.ROOT).contains(text)))
                .map(ConnectorResource::of)
                .toList();
        items = Paging.sort(items, "name", Map.of("name", Paging.byText(ConnectorResource::name)));
        return Paging.page(items, limit, cursor, Objects.toString(type, "") + "|" + Objects.toString(q, ""), response);
    }

    @Operation(operationId = "getConnector", summary = "Get a connector",
            description = "The connector's definition with secret settings masked (no configuration for job creators). Supports If-None-Match; the ETag is the version to send as If-Match when replacing or deleting it.")
    @ApiResponse(responseCode = "200", description = "The connector", headers = @Header(name = "ETag", description = "Version"))
    @ApiResponse(responseCode = "304", description = "Not modified")
    @GetMapping("/connectors/{name}")
    public ResponseEntity<ConnectorResource> get(@PathVariable String name,
                                                 @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ConnectorResource connector = ConnectorResource.of(find(name));
        String etag = Etags.of(connector, json);
        if (Etags.notModified(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(connector);
    }

    @Operation(operationId = "createConnector", summary = "Create a connector", description = "409 if the name is taken. Admins only.")
    @ApiResponse(responseCode = "201", description = "Created", headers = @Header(name = "Location", description = "URL of the connector"))
    @PostMapping("/connectors")
    public ResponseEntity<ConnectorResource> create(@Valid @RequestBody ConnectorInput input,
                                                    @Parameter(description = "Makes retries of this request safe") @RequestHeader(value = ApiV1.IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        if (findOrNull(input.name()) != null) {
            throw ApiProblem.conflict("connector-exists", "A connector named '" + input.name() + "' exists; replace it with PUT.");
        }
        ConnectorDTO saved = new ConnectorDTO(input.name(), input.description(), input.type(), input.className(),
                input.maxConnections(), ConnectorResource.unmask(input.configuration(), Map.of()));
        JobsV1Controller.requireOk(connectors.createConnector(saved), "Connector", input.name());
        ConnectorResource body = ConnectorResource.of(saved);
        return ResponseEntity.created(ApiV1.location("connectors", saved.name()))
                .eTag(Etags.of(body, json)).body(body);
    }

    @Operation(operationId = "replaceConnector", summary = "Replace a connector",
            description = "Replaces the whole definition. Masked secret values (\"" + ConnectorResource.MASK + "\") keep the stored ones. "
                    + "Supports If-Match. Admins only.")
    @ApiResponse(responseCode = "200", description = "Replaced", headers = @Header(name = "ETag", description = "New version"))
    @PutMapping("/connectors/{name}")
    public ResponseEntity<ConnectorResource> replace(@PathVariable String name, @Valid @RequestBody ConnectorInput input,
                                                     @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        ConnectorDTO current = find(name);
        Etags.requireMatch(ifMatch, Etags.of(ConnectorResource.of(current), json));
        if (!current.name().equals(input.name())) {
            throw ApiProblem.invalid("name", "must be '" + current.name() + "', the connector's name; renaming is not supported");
        }
        ConnectorDTO saved = new ConnectorDTO(current.name(), input.description(), input.type(), input.className(),
                input.maxConnections(), ConnectorResource.unmask(input.configuration(), current.configuration()));
        JobsV1Controller.requireOk(connectors.createConnector(saved), "Connector", name);
        ConnectorResource body = ConnectorResource.of(saved);
        return ResponseEntity.ok().eTag(Etags.of(body, json)).body(body);
    }

    @Operation(operationId = "deleteConnector", summary = "Delete a connector",
            description = "Refused with 409 while jobs use it, unless force=true. Supports If-Match. Admins only.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @DeleteMapping("/connectors/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name,
                                       @Parameter(description = "Delete even if jobs use it") @RequestParam(defaultValue = "false") boolean force,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        ConnectorDTO current = find(name);
        Etags.requireMatch(ifMatch, Etags.of(ConnectorResource.of(current), json));
        List<String> usedBy = jobs.getAllJobs().stream()
                .filter(job -> uses(job, current.name()))
                .map(JobController.JobDTO::id)
                .toList();
        if (!usedBy.isEmpty() && !force) {
            throw ApiProblem.conflict("connector-in-use", "Jobs use this connector; change them first, or delete with force=true.")
                    .with("jobs", usedBy);
        }
        JobsV1Controller.requireOk(connectors.deleteConnector(current.name()), "Connector", name);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "checkConnector", summary = "Test a configured connector",
            description = "A live connectivity and authentication check against the connector's system. Admins only.")
    @ApiResponse(responseCode = "200", description = "The check's result (success or failure, with a message)")
    @PostMapping("/connectors/{name}:check")
    public ConnectionCheckResult check(@PathVariable String name) {
        ConnectorDTO current = find(name);
        return checked(connectors.checkConnection(new ConnectorDTO(current.name(), null, null, null, null, null)));
    }

    @Operation(operationId = "checkConnectorDefinition", summary = "Test a connector definition before saving it",
            description = "The same check, for a definition that is not saved. Admins only.")
    @ApiResponse(responseCode = "200", description = "The check's result")
    @PostMapping("/connectors:check")
    public ConnectionCheckResult checkDefinition(@Valid @RequestBody ConnectorInput input) {
        ConnectorDTO stored = findOrNull(input.name());
        return checked(connectors.checkConnection(new ConnectorDTO(input.name(), input.description(), input.type(),
                input.className(), input.maxConnections(),
                ConnectorResource.unmask(input.configuration(), stored == null ? Map.of() : stored.configuration()))));
    }

    private static ConnectionCheckResult checked(ResponseEntity<ConnectionCheckResult> result) {
        JobsV1Controller.requireOk(result, "Connector", "");
        return result.getBody();
    }

    private static boolean uses(JobController.JobDTO job, String connector) {
        return connector.equalsIgnoreCase(job.repositoryConnector()) || connector.equalsIgnoreCase(job.outputConnector())
                || connector.equalsIgnoreCase(job.transformationConnector()) || connector.equalsIgnoreCase(job.authorityConnector());
    }

    private ConnectorDTO find(String name) {
        ConnectorDTO connector = findOrNull(name);
        if (connector == null) {
            throw ApiProblem.notFound("Connector", name);
        }
        return connector;
    }

    private ConnectorDTO findOrNull(String name) {
        return connectors.getAllConnectors().stream()
                .filter(c -> c.name() != null && c.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }
}
