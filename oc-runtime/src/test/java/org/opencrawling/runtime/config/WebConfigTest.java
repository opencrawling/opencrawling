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
package org.opencrawling.runtime.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebConfigTest {

    @RestController
    static class Api {
        @GetMapping("/api/ping")
        String ping() {
            return "pong";
        }
    }

    @EnableWebMvc
    static class Mvc {
    }

    private static MockMvc mvc() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Mvc.class, WebConfig.class, Api.class);
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void theAdminUiPagesAndItsSignInCallbackServeTheApp() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/jobs")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/auth/callback").param("code", "c").param("state", "s")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/api/ping")).andExpect(status().isOk());
    }
}
