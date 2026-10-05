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
package org.opencrawling.ozone.client;

import java.io.InputStream;
import java.net.URI;

/**
 * Common client interface for interacting with Apache Ozone storage in Migration Mode.
 */
public interface OzoneStorageClient extends AutoCloseable {

    void connect() throws Exception;

    URI putObject(String key, InputStream content, long contentLength, String contentType) throws Exception;

    void putText(String key, String content, String contentType) throws Exception;

    InputStream getObject(String key) throws Exception;

    void deleteObject(String key) throws Exception;

    boolean exists(String key);

    @Override
    default void close() throws Exception {}
}
