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
package org.opencrawling.hyland.cic.output;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.hyland.cic.config.HylandCicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** A Hyland Content Innovation Cloud output for one run, from its saved configuration (#319). */
public class HylandCicOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(HylandCicOutputConnectorFactory.class);

    @Override
    public String className() {
        return HylandCicOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        HylandCicProperties settings = HylandCicProperties.fromConfiguration(config);
        log.info("Resolving dynamic Hyland CIC output connector for base URL '{}' (target folder '{}')",
                settings.getBaseUrl(), settings.getTargetFolderPath());
        return new HylandCicOutputConnector(settings);
    }
}
