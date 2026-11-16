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
package org.opencrawling.sdk.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Asks for a dynamic job: crawl {@code start} (a folder) of {@code connector}.
 *
 * @param validateOnly only check that the job could start, without starting it
 * @param chunking     the job's own chunking ({@code chunkSize}, ...); null for the defaults
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DynamicJobRequest(String connector, String start, String name, Boolean validateOnly,
                                Map<String, Object> chunking) {

    public static DynamicJobRequest of(String connector, String start) {
        return new DynamicJobRequest(connector, start, null, null, null);
    }
}
