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
package org.opencrawling.runtime.dynamicjob;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * BPM document-transition events, when {@code opencrawling.events.bpm.enabled} is on. They run through the
 * {@link DynamicJobService}, which then exists even with dynamic jobs for agents switched off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = BpmEventProperties.PREFIX + ".enabled", havingValue = "true")
@EnableConfigurationProperties(BpmEventProperties.class)
public class BpmEventConfiguration {

    @Bean
    BpmEventAuthenticator bpmEventAuthenticator(BpmEventProperties properties) {
        properties.validate();
        return new BpmEventAuthenticator(properties, Clock.systemUTC());
    }

    @Bean
    public BpmEventService bpmEventService(DynamicJobService jobs, BpmEventProperties properties) {
        properties.validate();
        return new BpmEventService(jobs, properties, Clock.systemUTC());
    }
}
