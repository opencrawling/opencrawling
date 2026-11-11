/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.iceberg.output.catalog;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.hive.HiveCatalog;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.rest.RESTCatalog;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class IcebergCatalogFactory {

    private static final Logger log = LoggerFactory.getLogger(IcebergCatalogFactory.class);

    public static Catalog createCatalog(IcebergOutputProperties properties) {
        String catalogType = properties.getCatalogType() != null ? properties.getCatalogType().toLowerCase() : "in-memory";
        String catalogName = properties.getCatalogName() != null ? properties.getCatalogName() : "enterprise_lakehouse";
        String warehouse = properties.getWarehouse() != null ? properties.getWarehouse() : "tmp/iceberg-warehouse";
        String catalogUri = properties.getCatalogUri();

        log.info("Initializing Iceberg Catalog '{}' of type '{}' at URI '{}', warehouse '{}'",
                catalogName, catalogType, catalogUri, warehouse);

        Configuration conf = new Configuration();
        Map<String, String> catalogProperties = new HashMap<>();
        catalogProperties.put(CatalogProperties.WAREHOUSE_LOCATION, warehouse);
        if (catalogUri != null && !catalogUri.isBlank()) {
            catalogProperties.put(CatalogProperties.URI, catalogUri);
        }
        if (properties.getIoImpl() != null && !properties.getIoImpl().isBlank()) {
            catalogProperties.put(CatalogProperties.FILE_IO_IMPL, properties.getIoImpl());
        }

        // S3 storage options
        if (properties.getS3() != null) {
            IcebergOutputProperties.S3Properties s3 = properties.getS3();
            if (s3.getEndpoint() != null && !s3.getEndpoint().isBlank()) {
                catalogProperties.put("s3.endpoint", s3.getEndpoint());
            }
            if (s3.isPathStyleAccess()) {
                catalogProperties.put("s3.path-style-access", "true");
            }
            if (s3.getAccessKeyId() != null && !s3.getAccessKeyId().isBlank()) {
                catalogProperties.put("s3.access-key-id", s3.getAccessKeyId());
            }
            if (s3.getSecretAccessKey() != null && !s3.getSecretAccessKey().isBlank()) {
                catalogProperties.put("s3.secret-access-key", s3.getSecretAccessKey());
            }
            if (s3.getRegion() != null && !s3.getRegion().isBlank()) {
                catalogProperties.put("s3.region", s3.getRegion());
            }
        }

        Catalog catalog;
        switch (catalogType) {
            case "rest" -> {
                RESTCatalog restCatalog = new RESTCatalog();
                restCatalog.setConf(conf);
                restCatalog.initialize(catalogName, catalogProperties);
                catalog = restCatalog;
            }
            case "hive" -> {
                HiveCatalog hiveCatalog = new HiveCatalog();
                hiveCatalog.setConf(conf);
                hiveCatalog.initialize(catalogName, catalogProperties);
                catalog = hiveCatalog;
            }
            case "hadoop" -> {
                catalog = new HadoopCatalog(conf, warehouse);
            }
            case "in-memory" -> {
                InMemoryCatalog inMemoryCatalog = new InMemoryCatalog();
                inMemoryCatalog.initialize(catalogName, catalogProperties);
                catalog = inMemoryCatalog;
            }
            case "glue" -> {
                try {
                    Class<?> glueCatalogClass = Class.forName("org.apache.iceberg.aws.glue.GlueCatalog");
                    Catalog glueCatalog = (Catalog) glueCatalogClass.getDeclaredConstructor().newInstance();
                    try {
                        glueCatalogClass.getMethod("setConf", Configuration.class).invoke(glueCatalog, conf);
                    } catch (NoSuchMethodException ignored) {
                    }
                    glueCatalog.initialize(catalogName, catalogProperties);
                    catalog = glueCatalog;
                } catch (Exception e) {
                    throw new RuntimeException("AWS Glue Catalog dependencies are not available on classpath. Please add 'iceberg-aws' to dependencies.", e);
                }
            }
            case "jdbc" -> {
                try {
                    Class<?> jdbcCatalogClass = Class.forName("org.apache.iceberg.jdbc.JdbcCatalog");
                    Catalog jdbcCatalog = (Catalog) jdbcCatalogClass.getDeclaredConstructor().newInstance();
                    try {
                        jdbcCatalogClass.getMethod("setConf", Configuration.class).invoke(jdbcCatalog, conf);
                    } catch (NoSuchMethodException ignored) {
                    }
                    jdbcCatalog.initialize(catalogName, catalogProperties);
                    catalog = jdbcCatalog;
                } catch (Exception e) {
                    throw new RuntimeException("JDBC Catalog dependencies are not available on classpath. Please add 'iceberg-jdbc' to dependencies.", e);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported Iceberg catalog type: " + catalogType);
        }

        log.info("Successfully initialized Iceberg Catalog '{}' of type '{}'", catalogName, catalogType);
        return catalog;
    }
}
