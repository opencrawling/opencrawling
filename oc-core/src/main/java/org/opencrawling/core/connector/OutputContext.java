/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
package org.opencrawling.core.connector;

import org.opencrawling.core.pipeline.PipelineMode;

import java.util.Map;
import java.util.function.Function;

/**
 * What an {@link OutputConnectorFactory} gets besides the saved configuration (#319).
 *
 * @param mode           the run's pipeline mode
 * @param pipelineOutput the runtime's configured output, the one its Kafka pipeline writes to; {@code null} if none
 * @param services       the runtime's shared services by type (embedding model, text extraction, Kafka template,
 *                       pipeline properties); answers {@code null} for a service the runtime does not have
 */
public record OutputContext(PipelineMode mode, OutputConnector pipelineOutput, Function<Class<?>, Object> services) {

    public OutputContext {
        services = services != null ? services : type -> null;
    }

    /** A context with no shared services, as in tests. */
    public static OutputContext of(PipelineMode mode) {
        return new OutputContext(mode, null, null);
    }

    /**
     * The runtime's service of this type.
     *
     * @return the service, or {@code null} if the runtime has none
     */
    public <T> T service(Class<T> type) {
        Object service = services.apply(type);
        return type.isInstance(service) ? type.cast(service) : null;
    }

    /** The runtime's configured output if it is of this type, else {@code null}. */
    public <T extends OutputConnector> T pipelineOutput(Class<T> type) {
        return type.isInstance(pipelineOutput) ? type.cast(pipelineOutput) : null;
    }

    /** Reading a saved configuration: the first non-blank value of these keys, trimmed. */
    public static String text(Map<String, String> configuration, String fallback, String... keys) {
        if (configuration != null) {
            for (String key : keys) {
                String value = configuration.get(key);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return fallback;
    }

    /** The first of these keys as a whole number, or {@code fallback} when absent or not a number. */
    public static int integer(Map<String, String> configuration, int fallback, String... keys) {
        String value = text(configuration, null, keys);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The first of these keys as a boolean, or {@code fallback} when absent. */
    public static boolean flag(Map<String, String> configuration, boolean fallback, String... keys) {
        String value = text(configuration, null, keys);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
