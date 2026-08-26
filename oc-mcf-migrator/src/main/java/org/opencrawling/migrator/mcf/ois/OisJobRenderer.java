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
package org.opencrawling.migrator.mcf.ois;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.migrator.mcf.engine.JobPlanEntry;
import org.opencrawling.migrator.mcf.mapping.ConnectorMappingResult;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;
import org.opencrawling.migrator.mcf.mcf.model.McfPipelineStage;
import org.opencrawling.migrator.mcf.report.SecretRedactor;
import org.opencrawling.sdk.models.ConnectorRequest;
import org.opencrawling.sdk.models.JobRequest;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Converts one migrated (supported) job into a document matching the real {@code ois/v1alpha1}
 * job schema from
 * <a href="https://github.com/opencrawling/open-ingestion-standard">opencrawling/open-ingestion-standard</a>
 * — the schema opencrawling/opencrawling#96 refers to as "OIS-format YAML/JSON config output."
 *
 * <p>Deliberately does not populate {@code spec.pipeline} — ManifoldCF never declares per-job
 * text-extraction/chunking/embedding-provider settings, and inventing plausible-looking values
 * would violate this tool's own "never guess" design. {@code spec.schedule} is always defaulted
 * to a fixed daily crontab since the schema requires *some* value but neither ManifoldCF's
 * schedule model nor OpenCrawling (no scheduler) has anything real to put there — both omissions
 * are surfaced as {@link OisConversionResult#notes()} rather than silently glossed over.
 *
 * <p>Any configuration value that looks like a secret ({@link SecretRedactor}) is redacted before
 * it reaches the document — matching this tool's existing report-writing posture, and the OIS
 * spec's own {@code secretRef}-not-inline-value convention.
 */
public class OisJobRenderer {

    private static final String OIS_VERSION = "ois/v1alpha1";
    private static final String DEFAULT_SCHEDULE = "0 0 * * *";

    private static final Map<String, String> REPOSITORY_TYPE_NAMES = Map.of(
        "org.opencrawling.filesystem.FileSystemRepositoryConnector", "filesystem-source"
    );
    private static final Map<String, String> OUTPUT_TYPE_NAMES = Map.of(
        "org.opencrawling.vespa.VespaOutputConnector", "vespa",
        "org.opencrawling.opensearch2.OpenSearch2OutputConnector", "opensearch2"
    );

    private final Yaml yaml;
    private final ObjectMapper jsonMapper;

    public OisJobRenderer() {
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
        this.yaml = new Yaml(dumperOptions);
        this.jsonMapper = new ObjectMapper();
    }

    public OisConversionResult convert(JobPlanEntry entry, Map<String, ConnectorMappingResult> mappingsByKey) {
        JobRequest target = entry.mapping().target();
        List<String> notes = new ArrayList<>();

        // Looked up by the *original* ManifoldCF connection names (entry.source()), not
        // target.repositoryConnector()/.outputConnector() — JobMapper substitutes those with the
        // --map-connector override target name when one applies, which wouldn't be a key in
        // mappingsByKey (that map is keyed by original ManifoldCF connections' kind+name).
        List<McfPipelineStage> outputStages = entry.source().outputStages();
        if (outputStages.isEmpty()) {
            // JobMapper only produces a supported() result when there's exactly one output stage
            // (see JobMapper.map()'s outputStages.size() != 1 check), so this shouldn't happen for
            // an already-supported job — but this class never guesses, so fail loudly with context
            // rather than an opaque IndexOutOfBoundsException if that invariant is ever violated.
            throw new IllegalStateException(
                "Job '" + target.name() + "' is marked supported but has no output stage — JobMapper's invariant was violated");
        }
        String repositoryConnectionName = entry.source().repositoryConnectionName();
        String outputConnectionName = outputStages.get(0).connectionName();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", slug(target.name()));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("schedule", resolveSchedule(entry, notes));

        spec.put("connector", connectorBlock(McfConnectionKind.REPOSITORY, repositoryConnectionName, mappingsByKey, REPOSITORY_TYPE_NAMES, notes, "connector"));
        spec.put("output", connectorBlock(McfConnectionKind.OUTPUT, outputConnectionName, mappingsByKey, OUTPUT_TYPE_NAMES, notes, "output"));

        notes.add("spec.pipeline omitted — ManifoldCF doesn't declare per-job text-extraction/chunking/"
            + "embedding-provider settings, and OpenCrawling's transformation stage is configured "
            + "independently rather than as part of the job data this tool has to translate.");

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", OIS_VERSION);
        document.put("metadata", metadata);
        document.put("spec", spec);

        return new OisConversionResult(slug(target.name()), document, notes);
    }

    private static String resolveSchedule(JobPlanEntry entry, List<String> notes) {
        var scheduleRecords = entry.source().scheduleRecords();
        Optional<String> translated = CronTranslator.tryTranslate(scheduleRecords);
        if (translated.isPresent()) {
            notes.add("spec.schedule translated from this job's ManifoldCF schedule record (day-of-week/hour/"
                + "minute) — verify it matches intent; ManifoldCF's scheduling semantics (duration windows, "
                + "multiple records) are richer than a single crontab expression can fully capture.");
            return translated.get();
        }
        String why = scheduleRecords.isEmpty()
            ? "no schedule records present"
            : scheduleRecords.size() + " schedule record(s) present but too complex to translate to a single "
                + "cron expression (multiple records, a month/day-of-month restriction, or more than one "
                + "hour/minute value)";
        notes.add("spec.schedule defaulted to '" + DEFAULT_SCHEDULE + "' (daily) — " + why + ", and OpenCrawling "
            + "has no scheduler to honor a real one anyway. Edit this if a real schedule matters to you.");
        return DEFAULT_SCHEDULE;
    }

    public String renderYaml(Map<String, Object> document) {
        return yaml.dump(document);
    }

    public String renderJson(Map<String, Object> document) {
        try {
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render OIS JSON document", e);
        }
    }

    private static Map<String, Object> connectorBlock(McfConnectionKind kind, String connectionName, Map<String, ConnectorMappingResult> mappingsByKey,
            Map<String, String> typeNames, List<String> notes, String label) {
        Map<String, Object> block = new LinkedHashMap<>();
        ConnectorMappingResult mapping = mappingsByKey.get(McfConnection.key(kind, connectionName));
        if (mapping == null || !mapping.supported()) {
            block.put("type", "unknown");
            block.put("config", Map.of());
            notes.add("spec." + label + " could not be resolved for connection '" + connectionName
                + "' — this shouldn't happen for an already-supported job, but a placeholder was used rather than failing the whole conversion.");
            return block;
        }
        if (mapping.overrideTargetName() != null) {
            block.put("type", mapping.overrideTargetName());
            block.put("config", Map.of());
            notes.add("spec." + label + " manually mapped via --map-connector to '" + mapping.overrideTargetName()
                + "' — this tool has no visibility into that connector's real configuration, so spec." + label
                + ".config is empty; fill in whatever the target connector actually needs.");
            return block;
        }
        ConnectorRequest connectorTarget = mapping.target();
        String type = typeNames.get(connectorTarget.className());
        if (type == null) {
            type = connectorTarget.className();
            notes.add("spec." + label + ".type has no short OIS-style identifier registered yet for '"
                + connectorTarget.className() + "'; using the full class name instead.");
        }
        block.put("type", type);
        block.put("config", new LinkedHashMap<>(SecretRedactor.redact(connectorTarget.configuration())));
        return block;
    }

    private static String slug(String name) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "job" : slug;
    }
}
