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
package org.opencrawling.seatunnel;

public final class SeaTunnelConstants {

    private SeaTunnelConstants() {
        // Utility class
    }

    public static final String DEFAULT_REST_URL = "http://localhost:8080";
    public static final String DEFAULT_JOB_NAME = "opencrawling_ingestion_pipeline";
    public static final String DEFAULT_JOB_MODE = "STREAMING";
    public static final int DEFAULT_CHECKPOINT_INTERVAL_MS = 5000;
    public static final int DEFAULT_PARALLELISM = 4;
    public static final String DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    public static final String DEFAULT_KAFKA_TOPIC = "opencrawling-embedded";
    public static final String DEFAULT_KAFKA_GROUP_ID = "seatunnel-ingestion-consumer";
    public static final String DEFAULT_TARGET_SINKS = "console";
    public static final int DEFAULT_DIMENSIONS = 1024;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    // Field names for OIS SeaTunnel catalog schema
    public static final String FIELD_ID = "id";
    public static final String FIELD_DOC_ID = "doc_id";
    public static final String FIELD_ACTION = "action";
    public static final String FIELD_URI = "uri";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_VECTOR = "vector";
    public static final String FIELD_ACL = "acl";
    public static final String FIELD_SECURITY_ALLOWED_READ = "security_allowed_read";
    public static final String FIELD_SECURITY_DENIED_READ = "security_denied_read";
    public static final String FIELD_SECURITY_INHERITANCE = "security_inheritance";
    public static final String FIELD_LAST_MODIFIED = "last_modified";
    public static final String FIELD_METADATA = "metadata";

    // SeaTunnel RowKind representation
    public static final String ROW_KIND_INSERT = "+I";
    public static final String ROW_KIND_DELETE = "-D";
}
