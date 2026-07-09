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
    public PgVectorStore vectorStore(JdbcTemplate pgVectorJdbcTemplate, EmbeddingModel embeddingModel) {
        PrecomputedEmbeddingModel precomputedModel = new PrecomputedEmbeddingModel(embeddingModel);
        return PgVectorStore.builder(pgVectorJdbcTemplate, precomputedModel)
                .dimensions(dimensions)
                .initializeSchema(initializeSchema)
                .build();
    }
}
