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
package org.opencrawling.migrator.mcf.job;

import org.opencrawling.migrator.mcf.config.MigrationOptions;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mapping.FieldNote;
import org.opencrawling.migrator.mcf.mapping.FieldNoteKind;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;
import org.opencrawling.migrator.mcf.mcf.model.McfFileSystemSpec;
import org.opencrawling.migrator.mcf.mcf.model.McfFileSystemSpec.StartPoint;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.mcf.model.McfPipelineStage;
import org.opencrawling.migrator.mcf.mcf.parse.DocumentSpecificationParser;
import org.opencrawling.sdk.models.JobRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves one {@code McfJob} against the already-computed connector mapping results, per the
 * connector/job-level matching rule: a job migrates only if its repository connection <b>and
 * every</b> pipeline stage it references are individually supported; otherwise it's skipped,
 * naming exactly which connector(s) blocked it. Connector names are assumed unchanged across
 * migration (both shipped {@code ConnectorMapper}s preserve the source name verbatim), so a
 * supported job's {@code JobRequest} simply reuses the ManifoldCF connection names as-is.
 */
public class JobMapper {

    private static final String DEFAULT_PATH = "/data";

    /**
     * Class-name substrings {@code JobController.startJob()} actually special-cases at job-start
     * time (verified directly in {@code oc-runtime} source) — a repository connector outside this
     * list silently falls back to the injected {@code FileSystemRepositoryConnector} bean, and an
     * output connector outside its list falls back to the injected default {@code OutputConnector}
     * bean (effectively PGVector). "FileSystem" is included in the repository list even though it
     * has no dedicated {@code if}-branch, because the fallback bean IS a filesystem connector —
     * so a filesystem target is safe by coincidence, not by dynamic resolution.
     */
    private static final List<String> REPO_DYNAMICALLY_SAFE = List.of("Alfresco", "Iceberg", "FileSystem");
    private static final List<String> OUTPUT_DYNAMICALLY_SAFE = List.of("Qdrant", "Vespa");

    public JobMappingResult map(
            McfJob job,
            Map<String, McfConnection> connectionsByKey,
            Map<String, ConnectorMappingResult> mappingResultsByKey,
            MigrationOptions options) {

        String repositoryKey = McfConnection.key(McfConnectionKind.REPOSITORY, job.repositoryConnectionName());
        List<String> blocking = new ArrayList<>();
        if (!isSupported(repositoryKey, mappingResultsByKey)) {
            blocking.add(job.repositoryConnectionName() + " (repository)");
        }
        for (McfPipelineStage stage : job.pipelineStages()) {
            String stageKey = McfConnection.key(
                stage.isOutput() ? McfConnectionKind.OUTPUT : McfConnectionKind.TRANSFORMATION, stage.connectionName());
            if (!isSupported(stageKey, mappingResultsByKey)) {
                blocking.add(stage.connectionName() + " (" + (stage.isOutput() ? "output" : "transformation") + ")");
            }
        }
        if (!blocking.isEmpty()) {
            return JobMappingResult.unsupported(blocking,
                "blocked by unsupported connector(s): " + String.join(", ", blocking));
        }

        List<McfPipelineStage> outputStages = job.outputStages();
        if (outputStages.size() != 1) {
            return JobMappingResult.unsupported(List.of(),
                "job has " + outputStages.size() + " output stage(s); OpenCrawling's JobRequest has exactly one "
                    + "outputConnector slot");
        }
        List<McfPipelineStage> transformStages = job.transformationStages();

        List<FieldNote> notes = new ArrayList<>();
        if (transformStages.size() > 1) {
            notes.add(new FieldNote("transformationConnector", FieldNoteKind.DROPPED,
                "only the first transformation stage migrates (" + transformStages.get(0).connectionName()
                    + "); " + (transformStages.size() - 1) + " more dropped — OpenCrawling's JobRequest has "
                    + "exactly one transformationConnector slot"));
        }
        String transformationConnector;
        String transformationKey;
        if (transformStages.isEmpty()) {
            transformationConnector = null;
            transformationKey = null;
            notes.add(new FieldNote("transformationConnector", FieldNoteKind.DEFAULTED,
                "job had no transformation stage in ManifoldCF; left unset so OpenCrawling applies its own "
                    + "default embedding connector"));
        } else {
            transformationConnector = transformStages.get(0).connectionName();
            transformationKey = McfConnection.key(McfConnectionKind.TRANSFORMATION, transformationConnector);
        }

        String outputConnectionName = outputStages.get(0).connectionName();
        String outputKey = McfConnection.key(McfConnectionKind.OUTPUT, outputConnectionName);

        String path = resolvePath(job, connectionsByKey, notes);
        checkRuntimeResolution(repositoryKey, outputKey, mappingResultsByKey, notes);

        notes.add(new FieldNote("schedule", FieldNoteKind.DROPPED,
            "schedule/hopcount/recrawl/priority/expiration/reseed settings have no target — OpenCrawling has no "
                + "scheduler; job is created in Ready state for manual/API start"));
        if (job.notificationStageCount() > 0) {
            notes.add(new FieldNote("notifications", FieldNoteKind.DROPPED,
                job.notificationStageCount() + " notification stage(s) ignored — no target concept"));
        }

        JobRequest target = JobRequest.builder()
            .id(null)
            .name(job.description())
            .repositoryConnector(resolvedConnectorName(repositoryKey, job.repositoryConnectionName(), mappingResultsByKey))
            .outputConnector(resolvedConnectorName(outputKey, outputConnectionName, mappingResultsByKey))
            .transformationConnector(transformationKey == null ? null
                : resolvedConnectorName(transformationKey, transformationConnector, mappingResultsByKey))
            .authorityConnector("")
            .path(path)
            .status("Ready")
            .currentStage("Idle")
            .documents(0)
            .lastRun("N/A")
            .build();

        return JobMappingResult.supported(target, notes);
    }

