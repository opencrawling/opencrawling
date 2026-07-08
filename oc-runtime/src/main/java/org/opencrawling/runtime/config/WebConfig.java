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
        
        // Forward all non-api and non-static-resource routes to index.html for SPA routing
        registry.addViewController("/{path:[^\\.]*}")
                .setViewName("forward:/index.html");
    }
}
