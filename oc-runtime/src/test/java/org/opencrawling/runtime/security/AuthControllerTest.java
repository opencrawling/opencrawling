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
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {

    private static final String ISSUER = "https://idp.example/realms/opencrawling";

    private static AuthController controller(IdentityProperties identity, MockEnvironment environment) {
        ManagementAuthorization authorization = new ManagementAuthorization(new ManagementAuthorizationProperties(),
                CallerResolver.asserted(), identity);
        return new AuthController(identity, authorization, environment);
    }

    private static IdentityProperties jwt(String clientId) {
        IdentityProperties identity = new IdentityProperties();
        identity.setMode("jwt");
        identity.getAdminUi().setClientId(clientId);
        return identity;
    }

    @Test
    void theUiSignsInWithTheResourceServersIssuerUnlessAnotherIsSet() {
        MockEnvironment environment = new MockEnvironment().withProperty(AuthController.ISSUER_PROPERTY, ISSUER);

        AuthController.AuthConfig config = controller(jwt("opencrawling-admin-ui"), environment).config();
        assertThat(config.mode()).isEqualTo("jwt");
        assertThat(config.oidc()).isEqualTo(new AuthController.Oidc(ISSUER, "opencrawling-admin-ui", "openid profile"));

        IdentityProperties browserIssuer = jwt("opencrawling-admin-ui");
        browserIssuer.getAdminUi().setIssuer("http://localhost:8180/realms/opencrawling");
        browserIssuer.getAdminUi().setScope("openid profile groups");
        assertThat(controller(browserIssuer, environment).config().oidc())
                .isEqualTo(new AuthController.Oidc("http://localhost:8180/realms/opencrawling", "opencrawling-admin-ui",
                        "openid profile groups"));
    }

    @Test
    void theUiDoesNotSignInWithoutJwtModeAClientOrAnIssuer() {
        MockEnvironment environment = new MockEnvironment().withProperty(AuthController.ISSUER_PROPERTY, ISSUER);

        IdentityProperties none = new IdentityProperties();
        none.getAdminUi().setClientId("opencrawling-admin-ui");
        assertThat(controller(none, environment).config().mode()).isEqualTo("none");
        assertThat(controller(none, environment).config().oidc()).isNull();
        assertThat(controller(jwt(" "), environment).config().oidc()).isNull();
        assertThat(controller(jwt("opencrawling-admin-ui"), new MockEnvironment()).config().oidc()).isNull();
    }

    @Test
    void withoutATokenNobodyIsNamedAndWithoutAuthorizationEveryoneMayDoEverything() {
        AuthController.Me me = controller(new IdentityProperties(), new MockEnvironment()).me();
        assertThat(me.authenticated()).isFalse();
        assertThat(me.principal()).isNull();
        assertThat(me.managementAuthorization()).isFalse();
        assertThat(me.admin()).isTrue();
        assertThat(me.canCreateJobs()).isTrue();
    }
}
