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

import org.opencrawling.core.connector.discovery.VisibilityFilter;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.security.JwtCallerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Acts on document-transition events from BPM engines: when a configured route matches an event's process and
 * transition, the event's document is ingested again (UPSERT) or removed from the index once its repository no
 * longer has it (DELETE), through the same path as a caller's single-document request.
 *
 * <p>What may be ingested is decided by the routes an administrator configured, not by the sender: the route names
 * the connector and the action, and the connector still holds the document to its scope. The sender's own access
 * to the document is not checked (an engine's service account rarely appears in document ACLs); the document keeps
 * its source permissions in the index either way.
 *
 * <p>Each event id is acted on once per sender: a repeat within the retention period gets the first answer, marked
 * {@code duplicate}. Only answers that were reached are remembered, so a sender may retry after an error.
 */
public class BpmEventService {

    private static final Logger log = LoggerFactory.getLogger(BpmEventService.class);

    static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    static final int MAX_FIELD_LENGTH = 200;

    /**
     * A document-transition event.
     *
     * @param eventId              the sender's unique id for the event, for deduplication
     * @param engine               the engine that sent it ({@code flowable}, {@code aps}, ...); optional
     * @param processDefinitionKey the process definition key
     * @param processInstanceId    the process instance; optional, logged
     * @param transition           the transition, activity id or state reached
     * @param documentId           the document's id in the route's repository
     */
    public record Event(String eventId, String engine, String processDefinitionKey, String processInstanceId,
                        String transition, String documentId) {}

    /**
     * The answer to an event.
     *
     * @param status    {@code ACCEPTED} when a route matched and the document was handed to ingestion, {@code IGNORED}
     *                  when no route matched
     * @param duplicate {@code true} when the event id was already handled; the rest is the first answer
     */
    public record EventResult(String status, String eventId, String route, String connector, String document,
                              String jobId, String executionId, String note, boolean duplicate) {

        EventResult asDuplicate() {
            return new EventResult(status, eventId, route, connector, document, jobId, executionId, note, true);
        }
    }

    static final String ACCEPTED = "ACCEPTED";
    static final String IGNORED = "IGNORED";
    static final String NO_ROUTE = "No route matches this process and transition.";

    private final DynamicJobService jobs;
    private final BpmEventProperties properties;
    private final Clock clock;

    /** Guarded by {@code this}: sender and event id to the answer, or {@code null} while it is being handled. */
    private final Map<String, Remembered> handled = new LinkedHashMap<>();

    private record Remembered(EventResult result, Instant at) {}

    public BpmEventService(DynamicJobService jobs, BpmEventProperties properties, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Handles an event from a configured source, already authenticated by its signature or token. */
    public EventResult fromSource(String sourceName, Event event) {
        String principal = "bpm:" + sourceName;
        Event checked = checked(event);
        DynamicJobService.Requester requester = new DynamicJobService.Requester("bpm/source/" + sourceName, principal,
                ExecutionProvenance.verified(principal, null, List.of(), null, ExecutionProvenance.SOURCE_BPM, checked.eventId()));
        return handle(requester, checked);
    }

    /**
     * Handles an event from a caller verified by bearer token.
     *
     * @throws DynamicJobException {@code 401} without a verified caller or when tokens are not accepted, {@code 403}
     *                             when the token names no principal or lacks the required scope
     */
    public EventResult fromCaller(Caller caller, Event event) {
        String scope = properties.getRequiredScope();
        if (caller == null || !caller.authenticated() || scope == null || scope.isBlank()) {
            throw new DynamicJobException(HttpStatus.UNAUTHORIZED, "BPM events need a bearer token with the scope '"
                    + scope + "', or a configured source's signature or token.");
        }
        if (caller.principal() == null || caller.principal().isBlank()) {
            throw new DynamicJobException(HttpStatus.FORBIDDEN, "The token names no principal.");
        }
        if (!JwtCallerMapper.scopes(caller).contains(scope.strip())) {
            throw new DynamicJobException(HttpStatus.FORBIDDEN, "The token does not carry the scope '" + scope.strip() + "'.");
        }
        Event checked = checked(event);
        String owner = "bpm/token/" + (caller.tenant() == null ? "" : caller.tenant()) + "/" + caller.principal();
        DynamicJobService.Requester requester = new DynamicJobService.Requester(owner, caller.principal(),
                ExecutionProvenance.verified(caller.principal(),
                        caller.attributes().get(JwtCallerMapper.ACTOR) instanceof String actor ? actor : null,
                        caller.groups(), caller.tenant(), ExecutionProvenance.SOURCE_BPM, checked.eventId()));
        return handle(requester, checked);
    }

    private EventResult handle(DynamicJobService.Requester requester, Event event) {
        String key = requester.owner() + "\n" + event.eventId();
        synchronized (this) {
            evict();
            Remembered remembered = handled.get(key);
            if (remembered != null) {
                if (remembered.result() == null) {
                    throw new DynamicJobException(HttpStatus.CONFLICT, "Event '" + event.eventId() + "' is being handled.");
                }
                return remembered.result().asDuplicate();
            }
            handled.put(key, new Remembered(null, clock.instant()));
        }
        EventResult result = null;
        try {
            result = act(requester, event);
            return result;
        } finally {
            synchronized (this) {
                if (result != null) {
                    handled.put(key, new Remembered(result, clock.instant()));
                } else {
                    handled.remove(key);
                }
            }
        }
    }

    private EventResult act(DynamicJobService.Requester requester, Event event) {
        BpmEventProperties.Route route = route(event);
        if (route == null) {
            log.info("BPM event {} from '{}' ({} / {}, instance {}): no route, ignored.", event.eventId(),
                    requester.principal(), event.processDefinitionKey(), event.transition(), event.processInstanceId());
            return new EventResult(IGNORED, event.eventId(), null, null, event.documentId(), null, null, NO_ROUTE, false);
        }
        String routeName = route.getName() != null && !route.getName().isBlank() ? route.getName().strip()
                : route.getProcess() + "/" + route.getTransition();
        ConnectorDTO registration;
        try {
            registration = jobs.registration(route.getConnector().strip(), false);
        } catch (DynamicJobException e) {
            log.warn("BPM route '{}' names connector '{}', which is not a registered connector that can fetch documents: {}",
                    routeName, route.getConnector(), e.getMessage());
            throw new DynamicJobException(HttpStatus.CONFLICT, "Route '" + routeName + "' is not configured correctly.");
        }
        DynamicJobService.DocumentRequest request = DynamicJobService.checked(
                new DynamicJobService.DocumentRequest(registration.name(), event.documentId(), route.getAction()));
        log.info("BPM event {} from '{}' ({} / {}, instance {}): route '{}', {} of '{}' on '{}'.", event.eventId(),
                requester.principal(), event.processDefinitionKey(), event.transition(), event.processInstanceId(),
                routeName, request.action(), request.document(), registration.name());
        Object outcome = jobs.document(requester, registration, request, null, VisibilityFilter.ALL, properties.getMaxRunning(),
                null, null);
        if (outcome instanceof DynamicJobService.Accepted accepted) {
            return new EventResult(ACCEPTED, event.eventId(), routeName, registration.name(), request.document(),
                    accepted.jobId(), accepted.executionId(), null, false);
        }
        DynamicJobService.DeletionAccepted deletion = (DynamicJobService.DeletionAccepted) outcome;
        return new EventResult(ACCEPTED, event.eventId(), routeName, registration.name(), request.document(), null,
                null, deletion.note(), false);
    }

    /** The first route matching the event's engine, process and transition, or {@code null}. */
    private BpmEventProperties.Route route(Event event) {
        for (BpmEventProperties.Route route : properties.getRoutes()) {
            boolean engine = route.getEngine() == null || route.getEngine().isBlank()
                    || route.getEngine().strip().equalsIgnoreCase(event.engine());
            if (engine && matches(route.getProcess(), event.processDefinitionKey())
                    && matches(route.getTransition(), event.transition())) {
                return route;
            }
        }
        return null;
    }

    private static boolean matches(String pattern, String value) {
        return "*".equals(pattern.strip()) || pattern.strip().equals(value);
    }

    private static Event checked(Event event) {
        if (event == null) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "The request body must be a JSON event.");
        }
        if (event.eventId() == null || !EVENT_ID.matcher(event.eventId().strip()).matches()) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST,
                    "eventId is required: 1 to 200 letters, digits, '.', '_', ':' or '-'.");
        }
        String process = required("processDefinitionKey", event.processDefinitionKey());
        String transition = required("transition", event.transition());
        if (event.documentId() == null || event.documentId().isBlank()) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, "documentId is required.");
        }
        return new Event(event.eventId().strip(), optional("engine", event.engine()), process,
                optional("processInstanceId", event.processInstanceId()), transition, event.documentId().strip());
    }

    private static String required(String field, String value) {
        String checked = optional(field, value);
        if (checked == null) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST, field + " is required.");
        }
        return checked;
    }

    private static String optional(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > MAX_FIELD_LENGTH || stripped.chars().anyMatch(Character::isISOControl)) {
            throw new DynamicJobException(HttpStatus.BAD_REQUEST,
                    field + " may be at most " + MAX_FIELD_LENGTH + " characters, with no control characters.");
        }
        return stripped;
    }

    /** Forgets answers past the retention, then the oldest beyond the cap. Never one still being handled. Holds the lock. */
    private void evict() {
        Instant expired = clock.instant().minus(properties.getDedupRetention());
        handled.values().removeIf(entry -> entry.result() != null && entry.at().isBefore(expired));
        int excess = handled.size() - Math.max(1, properties.getMaxRememberedEvents());
        Iterator<Remembered> oldestFirst = handled.values().iterator();
        while (excess > 0 && oldestFirst.hasNext()) {
            if (oldestFirst.next().result() != null) {
                oldestFirst.remove();
                excess--;
            }
        }
    }
}
