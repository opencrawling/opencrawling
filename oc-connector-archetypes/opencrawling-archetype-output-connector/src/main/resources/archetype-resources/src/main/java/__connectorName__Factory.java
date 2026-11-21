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
package ${package};

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;

import java.util.Map;

/**
 * Builds a ${connectorName} for one job run from its saved configuration, so each run can write to its own target.
 * Registered in {@code META-INF/services/org.opencrawling.core.connector.OutputConnectorFactory}; a job that names
 * an output no factory builds does not start.
 */
public class ${connectorName}Factory implements OutputConnectorFactory {

    @Override
    public String className() {
        return ${connectorName}.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> configuration, OutputContext context) {
        ${connectorName}Configuration settings = new ${connectorName}Configuration();
        settings.setTargetEndpoint(OutputContext.text(configuration, settings.getTargetEndpoint(), "targetEndpoint"));
        settings.setIndexName(OutputContext.text(configuration, settings.getIndexName(), "indexName"));
        settings.setBatchSize(OutputContext.integer(configuration, settings.getBatchSize(), "batchSize"));
        settings.setRetryCount(OutputContext.integer(configuration, settings.getRetryCount(), "retryCount"));
        ${connectorName} connector = new ${connectorName}(settings, new ${connectorName}Exporter(settings));
        try {
            connector.connect();
        } catch (Exception e) {
            throw new IllegalStateException("Could not connect to " + settings.getTargetEndpoint(), e);
        }
        return connector;
    }
}