    /**
     * The name OpenCrawling should actually know this connector by: the ManifoldCF connection name
     * unchanged (the normal case — both shipped mappers preserve it verbatim), or the
     * {@code --map-connector} override target if this connection was manually redirected to an
     * already-existing OpenCrawling connector rather than auto-mapped.
     */
    private static String resolvedConnectorName(String key, String mcfConnectionName, Map<String, ConnectorMappingResult> mappingResultsByKey) {
        ConnectorMappingResult result = mappingResultsByKey.get(key);
        return (result != null && result.overrideTargetName() != null) ? result.overrideTargetName() : mcfConnectionName;
    }

    private String resolvePath(McfJob job, Map<String, McfConnection> connectionsByKey, List<FieldNote> notes) {
        McfConnection repoConnection = connectionsByKey.get(McfConnection.key(McfConnectionKind.REPOSITORY, job.repositoryConnectionName()));
        String repoClassName = repoConnection != null ? repoConnection.className() : null;
        Optional<McfFileSystemSpec> fsSpec =
            DocumentSpecificationParser.parse(job.documentSpecification(), repoClassName);

        if (fsSpec.isEmpty() || fsSpec.get().startPoints().isEmpty()) {
            notes.add(new FieldNote("path", FieldNoteKind.DEFAULTED,
                "could not determine a scan root from this job's document specification; defaulted to '"
                    + DEFAULT_PATH + "'"));
            return DEFAULT_PATH;
        }

        List<StartPoint> startPoints = fsSpec.get().startPoints();
        StartPoint first = startPoints.get(0);
        if (startPoints.size() > 1) {
            notes.add(new FieldNote("path", FieldNoteKind.DROPPED,
                (startPoints.size() - 1) + " additional startpoint(s) not migrated; OpenCrawling's JobRequest "
                    + "has exactly one scan root"));
        }
        if (!first.filters().isEmpty()) {
            notes.add(new FieldNote("path", FieldNoteKind.SCOPE_CHANGE,
                first.filters().size() + " include/exclude filter(s) dropped — OpenCrawling's "
                    + "FileSystemRepositoryConnector scans every file under the root unconditionally, so this "
                    + "job will ingest more than the original ManifoldCF job did"));
        }
        return first.path();
    }

    private static boolean isSupported(String key, Map<String, ConnectorMappingResult> mappingResultsByKey) {
        ConnectorMappingResult result = mappingResultsByKey.get(key);
        return result != null && result.supported();
    }

    private void checkRuntimeResolution(
            String repositoryKey,
            String outputKey,
            Map<String, ConnectorMappingResult> mappingResultsByKey,
            List<FieldNote> notes) {

        checkOneConnectorRuntimeResolution("repositoryConnector", repositoryKey, mappingResultsByKey,
            REPO_DYNAMICALLY_SAFE, "repository", "Alfresco/Iceberg", "FileSystemRepositoryConnector", notes);
        checkOneConnectorRuntimeResolution("outputConnector", outputKey, mappingResultsByKey,
            OUTPUT_DYNAMICALLY_SAFE, "output", "Qdrant/Vespa", "OutputConnector", notes);
    }

    private static void checkOneConnectorRuntimeResolution(
            String field, String key, Map<String, ConnectorMappingResult> mappingResultsByKey,
            List<String> dynamicallySafe, String kindLabel, String safeTypesLabel, String fallbackBeanLabel,
            List<FieldNote> notes) {

        ConnectorMappingResult result = mappingResultsByKey.get(key);
        if (result == null || !result.supported()) {
            return;
        }
        if (result.overrideTargetName() != null) {
            notes.add(new FieldNote(field, FieldNoteKind.RUNTIME_RISK,
                "manually mapped via --map-connector to '" + result.overrideTargetName() + "' — this tool has no "
                    + "visibility into that connector's class, so it can't verify whether OpenCrawling's dynamic "
                    + "connector resolution at job-start actually resolves to it; verify manually"));
            return;
        }
        String targetClass = result.target().className();
        if (dynamicallySafe.stream().noneMatch(targetClass::contains)) {
            notes.add(new FieldNote(field, FieldNoteKind.RUNTIME_RISK,
                "target class '" + targetClass + "' is outside OpenCrawling's dynamically-resolved " + kindLabel
                    + " types (" + safeTypesLabel + "); at job-start it will silently fall back to the default "
                    + fallbackBeanLabel + " bean instead"));
        }
    }
}
