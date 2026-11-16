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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.api.ConnectorController;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.v1.model.ConnectorInput;
import org.opencrawling.runtime.api.v1.model.ConnectorResource;
import org.opencrawling.runtime.service.ConnectorCheckerService.ConnectionCheckResult;
import org.springframework.beans.factory.ObjectProvider;
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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final Validator validator;

    public ConnectorsV1Controller(ConnectorController connectors, JobController jobs, JsonMapper json,
                                  ObjectProvider<Validator> validator) {
        this.connectors = connectors;
        this.jobs = jobs;
        this.json = json;
        this.validator = validator.getIfAvailable(() -> Validation.buildDefaultValidatorFactory().getValidator());
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
        List<ConnectorResource> items = connectors.connectorsWithSecrets().stream()
                .filter(c -> type == null || type.equalsIgnoreCase(c.type()))
                .filter(c -> text == null || (c.name() != null && c.name().toLowerCase(Locale.ROOT).contains(text))
                        || (c.description() != null && c.description().toLowerCase(Locale.ROOT).contains(text)))
                .map(ConnectorResource::of)
                .toList();
        items = Paging.sort(items, "name", Map.of("name", Paging.byText(ConnectorResource::name)), ConnectorResource::name);
        return Paging.page(items, limit, cursor, Objects.toString(type, "") + "|" + Objects.toString(q, ""), response,
                ConnectorResource::name);
    }

    @Operation(operationId = "getConnector", summary = "Get a connector",
            description = "The connector's definition with secret settings masked (no configuration for job creators). Supports If-None-Match; the ETag is the version to send as If-Match when replacing or deleting it.")
    @ApiResponse(responseCode = "200", description = "The connector", headers = @Header(name = "ETag", description = "Version"))
    @ApiResponse(responseCode = "304", description = "Not modified")
    @GetMapping("/connectors/{name}")
    public ResponseEntity<ConnectorResource> get(@PathVariable String name,
                                                 @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ConnectorDTO stored = find(name);
        ConnectorResource connector = ConnectorResource.of(stored);
        String etag = etag(stored);
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
        ConnectorDTO saved = new ConnectorDTO(input.name(), input.description(), input.type(), input.className(),
                input.maxConnections(), ConnectorResource.unmask(input.configuration(), Map.of()));
        // The existence check and the write under one lock: two creates of the same name cannot both succeed.
        synchronized (connectors) {
            if (findOrNull(input.name()) != null) {
                throw ApiProblem.conflict("connector-exists", "A connector named '" + input.name() + "' exists; replace it with PUT.");
            }
            JobsV1Controller.requireOk(connectors.save(saved), "Connector", input.name());
        }
        return ResponseEntity.created(ApiV1.location("connectors", saved.name()))
                .eTag(etag(saved)).body(ConnectorResource.of(saved));
    }

    @Operation(operationId = "replaceConnector", summary = "Replace a connector",
            description = "Replaces the whole definition. Masked secret values (\"" + ConnectorResource.MASK + "\") keep the stored ones. "
                    + "Supports If-Match. Admins only.")
    @ApiResponse(responseCode = "200", description = "Replaced", headers = @Header(name = "ETag", description = "New version"))
    @PutMapping("/connectors/{name}")
    public ResponseEntity<ConnectorResource> replace(@PathVariable String name, @Valid @RequestBody ConnectorInput input,
                                                     @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        ConnectorDTO saved;
        synchronized (connectors) {
            ConnectorDTO current = find(name);
            Etags.requireMatch(ifMatch, etag(current));
            if (!current.name().equals(input.name())) {
                throw ApiProblem.invalid("name", "must be '" + current.name() + "', the connector's name; renaming is not supported");
            }
            saved = new ConnectorDTO(current.name(), input.description(), input.type(), input.className(),
                    input.maxConnections(), ConnectorResource.unmask(input.configuration(), current.configuration(),
                    !java.util.Objects.equals(input.className(), current.className())));
            JobsV1Controller.requireOk(connectors.save(saved), "Connector", name);
        }
        return ResponseEntity.ok().eTag(etag(saved)).body(ConnectorResource.of(saved));
    }

    @Operation(operationId = "deleteConnector", summary = "Delete a connector",
            description = "Refused with 409 while jobs use it, unless force=true. Supports If-Match. Admins only.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @DeleteMapping("/connectors/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name,
                                       @Parameter(description = "Delete even if jobs use it") @RequestParam(defaultValue = "false") boolean force,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        // Lock order: this holds the connectors' lock and reads jobs without theirs (getAllJobs takes no lock). Never
        // call a synchronized JobController method here; JobsV1Controller holds the jobs' lock and reads connectors.
        synchronized (connectors) {
            ConnectorDTO current = find(name);
            Etags.requireMatch(ifMatch, etag(current));
            List<String> usedBy = jobs.getAllJobs().stream()
                    .filter(job -> uses(job, current.name()))
                    .map(JobController.JobDTO::id)
                    .toList();
            if (!usedBy.isEmpty() && !force) {
                throw ApiProblem.conflict("connector-in-use", "Jobs use this connector; change them first, or delete with force=true.")
                        .with("jobs", usedBy);
            }
            JobsV1Controller.requireOk(connectors.deleteConnector(current.name()), "Connector", name);
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "checkConnector", summary = "Test a configured connector",
            description = "A live connectivity and authentication check against the connector's system. Admins only.")
    @ApiResponse(responseCode = "200", description = "The check's result (success or failure, with a message)")
    @PostMapping("/connectors/{name}:check")
    public ConnectionCheckResult check(@PathVariable String name) {
        ConnectorDTO current = find(name);
        return checked(connectors.check(new ConnectorDTO(current.name(), null, null, null, null, null)));
    }

    @Operation(operationId = "checkConnectorDefinition", summary = "Test a connector definition before saving it",
            description = "The same check, for a definition that is not saved. The name may be left out; when it names a stored connector, its masked secrets are used. Admins only.")
    @ApiResponse(responseCode = "200", description = "The check's result")
    @PostMapping("/connectors:check")
    public ConnectionCheckResult checkDefinition(@RequestBody ConnectorInput input) {
        // A definition being written may have no name yet: every other rule applies.
        boolean unnamed = input.name() == null || input.name().isBlank();
        Set<ConstraintViolation<ConnectorInput>> violations = validator.validate(input);
        List<ApiProblem.FieldError> errors = violations.stream()
                .filter(v -> !(unnamed && "name".equals(v.getPropertyPath().toString())))
                .map(v -> new ApiProblem.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(ApiProblem.FieldError::field))
                .toList();
        if (!errors.isEmpty()) {
            throw ApiProblem.invalid(errors);
        }
        ConnectorDTO stored = unnamed ? null : findOrNull(input.name());
        return checked(connectors.check(new ConnectorDTO(input.name(), input.description(), input.type(),
                input.className(), input.maxConnections(),
                ConnectorResource.unmask(input.configuration(), stored == null ? Map.of() : stored.configuration(),
                        stored != null && !java.util.Objects.equals(input.className(), stored.className())))));
    }

    /**
     * The connector's version: its view as returned, plus a keyed digest of its stored secrets, so a change of a secret
     * (which the masked view hides) changes the tag too.
     */
    private String etag(ConnectorDTO stored) {
        return Etags.of(List.of(ConnectorResource.of(stored),
                ConnectorResource.secretsDigest(stored.configuration(), EtagKey.get())), json);
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
        return connectors.connectorsWithSecrets().stream()
                .filter(c -> c.name() != null && c.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }
}
