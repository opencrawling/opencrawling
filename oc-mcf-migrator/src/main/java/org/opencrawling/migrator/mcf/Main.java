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
package org.opencrawling.migrator.mcf;

import org.opencrawling.migrator.mcf.cli.AuditCommand;
import org.opencrawling.migrator.mcf.cli.ConvertCommand;
import org.opencrawling.migrator.mcf.cli.ImportCommand;
import org.opencrawling.migrator.mcf.cli.ListMappersCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Standalone CLI that migrates an Apache ManifoldCF crawler configuration into OpenCrawling, via
 * three subcommands matching opencrawling/opencrawling#96's proposed {@code oc mcf convert}/
 * {@code import}/{@code audit} structure exactly (just without the not-yet-existing {@code
 * oc-cli} prefix — see each command's own javadoc): {@link ConvertCommand} (file-to-file, OIS
 * output), {@link ImportCommand} (live write to OpenCrawling), {@link AuditCommand} (report only).
 * {@link ListMappersCommand} is a quick discoverability check of what this build currently
 * supports.
 */
@Command(
    name = "oc-mcf-migrator",
    mixinStandardHelpOptions = true,
    version = "oc-mcf-migrator 1.0.0",
    subcommands = {ConvertCommand.class, ImportCommand.class, AuditCommand.class, ListMappersCommand.class},
    description = "Migrates an Apache ManifoldCF crawler configuration into OpenCrawling, "
        + "skipping and clearly reporting anything with no direct connector-level mapping.")
public class Main implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
