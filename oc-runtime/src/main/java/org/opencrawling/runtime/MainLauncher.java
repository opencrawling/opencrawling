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
