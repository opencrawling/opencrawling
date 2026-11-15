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
package org.opencrawling.authorization.openfga;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFgaAuthorizationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenFgaAuthorizationConfiguration.class);

    @Test
    void metadataModeCreatesNothing() {
        runner.run(context -> assertThat(context).doesNotHaveBean(OpenFgaAuthorizationProvider.class)
                .doesNotHaveBean(AuthorizationSyncConsumer.class));
        runner.withPropertyValues("opencrawling.security.authorization.mode=metadata")
                .run(context -> assertThat(context).doesNotHaveBean(OpenFgaAuthorizationProvider.class));
    }

    @Test
    void relationshipModesRequireAPinnedStoreUnlessAutoProvisioning() {
        runner.withPropertyValues("opencrawling.security.authorization.mode= OpenFGA ")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("openfga.store-id is required"));
        runner.withPropertyValues("opencrawling.security.authorization.mode=shadow")
                .run(context -> assertThat(context).hasFailed());
    }
}
