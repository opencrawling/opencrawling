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
package org.opencrawling.migrator.mcf.cli;

import org.opencrawling.migrator.mcf.mapping.ConnectorMapper;
import org.opencrawling.migrator.mcf.mapping.ConnectorMapperRegistry;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code list-mappers}: a discoverability aid — shows exactly which ManifoldCF connector classes
 * this build knows how to migrate, and to what OpenCrawling type/class, without running a full
 * extraction against a live ManifoldCF instance.
 */
@Command(name = "list-mappers", description = "List every registered ConnectorMapper (ManifoldCF class -> OpenCrawling type).")
public class ListMappersCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        List<ConnectorMapper> mappers = new ConnectorMapperRegistry().all();
        if (mappers.isEmpty()) {
            System.out.println("No connector mappers found on the classpath.");
            return 0;
        }
        System.out.println("Registered connector mappers:");
        System.out.printf("  %-70s %-15s %s%n", "ManifoldCF class", "Target type", "Mapper");
        for (ConnectorMapper mapper : mappers) {
            System.out.printf("  %-70s %-15s %s%n",
                mapper.manifoldClassName(), mapper.targetType(), mapper.getClass().getName());
        }
        return 0;
    }
}
