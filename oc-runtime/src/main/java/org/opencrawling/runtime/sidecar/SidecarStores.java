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
package org.opencrawling.runtime.sidecar;

import org.opencrawling.ozone.client.OzoneNativeStorageClient;
import org.opencrawling.ozone.client.OzoneS3GatewayStorageClient;
import org.opencrawling.ozone.client.OzoneStorageClient;
import org.opencrawling.ozone.config.OzoneOutputProperties;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.opencrawling.runtime.connector.ConnectorFactory;
import org.opencrawling.runtime.connector.OutputKind;
import org.opencrawling.s3.client.S3ClientFactory;
import org.opencrawling.s3.config.S3OutputProperties;

import java.util.Map;

/**
 * Where a registered Ozone or S3 output connector keeps its sidecars, and a read-only store on its bucket. The
 * settings are the ones the job API builds the connector with ({@link ConnectorFactory}); buckets are never created.
 */
final class SidecarStores {

    /**
     * Where the sidecars are.
     *
     * @param basePrefix the key prefix the connector writes under ({@code ""} for the whole bucket)
     * @param suffix     the sidecar suffix, {@code .ois.json} by default
     */
    record Layout(String basePrefix, String suffix) {}

    private SidecarStores() {
    }

    /** Whether the connector is an Ozone or S3 output connector. */
    static boolean supports(ConnectorDTO connector) {
        OutputKind kind = OutputKind.of(connector.className());
        return kind == OutputKind.OZONE || kind == OutputKind.S3;
    }

    static Layout layout(ConnectorDTO connector) {
        Map<String, String> config = config(connector);
        if (OutputKind.of(connector.className()) == OutputKind.S3) {
            S3OutputProperties properties = ConnectorFactory.s3Properties(config);
            String prefix = properties.getKeyPrefix() != null ? properties.getKeyPrefix().trim().replaceAll("^/+", "") : "";
            if (!prefix.isEmpty() && !prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
            return new Layout(prefix, properties.getSidecarSuffix());
        }
        return new Layout("", ConnectorFactory.ozoneProperties(config).getSidecarSuffix());
    }

    /** A read-only store on the connector's bucket. */
    static SidecarStore open(ConnectorDTO connector) throws Exception {
        Map<String, String> config = config(connector);
        if (OutputKind.of(connector.className()) == OutputKind.S3) {
            S3OutputProperties properties = ConnectorFactory.s3Properties(config);
            properties.setAutoCreateBucket(false);
            return new S3SidecarStore(new S3ClientFactory(properties).createS3Client(), properties.getBucket());
        }
        OzoneOutputProperties properties = ConnectorFactory.ozoneProperties(config);
        properties.setAutoCreateBucket(false);
        properties.setAllowInMemoryFallback(false);
        OzoneStorageClient client = "S3G".equalsIgnoreCase(properties.getClientType())
                ? new OzoneS3GatewayStorageClient(properties)
                : new OzoneNativeStorageClient(properties);
        try {
            client.connect();
        } catch (Exception e) {
            client.close();
            throw e;
        }
        return new OzoneSidecarStore(client);
    }

    private static Map<String, String> config(ConnectorDTO connector) {
        return connector.configuration() != null ? connector.configuration() : Map.of();
    }
}
