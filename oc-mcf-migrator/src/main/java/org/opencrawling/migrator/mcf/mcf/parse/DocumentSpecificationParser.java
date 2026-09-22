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
package org.opencrawling.migrator.mcf.mcf.parse;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.migrator.mcf.mcf.model.McfFileSystemSpec;
import org.opencrawling.migrator.mcf.mcf.model.McfFileSystemSpec.IncludeExcludeFilter;
import org.opencrawling.migrator.mcf.mcf.model.McfFileSystemSpec.StartPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses a ManifoldCF job's {@code document_specification} — but only the stock FileConnector's
 * shape: {@code <startpoint path="..." converttouri="...">} with nested {@code <include
 * match="..." type="file|directory"/>} / {@code <exclude .../>} children. Every other repository
 * connector defines its own, unrelated specification vocabulary (e.g. the shared-drive connector's
 * {@code <security>}/{@code <access>} nodes) — since this tool has no mapper for any of them yet,
 * there is nothing meaningful to parse there, so this class returns {@link Optional#empty()}
 * rather than guessing.
 */
public final class DocumentSpecificationParser {

    private static final String STARTPOINT = "startpoint";
    private static final String INCLUDE = "include";
    private static final String EXCLUDE = "exclude";

    private static final String FILE_CONNECTOR_CLASS =
        "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector";

    private DocumentSpecificationParser() {
    }

    public static Optional<McfFileSystemSpec> parse(JsonNode documentSpecification, String repositoryClassName) {
        if (!FILE_CONNECTOR_CLASS.equals(repositoryClassName) || documentSpecification == null) {
            return Optional.empty();
        }

        List<StartPoint> startPoints = new ArrayList<>();
        for (JsonNode startpointNode : McfJsonNodes.childrenOfType(documentSpecification, STARTPOINT)) {
            String path = McfJsonNodes.attribute(startpointNode, "path").orElse(null);
            if (path == null) {
                continue;
            }
            List<IncludeExcludeFilter> filters = new ArrayList<>();
            addFilters(startpointNode, INCLUDE, true, filters);
            addFilters(startpointNode, EXCLUDE, false, filters);
            startPoints.add(new StartPoint(path, filters));
        }

        return startPoints.isEmpty() ? Optional.empty() : Optional.of(new McfFileSystemSpec(startPoints));
    }

    private static void addFilters(JsonNode startpointNode, String type, boolean included, List<IncludeExcludeFilter> out) {
        for (JsonNode filterNode : McfJsonNodes.childrenOfType(startpointNode, type)) {
            String match = McfJsonNodes.attribute(filterNode, "match").orElse(null);
            String filterType = McfJsonNodes.attribute(filterNode, "type").orElse(null);
            if (match != null) {
                out.add(new IncludeExcludeFilter(included, match, filterType));
            }
        }
    }
}
