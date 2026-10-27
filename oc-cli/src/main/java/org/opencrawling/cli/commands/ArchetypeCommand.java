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
package org.opencrawling.cli.commands;

import org.opencrawling.cli.util.AnsiColors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Picocli command group for Maven archetype scaffolding (`oc archetype`).
 */
@Command(
    name = "archetype",
    mixinStandardHelpOptions = true,
    description = "Scaffold custom connectors via Maven archetypes (init, generate)",
    subcommands = {
        ArchetypeCommand.ArchetypeInitCommand.class
    }
)
public class ArchetypeCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: init"));
    }

    @Command(name = "init", description = "Scaffold a new custom OpenCrawling connector project")
    public static class ArchetypeInitCommand implements Callable<Integer> {

        @Option(names = {"--type"}, required = true, description = "Connector type: repository, output, transformation")
        private String type;

        @Option(names = {"--name"}, required = true, description = "Class/Project name for the connector")
        private String name;

        @Option(names = {"--package"}, description = "Base Java package", defaultValue = "com.company.connectors")
        private String packageName;

        @Option(names = {"--output-dir"}, description = "Output directory", defaultValue = ".")
        private String outputDir;

        @Override
        public Integer call() {
            try {
                System.out.println(AnsiColors.cyan("Scaffolding OpenCrawling " + type + " connector: " + name));

                String artifactId = name.toLowerCase().replace("connector", "") + "-" + type.toLowerCase() + "-connector";
                Path projectPath = Path.of(outputDir, artifactId);
                Files.createDirectories(projectPath);

                // Create pom.xml
                String pomContent = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <project xmlns="http://maven.apache.org/POM/4.0.0"
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                            xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                            <modelVersion>4.0.0</modelVersion>
                            
                            <groupId>%s</groupId>
                            <artifactId>%s</artifactId>
                            <version>1.0.4-SNAPSHOT</version>
                            <name>%s</name>

                            <properties>
                                <java.version>25</java.version>
                                <opencrawling.version>1.0.4-SNAPSHOT</opencrawling.version>
                            </properties>

                            <dependencies>
                                <dependency>
                                    <groupId>org.opencrawling</groupId>
                                    <artifactId>oc-core</artifactId>
                                    <version>${opencrawling.version}</version>
                                </dependency>
                            </dependencies>
                        </project>
                        """.formatted(packageName, artifactId, name);

                Files.writeString(projectPath.resolve("pom.xml"), pomContent);

                // Create Java package structure
                Path srcPath = projectPath.resolve("src/main/java/" + packageName.replace('.', '/'));
                Files.createDirectories(srcPath);

                String className = name.endsWith("Connector") ? name : name + "Connector";
                String normalizedType = type.toLowerCase();
                String javaContent = switch (normalizedType) {
                    case "output" -> """
                        package %s;

                        import org.opencrawling.core.connector.OutputConnector;
                        import org.opencrawling.core.document.RepositoryDocument;
                        import reactor.core.publisher.Mono;

                        public class %s implements OutputConnector {
                            private final String name = "%s";

                            @Override
                            public String getName() {
                                return name;
                            }

                            @Override
                            public void connect() throws Exception {
                            }

                            @Override
                            public void disconnect() throws Exception {
                            }

                            @Override
                            public Mono<Void> send(RepositoryDocument document) {
                                return Mono.empty();
                            }
                        }
                        """.formatted(packageName, className, className);
                    case "transformation" -> """
                        package %s;

                        import org.opencrawling.core.connector.TransformationConnector;
                        import org.opencrawling.core.document.RepositoryDocument;
                        import reactor.core.publisher.Flux;

                        public class %s implements TransformationConnector {
                            private final String name = "%s";

                            @Override
                            public String getName() {
                                return name;
                            }

                            @Override
                            public void connect() throws Exception {
                            }

                            @Override
                            public void disconnect() throws Exception {
                            }

                            @Override
                            public Flux<RepositoryDocument> transform(RepositoryDocument document) {
                                return Flux.just(document);
                            }
                        }
                        """.formatted(packageName, className, className);
                    default -> """
                        package %s;

                        import org.opencrawling.core.connector.RepositoryConnector;
                        import org.opencrawling.core.document.RepositoryDocument;
                        import reactor.core.publisher.Flux;

                        public class %s implements RepositoryConnector {
                            private final String name = "%s";

                            @Override
                            public String getName() {
                                return name;
                            }

                            @Override
                            public void connect() throws Exception {
                            }

                            @Override
                            public void disconnect() throws Exception {
                            }

                            @Override
                            public Flux<RepositoryDocument> scan(String basePath) {
                                return Flux.empty();
                            }
                        }
                        """.formatted(packageName, className, className);
                };

                Files.writeString(srcPath.resolve(className + ".java"), javaContent);

                System.out.println(AnsiColors.green("✔ Successfully generated connector project at: ") + projectPath.toAbsolutePath());
                return 0;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Error initializing archetype: " + e.getMessage()));
                return 1;
            }
        }
    }
}
