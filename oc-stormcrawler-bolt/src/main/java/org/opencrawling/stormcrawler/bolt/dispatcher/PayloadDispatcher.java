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
package org.opencrawling.stormcrawler.bolt.dispatcher;

import java.io.Closeable;
import java.io.Serializable;
import java.util.Map;

/**
 * Strategy interface for dispatching Open Ingestion Standard (OIS) payloads
 * from OpenCrawlingBolt to OpenCrawling endpoints, event buses, or test sinks.
 */
public interface PayloadDispatcher extends Closeable, Serializable {

    /**
     * Initializes the dispatcher with topology configuration.
     *
     * @param conf Storm topology configuration map
     * @throws Exception if initialization fails
     */
    void init(Map<String, Object> conf) throws Exception;

    /**
     * Dispatches an OIS payload.
     *
     * @param documentId Document unique identifier (usually URL)
     * @param action Document lifecycle action ("UPSERT" or "DELETE")
     * @param jsonPayload Serialized OIS JSON payload
     * @throws Exception if dispatch fails
     */
    void dispatch(String documentId, String action, String jsonPayload) throws Exception;
}
