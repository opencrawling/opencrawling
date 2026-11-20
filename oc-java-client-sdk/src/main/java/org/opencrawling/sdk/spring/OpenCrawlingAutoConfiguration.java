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
package org.opencrawling.sdk.spring;

import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.auth.ClientCredentialsTokenProvider;
import org.opencrawling.sdk.auth.OAuth2Client;
import org.opencrawling.sdk.auth.TokenProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot AutoConfiguration for registering an OpenCrawlingClient bean automatically.
 */
@AutoConfiguration
@EnableConfigurationProperties(OpenCrawlingClientProperties.class)
public class OpenCrawlingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenCrawlingClient openCrawlingClient(
            OpenCrawlingClientProperties properties,
            ObjectProvider<TokenProvider> tokenProvider) {
        OpenCrawlingClient.Builder builder = OpenCrawlingClient.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .connectTimeout(properties.getConnectTimeout())
                .readTimeout(properties.getReadTimeout())
                .retryPolicy(properties.getRetry().toRetryPolicy());
        TokenProvider customProvider = tokenProvider != null ? tokenProvider.getIfAvailable() : null;
        OpenCrawlingClientProperties.OAuth2 oauth2 = properties.getOauth2();
        if (customProvider != null) {
            builder.tokenProvider(customProvider);
        } else if (oauth2.isConfigured()) {
            builder.tokenProvider(new ClientCredentialsTokenProvider(
                    new OAuth2Client(),
                    oauth2.getTokenUri(),
                    oauth2.getClientId(),
                    oauth2.getClientSecret(),
                    oauth2.getScope(),
                    oauth2.getAudience(),
                    null));
        } else {
            builder.bearerToken(properties.getBearerToken());
        }
        return builder.build();
    }
}
