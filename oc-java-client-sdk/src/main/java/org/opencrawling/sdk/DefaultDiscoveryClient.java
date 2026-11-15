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
package org.opencrawling.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import org.opencrawling.sdk.http.HttpTransport;
import org.opencrawling.sdk.models.DiscoveryConnectorInfo;
import org.opencrawling.sdk.models.DiscoveryDryRunRequest;
import org.opencrawling.sdk.models.DiscoveryDryRunView;
import org.opencrawling.sdk.models.DiscoveryTreeRequest;
import org.opencrawling.sdk.models.DiscoveryTreeView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default implementation of DiscoveryClient communicating with /api/discovery/**.
 */
public class DefaultDiscoveryClient implements DiscoveryClient {

    private final HttpTransport transport;

    public DefaultDiscoveryClient(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public List<DiscoveryConnectorInfo> listConnectors() {
        return listConnectors(null, null);
    }

    @Override
    public List<DiscoveryConnectorInfo> listConnectors(String userPrincipal, String userRoles) {
        StringBuilder uri = new StringBuilder("/api/discovery/connectors");
        List<String> queryParams = new ArrayList<>();
        addQueryParam(queryParams, "userPrincipal", userPrincipal);
        addQueryParam(queryParams, "userRoles", userRoles);
        if (!queryParams.isEmpty()) {
            uri.append("?").append(String.join("&", queryParams));
        }
        return transport.execute("GET", uri.toString(), null, new TypeReference<List<DiscoveryConnectorInfo>>() {});
    }

    @Override
    public DiscoveryTreeView exploreTree(String connector, DiscoveryTreeRequest request) {
        Objects.requireNonNull(connector, "connector");
        DiscoveryTreeRequest req = request != null ? request : DiscoveryTreeRequest.builder().build();
        StringBuilder uri = new StringBuilder("/api/discovery/")
                .append(URLEncoder.encode(connector, StandardCharsets.UTF_8))
                .append("/tree");

        List<String> queryParams = new ArrayList<>();
        addQueryParam(queryParams, "start", req.start());
        if (req.depth() != null) {
            addQueryParam(queryParams, "depth", String.valueOf(req.depth()));
        }
        if (req.maxNodes() != null) {
            addQueryParam(queryParams, "maxNodes", String.valueOf(req.maxNodes()));
        }
        if (req.includeDocuments() != null) {
            addQueryParam(queryParams, "includeDocuments", String.valueOf(req.includeDocuments()));
        }
        addQueryParam(queryParams, "userPrincipal", req.userPrincipal());
        addQueryParam(queryParams, "userRoles", req.userRoles());

        if (!queryParams.isEmpty()) {
            uri.append("?").append(String.join("&", queryParams));
        }

        return transport.execute("GET", uri.toString(), null, DiscoveryTreeView.class);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DryRunPayload(String scope, Integer sampleSize, Integer maxScan) {}

    @Override
    public DiscoveryDryRunView dryRun(String connector, DiscoveryDryRunRequest request) {
        Objects.requireNonNull(connector, "connector");
        DiscoveryDryRunRequest req = request != null ? request : DiscoveryDryRunRequest.builder().build();
        StringBuilder uri = new StringBuilder("/api/discovery/")
                .append(URLEncoder.encode(connector, StandardCharsets.UTF_8))
                .append("/dry-run");

        List<String> queryParams = new ArrayList<>();
        addQueryParam(queryParams, "userPrincipal", req.userPrincipal());
        addQueryParam(queryParams, "userRoles", req.userRoles());
        if (!queryParams.isEmpty()) {
            uri.append("?").append(String.join("&", queryParams));
        }

        DryRunPayload payload = new DryRunPayload(req.scope(), req.sampleSize(), req.maxScan());
        return transport.execute("POST", uri.toString(), payload, DiscoveryDryRunView.class);
    }

    private static void addQueryParam(List<String> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }
}
