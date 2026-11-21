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
package org.opencrawling.openarch.output;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.openarch.output.config.OpenArchOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Map;

import static org.opencrawling.core.connector.OutputContext.flag;
import static org.opencrawling.core.connector.OutputContext.integer;
import static org.opencrawling.core.connector.OutputContext.text;

/** A Hyland OpenArch output for one run, from its saved configuration (#319). */
public class OpenArchOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(OpenArchOutputConnectorFactory.class);

    @Override
    public String className() {
        return OpenArchOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        OpenArchOutputProperties properties = properties(config);
        log.info("Resolving dynamic OpenArch output connector for '{}' (repository '{}')",
                properties.getEndpointUrl(), properties.getRepositoryId());
        return new OpenArchOutputConnector(properties, null, context.service(EmbeddingModel.class),
                context.service(TextExtractionService.class));
    }

    /** The settings a saved OpenArch output connector's configuration describes; unset keys keep their defaults. */
    static OpenArchOutputProperties properties(Map<String, String> config) {
        OpenArchOutputProperties p = new OpenArchOutputProperties();
        p.setEndpointUrl(text(config, p.getEndpointUrl(), "endpointUrl"));
        p.setRepositoryId(text(config, p.getRepositoryId(), "repositoryId"));
        p.setUsername(text(config, p.getUsername(), "username"));
        p.setPassword(text(config, p.getPassword(), "password"));
        p.setRootFolderId(text(config, p.getRootFolderId(), "rootFolderId"));
        p.setParquetBatchThreshold(integer(config, p.getParquetBatchThreshold(), "parquetBatchThreshold"));
        p.setEmbeddingsType(text(config, p.getEmbeddingsType(), "embeddingsType"));
        p.setDimensions(integer(config, p.getDimensions(), "dimensions"));
        p.setUpsert(flag(config, p.isUpsert(), "upsert"));
        p.setSyncAcls(flag(config, p.isSyncAcls(), "syncAcls"));
        p.setUploadBinaries(flag(config, p.isUploadBinaries(), "uploadBinaries"));
        return p;
    }
}
