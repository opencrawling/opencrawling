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
package org.opencrawling.migrator.mcf.mapping.filesystem;

import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapper;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mapping.FieldNote;
import org.opencrawling.migrator.mcf.mapping.FieldNoteKind;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.sdk.models.ConnectorRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps the stock ManifoldCF {@code FileConnector} to OpenCrawling's {@code
 * FileSystemRepositoryConnector}. The ManifoldCF connection itself carries no configuration
 * (the scan root lives on the job's document specification, handled separately by {@code
 * JobMapper}/{@code DocumentSpecificationParser}) — so this is a near-identity mapping; the only
 * loss is repository-level ACL authority/throttle settings, which OpenCrawling's connector model
 * has no field for at all.
 */
public class FileConnectorMapper implements ConnectorMapper {

    public static final String MANIFOLDCF_CLASS =
        "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector";

    private static final String TARGET_CLASS = "org.opencrawling.filesystem.FileSystemRepositoryConnector";

    @Override
    public boolean supports(String manifoldClassName) {
        return MANIFOLDCF_CLASS.equals(manifoldClassName);
    }

    @Override
    public String manifoldClassName() {
        return MANIFOLDCF_CLASS;
    }

    @Override
    public String targetType() {
        return "repository";
    }

    @Override
    public ConnectorMappingResult map(McfConnection source, MigrationOptions options) {
        List<FieldNote> notes = new ArrayList<>();
        if (!source.configuration().isEmpty()) {
            notes.add(new FieldNote("configuration", FieldNoteKind.DROPPED,
                "ManifoldCF FileConnector connection had " + source.configuration().size()
                    + " configuration parameter(s); OpenCrawling's FileSystemRepositoryConnector takes no "
                    + "connection-level configuration at all (the scan root comes from the job, not the connection)"));
        }
        if (source.aclAuthority() != null && !source.aclAuthority().isBlank()) {
            notes.add(new FieldNote("aclAuthority", FieldNoteKind.DROPPED,
                "ACL authority '" + source.aclAuthority() + "' has no OpenCrawling equivalent — ConnectorRequest "
                    + "carries no authority-binding concept"));
        }
        if (!source.throttleMatches().isEmpty()) {
            notes.add(new FieldNote("throttles", FieldNoteKind.DROPPED,
                source.throttleMatches().size() + " throttle rule(s) have no OpenCrawling equivalent"));
        }

        ConnectorRequest target = ConnectorRequest.builder()
            .name(source.name())
            .description(source.description() != null ? source.description() : source.name())
            .type(targetType())
            .className(TARGET_CLASS)
            .maxConnections(source.maxConnections())
            .configuration(Map.of())
            .build();

        return ConnectorMappingResult.supported(target, notes);
    }
}
