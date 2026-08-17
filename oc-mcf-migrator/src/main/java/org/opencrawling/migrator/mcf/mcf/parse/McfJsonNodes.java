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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Navigates ManifoldCF's {@code ConfigurationNode}-shaped JSON, as produced by
 * {@code Configuration#toJSON()} / {@code writeNode(JSONWriter, ...)} in ManifoldCF core.
 *
 * <p>That serializer has two encodings for a node's children, chosen per-node at write time
 * depending on whether the children are homogeneously typed:
 * <ul>
 *   <li><b>Collapsed form</b> (all children share one type, or there's only one child):
 *       {@code {"<type>": {...}}} for a single child, or {@code {"<type>": [{...}, {...}]}}
 *       for more than one.</li>
 *   <li><b>Alternate form</b> (children of mixed types, e.g. a job's {@code id}/{@code
 *       description}/{@code pipelinestage}/... siblings, or a {@code startpoint}'s interleaved
 *       {@code include}/{@code exclude} children): {@code {"_children_": [{"_type_": "<type>",
 *       ...}, ...]}}, order-preserving.</li>
 * </ul>
 * A leaf with a value and no attributes/children serializes as a bare JSON scalar; anything else
 * becomes an object, with attributes as {@code _attribute_<name>} keys and value (if only present
 * alongside attributes/children) as {@code _value_}.
 *
 * <p>Both encodings occur throughout a single response (list endpoints are collapsed-form at the
 * top level since every item shares the same type; almost everything nested inside one item ends
 * up alternate-form because sibling fields differ in type) — callers must not assume either one.
 */
public final class McfJsonNodes {

    private static final String ATTRIBUTE_PREFIX = "_attribute_";
    private static final String VALUE_KEY = "_value_";
    private static final String CHILDREN_KEY = "_children_";
    private static final String TYPE_KEY = "_type_";

    private McfJsonNodes() {
    }

    /** All direct children of {@code node} whose type is {@code type}, in document order. */
    public static List<JsonNode> childrenOfType(JsonNode node, String type) {
        List<JsonNode> result = new ArrayList<>();
        if (node == null || !node.isObject()) {
            return result;
        }
        JsonNode children = node.get(CHILDREN_KEY);
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                JsonNode typeNode = child.get(TYPE_KEY);
                if (typeNode != null && type.equals(typeNode.asText())) {
                    result.add(child);
                }
            }
            return result;
        }
        JsonNode direct = node.get(type);
        if (direct == null) {
            return result;
        }
        if (direct.isArray()) {
            direct.forEach(result::add);
        } else {
            result.add(direct);
        }
        return result;
    }

    /** The first (and typically only) child of {@code node} with type {@code type}, if any. */
    public static Optional<JsonNode> firstChildOfType(JsonNode node, String type) {
        List<JsonNode> children = childrenOfType(node, type);
        return children.isEmpty() ? Optional.empty() : Optional.of(children.get(0));
    }

    /** The {@code _attribute_<name>} value on {@code node}, if present. */
    public static Optional<String> attribute(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode attr = node.get(ATTRIBUTE_PREFIX + name);
        return attr == null || attr.isNull() ? Optional.empty() : Optional.of(attr.asText());
    }

    /**
     * The node's own scalar value: either a bare JSON scalar (leaf with no attributes/children),
     * or the {@code _value_} key of an object node that also carries attributes/children.
     */
    public static Optional<String> value(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (node.isValueNode()) {
            return Optional.of(node.asText());
        }
        if (node.isObject()) {
            JsonNode value = node.get(VALUE_KEY);
            if (value != null && !value.isNull()) {
                return Optional.of(value.asText());
            }
        }
        return Optional.empty();
    }

    /** Convenience: {@link #value} of the first child of {@code type}, or empty. */
    public static Optional<String> childValue(JsonNode node, String type) {
        return firstChildOfType(node, type).flatMap(McfJsonNodes::value);
    }

    /**
     * Flattens a ManifoldCF {@code configuration} node (ConfigParams: repeated
     * {@code _PARAMETER_} children, each with a {@code name} attribute and a value) into a plain
     * string map, preserving declaration order. Returns an empty map if {@code configurationNode}
     * is absent (a connection with no configured parameters at all).
     */
    public static Map<String, String> configParamsMap(JsonNode configurationNode) {
        Map<String, String> result = new LinkedHashMap<>();
        if (configurationNode == null) {
            return result;
        }
        for (JsonNode param : childrenOfType(configurationNode, "_PARAMETER_")) {
            attribute(param, "name").ifPresent(name -> result.put(name, value(param).orElse("")));
        }
        return result;
    }
}
