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
package org.opencrawling.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.pipeline.PipelineProperties;
import org.opencrawling.s3.config.S3OutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** An AWS S3 output for one run, from its saved configuration (#319). */
public class S3OutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(S3OutputConnectorFactory.class);

    @Override
    public String className() {
        return S3OutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        S3OutputProperties properties = properties(config);
        S3OutputConnector connector = new S3OutputConnector(properties, null,
                context.service(PipelineProperties.class), null);
        connector.setPipelineMode(context.mode());
        log.info("Resolving dynamic AWS S3 output connector (Bucket: {}, Region: {}, Strategy: {})",
                properties.getBucket(), properties.getRegion(), properties.getKeyStrategy());
        return connector;
    }

    /** The AWS S3 settings a saved S3 output connector's configuration describes. */
    public static S3OutputProperties properties(Map<String, String> config) {
        Map<String, String> safe = config != null ? config : Map.of();
        S3OutputProperties properties = new S3OutputProperties();
        properties.setBucket(safe.getOrDefault("bucket", "enterprise-migration-lake"));
        properties.setRegion(safe.getOrDefault("region", "us-east-1"));
        properties.setEndpointOverride(safe.getOrDefault("endpointOverride", ""));
        properties.setPathStyleAccess(Boolean.parseBoolean(safe.getOrDefault("pathStyleAccess", "false")));
        properties.setCredentialsStrategy(safe.getOrDefault("credentialsStrategy", "DEFAULT_CHAIN"));
        properties.setAccessKey(safe.getOrDefault("accessKey", ""));
        properties.setSecretKey(safe.getOrDefault("secretKey", ""));
        properties.setSessionToken(safe.getOrDefault("sessionToken", ""));
        properties.setAssumeRoleArn(safe.getOrDefault("assumeRoleArn", ""));
        properties.setAssumeRoleExternalId(safe.getOrDefault("assumeRoleExternalId", ""));
        properties.setKeyStrategy(safe.getOrDefault("keyStrategy", "HIERARCHICAL"));
        properties.setKeyPrefix(safe.getOrDefault("keyPrefix", "migration/v1/"));
        properties.setStorageClass(safe.getOrDefault("storageClass", "INTELLIGENT_TIERING"));
        properties.setServerSideEncryption(safe.getOrDefault("serverSideEncryption", "SSE_S3"));
        properties.setKmsKeyId(safe.getOrDefault("kmsKeyId", ""));
        properties.setBucketKeyEnabled(Boolean.parseBoolean(safe.getOrDefault("bucketKeyEnabled", "true")));
        properties.setTombstoneAction(safe.getOrDefault("tombstoneAction", "DELETE_OBJECTS"));

        String customHeadersJson = safe.get("customHeaders");
        if (customHeadersJson != null && !customHeadersJson.isBlank()) {
            try {
                Map<String, String> parsed = new ObjectMapper().readValue(customHeadersJson,
                        new TypeReference<Map<String, String>>() {});
                if (parsed != null) {
                    properties.getCustomHeaders().putAll(parsed);
                }
            } catch (Exception ex) {
                log.warn("Failed to parse customHeaders JSON for S3 output connector: {}", ex.getMessage());
            }
        }
        safe.forEach((key, value) -> {
            if (key != null && key.startsWith("customHeader.") && value != null && !value.isBlank()) {
                String headerName = key.substring("customHeader.".length()).trim();
                if (!headerName.isBlank()) {
                    properties.getCustomHeaders().put(headerName, value.trim());
                }
            }
        });
        return properties;
    }
}
