/*
 * Copyright © 2026 Michael Cizmar (piergiorgio@apache.org)
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
package org.opencrawling.qdrant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "spring.opencrawling.output.qdrant")
public record QdrantOutputProperties(
        @DefaultValue("localhost") String host,
        @DefaultValue("6334") int port,
        @DefaultValue("") String apiKey,
        @DefaultValue("enterprise_kb") String collectionName,
        @DefaultValue("1024") int dimensions,
        @DefaultValue("COSINE") Distance distance,
        @DefaultValue("NONE") Quantization quantization,
        @DefaultValue("false") boolean useTls,
        @DefaultValue("500") int batchSize
) {

    public enum Distance {
        COSINE, DOT, EUCLID
    }

    //Todo: support binary?
    public enum Quantization {
        NONE, SCALAR_INT8, BINARY
    }
}
