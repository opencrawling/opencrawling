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
package org.opencrawling.migrator.mcf.mcf.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.mcf.parse.McfEntityParser;
import org.opencrawling.migrator.mcf.mcf.parse.McfXmlToJsonAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a ManifoldCF configuration snapshot from one native XML export file (as produced by
 * ManifoldCF's own {@code ExportConfiguration} tool) instead of a live REST API or a directory of
 * saved JSON responses — the file-to-file source opencrawling/opencrawling#96's {@code convert}
 * command is built around. Parses the whole file once via {@link McfXmlToJsonAdapter}, converting
 * it into the same JSON shape {@link McfEntityParser} already knows how to read, so every
 * connection-kind/job-parsing rule is shared unchanged with the live-API and JSON-snapshot paths.
 *
 * <p>See {@link McfXmlToJsonAdapter}'s javadoc for the important caveat: the XML↔JSON structural
 * parity this relies on has not been independently verified against a real ManifoldCF export file.
 */
public class ManifoldCFXmlExportSource implements ManifoldCFSource {

    private static final Logger log = LoggerFactory.getLogger(ManifoldCFXmlExportSource.class);

    private final Path file;
    private final Map<String, List<JsonNode>> itemsByTag;

    public ManifoldCFXmlExportSource(Path file) {
        this.file = file;
        try (InputStream in = Files.newInputStream(file)) {
            this.itemsByTag = McfXmlToJsonAdapter.parseExport(in);
        } catch (IOException e) {
            throw new ManifoldCFApiException("Failed to read/parse ManifoldCF XML export " + file, e);
        }
    }

    @Override
    public List<McfConnection> listRepositoryConnections() {
        return listConnections("repositoryconnection", McfConnectionKind.REPOSITORY);
    }

    @Override
    public List<McfConnection> listOutputConnections() {
        return listConnections("outputconnection", McfConnectionKind.OUTPUT);
    }

    @Override
    public List<McfConnection> listTransformationConnections() {
        return listConnections("transformationconnection", McfConnectionKind.TRANSFORMATION);
    }

    @Override
    public List<McfConnection> listAuthorityConnections() {
        return listConnections("authorityconnection", McfConnectionKind.AUTHORITY);
    }

    @Override
    public List<McfJob> listJobs() {
        List<McfJob> jobs = new ArrayList<>();
        for (JsonNode jobNode : itemsByTag.getOrDefault("job", List.of())) {
            jobs.add(McfEntityParser.parseJob(jobNode));
        }
        log.info("Read {} job(s) from XML export {}", jobs.size(), file);
        return jobs;
    }

    private List<McfConnection> listConnections(String tagName, McfConnectionKind kind) {
        List<McfConnection> connections = new ArrayList<>();
        for (JsonNode node : itemsByTag.getOrDefault(tagName, List.of())) {
            connections.add(McfEntityParser.parseConnection(node, kind));
        }
        log.info("Read {} {} connection(s) from XML export {}", connections.size(), kind, file);
        return connections;
    }
}
