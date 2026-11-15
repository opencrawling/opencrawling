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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagementAuthorizationInterceptorTest {

    private final ManagementAuthorizationInterceptor interceptor =
            new ManagementAuthorizationInterceptor(ManagementAuthorizationTest.enabled(List.of("hr-team")));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private boolean call(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/connectors", "POST, /api/connectors/check", "DELETE, /api/connectors/Alfresco",
            "GET, /api/system/settings", "POST, /api/system/settings", "PUT, /api/system/settings/transport",
            "GET, /api/v1/admin/settings/transport", "GET, /api/system/logs", "POST, /api/mcf-migration/apply",
            "POST, /api/vespa/query", "GET, /api/vespa/health", "GET, /api/observability/metrics",
            "POST, /api/reports/query",
            // what Spring routes the same way must be refused the same way
            "POST, /api/%76espa/query", "POST, /api/vespa;x=1/query", "GET, /api/system/settings/"
    })
    void adminRoutesRefuseEveryoneButAdmins(String method, String path) {
        ManagementAuthorizationTest.authenticate("hr.lead", List.of("hr-team"), null);
        assertThatThrownBy(() -> call(method, path)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");

        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> call(method, path)).isInstanceOf(ResponseStatusException.class);

        ManagementAuthorizationTest.authenticate("ops", List.of("opencrawling-admin"), null);
        assertThat(call(method, path)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"GET, /api/connectors", "GET, /api/connectors/repository", "POST, /api/transformation/copilot/generate"})
    void creatorsMayListConnectorsAndUseTheCopilot(String method, String path) {
        ManagementAuthorizationTest.authenticate("hr.lead", List.of("hr-team"), null);
        assertThat(call(method, path)).isTrue();

        ManagementAuthorizationTest.authenticate("intern", List.of("interns"), null);
        assertThatThrownBy(() -> call(method, path)).isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @CsvSource({"GET, /api/jobs", "POST, /api/jobs/7/start", "GET, /api/reports", "GET, /api/system/status",
            "GET, /api/observability/traces/7", "POST, /api/jobs/dynamic", "GET, /api/discovery/connectors"})
    void otherRoutesAreLeftToTheirControllers(String method, String path) {
        ManagementAuthorizationTest.authenticate("intern", List.of("interns"), null);
        assertThat(call(method, path)).isTrue();
    }

    @Test
    void routesAreMatchedWithinTheApplicationsContextPath() {
        ManagementAuthorizationTest.authenticate("hr.lead", List.of("hr-team"), null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/opencrawling/api/vespa/query");
        request.setContextPath("/opencrawling");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void offEveryRouteIsOpen() {
        ManagementAuthorizationProperties off = new ManagementAuthorizationProperties();
        ManagementAuthorizationInterceptor open = new ManagementAuthorizationInterceptor(
                new ManagementAuthorization(off, (CallerResolver) null, (IdentityProperties) null));

        assertThat(open.preHandle(new MockHttpServletRequest("POST", "/api/vespa/query"), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }
}
