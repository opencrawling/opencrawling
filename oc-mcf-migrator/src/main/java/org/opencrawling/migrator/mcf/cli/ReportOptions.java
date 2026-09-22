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

import org.opencrawling.migrator.mcf.report.HtmlReportRenderer;
import org.opencrawling.migrator.mcf.report.JsonReportRenderer;
import org.opencrawling.migrator.mcf.report.MarkdownReportRenderer;
import org.opencrawling.migrator.mcf.report.ReportRenderer;
import picocli.CommandLine.Option;

import java.time.Instant;

/**
 * The report-rendering options shared by every subcommand that produces an audit report (`audit`,
 * `import`, and `convert` — the latter emits one alongside its OIS job files).
 */
class ReportOptions {

    enum Format {
        MARKDOWN(".md"), JSON(".json"), HTML(".html");

        final String extension;

        Format(String extension) {
            this.extension = extension;
        }
    }

    @Option(names = "--report-file", description = "Where to write the full report (default: ./mcf-migration-report-<timestamp>.<ext>)")
    String reportFile;

    @Option(names = "--report-format", defaultValue = "markdown", description = "markdown (default), json, or html")
    String reportFormat;

    @Option(names = "--fail-on-skip", description = "Exit with code 2 if anything was skipped (CI-friendly strict mode)")
    boolean failOnSkip;

    /** Call once per command invocation — logs a warning on an unrecognized format as a side effect. */
    Format resolvedFormat() {
        if ("json".equalsIgnoreCase(reportFormat)) {
            return Format.JSON;
        }
        if ("html".equalsIgnoreCase(reportFormat)) {
            return Format.HTML;
        }
        if (!"markdown".equalsIgnoreCase(reportFormat)) {
            System.err.println("Unknown --report-format '" + reportFormat + "'; using markdown.");
        }
        return Format.MARKDOWN;
    }

    String resolvedReportFile(Format format) {
        return reportFile != null ? reportFile : defaultReportFilePath(format);
    }

    ReportRenderer renderer(Format format) {
        return switch (format) {
            case JSON -> new JsonReportRenderer();
            case HTML -> new HtmlReportRenderer();
            case MARKDOWN -> new MarkdownReportRenderer();
        };
    }

    private static String defaultReportFilePath(Format format) {
        return "mcf-migration-report-" + Instant.now().toEpochMilli() + format.extension;
    }
}
