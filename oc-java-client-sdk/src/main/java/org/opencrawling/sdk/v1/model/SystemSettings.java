/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
package org.opencrawling.sdk.v1.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * The runtime's crawling and extraction settings, kept exactly as the runtime sent them: every field survives a read,
 * change and replace, including fields this SDK version does not know, so replacing the settings never resets
 * settings you did not touch. Common fields have typed accessors; {@link #get} and {@link #with} reach any field.
 * Immutable; each {@code with...} returns a copy.
 */
public final class SystemSettings {

    private final ObjectNode json;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SystemSettings(ObjectNode json) {
        this.json = Objects.requireNonNull(json, "json").deepCopy();
    }

    /** The settings document, as sent to the runtime. */
    @JsonValue
    public ObjectNode json() {
        return json.deepCopy();
    }

    /** A field's value, or null when absent. */
    public JsonNode get(String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.deepCopy();
    }

    /** A copy with one field set; a null value removes it, so the runtime applies its default. */
    public SystemSettings with(String field, Object value) {
        ObjectNode copy = json.deepCopy();
        if (value == null) {
            copy.remove(field);
        } else {
            copy.set(field, toNode(value));
        }
        return new SystemSettings(copy);
    }

    private static JsonNode toNode(Object value) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        return switch (value) {
            case JsonNode node -> node;
            case String text -> nodes.textNode(text);
            case Integer number -> nodes.numberNode(number);
            case Long number -> nodes.numberNode(number);
            case Double number -> nodes.numberNode(number);
            case Boolean bool -> nodes.booleanNode(bool);
            default -> nodes.textNode(value.toString());
        };
    }

    public String embeddingProvider() { return text("embeddingProvider"); }
    public String ollamaBaseUrl() { return text("ollamaBaseUrl"); }
    public String ollamaModel() { return text("ollamaModel"); }
    public Integer vectorDimensions() { return integer("vectorDimensions"); }
    public String chunkerType() { return text("chunkerType"); }
    /** Chunk size in tokens (1 to 8192) for jobs that do not set their own chunking. */
    public Integer chunkSize() { return integer("chunkSize"); }
    public String claimCheckStore() { return text("claimCheckStore"); }
    public String tikaEngine() { return text("tikaEngine"); }
    public Boolean tikaForkEnabled() { return json.hasNonNull("tikaForkEnabled") ? json.get("tikaForkEnabled").asBoolean() : null; }
    public Long tikaTimeoutMs() { return json.hasNonNull("tikaTimeoutMs") ? json.get("tikaTimeoutMs").asLong() : null; }

    public SystemSettings withChunkSize(int value) { return with("chunkSize", value); }
    public SystemSettings withOllamaModel(String value) { return with("ollamaModel", value); }
    public SystemSettings withOllamaBaseUrl(String value) { return with("ollamaBaseUrl", value); }
    public SystemSettings withVectorDimensions(int value) { return with("vectorDimensions", value); }
    public SystemSettings withTikaTimeoutMs(long value) { return with("tikaTimeoutMs", value); }

    private String text(String field) {
        return json.hasNonNull(field) ? json.get(field).asText() : null;
    }

    private Integer integer(String field) {
        return json.hasNonNull(field) ? json.get(field).asInt() : null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SystemSettings that && json.equals(that.json);
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }

    @Override
    public String toString() {
        return "SystemSettings" + json;
    }
}
