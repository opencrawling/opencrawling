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
package org.opencrawling.runtime.authorization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationConfigurationTest {

    private final AuthorizationConfiguration configuration = new AuthorizationConfiguration();

    @ParameterizedTest
    @ValueSource(strings = {"metadata", " Metadata ", ""})
    void metadataModeSelectsTheMetadataProvider(String mode) {
        assertThat(configuration.metadataAuthorizationProvider(mode))
                .isInstanceOf(MetadataAuthorizationProvider.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"shadow", "intersection", "openfga", "OpenFGA"})
    void relationshipModesStopStartupWithoutTheOpenFgaModule(String mode) {
        assertThatThrownBy(() -> configuration.metadataAuthorizationProvider(mode))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenFGA authorization module");
    }

    @Test
    void unknownModeStopsStartup() {
        assertThatThrownBy(() -> configuration.metadataAuthorizationProvider("permissive"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown opencrawling.security.authorization.mode 'permissive'");
    }
}
