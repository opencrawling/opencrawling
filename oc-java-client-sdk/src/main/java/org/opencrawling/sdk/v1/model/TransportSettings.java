/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
 * The runtime's internal gRPC/REST transport settings ({@code /api/v1/settings/transport}), kept losslessly as sent by
 * the runtime so unknown or future fields survive a read, change, and replace without being reset.
 */
public final class TransportSettings {

    private final ObjectNode json;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public TransportSettings(ObjectNode json) {
        this.json = Objects.requireNonNull(json, "json").deepCopy();
    }

    /** The settings document as sent to the runtime. */
    @JsonValue
    public ObjectNode json() {
        return json.deepCopy();
    }

    /** A field's value, or null when absent. */
    public JsonNode get(String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.deepCopy();
    }

    /** A copy with one field set; a null value removes it. */
    public TransportSettings with(String field, Object value) {
        ObjectNode copy = json.deepCopy();
        if (value == null) {
            copy.remove(field);
        } else {
            copy.set(field, toNode(value));
        }
        return new TransportSettings(copy);
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

    public Boolean grpcEnabled() { return bool("grpcEnabled"); }
    public String grpcHost() { return text("grpcHost"); }
    public Integer grpcPort() { return integer("grpcPort"); }
    public Boolean tlsEnabled() { return bool("tlsEnabled"); }
    public Boolean restFallbackEnabled() { return bool("restFallbackEnabled"); }

    public TransportSettings withGrpcEnabled(boolean value) { return with("grpcEnabled", value); }
    public TransportSettings withGrpcHost(String value) { return with("grpcHost", value); }
    public TransportSettings withGrpcPort(int value) { return with("grpcPort", value); }
    public TransportSettings withTlsEnabled(boolean value) { return with("tlsEnabled", value); }
    public TransportSettings withRestFallbackEnabled(boolean value) { return with("restFallbackEnabled", value); }

    private String text(String field) {
        return json.hasNonNull(field) ? json.get(field).asText() : null;
    }

    private Integer integer(String field) {
        return json.hasNonNull(field) ? json.get(field).asInt() : null;
    }

    private Boolean bool(String field) {
        return json.hasNonNull(field) ? json.get(field).asBoolean() : null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TransportSettings that && json.equals(that.json);
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }

    @Override
    public String toString() {
        return "TransportSettings" + json;
    }
}
