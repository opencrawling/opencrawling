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
package org.opencrawling.runtime.connector;

/**
 * Which storage output a configured {@code className} names, for the features that read an output's store directly
 * (OIS sidecars, Iceberg table access).
 *
 * <p>Job runs no longer use it: {@link ConnectorFactory} builds every output through its
 * {@link org.opencrawling.core.connector.OutputConnectorFactory} (#319). {@link #ACTIVE} means none of these.
 */
public enum OutputKind {
    QDRANT,
    VESPA,
    SEATUNNEL,
    OZONE,
    S3,
    ICEBERG,
    HYLAND_CIC,
    DOXIS,
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
        } else if (className.contains("Iceberg") || className.contains("iceberg")) {
            return ICEBERG;
        } else if (className.contains("DoxisOutputConnector")) {
            return DOXIS;
        } else if (className.contains("HylandCic") || className.contains("HylandCIC") || className.contains("hyland-cic")) {
            return HYLAND_CIC;
        }
        return ACTIVE;
    }
}
