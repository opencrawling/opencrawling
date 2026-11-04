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

import java.util.List;

/**
 * Which repository connector a configured {@code className} names, for jobs started through the job API.
 *
 * <p>The checks run in the order the job API has always used (for example {@code Alfresco} before {@code Cmis}),
 * so every class name resolves as before. What changed is the end of the chain: a name that matches nothing is
 * {@link #UNKNOWN}, and the connectors that only run in their own crawler service are {@link #NOT_RUNNABLE}. Both
 * used to fall back to the local file system connector silently.
 */
public enum RepositoryKind {
    ALFRESCO,
    ICEBERG,
    CMIS,
    DOXIS,
    SHAREPOINT,
    MFILES,
    FILENET,
    DOCUMENTUM,
    JDBC,
    GRPC_PUSH,
    FILESYSTEM,
    /** A known connector that the job API cannot build yet; it runs in its own crawler service. */
    NOT_RUNNABLE,
    UNKNOWN;

    /** Connectors configured through their crawler service's properties, not through the job API. */
    static final List<String> NOT_RUNNABLE_CLASSES = List.of(
            "CamundaRepositoryConnector",
            "FlowableRepositoryConnector",
            "ApsRepositoryConnector",
            "StormCrawlerRepositoryConnector",
            "OpenArchRepositoryConnector");

    public static RepositoryKind of(String className) {
        if (className == null || className.isBlank()) {
            return UNKNOWN;
        }
        if (className.contains("Alfresco")) {
            return ALFRESCO;
        } else if (className.contains("Iceberg")) {
            return ICEBERG;
        } else if (className.contains("Cmis") || className.contains("cmis")) {
            return CMIS;
        } else if (className.contains("DoxisRepositoryConnector")) {
            return DOXIS;
        } else if (className.contains("SharePointRepositoryConnector")) {
            return SHAREPOINT;
        } else if (className.contains("MFilesRepositoryConnector")) {
            return MFILES;
        } else if (className.contains("FileNetRepositoryConnector")) {
            return FILENET;
        } else if (className.contains("DocumentumRepositoryConnector")) {
            return DOCUMENTUM;
        } else if (className.contains("Jdbc")) {
            return JDBC;
        } else if (className.contains("GrpcPush") || className.contains("grpcPush")) {
            return GRPC_PUSH;
        }
        // The same rule the admin UI uses; the built-in default still carries the legacy FileConnector name.
        if (className.contains("filesystem.FileConnector") || className.contains("FileSystem")) {
            return FILESYSTEM;
        }
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        if (NOT_RUNNABLE_CLASSES.contains(simpleName)) {
            return NOT_RUNNABLE;
        }
        return UNKNOWN;
    }
}
