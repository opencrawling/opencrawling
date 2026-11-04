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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decoupled crawler and consumer containers run the runtime without a web server. The security configuration
 * must stay out of those contexts: Spring Security's {@code HttpSecurity} only exists in servlet web applications.
 */
class NonWebStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class,
                    ServletWebSecurityAutoConfiguration.class))
            .withUserConfiguration(IdentitySecurityConfiguration.class);

    @Test
    void aNonWebContextStartsWithoutTheSecurityConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
            assertThat(context).doesNotHaveBean(CallerResolver.class);
        });
    }

    @Test
    void aNonWebContextStartsInJwtModeToo() {
        runner.withPropertyValues("opencrawling.security.identity.mode=jwt").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
        });
    }
}
