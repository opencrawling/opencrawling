/*
 * Copyright © 2026 Piergiorgio Lucidi (plucidi@ziaconsulting.com)
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
package org.opencrawling.sdk;

import org.opencrawling.sdk.models.ConnectionCheckResponse;
import org.opencrawling.sdk.models.ConnectorRequest;
import org.opencrawling.sdk.models.ConnectorResponse;

import java.util.List;

/**
 * Client for managing repository, output, and transformation connectors.
 */
public interface ConnectorClient {

    /**
     * Lists connectors of a given type ("repository", "output", "transformation").
     */
    List<ConnectorResponse> list(String type);

    /**
     * Creates or updates a connector configuration.
     */
    void create(ConnectorRequest request);

    /**
     * Checks connection consistency and reliability for a given connector configuration.
     */
    ConnectionCheckResponse checkConnection(ConnectorRequest request);

    /**
     * Deletes a connector configuration by name/id.
     */
    void delete(String name);
}
