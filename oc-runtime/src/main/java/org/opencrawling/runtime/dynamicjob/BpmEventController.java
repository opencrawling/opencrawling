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
package org.opencrawling.runtime.dynamicjob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencrawling.runtime.security.CallerResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * Receives document-transition events from BPM engines. A request authenticates in one of three ways, checked in
 * this order:
 * <ol>
 *   <li>an {@value BpmEventAuthenticator#SIGNATURE_HEADER} header: HMAC-SHA256 of the timestamp and body with a
 *       configured source's secret, plus {@value BpmEventAuthenticator#SOURCE_HEADER} and
 *       {@value BpmEventAuthenticator#TIMESTAMP_HEADER};</li>
 *   <li>Basic auth: a configured source's name and static token;</li>
 *   <li>a bearer token with the required scope ({@code opencrawling.security.identity.mode=jwt} and
 *       {@code protect-api}).</li>
 * </ol>
 */
@RestController
@RequestMapping(BpmEventController.PATH)
@ConditionalOnProperty(name = BpmEventProperties.PREFIX + ".enabled", havingValue = "true")
@Tag(name = "BPM Events", description = "Document-transition events from Flowable or Alfresco Process Services")
public class BpmEventController {

    static final String PATH = "/api/events/bpm";
    static final int MAX_BODY_BYTES = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BpmEventService service;
    private final BpmEventAuthenticator authenticator;
    private CallerResolver callerResolver = CallerResolver.asserted();

    BpmEventController(BpmEventService service, BpmEventAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @Autowired(required = false)
    public void setCallerResolver(CallerResolver callerResolver) {
        if (callerResolver != null) {
            this.callerResolver = callerResolver;
        }
    }

    @Operation(summary = "A document reached a process transition", description = "When a configured route matches the "
            + "event's process and transition, its document is ingested again (UPSERT) or removed from the index once its "
            + "repository no longer has it (DELETE). Body: {eventId, engine, processDefinitionKey, processInstanceId, "
            + "transition (or activityId), documentId}.")
    @ApiResponse(responseCode = "202", description = "A route matched; the document was handed to ingestion")
    @ApiResponse(responseCode = "200", description = "No route matched (status IGNORED), or the event id was already handled (duplicate)")
    @ApiResponse(responseCode = "400", description = "Invalid event, or the route's connector has no such document in its scope")
    @ApiResponse(responseCode = "401", description = "Credentials not accepted")
    @ApiResponse(responseCode = "409", description = "The event is being handled, or the matching route is misconfigured")
    @PostMapping("/document-transition")
    public ResponseEntity<BpmEventService.EventResult> documentTransition(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = BpmEventAuthenticator.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(value = BpmEventAuthenticator.SOURCE_HEADER, required = false) String source,
            @RequestHeader(value = BpmEventAuthenticator.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        byte[] raw = body != null ? body : new byte[0];
        if (raw.length > MAX_BODY_BYTES) {
            throw new DynamicJobException(HttpStatus.CONTENT_TOO_LARGE, "An event may be at most " + MAX_BODY_BYTES + " bytes.");
        }
        BpmEventService.EventResult result;
        if (signature != null) {
            result = service.fromSource(authenticator.verifySignature(source, timestamp, signature, raw), parse(raw));
        } else if (isBasic(authorization)) {
            result = service.fromSource(authenticator.verifyBasic(authorization), parse(raw));
        } else {
            result = service.fromCaller(callerResolver.verifiedCaller().orElse(null), parse(raw));
        }
        boolean acted = BpmEventService.ACCEPTED.equals(result.status()) && !result.duplicate();
        return ResponseEntity.status(acted ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result);
    }

    @ExceptionHandler(DynamicJobException.class)
    ResponseEntity<Map<String, String>> failed(DynamicJobException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    static boolean isBasic(String authorization) {
        return authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
    }

    /** The event in a JSON body. {@code activityId} is read when there is no {@code transition}. */
    static BpmEventService.Event parse(byte[] body) {
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (IOException e) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "The request body must be a JSON event.");
        }
        if (node == null || !node.isObject()) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "The request body must be a JSON event.");
        }
        String transition = text(node, "transition");
        return new BpmEventService.Event(text(node, "eventId"), text(node, "engine"), text(node, "processDefinitionKey"),
                text(node, "processInstanceId"), transition != null ? transition : text(node, "activityId"),
                text(node, "documentId"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
    }
}
