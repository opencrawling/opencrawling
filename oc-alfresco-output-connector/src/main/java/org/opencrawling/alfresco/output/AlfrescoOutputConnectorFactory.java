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
package org.opencrawling.alfresco.output;

import org.opencrawling.alfresco.output.config.AlfrescoOutputProperties;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;

import static org.opencrawling.core.connector.OutputContext.flag;
import static org.opencrawling.core.connector.OutputContext.integer;
import static org.opencrawling.core.connector.OutputContext.text;

/** An Alfresco Content Services output for one run, from its saved configuration (#319). */
public class AlfrescoOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(AlfrescoOutputConnectorFactory.class);

    @Override
    public String className() {
        return AlfrescoOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        AlfrescoOutputProperties properties = properties(config);
        AlfrescoOutputConnector connector = new AlfrescoOutputConnector(properties, null,
                context.service(PipelineProperties.class), null);
        connector.setPipelineMode(context.mode());
        log.info("Resolving dynamic Alfresco output connector for '{}' (parent node '{}', path prefix '{}')",
                properties.getUrl(), properties.getTargetParentNodeId(), properties.getTargetPathPrefix());
        return connector;
    }

    /** The settings a saved Alfresco output connector's configuration describes; unset keys keep their defaults. */
    static AlfrescoOutputProperties properties(Map<String, String> config) {
        AlfrescoOutputProperties p = new AlfrescoOutputProperties();
        p.setUrl(text(config, p.getUrl(), "url"));
        p.setUsername(text(config, p.getUsername(), "username"));
        p.setPassword(text(config, p.getPassword(), "password"));
        p.setAuthType(text(config, p.getAuthType(), "authType"));
        p.setBearerToken(text(config, p.getBearerToken(), "bearerToken"));
        p.setTargetParentNodeId(text(config, p.getTargetParentNodeId(), "targetParentNodeId"));
        p.setTargetPathPrefix(text(config, p.getTargetPathPrefix(), "targetPathPrefix"));
        p.setPathStrategy(text(config, p.getPathStrategy(), "pathStrategy"));
        p.setNodeType(text(config, p.getNodeType(), "nodeType"));
        String aspects = text(config, null, "aspects");
        if (aspects != null) {
            p.setAspects(Arrays.stream(aspects.split(",")).map(String::trim).filter(a -> !a.isEmpty()).toList());
        }
        p.setConflictStrategy(text(config, p.getConflictStrategy(), "conflictStrategy"));
        p.setSyncPermissions(flag(config, p.isSyncPermissions(), "syncPermissions"));
        p.setDefaultPermissionRole(text(config, p.getDefaultPermissionRole(), "defaultPermissionRole"));
        p.setInheritanceEnabled(flag(config, p.isInheritanceEnabled(), "inheritanceEnabled", "isInheritanceEnabled"));
        p.setTombstoneAction(text(config, p.getTombstoneAction(), "tombstoneAction"));
        p.setValidateAuthorities(flag(config, p.isValidateAuthorities(), "validateAuthorities"));
        p.setAuthorityValidationMode(text(config, p.getAuthorityValidationMode(), "authorityValidationMode"));
        p.setMissingAuthorityAction(text(config, p.getMissingAuthorityAction(), "missingAuthorityAction"));
        p.setAuthorityGapReportPath(text(config, p.getAuthorityGapReportPath(), "authorityGapReportPath"));
        p.setTimeoutSeconds(integer(config, p.getTimeoutSeconds(), "timeoutSeconds"));
        p.setParallelism(integer(config, p.getParallelism(), "parallelism"));
        return p;
    }
}
