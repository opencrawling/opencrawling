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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The legacy management endpoints that the Platform API replaces, each with its successor. They keep working, and
 * answer with {@code Deprecation} (RFC 9745) and {@code Link: <successor>; rel="successor-version"} headers
 * ({@link LegacyDeprecationFilter}); the "all" OpenAPI group marks them deprecated.
 *
 * <p>Not here, so not deprecated: the machine-to-machine endpoints with their own authentication (OIS ingest, BPM
 * events) and the public sign-in settings ({@code /api/auth/config}), which have no v1 counterpart.
 */
public final class LegacyApi {

    /** When the legacy endpoints were deprecated: the Platform API's clients (UI, SDK, CLI) had all moved. */
    public static final Instant DEPRECATED_ON = Instant.parse("2026-11-15T00:00:00Z");

    /** A legacy path prefix and the v1 resource that replaces it. */
    record Successor(String legacyPrefix, String successor) {}

    /** Most specific first: the first prefix that matches decides. */
    static final List<Successor> SUCCESSORS = List.of(
            new Successor("/api/jobs/dynamic", ApiV1.BASE + "/dynamic-jobs"),
            new Successor("/api/jobs/permissions", ApiV1.BASE + "/jobs"),
            new Successor("/api/jobs/runs", ApiV1.BASE + "/job-runs"),
            new Successor("/api/jobs", ApiV1.BASE + "/jobs"),
            new Successor("/api/connectors", ApiV1.BASE + "/connectors"),
            new Successor("/api/system/settings/transport", ApiV1.BASE + "/settings/transport"),
            new Successor("/api/system/settings", ApiV1.BASE + "/settings/system"),
            new Successor("/api/system/status", ApiV1.BASE + "/system/status"),
            new Successor("/api/system/throughput", ApiV1.BASE + "/system/throughput"),
            new Successor("/api/system/logs", ApiV1.BASE + "/system/logs"),
            new Successor("/api/v1/admin/settings/transport", ApiV1.BASE + "/settings/transport"),
            new Successor("/api/reports", ApiV1.BASE + "/executions"),
            new Successor("/api/observability/metrics", ApiV1.BASE + "/observability/metrics"),
            new Successor("/api/observability", ApiV1.BASE + "/jobs"),
            new Successor("/api/mcf-migration", ApiV1.BASE + "/migrations/manifoldcf"),
            new Successor("/api/vespa", ApiV1.BASE + "/integrations/vespa"),
            new Successor("/api/transformation/copilot", ApiV1.BASE + "/narrativization:generate-template"),
            new Successor("/api/discovery", ApiV1.BASE + "/discovery/connectors"),
            new Successor("/api/sidecars", ApiV1.BASE + "/sidecar-stores"),
            new Successor("/api/auth/me", ApiV1.BASE + "/me"));

    /** Where the deprecation is documented (RFC 9745 3: {@code Link: <...>; rel="deprecation"}). */
    public static final String DOCUMENTATION = "https://github.com/opencrawling/opencrawling/wiki/Platform-API#legacy-endpoints";

    /** Legacy paths whose own successor is known: a resource or action, not only its collection. */
    private static final List<java.util.Map.Entry<java.util.regex.Pattern, String>> EXACT = List.of(
            java.util.Map.entry(java.util.regex.Pattern.compile("/api/jobs/dynamic/documents"), ApiV1.BASE + "/dynamic-jobs:ingest-document"),
            java.util.Map.entry(java.util.regex.Pattern.compile("/api/jobs/dynamic/([^/]+)/(stop|approve|reject)"),
                    ApiV1.BASE + "/dynamic-jobs/$1:$2"),
            java.util.Map.entry(java.util.regex.Pattern.compile("/api/jobs/dynamic/([^/]+)"), ApiV1.BASE + "/dynamic-jobs/$1"),
            java.util.Map.entry(java.util.regex.Pattern.compile("/api/reports/query"), ApiV1.BASE + "/executions:query"),
            java.util.Map.entry(java.util.regex.Pattern.compile("/api/jobs/(?!runs$|permissions$|dynamic$)([^/]+)/(start|stop|pause)"),
                    ApiV1.BASE + "/jobs/$1:$2"),
            java.util.Map.entry(java.util.regex.Pattern.compile("/api/jobs/(?!runs$|permissions$|dynamic$)([^/]+)"), ApiV1.BASE + "/jobs/$1"),
            java.util.Map.entry(java.util.regex.Pattern.compile("/api/reports/[^/]+/([^/]+)(/documents|/export)?"),
                    ApiV1.BASE + "/executions/$1$2"),
            java.util.Map.entry(java.util.regex.Pattern.compile("/api/observability/diagnose/([^/]+)"), ApiV1.BASE + "/jobs/$1/diagnosis"),
            java.util.Map.entry(java.util.regex.Pattern.compile("/api/observability/(traces|errors)/([^/]+)"), ApiV1.BASE + "/jobs/$2/$1"));

    private LegacyApi() {}

    /**
     * The v1 resource that replaces a legacy path, or empty if the path is not a deprecated legacy endpoint: the exact
     * successor where there is one ({@code /api/jobs/42} is {@code /api/v1/jobs/42}), otherwise its collection.
     */
    public static Optional<String> successor(String path) {
        if (path == null) {
            return Optional.empty();
        }
        for (java.util.Map.Entry<java.util.regex.Pattern, String> exact : EXACT) {
            java.util.regex.Matcher matcher = exact.getKey().matcher(path);
            if (matcher.matches()) {
                return Optional.of(matcher.replaceFirst(exact.getValue()));
            }
        }
        for (Successor successor : SUCCESSORS) {
            String prefix = successor.legacyPrefix();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return Optional.of(successor.successor());
            }
        }
        return Optional.empty();
    }

    /** The {@code Deprecation} header's value (RFC 9745: a date, as {@code @} and Unix seconds). */
    public static String deprecationHeader() {
        return "@" + DEPRECATED_ON.getEpochSecond();
    }
}
