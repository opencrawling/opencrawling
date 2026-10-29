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
package org.opencrawling.vector.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "pgvector", matchIfMissing = true)
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Value("${spring.ai.vectorstore.pgvector.url}")
    private String url;

    @Value("${spring.ai.vectorstore.pgvector.username}")
    private String username;

    @Value("${spring.ai.vectorstore.pgvector.password}")
    private String password;

    @Value("${spring.ai.vectorstore.pgvector.driver-class-name:org.postgresql.Driver}")
    private String driverClassName;

    @Value("${spring.ai.vectorstore.pgvector.initialize-schema:true}")
    private boolean initializeSchema;

    @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}")
    private int dimensions;

    @Value("${spring.opencrawling.output.pgvector.flowable-collocated-schema:false}")
    private boolean flowableCollocatedSchema;

    @Value("${spring.opencrawling.output.pgvector.flowable-expression-indexes:true}")
    private boolean flowableExpressionIndexes;

    @Bean
    public DataSource pgVectorDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate pgVectorJdbcTemplate() {
        return new JdbcTemplate(pgVectorDataSource());
    }

    @Bean
    public EmbeddingModel precomputedEmbeddingModel() {
        return new PrecomputedEmbeddingModel(dimensions);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public PgVectorStore vectorStore(JdbcTemplate pgVectorJdbcTemplate, EmbeddingModel precomputedEmbeddingModel) {
        PgVectorStore store = PgVectorStore.builder(pgVectorJdbcTemplate, precomputedEmbeddingModel)
                .vectorTableName("vector_store")
                .dimensions(dimensions)
                .initializeSchema(initializeSchema)
                .build();
        applyFlowableOptimizations(pgVectorJdbcTemplate, "vector_store");
        return store;
    }

    @Bean
    public PgVectorStore vectorStore384(JdbcTemplate pgVectorJdbcTemplate, EmbeddingModel precomputedEmbeddingModel) {
        PgVectorStore store = PgVectorStore.builder(pgVectorJdbcTemplate, precomputedEmbeddingModel)
                .vectorTableName("vector_store_384")
                .dimensions(384)
                .initializeSchema(initializeSchema)
                .build();
        applyFlowableOptimizations(pgVectorJdbcTemplate, "vector_store_384");
        return store;
    }

    @Bean
    public PgVectorStore vectorStore768(JdbcTemplate pgVectorJdbcTemplate, EmbeddingModel precomputedEmbeddingModel) {
        PgVectorStore store = PgVectorStore.builder(pgVectorJdbcTemplate, precomputedEmbeddingModel)
                .vectorTableName("vector_store_768")
                .dimensions(768)
                .initializeSchema(initializeSchema)
                .build();
        applyFlowableOptimizations(pgVectorJdbcTemplate, "vector_store_768");
        return store;
    }

    @Bean
    public PgVectorStore vectorStore1024(JdbcTemplate pgVectorJdbcTemplate, EmbeddingModel precomputedEmbeddingModel) {
        PgVectorStore store = PgVectorStore.builder(pgVectorJdbcTemplate, precomputedEmbeddingModel)
                .vectorTableName("vector_store_1024")
                .dimensions(1024)
                .initializeSchema(initializeSchema)
                .build();
        applyFlowableOptimizations(pgVectorJdbcTemplate, "vector_store_1024");
        return store;
    }

    private void applyFlowableOptimizations(JdbcTemplate jdbcTemplate, String tableName) {
        if (!initializeSchema) {
            return;
        }
        try {
            if (flowableCollocatedSchema) {
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS scope_id_ VARCHAR(255)");
                jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS scope_type_ VARCHAR(255)");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_scope ON " + tableName + " (scope_id_, scope_type_)");
                log.info("Initialized dedicated Flowable collocated columns and index on {}", tableName);
            }
            if (flowableExpressionIndexes) {
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_flowable_scope ON " + tableName + " (((metadata->>'flowable_scope_id')), ((metadata->>'flowable_scope_type')))");
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_flowable_proc ON " + tableName + " (((metadata->>'flowable_proc_inst_id')))");
                log.info("Initialized Flowable expression indexes on {}", tableName);
            }
        } catch (Exception e) {
            log.warn("Could not apply Flowable schema optimizations on {}: {}", tableName, e.getMessage());
        }
    }

    public boolean isFlowableCollocatedSchema() {
        return flowableCollocatedSchema;
    }

    public boolean isFlowableExpressionIndexes() {
        return flowableExpressionIndexes;
    }
}
