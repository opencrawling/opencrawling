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
package org.opencrawling.migrator.mcf.cli;

import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFClient;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFFileSource;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFSource;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFXmlExportSource;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ManifoldCF-source options every subcommand (`convert`/`import`/`audit`) needs identically —
 * shared via picocli {@code @Mixin} rather than repeated on each command, and via this class'
 * instance methods rather than static helpers threading every field through as parameters.
 */
class SourceOptions {

    static final String DEFAULT_MCF_URL = "http://localhost:8345/mcf-api-service/json";

    @Option(names = "--mcf-url", description = "ManifoldCF REST API base URL (env MANIFOLDCF_URL, default: " + DEFAULT_MCF_URL + "). Ignored if --mcf-input-dir is set.")
    String mcfUrl;

    @Option(names = "--mcf-input-dir", description = "Read a previously-saved ManifoldCF JSON snapshot from this directory instead of a live API — "
        + "expects files named repositoryconnections[.json], outputconnections[.json], transformationconnections[.json], "
        + "authorityconnections[.json], jobs[.json], each containing exactly what GET /json/<name> would have returned. "
        + "Takes priority over --mcf-url/--mcf-user/--mcf-password when set, but not over --mcf-export-file.")
    String mcfInputDir;

    @Option(names = "--mcf-export-file", description = "Read ManifoldCF's native combined configuration XML export (from its own ExportConfiguration "
        + "tool) instead of a live API or saved JSON snapshot — pure file-to-file, no ManifoldCF connectivity needed at all. "
        + "Takes priority over --mcf-input-dir and --mcf-url when set. See ManifoldCFXmlExportSource's javadoc for an important "
        + "caveat: the XML parsing assumes the same node-type vocabulary as ManifoldCF's JSON API, which hasn't been "
        + "independently verified against a real export file — spot-check the result before trusting it in production.")
    String mcfExportFile;

    @Option(names = "--mcf-user", description = "ManifoldCF Basic Auth username, if required")
    String mcfUser;

    @Option(names = "--mcf-password", description = "ManifoldCF Basic Auth password (prefer env MANIFOLDCF_PASSWORD to avoid shell-history leakage)")
    String mcfPassword;

    @Option(names = "--default-embedding-dimensions", defaultValue = "384", description = "Embedding dimension to assume for migrated Vespa/OpenSearch output connectors, since ManifoldCF's connectors don't declare this statically")
    int defaultEmbeddingDimensions;

    @Option(names = "--only-connections", split = ",", description = "Limit the run to these connection names")
    List<String> onlyConnections = List.of();

    @Option(names = "--only-jobs", split = ",", description = "Limit the run to these job names")
    List<String> onlyJobs = List.of();

    @Option(names = "--map-connector", split = ",", description = "Manually redirect a named ManifoldCF connection to an OpenCrawling connector you've "
        + "already created by hand, bypassing automatic class-based mapping entirely for that connection — e.g. "
        + "--map-connector \"Solr_Output=Qdrant_Vector_Store\". Repeatable/comma-separated. Nothing is created for the "
        + "source side; jobs referencing it are treated as supported and get the target name substituted in.")
    List<String> mapConnector = List.of();

    @Option(names = "--timeout-seconds", defaultValue = "30", description = "HTTP timeout for both ManifoldCF and OpenCrawling calls")
    int timeoutSeconds;

    boolean useXmlExportSource() {
        return mcfExportFile != null && !mcfExportFile.isBlank();
    }

    boolean useFileSource() {
        return !useXmlExportSource() && mcfInputDir != null && !mcfInputDir.isBlank();
    }

    String sourceDescription() {
        if (useXmlExportSource()) {
            return "file://" + Path.of(mcfExportFile).toAbsolutePath();
        }
        return useFileSource()
            ? "file://" + Path.of(mcfInputDir).toAbsolutePath()
            : valueOr(mcfUrl, "MANIFOLDCF_URL", DEFAULT_MCF_URL);
    }

    String resolvedPassword() {
        return valueOr(mcfPassword, "MANIFOLDCF_PASSWORD", null);
    }

    ManifoldCFSource buildSource() {
        if (useXmlExportSource()) {
            return new ManifoldCFXmlExportSource(Path.of(mcfExportFile));
        }
        return useFileSource()
            ? new ManifoldCFFileSource(Path.of(mcfInputDir))
            : new ManifoldCFClient(sourceDescription(), mcfUser, resolvedPassword(), timeoutSeconds);
    }

    /** Parses {@code --map-connector "A=B,C=D"} entries into a source-name → target-name map. */
    Map<String, String> connectorOverrides() {
        Map<String, String> overrides = new LinkedHashMap<>();
        for (String entry : mapConnector) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                System.err.println("Ignoring malformed --map-connector entry '" + entry + "'; expected \"Source=Target\".");
                continue;
            }
            overrides.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
        }
        return overrides;
    }

    static String valueOr(String flagValue, String envVar, String fallback) {
        if (flagValue != null && !flagValue.isBlank()) {
            return flagValue;
        }
        String env = System.getenv(envVar);
        return (env != null && !env.isBlank()) ? env : fallback;
    }
}
