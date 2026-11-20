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
package org.opencrawling.sdk.v1.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * A configured connector.
 *
 * @param type          repository, output, transformation or authority
 * @param configuration its settings, with secret values masked as {@value #MASK}; null for callers who may not see
 *                      them
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Connector(String name, String description, String type, String className, Integer maxConnections,
                        Map<String, String> configuration) {

    /** What a secret value reads as. Sent back unchanged, it keeps the stored secret. */
    public static final String MASK = "********";

    /** This connector's definition, to change and send back with {@code replace}; masked secrets stay as they are. */
    public ConnectorInput toInput() {
        return new ConnectorInput(name, description, type, className, maxConnections, configuration);
    }
}
