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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Document-transition events from BPM engines (Flowable, Alfresco Process Services) ({@code opencrawling.events.bpm.*}).
 *
 * <p>Off by default. An event is acted on only when a configured {@link Route} matches its process and transition;
 * the route, not the event, names the connector and the action. A caller authenticates with a bearer token carrying
 * {@link #getRequiredScope() the required scope}, or as one of the configured {@link Source sources}, by HMAC
 * signature or by its static token over Basic auth.
 */
@ConfigurationProperties(prefix = BpmEventProperties.PREFIX)
public class BpmEventProperties {

    public static final String PREFIX = "opencrawling.events.bpm";

    /** Secrets shorter than this are refused at startup. */
    static final int MIN_SECRET_LENGTH = 32;

    /** Whether the event endpoint exists. */
    private boolean enabled = false;
    /** The OAuth2 scope a bearer token must carry. Blank: tokens are not accepted. */
    private String requiredScope = "oc:events:write";
    /** How far an HMAC-signed request's timestamp may be from this runtime's clock. */
    private Duration maxClockSkew = Duration.ofMinutes(5);
    /** How long a handled event id is remembered, so that a repeat is not acted on twice. */
    private Duration dedupRetention = Duration.ofHours(24);
    /** The most event ids remembered; the oldest go first. */
    private int maxRememberedEvents = 10_000;
    /** How many jobs one caller's events may have running at once (0: no limit of its own). */
    private int maxRunning = 10;
    /** Callers that authenticate with a shared secret instead of a token. */
    private List<Source> sources = new ArrayList<>();
    /** Which events are acted on, and how. The first matching route wins. */
    private List<Route> routes = new ArrayList<>();

    /** A caller authenticated by a shared secret: an HMAC signing key, a static token, or both. */
    public static class Source {
        /** The source's name: the {@code X-OpenCrawling-Source} header, or the Basic auth username. */
        private String name;
        /** Key for {@code X-OpenCrawling-Signature} (HMAC-SHA256 over timestamp, '.', body). Empty: not accepted. */
        private String hmacSecret;
        /** Static token, sent as the Basic auth password. Empty: not accepted. */
        private String token;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getHmacSecret() {
            return hmacSecret;
        }

        public void setHmacSecret(String hmacSecret) {
            this.hmacSecret = hmacSecret;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    /** Maps a process transition to a registered connector and an action. */
    public static class Route {
        /** A label, shown in answers and logs. */
        private String name;
        /** Only events from this engine (as the event names it); empty: any. */
        private String engine;
        /** The process definition key; {@code *} for any. */
        private String process;
        /** The transition (activity id or state); {@code *} for any. */
        private String transition;
        /** The registered repository connector the event's document id belongs to. */
        private String connector;
        /** {@code UPSERT} (default) or {@code DELETE}. */
        private DynamicJobService.DocumentRequest.Action action = DynamicJobService.DocumentRequest.Action.UPSERT;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEngine() {
            return engine;
        }

        public void setEngine(String engine) {
            this.engine = engine;
        }

        public String getProcess() {
            return process;
        }

        public void setProcess(String process) {
            this.process = process;
        }

        public String getTransition() {
            return transition;
        }

        public void setTransition(String transition) {
            this.transition = transition;
        }

        public String getConnector() {
            return connector;
        }

        public void setConnector(String connector) {
            this.connector = connector;
        }

        public DynamicJobService.DocumentRequest.Action getAction() {
            return action;
        }

        public void setAction(DynamicJobService.DocumentRequest.Action action) {
            this.action = action;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public void setRequiredScope(String requiredScope) {
        this.requiredScope = requiredScope;
    }

    public Duration getMaxClockSkew() {
        return maxClockSkew;
    }

    public void setMaxClockSkew(Duration maxClockSkew) {
        this.maxClockSkew = maxClockSkew;
    }

    public Duration getDedupRetention() {
        return dedupRetention;
    }

    public void setDedupRetention(Duration dedupRetention) {
        this.dedupRetention = dedupRetention;
    }

    public int getMaxRememberedEvents() {
        return maxRememberedEvents;
    }

    public void setMaxRememberedEvents(int maxRememberedEvents) {
        this.maxRememberedEvents = maxRememberedEvents;
    }

    public int getMaxRunning() {
        return maxRunning;
    }

    public void setMaxRunning(int maxRunning) {
        this.maxRunning = maxRunning;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources == null ? new ArrayList<>() : sources;
    }

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes == null ? new ArrayList<>() : routes;
    }

    /**
     * Checks the settings at startup.
     *
     * @throws IllegalStateException when a source or route is incomplete, or a secret is too short
     */
    void validate() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Source source : sources) {
            if (source.getName() == null || source.getName().isBlank() || source.getName().contains(":")) {
                throw new IllegalStateException(PREFIX + ".sources: every source needs a name without ':'.");
            }
            if (!names.add(source.getName().strip())) {
                throw new IllegalStateException(PREFIX + ".sources: the name '" + source.getName() + "' is used twice.");
            }
            boolean hmac = source.getHmacSecret() != null && !source.getHmacSecret().isEmpty();
            boolean token = source.getToken() != null && !source.getToken().isEmpty();
            if (!hmac && !token) {
                throw new IllegalStateException(PREFIX + ".sources: source '" + source.getName() + "' needs an hmac-secret or a token.");
            }
            if ((hmac && source.getHmacSecret().length() < MIN_SECRET_LENGTH)
                    || (token && source.getToken().length() < MIN_SECRET_LENGTH)) {
                throw new IllegalStateException(PREFIX + ".sources: the secrets of source '" + source.getName()
                        + "' must be at least " + MIN_SECRET_LENGTH + " characters.");
            }
        }
        for (Route route : routes) {
            if (blank(route.getProcess()) || blank(route.getTransition()) || blank(route.getConnector())
                    || route.getAction() == null) {
                throw new IllegalStateException(PREFIX + ".routes: every route needs a process, a transition and a connector"
                        + (blank(route.getName()) ? "." : " (route '" + route.getName() + "')."));
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
