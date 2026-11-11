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
package org.opencrawling.iceberg.output;

import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.junit.jupiter.api.Test;
import org.opencrawling.iceberg.output.catalog.IcebergCatalogFactory;
import org.opencrawling.iceberg.output.config.IcebergOutputProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class IcebergCatalogFactoryTest {

    @Test
    public void testCreateInMemoryCatalog() {
        IcebergOutputProperties props = new IcebergOutputProperties();
        props.setCatalogType("in-memory");
        props.setCatalogName("mem_test");
        props.setWarehouse("inmemory://test");

        Catalog catalog = IcebergCatalogFactory.createCatalog(props);

        assertThat(catalog).isInstanceOf(InMemoryCatalog.class);
    }

    @Test
    public void testCreateHadoopCatalog() {
        IcebergOutputProperties props = new IcebergOutputProperties();
        props.setCatalogType("hadoop");
        props.setCatalogName("hadoop_test");
        props.setWarehouse("target/hadoop-test-warehouse");

        Catalog catalog = IcebergCatalogFactory.createCatalog(props);

        assertThat(catalog).isInstanceOf(HadoopCatalog.class);
    }

    @Test
    public void testUnsupportedCatalogTypeThrowsException() {
        IcebergOutputProperties props = new IcebergOutputProperties();
        props.setCatalogType("unknown-catalog");

        assertThatThrownBy(() -> IcebergCatalogFactory.createCatalog(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Iceberg catalog type");
    }
}
