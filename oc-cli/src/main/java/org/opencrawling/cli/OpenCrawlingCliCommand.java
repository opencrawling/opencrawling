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
package org.opencrawling.cli;

import org.opencrawling.cli.commands.*;
import org.opencrawling.cli.util.AnsiColors;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Root Picocli command entry point for `oc` executable.
 */
@Command(
    name = "oc",
    mixinStandardHelpOptions = true,
    version = "OpenCrawling CLI v1.0.0-SNAPSHOT (Java 25)",
    description = "OpenCrawling CLI (oc-cli) for DevSecOps & Terminal Ingestion Management",
    subcommands = {
        JobCommand.class,
        ConnectorCommand.class,
        ArchetypeCommand.class,
        CopilotCommand.class,
        AIOpsCommand.class,
        ConfigCommand.class,
        SystemCommand.class,
        SchemaCommand.class,
        McfCommand.class
    }
)
public class OpenCrawlingCliCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println(AnsiColors.banner());
        CommandLine.usage(this, System.out);
        return 0;
    }
}
