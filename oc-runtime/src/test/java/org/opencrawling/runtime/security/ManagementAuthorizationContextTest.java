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
package org.opencrawling.runtime.security;

import org.junit.jupiter.api.Test;
import org.opencrawling.runtime.api.JobController;
import org.opencrawling.runtime.api.JobVisibility;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Spring can build the authorization beans, and refuses to start when authorization is on without a verified caller. */
class ManagementAuthorizationContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ManagementAuthorization.class, JobVisibility.class)
            .withBean(JobController.class, () -> mock(JobController.class));

    @Configuration
    @EnableConfigurationProperties(IdentityProperties.class)
    static class WebIdentity {
        @Bean
        CallerResolver callerResolver(IdentityProperties identity) {
            return identity.isJwt() ? CallerResolver.verified(new JwtCallerMapper(identity.getJwt())) : CallerResolver.asserted();
        }
    }

    @Test
    void offByDefault() {
        runner.withUserConfiguration(WebIdentity.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ManagementAuthorization.class).isEnabled()).isFalse();
            assertThat(context.getBean(JobVisibility.class).canReadJob("any")).isTrue();
        });
    }

    @Test
    void onWithJwtAndAProtectedApiStarts() {
        runner.withUserConfiguration(WebIdentity.class)
                .withPropertyValues("opencrawling.security.management.enabled=true",
                        "opencrawling.security.management.admin-groups=platform-admins",
                        "opencrawling.security.identity.mode=jwt", "opencrawling.security.identity.protect-api=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ManagementAuthorizationProperties.class).getAdminGroups()).containsExactly("platform-admins");
                    assertThat(context.getBean(ManagementAuthorization.class).isAdmin()).as("no token, no admin").isFalse();
                });
    }

    @Test
    void onWithoutAProtectedApiFailsToStart() {
        runner.withUserConfiguration(WebIdentity.class)
                .withPropertyValues("opencrawling.security.management.enabled=true", "opencrawling.security.identity.mode=jwt")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("protect-api=true"));
    }

    @Test
    void onInAWorkerWithoutTheWebApiStarts() {
        runner.withPropertyValues("opencrawling.security.management.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
