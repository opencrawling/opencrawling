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
package org.opencrawling.migrator.mcf.mcf.model;

import java.util.List;
import java.util.Map;

/**
 * A ManifoldCF repository/output/transformation/authority/mapping/notification connection,
 * as extracted from the {@code /json/<kind>connections} REST endpoints. {@code configuration}
 * is the flattened {@code _PARAMETER_ name="x"} node list ManifoldCF's {@code ConfigParams}
 * exposes as a plain string map — the same data a {@code ConnectorMapper} translates.
 *
 * <p>{@code aclAuthority} and {@code throttleMatches} only ever appear on {@code REPOSITORY}
 * connections (ManifoldCF's own data model has no such fields on output/transformation/authority
 * connections) and have no OpenCrawling target at all — {@code ConnectorRequest} carries neither
 * concept — so a repository mapper should note them as dropped when non-empty.
 */
public record McfConnection(
    McfConnectionKind kind,
    String name,
    String description,
    String className,
    int maxConnections,
    Map<String, String> configuration,
    String aclAuthority,
    List<String> throttleMatches
) {
    public McfConnection {
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        throttleMatches = throttleMatches == null ? List.of() : List.copyOf(throttleMatches);
    }

    /**
     * Cross-kind-safe lookup key for maps keyed by connection identity. ManifoldCF only guarantees
     * name-uniqueness within one kind's own registry — a repository connection and an output
     * connection can legally share a name — so a map keyed by {@link #name()} alone risks one
     * kind's entry silently overwriting another's.
     */
    public String lookupKey() {
        return key(kind, name);
    }

    public static String key(McfConnectionKind kind, String name) {
        return kind + ":" + name;
    }
}
