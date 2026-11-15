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
package org.opencrawling.runtime.api.v1;

import org.opencrawling.runtime.security.CallerResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Locale;

/** The Platform API's cross-cutting parts: problem details, request ids and idempotency keys. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiV1Configuration {

    @Bean
    public ApiV1ExceptionResolver apiV1ExceptionResolver(JsonMapper json) {
        return new ApiV1ExceptionResolver(json);
    }

    /** Before Spring Security, so refused requests carry a request id too. */
    @Bean
    public FilterRegistrationBean<RequestIdFilter> apiV1RequestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns(ApiV1.BASE, ApiV1.BASE + "/*");
        return registration;
    }

    /**
     * Where idempotency keys are kept: {@code memory} (the default; one runtime, lost on restart) or {@code redis}
     * (shared by every runtime instance, kept across restarts; uses the runtime's {@code spring.data.redis}
     * connection).
     */
    @Bean
    public IdempotencyStore apiV1IdempotencyStore(
            @Value("${opencrawling.api.idempotency.store:memory}") String store,
            @Value("${opencrawling.api.idempotency.retention:PT24H}") Duration retention,
            @Value("${opencrawling.api.idempotency.in-progress-timeout:PT10M}") Duration inProgressTimeout,
            @Value("${opencrawling.api.idempotency.max-entries:10000}") int maxEntries,
            @Value("${opencrawling.api.idempotency.max-entries-per-caller:1000}") int maxEntriesPerCaller,
            ObjectProvider<StringRedisTemplate> redis, JsonMapper json) {
        return switch (store.strip().toLowerCase(Locale.ROOT)) {
            case "memory" -> new InMemoryIdempotencyStore(retention, maxEntries, maxEntriesPerCaller, null);
            case "redis" -> {
                StringRedisTemplate template = redis.getIfAvailable();
                if (template == null) {
                    throw new IllegalStateException("opencrawling.api.idempotency.store=redis needs a Redis "
                            + "connection (spring.data.redis.*), and none is configured");
                }
                yield new RedisIdempotencyStore(template, json, retention, inProgressTimeout);
            }
            default -> throw new IllegalStateException("opencrawling.api.idempotency.store must be 'memory' or "
                    + "'redis', not '" + store + "'");
        };
    }

    /** Before Spring Security and the idempotency filter, so no oversized body is read by anything. */
    @Bean
    public FilterRegistrationBean<RequestSizeFilter> apiV1RequestSizeFilter(
            @Value("${opencrawling.api.max-request-bytes:1048576}") long maxRequestBytes, JsonMapper json) {
        FilterRegistrationBean<RequestSizeFilter> registration =
                new FilterRegistrationBean<>(new RequestSizeFilter(maxRequestBytes, json));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 12);
        registration.addUrlPatterns(ApiV1.BASE, ApiV1.BASE + "/*");
        return registration;
    }

    /** Only with opencrawling.api.require-if-match: unconditional changes of versioned resources answer 428. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "opencrawling.api.require-if-match", havingValue = "true")
    public FilterRegistrationBean<ConditionalRequestFilter> apiV1ConditionalRequestFilter(JsonMapper json) {
        FilterRegistrationBean<ConditionalRequestFilter> registration =
                new FilterRegistrationBean<>(new ConditionalRequestFilter(json));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 5);
        registration.addUrlPatterns(ApiV1.BASE + "/*");
        return registration;
    }

    /** Before Spring Security, so a refused call to a legacy endpoint also learns its successor. */
    @Bean
    public FilterRegistrationBean<LegacyDeprecationFilter> legacyDeprecationFilter() {
        FilterRegistrationBean<LegacyDeprecationFilter> registration =
                new FilterRegistrationBean<>(new LegacyDeprecationFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    /** After Spring Security, so the key is scoped to the verified caller. */
    @Bean
    public FilterRegistrationBean<IdempotencyFilter> apiV1IdempotencyFilter(IdempotencyStore store, JsonMapper json,
            ObjectProvider<CallerResolver> callerResolver,
            @Value("${opencrawling.api.idempotency.max-stored-bytes:262144}") int maxStoredBytes,
            @Value("${opencrawling.api.max-request-bytes:1048576}") long maxRequestBytes) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(
                new IdempotencyFilter(store, json, callerResolver::getIfAvailable, maxStoredBytes, maxRequestBytes));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10);
        registration.addUrlPatterns(ApiV1.BASE + "/*");
        return registration;
    }
}
