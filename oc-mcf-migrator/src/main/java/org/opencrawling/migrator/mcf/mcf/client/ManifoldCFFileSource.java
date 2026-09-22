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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.mcf.parse.McfEntityParser;
import org.opencrawling.migrator.mcf.mcf.parse.McfJsonNodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a ManifoldCF configuration snapshot from a directory of saved JSON files instead of a live
 * REST API — the exact same JSON shape {@link ManifoldCFClient} would have received from {@code
 * GET /json/<endpoint>} (see {@code McfJsonNodes} for the encoding), just pre-fetched to disk. This
 * is the "file-based" extraction path opencrawling/opencrawling#96 asks for, without needing a
 * parser for ManifoldCF's separate, proprietary {@code ExportConfiguration} binary zip format —
 * anyone who can reach the live API once can save its five responses and replay them offline
 * (for a walled-off environment, an audit trail, or repeatable test fixtures).
 *
 * <p>Expected files in the directory, each accepted with or without a {@code .json} suffix:
 * {@code repositoryconnections}, {@code outputconnections}, {@code transformationconnections},
 * {@code authorityconnections}, {@code jobs}. A missing file is treated as an empty response
 * (logged, not an error) rather than failing the whole extraction — a partial snapshot is still
 * useful to plan against.
 */
public class ManifoldCFFileSource implements ManifoldCFSource {

    private static final Logger log = LoggerFactory.getLogger(ManifoldCFFileSource.class);

    private final Path directory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ManifoldCFFileSource(Path directory) {
        this.directory = directory;
    }

    @Override
    public List<McfConnection> listRepositoryConnections() {
        return listConnections("repositoryconnections", "repositoryconnection", McfConnectionKind.REPOSITORY);
    }

    @Override
    public List<McfConnection> listOutputConnections() {
        return listConnections("outputconnections", "outputconnection", McfConnectionKind.OUTPUT);
    }

    @Override
    public List<McfConnection> listTransformationConnections() {
        return listConnections("transformationconnections", "transformationconnection", McfConnectionKind.TRANSFORMATION);
    }

    @Override
    public List<McfConnection> listAuthorityConnections() {
        return listConnections("authorityconnections", "authorityconnection", McfConnectionKind.AUTHORITY);
    }

    @Override
    public List<McfJob> listJobs() {
        JsonNode root = readJson("jobs");
        List<McfJob> jobs = new ArrayList<>();
        if (root != null) {
            for (JsonNode jobNode : McfJsonNodes.childrenOfType(root, "job")) {
                jobs.add(McfEntityParser.parseJob(jobNode));
            }
        }
        log.info("Read {} job(s) from {}", jobs.size(), directory);
        return jobs;
    }

    private List<McfConnection> listConnections(String fileBaseName, String nodeType, McfConnectionKind kind) {
        JsonNode root = readJson(fileBaseName);
        List<McfConnection> connections = new ArrayList<>();
        if (root != null) {
            for (JsonNode connectionNode : McfJsonNodes.childrenOfType(root, nodeType)) {
                connections.add(McfEntityParser.parseConnection(connectionNode, kind));
            }
        }
        log.info("Read {} {} connection(s) from {}", connections.size(), kind, directory);
        return connections;
    }

    private JsonNode readJson(String fileBaseName) {
        Path file = resolveFile(fileBaseName);
        if (file == null) {
            log.warn("No '{}' or '{}.json' file found in {}; treating as empty", fileBaseName, fileBaseName, directory);
            return null;
        }

        String body;
        try {
            body = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ManifoldCFApiException("Failed to read " + file, e);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new ManifoldCFApiException("Malformed JSON in " + file, e);
        }

        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            throw new ManifoldCFApiException("ManifoldCF error recorded in " + file + ": " + error.asText());
        }
        return root;
    }

    private Path resolveFile(String fileBaseName) {
        Path withExtension = directory.resolve(fileBaseName + ".json");
        if (Files.isRegularFile(withExtension)) {
            return withExtension;
        }
        Path withoutExtension = directory.resolve(fileBaseName);
        if (Files.isRegularFile(withoutExtension)) {
            return withoutExtension;
        }
        return null;
    }
}
