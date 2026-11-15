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
package org.opencrawling.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Separate from IcebergRepositoryConnectorTest, whose fixture writes a fixed-path file to the shared in-memory store per test. */
class IcebergSourceInstanceTest {

    @Test
    void sourceInstanceIsTheCatalogUri() {
        assertThat(new IcebergRepositoryConnector("rest", "https://catalog.example.com/api/catalog", "s3://wh", "id")
            .sourceInstance()).isEqualTo("https://catalog.example.com/api/catalog");
        assertThat(new IcebergRepositoryConnector("in-memory", null, "inmemory://warehouse", "id").sourceInstance())
            .isNull();
    }
}
