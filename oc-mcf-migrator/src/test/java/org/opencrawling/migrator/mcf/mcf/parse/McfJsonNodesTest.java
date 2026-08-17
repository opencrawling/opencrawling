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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both JSON encodings ManifoldCF's {@code Configuration#toJSON()} produces are exercised here,
 * derived directly from reading that serializer's source (see class javadoc on {@link
 * McfJsonNodes}) rather than guessed: the "collapsed" form for a node whose children share one
 * type, and the "{@code _children_}"/"{@code _type_}" alternate form for mixed-type siblings —
 * which is what ManifoldCF actually emits for almost anything with more than one distinct child
 * type in sequence (e.g. a connection's own isnew/name/class_name/... siblings, or a
 * startpoint's interleaved include/exclude children).
 */
class McfJsonNodesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void collapsedForm_singleChild_isASingletonObjectNotAnArray() throws Exception {
        JsonNode root = mapper.readTree("""
            {"repositoryconnection": {"name": "SharepointDrive"}}
            """);
        List<JsonNode> children = McfJsonNodes.childrenOfType(root, "repositoryconnection");
        assertThat(children).hasSize(1);
        assertThat(children.get(0).get("name").asText()).isEqualTo("SharepointDrive");
    }

    @Test
    void collapsedForm_multipleChildren_isAnArray() throws Exception {
        JsonNode root = mapper.readTree("""
            {"repositoryconnection": [{"name": "A"}, {"name": "B"}, {"name": "C"}]}
            """);
        List<JsonNode> children = McfJsonNodes.childrenOfType(root, "repositoryconnection");
        assertThat(children).extracting(n -> n.get("name").asText()).containsExactly("A", "B", "C");
    }

    @Test
    void absentType_returnsEmptyNotNull() throws Exception {
        JsonNode root = mapper.readTree("{}");
        assertThat(McfJsonNodes.childrenOfType(root, "repositoryconnection")).isEmpty();
    }

    @Test
    void alternateForm_mixedSiblingTypes_filtersByType() throws Exception {
        // Mirrors a real job node's own children: id, description, repository_connection, ... —
        // all different consecutive types, which is exactly what triggers the alternate encoding.
        JsonNode root = mapper.readTree("""
            {"_children_": [
                {"_type_": "id", "_value_": "42"},
                {"_type_": "description", "_value_": "SharePoint drive to Vespa"},
                {"_type_": "pipelinestage", "_value_": "stage-a"},
                {"_type_": "pipelinestage", "_value_": "stage-b"}
            ]}
            """);
        assertThat(McfJsonNodes.childValue(root, "id")).contains("42");
        assertThat(McfJsonNodes.childValue(root, "description")).contains("SharePoint drive to Vespa");
        assertThat(McfJsonNodes.childrenOfType(root, "pipelinestage")).hasSize(2);
        assertThat(McfJsonNodes.childrenOfType(root, "nonexistent")).isEmpty();
    }

    @Test
    void bareScalarLeaf_hasNoAttributesOrChildren() throws Exception {
        JsonNode leaf = mapper.readTree("\"admin\"");
        assertThat(McfJsonNodes.value(leaf)).contains("admin");
        assertThat(McfJsonNodes.attribute(leaf, "name")).isEmpty();
    }

    @Test
    void objectLeaf_withAttributesAndValue() throws Exception {
        // A _PARAMETER_ node: has both a "name" attribute and a text value.
        JsonNode param = mapper.readTree("""
            {"_value_": "repo2.localhost", "_attribute_name": "hostname"}
            """);
        assertThat(McfJsonNodes.value(param)).contains("repo2.localhost");
        assertThat(McfJsonNodes.attribute(param, "name")).contains("hostname");
        assertThat(McfJsonNodes.attribute(param, "missing")).isEmpty();
    }

    @Test
    void configParamsMap_flattensParameterNodes_preservingOrder() throws Exception {
        JsonNode configuration = mapper.readTree("""
            {"_PARAMETER_": [
                {"_value_": "http", "_attribute_name": "protocol"},
                {"_value_": "repo2.localhost", "_attribute_name": "hostname"},
                {"_value_": "80", "_attribute_name": "port"}
            ]}
            """);
        Map<String, String> params = McfJsonNodes.configParamsMap(configuration);
        assertThat(params).containsExactly(
            Map.entry("protocol", "http"), Map.entry("hostname", "repo2.localhost"), Map.entry("port", "80"));
    }

    @Test
    void configParamsMap_nullNode_returnsEmptyMap() {
        assertThat(McfJsonNodes.configParamsMap(null)).isEmpty();
    }

    @Test
    void singleParameter_collapsedForm_isStillReadCorrectly() throws Exception {
        // Only one _PARAMETER_ -> collapsed to a singleton, not an array.
        JsonNode configuration = mapper.readTree("""
            {"_PARAMETER_": {"_value_": "solo", "_attribute_name": "onlyParam"}}
            """);
        assertThat(McfJsonNodes.configParamsMap(configuration)).containsExactly(Map.entry("onlyParam", "solo"));
    }
}
