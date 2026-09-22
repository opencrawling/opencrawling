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

/**
 * The parsed shape of a stock ManifoldCF FileConnector's document specification: one or more
 * {@code <startpoint path="...">} scan roots, each with its own include/exclude filters.
 * OpenCrawling's {@code FileSystemRepositoryConnector} has no filtering at all and a job carries
 * exactly one scan root, so {@code JobMapper} uses only the first startpoint's path and reports
 * everything else (extra roots, all filters) as dropped.
 */
public record McfFileSystemSpec(List<StartPoint> startPoints) {

    public McfFileSystemSpec {
        startPoints = startPoints == null ? List.of() : List.copyOf(startPoints);
    }

    public record StartPoint(String path, List<IncludeExcludeFilter> filters) {
        public StartPoint {
            filters = filters == null ? List.of() : List.copyOf(filters);
        }
    }

    public record IncludeExcludeFilter(boolean included, String matchPattern, String type) {
    }
}
