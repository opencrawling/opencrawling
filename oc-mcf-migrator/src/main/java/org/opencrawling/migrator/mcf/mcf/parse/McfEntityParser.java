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
package org.opencrawling.migrator.mcf.mcf.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.migrator.mcf.mcf.model.McfConnection;
import org.opencrawling.migrator.mcf.mcf.model.McfConnectionKind;
import org.opencrawling.migrator.mcf.mcf.model.McfJob;
import org.opencrawling.migrator.mcf.mcf.model.McfPipelineStage;
import org.opencrawling.migrator.mcf.mcf.model.McfScheduleRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a single ManifoldCF connection/job/pipeline-stage node out of the JSON shape described by
 * {@link McfJsonNodes}. Shared by every {@code ManifoldCFSource} implementation (live REST client,
 * file-based source, ...) so there is exactly one place that knows the field-name mapping.
 */
public final class McfEntityParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String NODE_PIPELINE_STAGE = "pipelinestage";
    private static final String NODE_NOTIFICATION_STAGE = "notificationstage";

    private McfEntityParser() {
    }

    public static McfConnection parseConnection(JsonNode node, McfConnectionKind kind) {
        String name = McfJsonNodes.childValue(node, "name").orElse("");
        String className = McfJsonNodes.childValue(node, "class_name").orElse("");
        String description = McfJsonNodes.childValue(node, "description").orElse(null);
        int maxConnections = McfJsonNodes.childValue(node, "max_connections").map(Integer::parseInt).orElse(10);
        JsonNode configurationNode = McfJsonNodes.firstChildOfType(node, "configuration").orElse(null);
        String aclAuthority = McfJsonNodes.childValue(node, "acl_authority").orElse(null);
        List<String> throttleMatches = new ArrayList<>();
        for (JsonNode throttleNode : McfJsonNodes.childrenOfType(node, "throttle")) {
            McfJsonNodes.childValue(throttleNode, "match").ifPresent(throttleMatches::add);
        }
        return new McfConnection(kind, name, description, className, maxConnections,
            McfJsonNodes.configParamsMap(configurationNode), aclAuthority, throttleMatches);
    }

    public static McfJob parseJob(JsonNode node) {
        String id = McfJsonNodes.childValue(node, "id").orElse(null);
        String description = McfJsonNodes.childValue(node, "description").orElse("");
        String repositoryConnection = McfJsonNodes.childValue(node, "repository_connection").orElse("");
        JsonNode documentSpecification = McfJsonNodes.firstChildOfType(node, "document_specification").orElse(null);

        List<McfPipelineStage> stages = new ArrayList<>();
        for (JsonNode stageNode : McfJsonNodes.childrenOfType(node, NODE_PIPELINE_STAGE)) {
            stages.add(parseStage(stageNode));
        }
        int notificationStageCount = McfJsonNodes.childrenOfType(node, NODE_NOTIFICATION_STAGE).size();

        List<McfScheduleRecord> scheduleRecords = new ArrayList<>();
        for (JsonNode scheduleNode : McfJsonNodes.childrenOfType(node, "schedule")) {
            scheduleRecords.add(parseScheduleRecord(scheduleNode));
        }

        return new McfJob(
            id,
            description,
            repositoryConnection,
            documentSpecification,
            stages,
            notificationStageCount,
            McfJsonNodes.childValue(node, "start_mode").orElse(null),
            McfJsonNodes.childValue(node, "run_mode").orElse(null),
            McfJsonNodes.childValue(node, "hopcount_mode").orElse(null),
            McfJsonNodes.childValue(node, "priority").orElse(null),
            McfJsonNodes.childValue(node, "recrawl_interval").orElse(null),
            McfJsonNodes.childValue(node, "max_recrawl_interval").orElse(null),
            McfJsonNodes.childValue(node, "expiration_interval").orElse(null),
            McfJsonNodes.childValue(node, "reseed_interval").orElse(null),
            scheduleRecords
        );
    }

    /**
     * See {@link McfScheduleRecord}'s javadoc: this field-name mapping follows the project's
     * established node-type vocabulary but hasn't been independently verified against a real
     * populated ManifoldCF schedule. An unrecognized/differently-named field simply parses as
     * empty here — never as a wrong value — which is what lets {@code CronTranslator} fail safe.
     */
    private static McfScheduleRecord parseScheduleRecord(JsonNode node) {
        return new McfScheduleRecord(
            intValuesOfType(node, "dayofweek"),
            intValuesOfType(node, "hourofday"),
            intValuesOfType(node, "minutesofhour"),
            intValuesOfType(node, "monthofyear"),
            intValuesOfType(node, "dayofmonth"),
            McfJsonNodes.childValue(node, "duration").map(Integer::parseInt).orElse(null)
        );
    }

    private static List<Integer> intValuesOfType(JsonNode node, String type) {
        List<Integer> result = new ArrayList<>();
        for (JsonNode child : McfJsonNodes.childrenOfType(node, type)) {
            McfJsonNodes.value(child).map(Integer::parseInt).ifPresent(result::add);
        }
        return result;
    }

    public static McfPipelineStage parseStage(JsonNode node) {
        int stageId = McfJsonNodes.childValue(node, "stage_id").map(Integer::parseInt).orElse(0);
        int prerequisite = McfJsonNodes.childValue(node, "stage_prerequisite").map(Integer::parseInt).orElse(-1);
        boolean isOutput = McfJsonNodes.childValue(node, "stage_isoutput").map(Boolean::parseBoolean).orElse(false);
        String connectionName = McfJsonNodes.childValue(node, "stage_connectionname").orElse("");
        String description = McfJsonNodes.childValue(node, "stage_description").orElse(null);
        JsonNode rawSpec = McfJsonNodes.firstChildOfType(node, "stage_specification")
            .orElse(OBJECT_MAPPER.createObjectNode());
        return new McfPipelineStage(stageId, prerequisite, isOutput, connectionName, description, rawSpec);
    }
}
