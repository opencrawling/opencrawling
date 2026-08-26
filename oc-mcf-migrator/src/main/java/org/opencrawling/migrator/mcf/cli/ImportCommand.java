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
import org.opencrawling.migrator.mcf.engine.ApplyOutcome;
import org.opencrawling.migrator.mcf.engine.MigrationEngine;
import org.opencrawling.migrator.mcf.engine.MigrationPlan;
import org.opencrawling.migrator.mcf.engine.MigrationSnapshot;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapperRegistry;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFSource;
import org.opencrawling.migrator.mcf.oc.OpenCrawlingWriter;
import org.opencrawling.migrator.mcf.report.ConsoleReportRenderer;
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
import java.util.concurrent.Callable;

/**
 * {@code import}: extract ManifoldCF's connections/jobs, map every one against the registered
 * {@code ConnectorMapper}s, and write the supported half of the plan to a live OpenCrawling
 * instance. Always writes — there is no dry-run flag here; use {@code audit} first if you want to
 * review before writing. Matches opencrawling/opencrawling#96's proposed {@code oc mcf import}
 * command exactly (just without the not-yet-existing {@code oc-cli} prefix).
 *
 * <p>Exit codes: {@code 0} clean; {@code 1} connectivity/usage error; {@code 2} completed with
 * skips, only under {@code --fail-on-skip}; {@code 3} at least one write failure (takes priority
 * over 2).
 */
@Command(name = "import", description = "Migrate ManifoldCF connections/jobs and write the supported half directly to a live OpenCrawling instance.")
public class ImportCommand implements Callable<Integer> {

    private static final String DEFAULT_OC_URL = "http://localhost:8080";

    @Mixin
    SourceOptions source;

    @Mixin
    ReportOptions report;

    @Option(names = "--oc-url", description = "OpenCrawling REST API base URL (env OPENCRAWLING_URL, default: " + DEFAULT_OC_URL + ")")
    String ocUrl;

    @Option(names = "--oc-api-key", description = "OpenCrawling API key, if the target instance requires one")
    String ocApiKey;

    @Option(names = "--oc-bearer-token", description = "OpenCrawling bearer token, if the target instance requires one")
    String ocBearerToken;

    @Override
    public Integer call() {
        ReportOptions.Format format = report.resolvedFormat();

        MigrationOptions options = new MigrationOptions(
            source.sourceDescription(),
            source.mcfUser,
            source.resolvedPassword(),
            SourceOptions.valueOr(ocUrl, "OPENCRAWLING_URL", DEFAULT_OC_URL),
            ocApiKey,
            true,
            report.resolvedReportFile(format),
            source.defaultEmbeddingDimensions,
            source.onlyConnections,
            source.onlyJobs,
            report.failOnSkip,
            source.timeoutSeconds,
            source.connectorOverrides()
        );

        ManifoldCFSource mcfClient = source.buildSource();
        ConnectorMapperRegistry registry = new ConnectorMapperRegistry();
        OpenCrawlingWriter writer = new OpenCrawlingWriter(
            options.openCrawlingUrl(), options.openCrawlingApiKey(), ocBearerToken, options.timeoutSeconds());
        MigrationEngine engine = new MigrationEngine(mcfClient, registry, writer, options);

        MigrationPlan plan;
        try {
            MigrationSnapshot snapshot = engine.extract();
            plan = engine.plan(snapshot);
        } catch (Exception e) {
            System.err.println("Failed to extract/plan migration: " + e.getMessage());
            return 1;
        }

        ApplyOutcome applyOutcome;
        boolean applyHadFailure;
        try {
            applyOutcome = engine.apply(plan);
            applyHadFailure = applyOutcome.connectionResults().values().stream().anyMatch(r -> !r.success())
                || applyOutcome.jobResults().values().stream().anyMatch(r -> !r.success());
        } catch (Exception e) {
            System.err.println("Failed to apply migration: " + e.getMessage());
            return 1;
        }

        MigrationReport migrationReport = new MigrationReport(
            DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            true, options.manifoldCfUrl(), options.openCrawlingUrl(), plan, applyOutcome);

        ReportRenderer renderer = report.renderer(format);
        try {
            Files.writeString(Path.of(options.reportFile()), renderer.render(migrationReport), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to write report file '" + options.reportFile() + "': " + e.getMessage());
            return 1;
        }

        System.out.print(new ConsoleReportRenderer(options.reportFile()).render(migrationReport));

        if (applyHadFailure) {
            return 3;
        }
        boolean anySkips = plan.connections().stream().anyMatch(e -> !e.mapping().supported())
            || plan.jobs().stream().anyMatch(e -> !e.mapping().supported());
        if (report.failOnSkip && anySkips) {
            return 2;
        }
        return 0;
    }
}
