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
package org.opencrawling.runtime.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Map the root path to index.html
        registry.addViewController("/").setViewName("forward:/index.html");
        
        // Forward all non-api, non-swagger, and non-static-resource routes to index.html for SPA routing
        registry.addViewController("/{path:^(?!api|v3|swagger-ui|webjars)[^\\.]*}")
                .setViewName("forward:/index.html");

        // The admin UI's OpenID Connect sign-in returns here (two segments, so the rule above does not match it)
        registry.addViewController("/auth/callback").setViewName("forward:/index.html");
    }
}
