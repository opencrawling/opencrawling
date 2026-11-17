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
package org.opencrawling.runtime.controlplane;

import org.opencrawling.runtime.api.PersistenceHelper;
import org.opencrawling.runtime.orchestrator.InMemoryRunStore;
import org.opencrawling.runtime.orchestrator.RunStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.Locale;

/** Chooses the control-plane store with {@code opencrawling.control-plane.store}: {@code file} (default) or {@code jdbc}. */
@Configuration(proxyBeanMethods = false)
public class ControlPlaneConfiguration {

    public static final String STORE_PROPERTY = "opencrawling.control-plane.store";

    @Bean
    public ControlPlaneStore controlPlaneStore(@Value("${" + STORE_PROPERTY + ":file}") String store,
                                               ObjectProvider<JdbcTemplate> jdbc,
                                               ObjectProvider<PlatformTransactionManager> transactions) {
        return switch (store.trim().toLowerCase(Locale.ROOT)) {
            case "file" -> ControlPlaneStore.files();
            case "jdbc" -> {
                JdbcTemplate template = jdbc.getObject();
                PlatformTransactionManager manager = transactions.getIfAvailable(
                        () -> new DataSourceTransactionManager(template.getDataSource()));
                yield new JdbcControlPlaneStore(template, new TransactionTemplate(manager), Path.of(PersistenceHelper.DATA_DIR));
            }
            default -> throw new IllegalArgumentException(STORE_PROPERTY + " must be file or jdbc, not '" + store + "'");
        };
    }

    /**
     * Where job runs are kept: in memory with the {@code file} store (one runtime owns the state), in the control-plane
     * database with {@code jdbc}, shared by the runtimes on it.
     */
    @Bean
    public RunStore runStore(ControlPlaneStore controlPlaneStore, ObjectProvider<JdbcTemplate> jdbc) {
        return controlPlaneStore instanceof JdbcControlPlaneStore
                ? new JdbcRunStore(jdbc.getObject(), controlPlaneStore)
                : new InMemoryRunStore();
    }
}
