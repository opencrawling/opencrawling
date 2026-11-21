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
package org.opencrawling.iceberg.output;

import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** An Apache Iceberg output for one run, from its saved configuration (#319). */
public class IcebergOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(IcebergOutputConnectorFactory.class);

    @Override
    public String className() {
        return IcebergOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        IcebergOutputProperties properties = properties(config);
        log.info("Resolving dynamic Iceberg output connector for catalog '{}' (table strategy: {})",
                properties.getCatalogName(), properties.getTableStrategy());
        return new IcebergOutputConnector(properties);
    }

    /** The Iceberg settings a saved Iceberg output connector's configuration describes. */
    public static IcebergOutputProperties properties(Map<String, String> config) {
        Map<String, String> safe = config != null ? config : Map.of();
        IcebergOutputProperties props = new IcebergOutputProperties();
        String catalogType = OutputContext.text(safe, null, "catalogType", "catalog.type");
        if (catalogType != null) {
            props.setCatalogType(catalogType);
        } else {
            props.setCatalogType("rest");
        }
        String catalogName = OutputContext.text(safe, null, "catalogName", "catalog.name");
        props.setCatalogName(catalogName != null ? catalogName : "opencrawling_catalog");
        String catalogUri = OutputContext.text(safe, null, "catalogUri", "catalog.uri");
        if (catalogUri != null) {
            props.setCatalogUri(catalogUri);
        } else if ("rest".equalsIgnoreCase(props.getCatalogType())) {
            props.setCatalogUri("http://localhost:8181");
        }
        String warehouse = OutputContext.text(safe, null, "warehouse");
        props.setWarehouse(warehouse != null ? warehouse : "s3://warehouse");
        String namespace = OutputContext.text(safe, null, "namespace");
        props.setNamespace(namespace != null ? namespace : "enterprise_data");
        String tableName = OutputContext.text(safe, null, "tableName", "table.name");
        props.setTableName(tableName != null ? tableName : "documents");
        String tableStrategy = OutputContext.text(safe, null, "tableStrategy", "table.strategy");
        if (tableStrategy != null) {
            try {
                props.setTableStrategy(IcebergOutputProperties.TableStrategy.valueOf(tableStrategy.toUpperCase()));
            } catch (Exception ignored) {}
        }
        String partitionStrategy = OutputContext.text(safe, null, "partitionStrategy", "partitionBy", "partition.strategy");
        if (partitionStrategy != null) {
            try {
                props.setPartitionBy(IcebergOutputProperties.PartitionStrategy.valueOf(partitionStrategy.toUpperCase()));
            } catch (Exception ignored) {}
        }
        String autoCreate = OutputContext.text(safe, null, "autoCreateTable", "auto.create.table");
        if (autoCreate != null) {
            props.setAutoCreateTable(Boolean.parseBoolean(autoCreate));
        }
        String autoSchemaEvolution = OutputContext.text(safe, null, "autoSchemaEvolution", "auto.schema.evolution");
        if (autoSchemaEvolution != null) {
            props.setAutoSchemaEvolution(Boolean.parseBoolean(autoSchemaEvolution));
        }
        String writeFormat = OutputContext.text(safe, null, "writeFormat", "write.format");
        if (writeFormat != null) {
            props.setWriteFormat(writeFormat);
        }
        String compressionCodec = OutputContext.text(safe, null, "compressionCodec", "compression.codec");
        if (compressionCodec != null) {
            props.setCompressionCodec(compressionCodec);
        }
        String ioImpl = OutputContext.text(safe, null, "ioImpl", "io.impl", "fileIoImpl");
        if (ioImpl != null) {
            props.setIoImpl(ioImpl);
        }

        IcebergOutputProperties.S3Properties s3 = props.getS3();
        String s3Endpoint = OutputContext.text(safe, null, "s3Endpoint", "s3.endpoint");
        if (s3Endpoint != null) {
            s3.setEndpoint(s3Endpoint);
        }
        String s3Region = OutputContext.text(safe, null, "s3Region", "s3.region");
        if (s3Region != null) {
            s3.setRegion(s3Region);
        }
        String s3AccessKeyId = OutputContext.text(safe, null, "s3AccessKeyId", "s3.access-key-id", "s3AccessKey");
        if (s3AccessKeyId != null) {
            s3.setAccessKeyId(s3AccessKeyId);
        }
        String s3SecretAccessKey = OutputContext.text(safe, null, "s3SecretAccessKey", "s3.secret-access-key", "s3SecretKey");
        if (s3SecretAccessKey != null) {
            s3.setSecretAccessKey(s3SecretAccessKey);
        }
        String s3PathStyle = OutputContext.text(safe, null, "s3PathStyleAccess", "s3.path-style-access", "pathStyleAccess");
        if (s3PathStyle != null) {
            s3.setPathStyleAccess(Boolean.parseBoolean(s3PathStyle));
        }
        return props;
    }
}
