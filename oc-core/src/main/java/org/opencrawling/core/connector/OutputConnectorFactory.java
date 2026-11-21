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

import java.util.Map;

/**
 * Creates an {@link OutputConnector} for one run, from the output's saved configuration (#319).
 *
 * <p>Every output module provides one, discovered through {@link java.util.ServiceLoader}
 * ({@code META-INF/services/org.opencrawling.core.connector.OutputConnectorFactory}) and matched by
 * {@link #className()} against the {@code className} of the saved connector. Each call returns a new connector with
 * its own clients, so two runs can write to two instances of the same output at the same time; the runtime releases
 * it ({@link OutputConnector#disconnect()}) when the run ends.
 *
 * <p>A vector store whose documents the runtime's Kafka pipeline writes in RAG mode must build a connector that writes
 * directly unless {@link OutputContext#pipelineOutput()} already writes to the same destination: Kafka messages carry
 * no output route, so the pipeline only ever reaches the runtime's own output.
 */
public interface OutputConnectorFactory {

    /**
     * @return the fully qualified class name of the connector this factory creates
     */
    String className();

    /**
     * Creates a connector for one run.
     *
     * @param configuration the saved connector configuration ({@code connectors.json})
     * @param context       the run's pipeline mode and the runtime's shared services
     * @throws IllegalArgumentException if the configuration is invalid
     */
    OutputConnector create(Map<String, String> configuration, OutputContext context);
}
