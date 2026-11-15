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
package org.opencrawling.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.sdk.auth.TokenProvider;
import org.opencrawling.sdk.http.HttpTransport;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Default implementation of OpenCrawlingClient encapsulating all API domain sub-clients.
 */
public class DefaultOpenCrawlingClient implements OpenCrawlingClient {

    private final HttpTransport transport;
    private final JobClient jobClient;
    private final ConnectorClient connectorClient;
    private final SystemClient systemClient;
    private final ObservabilityClient observabilityClient;
    private final NarrativizationCopilotClient narrativizationCopilotClient;
    private final ReportClient reportClient;
    private final OpenApiClient openApiClient;
    private final DiscoveryClient discoveryClient;
    private final AuthClient authClient;

    public DefaultOpenCrawlingClient(
            HttpClient httpClient,
            String baseUrl,
            String apiKey,
            String bearerToken,
            Duration readTimeout,
            ObjectMapper objectMapper) {
        this(httpClient, baseUrl, apiKey, TokenProvider.of(bearerToken), readTimeout, objectMapper);
    }

    public DefaultOpenCrawlingClient(
            HttpClient httpClient,
            String baseUrl,
            String apiKey,
            TokenProvider tokenProvider,
            Duration readTimeout,
            ObjectMapper objectMapper) {
        this.transport = new HttpTransport(httpClient, baseUrl, apiKey, tokenProvider, readTimeout, objectMapper);
        this.jobClient = new DefaultJobClient(transport);
        this.connectorClient = new DefaultConnectorClient(transport);
        this.systemClient = new DefaultSystemClient(transport);
        this.observabilityClient = new DefaultObservabilityClient(transport);
        this.narrativizationCopilotClient = new DefaultNarrativizationCopilotClient(transport);
        this.reportClient = new DefaultReportClient(transport);
        this.openApiClient = new DefaultOpenApiClient(transport);
        this.discoveryClient = new DefaultDiscoveryClient(transport);
        this.authClient = new DefaultAuthClient(transport);
    }

    @Override
    public JobClient jobs() {
        return jobClient;
    }

    @Override
    public ConnectorClient connectors() {
        return connectorClient;
    }

    @Override
    public SystemClient system() {
        return systemClient;
    }

    @Override
    public ObservabilityClient observability() {
        return observabilityClient;
    }

    @Override
    public NarrativizationCopilotClient narrativization() {
        return narrativizationCopilotClient;
    }

    @Override
    public ReportClient reports() {
        return reportClient;
    }

    @Override
    public OpenApiClient openApi() {
        return openApiClient;
    }

    @Override
    public DiscoveryClient discovery() {
        return discoveryClient;
    }

    @Override
    public AuthClient auth() {
        return authClient;
    }
}
