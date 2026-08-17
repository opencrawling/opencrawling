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
package org.opencrawling.migrator.mcf.report;

import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.engine.ApplyOutcome;
import org.opencrawling.migrator.mcf.engine.ApplyOutcome.ApplyResult;
import org.opencrawling.migrator.mcf.engine.ConnectionPlanEntry;
import org.opencrawling.migrator.mcf.engine.JobPlanEntry;
import org.opencrawling.migrator.mcf.engine.MigrationPlan;
import org.opencrawling.migrator.mcf.job.JobMappingResult;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mapping.filesystem.FileConnectorMapper;
import org.opencrawling.migrator.mcf.mapping.vespa.VespaOutputConnectorMapper;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownReportRendererTest {

    private static final String REAL_SECRET = "sekrit-password-abc123";

    private final MarkdownReportRenderer renderer = new MarkdownReportRenderer();
    private final MigrationOptions options = new MigrationOptions(
        "http://mcf", null, null, "http://oc", null, false, "report.md", 384, List.of(), List.of(), false, 30, Map.of());

    @Test
    void render_includesAllExpectedSections() {
        MigrationReport report = buildRealisticReport(false);
        String rendered = renderer.render(report);

        assertThat(rendered).contains("# ManifoldCF → OpenCrawling Migration Report");
        assertThat(rendered).contains("## Summary");
        assertThat(rendered).contains("## Connections");
        assertThat(rendered).contains("## Jobs");
        assertThat(rendered).contains("## Field-level warnings");
        assertThat(rendered).contains("## Known target-system limitations");
        assertThat(rendered).doesNotContain("## Apply Results");
    }

    @Test
    void render_applyResultsSection_onlyPresentWhenApplyOutcomeGiven() {
        String dryRun = renderer.render(buildRealisticReport(false));
        String applied = renderer.render(buildRealisticReport(true));

        assertThat(dryRun).doesNotContain("## Apply Results");
        assertThat(applied).contains("## Apply Results").contains("SUCCESS");
    }

    @Test
    void render_skippedConnectionShowsReason() {
        String rendered = renderer.render(buildRealisticReport(false));
        assertThat(rendered).contains("Mfiles Source Repository").contains("no registered mapper");
    }

    @Test
    void render_scopeChangeNoteIsVisuallyDistinguished() {
        String rendered = renderer.render(buildRealisticReport(false));
        assertThat(rendered).contains("**SCOPE_CHANGE**");
    }

    @Test
    void render_neverLeaksARealSecretValue() {
        // Vespa connection's ManifoldCF config below genuinely carries a password; only the
        // mapper's redaction/omission behavior (already verified in VespaOutputConnectorMapperTest)
        // stands between that and this report — this test proves the renderer doesn't undo it.
        String rendered = renderer.render(buildRealisticReport(true));
        assertThat(rendered).doesNotContain(REAL_SECRET);
    }

    private MigrationReport buildRealisticReport(boolean withApplyOutcome) {
        FileConnectorMapper fileMapper = new FileConnectorMapper();
        VespaOutputConnectorMapper vespaMapper = new VespaOutputConnectorMapper();

        McfConnection sharepoint = new McfConnection(McfConnectionKind.REPOSITORY, "SharepointDrive", "Company File Share",
            "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector", 10, Map.of(), null, List.of());
        ConnectorMappingResult sharepointMapping = fileMapper.map(sharepoint, options);

        Map<String, String> vespaConfig = new LinkedHashMap<>();
        vespaConfig.put("vespaEndpoint", "http://vespa:8080");
        vespaConfig.put("vespaPassword", REAL_SECRET);
        McfConnection vespa = new McfConnection(McfConnectionKind.OUTPUT, "Vespa Federated Index", "Vespa Federated Index",
            "org.apache.manifoldcf.agents.output.vespa.VespaOutputConnector", 10, vespaConfig, null, List.of());
        ConnectorMappingResult vespaMapping = vespaMapper.map(vespa, options);

        McfConnection mfiles = new McfConnection(McfConnectionKind.REPOSITORY, "Mfiles Source Repository", "Mfiles Source Repository",
            "com.speedysearch.manifoldcf.mfiles.MFilesRepositoryConnector", 10, Map.of(), null, List.of());
        ConnectorMappingResult mfilesMapping = ConnectorMappingResult.unsupported(
            "no registered mapper for class 'com.speedysearch.manifoldcf.mfiles.MFilesRepositoryConnector'");

        List<ConnectionPlanEntry> connections = List.of(
            new ConnectionPlanEntry(sharepoint, sharepointMapping),
            new ConnectionPlanEntry(vespa, vespaMapping),
            new ConnectionPlanEntry(mfiles, mfilesMapping));

        var supportedJob = JobMappingResult.supported(
            org.opencrawling.sdk.models.JobRequest.builder()
                .name("SharePoint drive to Vespa").repositoryConnector("SharepointDrive")
                .outputConnector("Vespa Federated Index").path("/mnt/drive-a/files").build(),
            List.of(new org.opencrawling.migrator.mcf.mapping.FieldNote("path",
                org.opencrawling.migrator.mcf.mapping.FieldNoteKind.SCOPE_CHANGE, "2 filter(s) dropped")));
        var skippedJob = JobMappingResult.unsupported(List.of("Mfiles Source Repository (repository)"),
            "blocked by unsupported connector(s): Mfiles Source Repository (repository)");

        List<JobPlanEntry> jobs = List.of(
            new JobPlanEntry(job("SharePoint drive to Vespa", sharepoint), supportedJob),
            new JobPlanEntry(job("Mfiles to Vespa", mfiles), skippedJob));

        MigrationPlan plan = new MigrationPlan(connections, jobs);

        ApplyOutcome applyOutcome = withApplyOutcome
            ? new ApplyOutcome(Map.of("SharepointDrive", new ApplyResult(true, "created/updated"),
                "Vespa Federated Index", new ApplyResult(true, "created/updated")),
                Map.of("SharePoint drive to Vespa", new ApplyResult(true, "created/updated")))
            : null;

        return new MigrationReport("2026-08-13T00:00:00Z", withApplyOutcome, "http://mcf", "http://oc", plan, applyOutcome);
    }

    private org.opencrawling.migrator.mcf.mcf.model.McfJob job(String description, McfConnection repo) {
        return new org.opencrawling.migrator.mcf.mcf.model.McfJob(
            String.valueOf(description.hashCode()), description, repo.name(), null, List.of(), 0,
            null, null, null, null, null, null, null, null, List.of());
    }
}
