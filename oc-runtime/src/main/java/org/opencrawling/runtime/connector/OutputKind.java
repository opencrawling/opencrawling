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
package org.opencrawling.runtime.connector;

/**
 * Which output connector a configured {@code className} names, for jobs started through the job API.
 *
 * <p>Only some outputs are built per job; every other class name uses the runtime's configured output connector
 * ({@link #ACTIVE}), as before. The checks run in the order the job API has always used.
 */
public enum OutputKind {
    QDRANT,
    VESPA,
    SEATUNNEL,
    OZONE,
    S3,
    /** The runtime's configured output connector ({@code spring.opencrawling.output.type}). */
    ACTIVE;

    public static OutputKind of(String className) {
        if (className == null) {
            return ACTIVE;
        }
        if (className.contains("Qdrant")) {
            return QDRANT;
        } else if (className.contains("Vespa")) {
            return VESPA;
        } else if (className.contains("SeaTunnel") || className.contains("seatunnel")) {
            return SEATUNNEL;
        } else if (className.contains("Ozone") || className.contains("ozone")) {
            return OZONE;
        } else if (className.contains("S3")
                || (className.contains("s3") && !className.contains("opensearch") && !className.contains("OpenSearch"))) {
            return S3;
        }
        return ACTIVE;
    }
}
