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

import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;

/**
 * Translates one ManifoldCF connector class's configuration into OpenCrawling's equivalent.
 * Discovered via {@link java.util.ServiceLoader} (see {@code
 * META-INF/services/org.opencrawling.migrator.mcf.mapping.ConnectorMapper}) — the same
 * extension mechanism OpenCrawling itself uses for its own connector plugins.
 *
 * <p>{@link #supports} must be an <b>exact</b> class-name match, never a substring guess (unlike
 * OpenCrawling's own runtime connector resolution, which does use substring matching) — this
 * mapper's whole purpose is a predictable, honest migration report, and a substring match could
 * silently claim support for an unrelated custom fork of a similarly-named connector.
 */
public interface ConnectorMapper {

    boolean supports(String manifoldClassName);

    /**
     * The ManifoldCF class name this mapper supports, for {@code list-mappers} display only —
     * {@link #supports} remains the authoritative check used during planning.
     */
    String manifoldClassName();

    /** {@code "repository"}, {@code "output"}, or {@code "transformation"} — the OpenCrawling connector type this mapper produces. */
    String targetType();

    ConnectorMappingResult map(McfConnection source, MigrationOptions options);
}
