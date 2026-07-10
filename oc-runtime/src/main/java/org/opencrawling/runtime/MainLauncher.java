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
package org.opencrawling.runtime;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Separate Launcher to avoid JavaFX detection by the Java 11+ launcher.
 * This is a known workaround when the launcher incorrectly identifies 
 * a Spring Boot app as a JavaFX application.
 */
public class MainLauncher {
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        new SpringApplicationBuilder(OpenCrawlingApplication.class)
            .web(WebApplicationType.SERVLET)
            .run(args);
    }
}
