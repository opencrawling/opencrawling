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
package org.opencrawling.migrator.mcf.mapping;

import org.opencrawling.sdk.models.ConnectorRequest;

import java.util.List;

/**
 * The outcome of running one {@code McfConnection} through a {@code ConnectorMapper}. When
 * {@code supported} is false, {@code target} is null and {@code unsupportedReason} explains why
 * (always: "no registered mapper for class X"). When true and {@code overrideTargetName} is null,
 * {@code target} is ready to {@code POST /api/connectors}, and {@code notes} lists every
 * field-level fidelity compromise made along the way.
 *
 * <p>When {@code overrideTargetName} is non-null (via {@code --map-connector}), this connection was
 * never run through a {@code ConnectorMapper} at all — the user asserted an already-existing
 * OpenCrawling connector by that name should stand in for it. {@code target} stays null (nothing to
 * create) and jobs referencing this connection get {@code overrideTargetName} substituted in place
 * of the original ManifoldCF connection name.
 */
public record ConnectorMappingResult(
    boolean supported,
    ConnectorRequest target,
    List<FieldNote> notes,
    String unsupportedReason,
    String overrideTargetName
) {
    public ConnectorMappingResult {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static ConnectorMappingResult unsupported(String reason) {
        return new ConnectorMappingResult(false, null, List.of(), reason, null);
    }

    public static ConnectorMappingResult supported(ConnectorRequest target, List<FieldNote> notes) {
        return new ConnectorMappingResult(true, target, notes, null, null);
    }

    /** {@code targetConnectorName} is an OpenCrawling connector the user already created by hand. */
    public static ConnectorMappingResult overridden(String targetConnectorName) {
        return new ConnectorMappingResult(true, null, List.of(), null, targetConnectorName);
    }
}
