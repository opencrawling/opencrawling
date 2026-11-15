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
package org.opencrawling.elasticsearch;

import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Elasticsearch output against a 9.x server. The inherited tests use the default {@code compatible-with=8}
 * (REST API compatibility mode); {@link #nativeCompatibilityNine()} covers {@code compatible-with=9}.
 */
@Testcontainers(disabledWithoutDocker = true)
class Elasticsearch9OutputConnectorIT extends AbstractElasticsearchOutputConnectorIT {

    @Container
    private static final ElasticsearchContainer ELASTICSEARCH = newContainer("9.4.3");

    @Override
    protected ElasticsearchContainer container() {
        return ELASTICSEARCH;
    }

    @Test
    void nativeCompatibilityNine() throws Exception {
        roundTripWithCompatibility(9);
    }
}
