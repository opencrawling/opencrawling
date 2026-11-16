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
package org.opencrawling.runtime.api.v1;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JSON Merge Patch (RFC 7396): objects merge recursively, {@code null} removes a member, anything else replaces. */
public final class JsonMergePatch {

    private JsonMergePatch() {}

    public static JsonNode apply(JsonNode target, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            return patch;
        }
        ObjectNode result = target != null && target.isObject() ? ((ObjectNode) target).deepCopy() : JsonNodeFactory.instance.objectNode();
        List<Map.Entry<String, JsonNode>> members = new ArrayList<>(patch.properties());
        for (Map.Entry<String, JsonNode> member : members) {
            if (member.getValue().isNull()) {
                result.remove(member.getKey());
            } else {
                result.set(member.getKey(), apply(result.get(member.getKey()), member.getValue()));
            }
        }
        return result;
    }
}
