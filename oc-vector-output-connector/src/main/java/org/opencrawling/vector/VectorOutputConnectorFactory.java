/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
package org.opencrawling.vector;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.connector.OutputConnectorFactory;
import org.opencrawling.core.connector.OutputContext;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.vector.config.PrecomputedEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A pgvector output for one run, from its saved configuration (#319): its own connection pool and table, created if
 * it does not exist. A RAG run sends its documents to it directly unless it is the runtime's own database and table,
 * where the pipeline writer stores them.
 */
public class VectorOutputConnectorFactory implements OutputConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(VectorOutputConnectorFactory.class);
    /** The table name goes into SQL, so it must be a plain identifier (optionally schema-qualified). */
    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    @Override
    public String className() {
        return VectorOutputConnector.class.getName();
    }

    @Override
    public OutputConnector create(Map<String, String> config, OutputContext context) {
        String url = OutputContext.text(config, null, "pgVectorUrl");
        if (url == null) {
            throw new IllegalArgumentException("pgVectorUrl is required");
        }
        String table = OutputContext.text(config, "vector_store", "pgVectorTableName");
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("pgVectorTableName '" + table + "' is not a valid table name");
        }
        int dimensions = OutputContext.integer(config, 1024, "pgVectorDimensions");

        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        pool.setUsername(OutputContext.text(config, null, "pgVectorUsername"));
        pool.setPassword(OutputContext.text(config, null, "pgVectorPassword"));
        pool.setDriverClassName(OutputContext.text(config, "org.postgresql.Driver", "pgVectorDriverClassName"));
        pool.setMaximumPoolSize(OutputContext.integer(config, 4, "pgVectorMaxPoolSize"));
        pool.setPoolName("oc-run-pgvector");
        HikariDataSource dataSource = new HikariDataSource(pool);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            PgVectorStore store = PgVectorStore.builder(jdbc, new PrecomputedEmbeddingModel(dimensions))
                    .vectorTableName(table)
                    .dimensions(dimensions)
                    .initializeSchema(OutputContext.flag(config, true, "pgVectorInitializeSchema"))
                    .build();
            store.afterPropertiesSet();
            log.info("Resolving dynamic pgvector output connector for table '{}'", table);
            return new VectorOutputConnector(store, context.service(EmbeddingModel.class),
                    context.service(TextExtractionService.class), jdbc, table, url, dataSource,
                    !pipelineWrites(context, url, table));
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    /** Whether this is the runtime's own pgvector database and table, which its pipeline writer stores to. */
    public static boolean pipelineWrites(OutputContext context, String url, String table) {
        VectorOutputConnector pipeline = context.pipelineOutput(VectorOutputConnector.class);
        return pipeline != null && Objects.equals(pipeline.url(), url)
                && Objects.equals(pipeline.getVectorTableName(), table);
    }
}
