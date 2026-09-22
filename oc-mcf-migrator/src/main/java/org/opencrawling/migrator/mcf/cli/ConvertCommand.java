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

import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.engine.ConnectionPlanEntry;
import org.opencrawling.migrator.mcf.engine.JobPlanEntry;
import org.opencrawling.migrator.mcf.engine.MigrationEngine;
import org.opencrawling.migrator.mcf.engine.MigrationPlan;
import org.opencrawling.migrator.mcf.engine.MigrationSnapshot;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapperRegistry;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFSource;
import org.opencrawling.migrator.mcf.ois.OisConversionResult;
import org.opencrawling.migrator.mcf.ois.OisJobRenderer;
import org.opencrawling.migrator.mcf.report.MigrationReport;
import org.opencrawling.migrator.mcf.report.ReportRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code convert}: extract ManifoldCF's connections/jobs, map every one against the registered
 * {@code ConnectorMapper}s, and write each supported job as an
 * <a href="https://github.com/opencrawling/open-ingestion-standard">OIS</a>-format ({@code
 * ois/v1alpha1}) YAML or JSON file — no OpenCrawling API call at all, purely file-to-file. Matches
 * opencrawling/opencrawling#96's proposed {@code oc mcf convert} command exactly (just without the
 * not-yet-existing {@code oc-cli} prefix). Also writes the same audit report {@code audit}/{@code
 * import} do, since the underlying plan is computed anyway.
 *
 * <p>Exit codes: {@code 0} clean; {@code 1} connectivity/usage error; {@code 2} completed with
 * skips, only under {@code --fail-on-skip}.
 */
@Command(name = "convert", description = "Convert ManifoldCF jobs into OIS-format (ois/v1alpha1) YAML/JSON files — no OpenCrawling API call.")
public class ConvertCommand implements Callable<Integer> {

    @Mixin
    SourceOptions source;

    @Mixin
    ReportOptions report;

    @Option(names = "--output-dir", required = true, description = "Directory to write one OIS job file into per supported job (created if missing)")
    String outputDir;

    @Option(names = "--output-format", defaultValue = "yaml", description = "yaml (default) or json")
    String outputFormat;

    @Override
    public Integer call() {
        if (!"yaml".equalsIgnoreCase(outputFormat) && !"json".equalsIgnoreCase(outputFormat)) {
            System.err.println("Unknown --output-format '" + outputFormat + "'; using yaml.");
        }
        boolean outputJson = "json".equalsIgnoreCase(outputFormat);
        ReportOptions.Format format = report.resolvedFormat();

        MigrationOptions options = new MigrationOptions(
            source.sourceDescription(),
            source.mcfUser,
            source.resolvedPassword(),
            "(not applicable — convert only, no live OpenCrawling target)",
            null,
            false,
            report.resolvedReportFile(format),
            source.defaultEmbeddingDimensions,
            source.onlyConnections,
            source.onlyJobs,
            report.failOnSkip,
            source.timeoutSeconds,
            source.connectorOverrides()
        );

        ManifoldCFSource mcfClient = source.buildSource();
        MigrationEngine engine = new MigrationEngine(mcfClient, new ConnectorMapperRegistry(), null, options);

        MigrationPlan plan;
        try {
            MigrationSnapshot snapshot = engine.extract();
            plan = engine.plan(snapshot);
        } catch (Exception e) {
            System.err.println("Failed to extract/plan migration: " + e.getMessage());
            return 1;
        }

        Path outDir = Path.of(outputDir);
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            System.err.println("Failed to create --output-dir '" + outputDir + "': " + e.getMessage());
            return 1;
        }

        Map<String, ConnectorMappingResult> mappingsByKey = new LinkedHashMap<>();
        for (ConnectionPlanEntry entry : plan.connections()) {
            if (entry.mapping().supported()) {
                mappingsByKey.put(entry.source().lookupKey(), entry.mapping());
            }
        }

        OisJobRenderer oisRenderer = new OisJobRenderer();
        int converted = 0;
        for (JobPlanEntry entry : plan.jobs()) {
            if (!entry.mapping().supported()) {
                System.out.println("SKIP  " + entry.source().description() + " — " + entry.mapping().unsupportedReason());
                continue;
            }
            OisConversionResult result = oisRenderer.convert(entry, mappingsByKey);
            String extension = outputJson ? ".json" : ".yaml";
            Path file = outDir.resolve(result.fileBaseName() + extension);
            String rendered = outputJson ? oisRenderer.renderJson(result.document()) : oisRenderer.renderYaml(result.document());
            try {
                Files.writeString(file, rendered, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("Failed to write '" + file + "': " + e.getMessage());
                return 1;
            }
            converted++;
            System.out.println("OK    " + entry.source().description() + " -> " + file);
            result.notes().forEach(note -> System.out.println("        note: " + note));
        }

        MigrationReport migrationReport = new MigrationReport(
            DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            false, options.manifoldCfUrl(), options.openCrawlingUrl(), plan, null);
        ReportRenderer renderer = report.renderer(format);
        try {
            Files.writeString(Path.of(options.reportFile()), renderer.render(migrationReport), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to write report file '" + options.reportFile() + "': " + e.getMessage());
            return 1;
        }

        int totalJobs = plan.jobs().size();
        System.out.println();
        System.out.println("Converted " + converted + " of " + totalJobs + " job(s) to OIS format in " + outDir.toAbsolutePath());
        System.out.println("Full audit report: " + options.reportFile());

        boolean anySkips = converted < totalJobs
            || plan.connections().stream().anyMatch(e -> !e.mapping().supported());
        if (report.failOnSkip && anySkips) {
            return 2;
        }
        return 0;
    }
}
