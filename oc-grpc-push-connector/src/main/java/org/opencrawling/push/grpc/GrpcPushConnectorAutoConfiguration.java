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
package org.opencrawling.push.grpc;

import org.opencrawling.push.v1.IngestionStatus;
import org.opencrawling.push.v1.PushDocumentResponse;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the gRPC Push Repository Connector.
 */
@AutoConfiguration
@EnableConfigurationProperties(GrpcPushProperties.class)
@ConditionalOnProperty(name = "opencrawling.connector.grpc-push.enabled", havingValue = "true", matchIfMissing = true)
public class GrpcPushConnectorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GrpcSecurityInterceptor grpcSecurityInterceptor(GrpcPushProperties properties) {
        return new GrpcSecurityInterceptor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenTelemetryServerInterceptor openTelemetryServerInterceptor() {
        return new OpenTelemetryServerInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public PushDocumentDispatcher defaultPushDocumentDispatcher() {
        return request -> PushDocumentResponse.newBuilder()
                .setStatus(IngestionStatus.ERROR_INTERNAL)
                .setMessage("No active PushDocumentDispatcher registered in context")
                .setProcessedAt(System.currentTimeMillis())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public OisPushServiceImpl oisPushService(PushDocumentDispatcher dispatcher) {
        return new OisPushServiceImpl(dispatcher);
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcPushServerManager grpcPushServerManager(
            GrpcPushProperties properties,
            OisPushServiceImpl oisPushService,
            GrpcSecurityInterceptor securityInterceptor,
            OpenTelemetryServerInterceptor otelInterceptor) {
        return new GrpcPushServerManager(properties, oisPushService, securityInterceptor, otelInterceptor);
    }

    @Bean
    @ConditionalOnMissingBean
    public GrpcPushRepositoryConnector grpcPushRepositoryConnector(
            GrpcPushProperties properties,
            GrpcPushServerManager serverManager) {
        return new GrpcPushRepositoryConnector(properties, serverManager);
    }
}
