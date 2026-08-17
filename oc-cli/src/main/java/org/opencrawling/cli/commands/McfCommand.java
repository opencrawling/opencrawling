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
import org.opencrawling.migrator.mcf.cli.AuditCommand;
import org.opencrawling.migrator.mcf.cli.ConvertCommand;
import org.opencrawling.migrator.mcf.cli.ImportCommand;
import org.opencrawling.migrator.mcf.cli.ListMappersCommand;
import picocli.CommandLine.Command;

/**
 * Picocli command group for migrating an Apache ManifoldCF crawler configuration into OpenCrawling
 * (`oc mcf`) — matches opencrawling/opencrawling#96's proposed {@code oc mcf convert/import/audit}
 * structure exactly. These subcommand classes live in, and are otherwise unchanged from,
 * {@code oc-mcf-migrator} (which remains independently usable as its own standalone CLI jar) —
 * this class only registers them here, it doesn't reimplement anything.
 *
 * <p>Note: unlike {@code oc connector}/{@code oc job}, `oc mcf`'s subcommands do <b>not</b> fall
 * back to {@code ~/.oc/config.json} (via {@code CliConfigService}) for the target OpenCrawling URL/
 * credentials — they keep their own {@code --oc-url}/{@code --oc-api-key}/{@code --oc-bearer-token}
 * flags (with {@code OPENCRAWLING_URL}/{@code OPENCRAWLING_PASSWORD} env var fallback), deliberately
 * prefixed to disambiguate from the equally-required {@code --mcf-url} pointing at the *source*
 * ManifoldCF instance — a migration tool talks to two systems, unlike its siblings here which only
 * ever talk to one. Wiring `oc mcf` into {@code ~/.oc/config.json} too would need that config
 * mechanism to live somewhere both {@code oc-cli} and {@code oc-mcf-migrator} can depend on without
 * a cycle (e.g. {@code oc-java-client-sdk}) — a bigger, cross-module decision left for later.
 */
@Command(
    name = "mcf",
    mixinStandardHelpOptions = true,
    description = "Migrate an Apache ManifoldCF crawler configuration into OpenCrawling (audit, import, convert)",
    subcommands = {
        AuditCommand.class,
        ImportCommand.class,
        ConvertCommand.class,
        ListMappersCommand.class
    }
)
public class McfCommand implements Runnable {

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: audit, import, convert, list-mappers"));
    }
}
