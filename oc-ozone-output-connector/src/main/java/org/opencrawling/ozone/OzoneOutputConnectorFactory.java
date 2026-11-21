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
package org.opencrawling.ozone;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.ozone.config.OzoneOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** An Apache Ozone output for one run, from its saved configuration (#319). */
public class OzoneOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(OzoneOutputConnectorFactory.class);

    @Override
    public String className() {
        return OzoneOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        OzoneOutputProperties properties = properties(config);
        OzoneOutputConnector connector = new OzoneOutputConnector(properties, null,
                context.service(PipelineProperties.class), null);
        connector.setPipelineMode(context.mode());
        log.info("Resolving dynamic Apache Ozone output connector (Volume: {}, Bucket: {}, Strategy: {})",
                properties.getVolume(), properties.getBucket(), properties.getClientType());
        return connector;
    }

    /** The Apache Ozone settings a saved Ozone output connector's configuration describes. */
    public static OzoneOutputProperties properties(Map<String, String> config) {
        Map<String, String> safe = config != null ? config : Map.of();
        OzoneOutputProperties properties = new OzoneOutputProperties();
        properties.setVolume(safe.getOrDefault("volume", "opencrawling"));
        properties.setBucket(safe.getOrDefault("bucket", "migration"));
        properties.setClientType(safe.getOrDefault("clientType", "NATIVE"));
        properties.setOmHost(safe.getOrDefault("omHost", "localhost"));
        properties.setOmPort(OutputContext.integer(safe, 9862, "omPort"));
        properties.setS3Endpoint(safe.getOrDefault("s3Endpoint", "http://localhost:9878"));
        properties.setAccessKey(safe.getOrDefault("accessKey", "any"));
        properties.setSecretKey(safe.getOrDefault("secretKey", "any"));
        properties.setKeyStrategy(safe.getOrDefault("keyStrategy", properties.getKeyStrategy()));
        properties.setTombstoneAction(safe.getOrDefault("tombstoneAction", properties.getTombstoneAction()));
        return properties;
    }
}
