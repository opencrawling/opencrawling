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
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

@Configuration
public class VectorStoreConfig {

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
    @org.springframework.context.annotation.Primary
    public PgVectorStore vectorStore(JdbcTemplate pgVectorJdbcTemplate, EmbeddingModel embeddingModel) {
        PrecomputedEmbeddingModel precomputedModel = new PrecomputedEmbeddingModel(embeddingModel);
        return PgVectorStore.builder(pgVectorJdbcTemplate, precomputedModel)
                .vectorTableName("vector_store")
                .dimensions(dimensions)
                .initializeSchema(initializeSchema)
                .build();
    }

    @Bean
    public PgVectorStore vectorStore384(JdbcTemplate pgVectorJdbcTemplate, EmbeddingModel embeddingModel) {
        PrecomputedEmbeddingModel precomputedModel = new PrecomputedEmbeddingModel(embeddingModel);
        return PgVectorStore.builder(pgVectorJdbcTemplate, precomputedModel)
                .vectorTableName("vector_store_384")
                .dimensions(384)
                .initializeSchema(initializeSchema)
                .build();
    }

    @Bean
    public PgVectorStore vectorStore768(JdbcTemplate pgVectorJdbcTemplate, EmbeddingModel embeddingModel) {
        PrecomputedEmbeddingModel precomputedModel = new PrecomputedEmbeddingModel(embeddingModel);
        return PgVectorStore.builder(pgVectorJdbcTemplate, precomputedModel)
                .vectorTableName("vector_store_768")
                .dimensions(768)
                .initializeSchema(initializeSchema)
                .build();
    }

    @Bean
    public PgVectorStore vectorStore1024(JdbcTemplate pgVectorJdbcTemplate, EmbeddingModel embeddingModel) {
        PrecomputedEmbeddingModel precomputedModel = new PrecomputedEmbeddingModel(embeddingModel);
        return PgVectorStore.builder(pgVectorJdbcTemplate, precomputedModel)
                .vectorTableName("vector_store_1024")
                .dimensions(1024)
                .initializeSchema(initializeSchema)
                .build();
    }
}
