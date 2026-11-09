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

import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The Elasticsearch output against an 8.x server (the 9.x Java client in 8.x compatibility mode). */
@Testcontainers(disabledWithoutDocker = true)
class Elasticsearch8OutputConnectorIT extends AbstractElasticsearchOutputConnectorIT {

    @Container
    private static final ElasticsearchContainer ELASTICSEARCH = newContainer("8.19.20");

    @Override
    protected ElasticsearchContainer container() {
        return ELASTICSEARCH;
    }
}
