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
package org.opencrawling.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;

import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.connector.ConnectorSchema.SchemaField;
import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.observability.concurrency.ObservabilityTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Enterprise-grade JDBC Repository Connector for Relational Database Ingestion,
 * Tabular RAG, and BLOB Streaming into OpenCrawling with Java 25 Structured Concurrency
 * and Spring Virtual Threads integration.
 */
@Component
public class JdbcRepositoryConnector implements RepositoryConnector {

    private static final Logger log = LoggerFactory.getLogger(JdbcRepositoryConnector.class);

    private final String url;
    private final String driverClassName;
    private final String username;
    private final String password;
    private final JdbcCrawlMode crawlMode;
    private final String tableName;
    private final String schemaName;
    private final Set<String> primaryKeyColumns;
    private final String titleColumn;
    private final Set<String> excludedColumns;
    private final String querySql;
    private final String blobColumnName;
    private final String fileNameColumn;
    private final String mimeTypeColumn;
    private final boolean incrementalEnabled;
    private final String hwmColumn;
    private final String hwmType;
    private final boolean softDeleteEnabled;
    private final String softDeleteColumn;
    private final String softDeleteValue;
    private final boolean securityEnabled;
    private final Set<String> userColumns;
    private final Set<String> groupColumns;
    private final String tenantColumn;
    private final String defaultPermission;
    private final int fetchSize;
    private final int batchSize;
    private final int maxPoolSize;
    private final int minIdle;
    private final long connectionTimeoutMs;
    private final int concurrencyLimit;
    private final boolean multiTableParallelEnabled;
    private String narrativizationTemplate;
    private Template compiledNarrativeTemplate;

    @Autowired(required = false)
    @Qualifier("virtualThreadExecutor")
    private AsyncTaskExecutor taskExecutor;

    @Autowired(required = false)
    private TaskDecorator taskDecorator;

    private SimpleAsyncTaskExecutor internalTaskExecutor;

    private final ObjectMapper objectMapper;
    private DataSource dataSource;
    private boolean externalDataSource = false;

    public JdbcRepositoryConnector() {
        this("jdbc:h2:mem:opencrawling;DB_CLOSE_DELAY=-1",
             "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
             "", "", Set.of("id"), "title", Set.of(),
             "", "", "", "",
             false, "updated_at", "timestamp",
             false, "is_deleted", "true",
             false, Set.of(), Set.of(), "", "read",
             1000, 100, 10, 2, 30000L, 64, true, null);
    }

    @Autowired
    public JdbcRepositoryConnector(
            @Value("${spring.opencrawling.connector.jdbc.url:jdbc:h2:mem:opencrawling;DB_CLOSE_DELAY=-1}") String url,
            @Value("${spring.opencrawling.connector.jdbc.driver-class-name:}") String driverClassName,
            @Value("${spring.opencrawling.connector.jdbc.username:sa}") String username,
            @Value("${spring.opencrawling.connector.jdbc.password:}") String password,
            @Value("${spring.opencrawling.connector.jdbc.mode:table}") String crawlModeStr,
            @Value("${spring.opencrawling.connector.jdbc.table-name:}") String tableName,
            @Value("${spring.opencrawling.connector.jdbc.schema-name:}") String schemaName,
            @Value("${spring.opencrawling.connector.jdbc.primary-key-columns:id}") String primaryKeyColumnsStr,
            @Value("${spring.opencrawling.connector.jdbc.title-column:title}") String titleColumn,
            @Value("${spring.opencrawling.connector.jdbc.excluded-columns:}") String excludedColumnsStr,
            @Value("${spring.opencrawling.connector.jdbc.query-sql:}") String querySql,
            @Value("${spring.opencrawling.connector.jdbc.blob-column-name:}") String blobColumnName,
            @Value("${spring.opencrawling.connector.jdbc.file-name-column:}") String fileNameColumn,
            @Value("${spring.opencrawling.connector.jdbc.mime-type-column:}") String mimeTypeColumn,
            @Value("${spring.opencrawling.connector.jdbc.incremental-enabled:false}") boolean incrementalEnabled,
            @Value("${spring.opencrawling.connector.jdbc.hwm-column:updated_at}") String hwmColumn,
            @Value("${spring.opencrawling.connector.jdbc.hwm-type:timestamp}") String hwmType,
            @Value("${spring.opencrawling.connector.jdbc.soft-delete-enabled:false}") boolean softDeleteEnabled,
            @Value("${spring.opencrawling.connector.jdbc.soft-delete-column:is_deleted}") String softDeleteColumn,
            @Value("${spring.opencrawling.connector.jdbc.soft-delete-value:true}") String softDeleteValue,
            @Value("${spring.opencrawling.connector.jdbc.security-enabled:false}") boolean securityEnabled,
            @Value("${spring.opencrawling.connector.jdbc.user-columns:}") String userColumnsStr,
            @Value("${spring.opencrawling.connector.jdbc.group-columns:}") String groupColumnsStr,
            @Value("${spring.opencrawling.connector.jdbc.tenant-column:}") String tenantColumn,
            @Value("${spring.opencrawling.connector.jdbc.default-permission:read}") String defaultPermission,
            @Value("${spring.opencrawling.connector.jdbc.fetch-size:1000}") int fetchSize,
            @Value("${spring.opencrawling.connector.jdbc.batch-size:100}") int batchSize,
            @Value("${spring.opencrawling.connector.jdbc.max-pool-size:10}") int maxPoolSize,
            @Value("${spring.opencrawling.connector.jdbc.min-idle:2}") int minIdle,
            @Value("${spring.opencrawling.connector.jdbc.connection-timeout-ms:30000}") long connectionTimeoutMs,
            @Value("${spring.opencrawling.connector.jdbc.concurrency-limit:64}") int concurrencyLimit,
            @Value("${spring.opencrawling.connector.jdbc.multi-table-parallel-enabled:true}") boolean multiTableParallelEnabled,
            @Value("${spring.opencrawling.connector.jdbc.narrativization-template:}") String narrativizationTemplate) {

        this(url, driverClassName, username, password,
             JdbcCrawlMode.fromString(crawlModeStr),
             tableName, schemaName,
             parseDelimitedSet(primaryKeyColumnsStr, Set.of("id")),
             titleColumn,
             parseDelimitedSet(excludedColumnsStr, Set.of()),
             querySql, blobColumnName, fileNameColumn, mimeTypeColumn,
             incrementalEnabled, hwmColumn, hwmType,
             softDeleteEnabled, softDeleteColumn, softDeleteValue,
             securityEnabled,
             parseDelimitedSet(userColumnsStr, Set.of()),
             parseDelimitedSet(groupColumnsStr, Set.of()),
             tenantColumn, defaultPermission,
             fetchSize, batchSize, maxPoolSize, minIdle, connectionTimeoutMs,
             concurrencyLimit, multiTableParallelEnabled, narrativizationTemplate);
    }

    public JdbcRepositoryConnector(
            String url,
            String driverClassName,
            String username,
            String password,
            JdbcCrawlMode crawlMode,
            String tableName,
            String schemaName,
            Set<String> primaryKeyColumns,
            String titleColumn,
            Set<String> excludedColumns,
            String querySql,
            String blobColumnName,
            String fileNameColumn,
            String mimeTypeColumn,
            boolean incrementalEnabled,
            String hwmColumn,
            String hwmType,
            boolean softDeleteEnabled,
            String softDeleteColumn,
            String softDeleteValue,
            boolean securityEnabled,
            Set<String> userColumns,
            Set<String> groupColumns,
            String tenantColumn,
            String defaultPermission,
            int fetchSize,
            int batchSize,
            int maxPoolSize,
            int minIdle,
            long connectionTimeoutMs) {

        this(url, driverClassName, username, password, crawlMode,
             tableName, schemaName, primaryKeyColumns, titleColumn, excludedColumns,
             querySql, blobColumnName, fileNameColumn, mimeTypeColumn,
             incrementalEnabled, hwmColumn, hwmType,
             softDeleteEnabled, softDeleteColumn, softDeleteValue,
             securityEnabled, userColumns, groupColumns, tenantColumn, defaultPermission,
             fetchSize, batchSize, maxPoolSize, minIdle, connectionTimeoutMs,
             64, true);
    }

    public JdbcRepositoryConnector(
            String url,
            String driverClassName,
            String username,
            String password,
            JdbcCrawlMode crawlMode,
            String tableName,
            String schemaName,
            Set<String> primaryKeyColumns,
            String titleColumn,
            Set<String> excludedColumns,
            String querySql,
            String blobColumnName,
            String fileNameColumn,
            String mimeTypeColumn,
            boolean incrementalEnabled,
            String hwmColumn,
            String hwmType,
            boolean softDeleteEnabled,
            String softDeleteColumn,
            String softDeleteValue,
            boolean securityEnabled,
            Set<String> userColumns,
            Set<String> groupColumns,
            String tenantColumn,
            String defaultPermission,
            int fetchSize,
            int batchSize,
            int maxPoolSize,
            int minIdle,
            long connectionTimeoutMs,
            int concurrencyLimit,
            boolean multiTableParallelEnabled) {

        this(url, driverClassName, username, password, crawlMode,
             tableName, schemaName, primaryKeyColumns, titleColumn, excludedColumns,
             querySql, blobColumnName, fileNameColumn, mimeTypeColumn,
             incrementalEnabled, hwmColumn, hwmType,
             softDeleteEnabled, softDeleteColumn, softDeleteValue,
             securityEnabled, userColumns, groupColumns, tenantColumn, defaultPermission,
             fetchSize, batchSize, maxPoolSize, minIdle, connectionTimeoutMs,
             concurrencyLimit, multiTableParallelEnabled, null);
    }

    public JdbcRepositoryConnector(
            String url,
            String driverClassName,
            String username,
            String password,
            JdbcCrawlMode crawlMode,
            String tableName,
            String schemaName,
            Set<String> primaryKeyColumns,
            String titleColumn,
            Set<String> excludedColumns,
            String querySql,
            String blobColumnName,
            String fileNameColumn,
            String mimeTypeColumn,
            boolean incrementalEnabled,
            String hwmColumn,
            String hwmType,
            boolean softDeleteEnabled,
            String softDeleteColumn,
            String softDeleteValue,
            boolean securityEnabled,
            Set<String> userColumns,
            Set<String> groupColumns,
            String tenantColumn,
            String defaultPermission,
            int fetchSize,
            int batchSize,
            int maxPoolSize,
            int minIdle,
            long connectionTimeoutMs,
            int concurrencyLimit,
            boolean multiTableParallelEnabled,
            String narrativizationTemplate) {

        this.url = (url != null && !url.isBlank()) ? url.trim() : "jdbc:h2:mem:opencrawling;DB_CLOSE_DELAY=-1";
        this.driverClassName = driverClassName != null ? driverClassName.trim() : "";
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";
        this.crawlMode = crawlMode != null ? crawlMode : JdbcCrawlMode.TABLE;
        this.tableName = tableName != null ? tableName.trim() : "";
        this.schemaName = schemaName != null ? schemaName.trim() : "";
        this.primaryKeyColumns = (primaryKeyColumns != null && !primaryKeyColumns.isEmpty()) ? primaryKeyColumns : Set.of("id");
        this.titleColumn = (titleColumn != null && !titleColumn.isBlank()) ? titleColumn.trim() : "title";
        this.excludedColumns = excludedColumns != null ? excludedColumns : Set.of();
        this.querySql = querySql != null ? querySql.trim() : "";
        this.blobColumnName = blobColumnName != null ? blobColumnName.trim() : "";
        this.fileNameColumn = fileNameColumn != null ? fileNameColumn.trim() : "";
        this.mimeTypeColumn = mimeTypeColumn != null ? mimeTypeColumn.trim() : "";
        this.incrementalEnabled = incrementalEnabled;
        this.hwmColumn = (hwmColumn != null && !hwmColumn.isBlank()) ? hwmColumn.trim() : "updated_at";
        this.hwmType = (hwmType != null && !hwmType.isBlank()) ? hwmType.trim() : "timestamp";
        this.softDeleteEnabled = softDeleteEnabled;
        this.softDeleteColumn = (softDeleteColumn != null && !softDeleteColumn.isBlank()) ? softDeleteColumn.trim() : "is_deleted";
        this.softDeleteValue = (softDeleteValue != null && !softDeleteValue.isBlank()) ? softDeleteValue.trim() : "true";
        this.securityEnabled = securityEnabled;
        this.userColumns = userColumns != null ? userColumns : Set.of();
        this.groupColumns = groupColumns != null ? groupColumns : Set.of();
        this.tenantColumn = tenantColumn != null ? tenantColumn.trim() : "";
        this.defaultPermission = (defaultPermission != null && !defaultPermission.isBlank()) ? defaultPermission.trim() : "read";
        this.fetchSize = fetchSize > 0 ? fetchSize : 1000;
        this.batchSize = batchSize > 0 ? batchSize : 100;
        this.maxPoolSize = maxPoolSize > 0 ? maxPoolSize : 10;
        this.minIdle = minIdle >= 0 ? minIdle : 2;
        this.connectionTimeoutMs = connectionTimeoutMs > 0 ? connectionTimeoutMs : 30000L;
        this.concurrencyLimit = concurrencyLimit > 0 ? concurrencyLimit : 64;
        this.multiTableParallelEnabled = multiTableParallelEnabled;
        this.narrativizationTemplate = (narrativizationTemplate != null && !narrativizationTemplate.isBlank()) ? narrativizationTemplate.trim() : null;
        if (this.narrativizationTemplate != null) {
            this.compiledNarrativeTemplate = Mustache.compiler()
                    .defaultValue("")
                    .nullValue("")
                    .compile(this.narrativizationTemplate);
        } else {
            this.compiledNarrativeTemplate = null;
        }
        this.objectMapper = new ObjectMapper();
    }

    private static Set<String> parseDelimitedSet(String input, Set<String> defaultSet) {
        if (input != null && !input.isBlank()) {
            return Arrays.stream(input.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }
        return defaultSet;
    }

    @Override
    public String getName() {
        return "JdbcConnector";
    }

    @Override
    public synchronized void connect() throws Exception {
        if (this.dataSource != null) {
            log.info("JDBC DataSource is already initialized.");
            return;
        }

        log.info("Connecting to JDBC Database at URL: {}", url);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        if (driverClassName != null && !driverClassName.isBlank()) {
            config.setDriverClassName(driverClassName);
        }
        if (username != null && !username.isBlank()) {
            config.setUsername(username);
        }
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeoutMs);
        config.setPoolName("OpenCrawling-HikariPool-" + Math.abs(url.hashCode()));

        this.dataSource = new HikariDataSource(config);

        // Connection validation test
        try (Connection conn = this.dataSource.getConnection()) {
            if (!conn.isValid(5)) {
                throw new SQLException("Failed to validate JDBC connection to: " + url);
            }
            log.info("Successfully connected to JDBC Database. Product: {} Version: {}",
                    conn.getMetaData().getDatabaseProductName(),
                    conn.getMetaData().getDatabaseProductVersion());
        }
    }

    @Override
    public synchronized void disconnect() throws Exception {
        log.info("Disconnecting from JDBC Database.");
        if (this.internalTaskExecutor != null) {
            log.info("Shutting down internal Spring SimpleAsyncTaskExecutor...");
            try {
                this.internalTaskExecutor.close();
            } catch (Exception e) {
                log.warn("Error closing internal SimpleAsyncTaskExecutor: {}", e.getMessage());
            }
            this.internalTaskExecutor = null;
        }
        if (this.dataSource instanceof HikariDataSource hikari && !externalDataSource) {
            hikari.close();
        }
        this.dataSource = null;
    }

    @PreDestroy
    public void cleanup() {
        try {
            disconnect();
        } catch (Exception e) {
            log.warn("Error during JDBC connector destroy cleanup: {}", e.getMessage());
        }
    }

    public synchronized AsyncTaskExecutor getOrCreateTaskExecutor() {
        if (this.taskExecutor != null) {
            return this.taskExecutor;
        }
        if (this.internalTaskExecutor == null) {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("jdbc-crawler-");
            executor.setVirtualThreads(true);
            if (concurrencyLimit > 0) {
                executor.setConcurrencyLimit(concurrencyLimit);
            }
            if (this.taskDecorator != null) {
                executor.setTaskDecorator(this.taskDecorator);
            } else {
                executor.setTaskDecorator(ObservabilityTask::observed);
            }
            this.internalTaskExecutor = executor;
            this.taskExecutor = executor;
        }
        return this.taskExecutor;
    }

    public void setTaskExecutor(AsyncTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public void setTaskDecorator(TaskDecorator taskDecorator) {
        this.taskDecorator = taskDecorator;
    }

    public AsyncTaskExecutor getTaskExecutor() {
        return getOrCreateTaskExecutor();
    }

    public TaskDecorator getTaskDecorator() {
        return this.taskDecorator;
    }

    private <T> Callable<T> decorateCallable(Callable<T> callable) {
        Callable<T> observed = ObservabilityTask.observed(callable);
        if (this.taskDecorator == null) {
            return observed;
        }
        return () -> {
            AtomicReference<T> resultRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            Runnable decorated = taskDecorator.decorate(() -> {
                try {
                    resultRef.set(observed.call());
                } catch (Throwable t) {
                    errorRef.set(t);
                }
            });
            decorated.run();
            if (errorRef.get() != null) {
                if (errorRef.get() instanceof Exception ex) {
                    throw ex;
                }
                throw new RuntimeException(errorRef.get());
            }
            return resultRef.get();
        };
    }

    private Runnable decorateRunnable(Runnable runnable) {
        Runnable observed = ObservabilityTask.observed(runnable);
        if (this.taskDecorator != null) {
            return taskDecorator.decorate(observed);
        }
        return observed;
    }

    public String getNarrativizationTemplate() {
        return narrativizationTemplate;
    }

    public void setNarrativizationTemplate(String narrativizationTemplate) {
        this.narrativizationTemplate = (narrativizationTemplate != null && !narrativizationTemplate.isBlank()) ? narrativizationTemplate.trim() : null;
        if (this.narrativizationTemplate != null) {
            this.compiledNarrativeTemplate = Mustache.compiler()
                    .defaultValue("")
                    .nullValue("")
                    .compile(this.narrativizationTemplate);
        } else {
            this.compiledNarrativeTemplate = null;
        }
    }

    public synchronized void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        this.externalDataSource = true;
    }

    @Override
    public ConnectorSchema getSchema(String basePath) {
        try {
            if (this.dataSource == null) {
                connect();
            }

            String target = resolveTarget(basePath);
            boolean isQuery = isSql(target);

            try (Connection conn = dataSource.getConnection()) {
                List<SchemaField> fields = new ArrayList<>();

                if (isQuery) {
                    try (PreparedStatement stmt = conn.prepareStatement(target)) {
                        ResultSetMetaData md = stmt.getMetaData();
                        if (md != null) {
                            int count = md.getColumnCount();
                            for (int i = 1; i <= count; i++) {
                                String colName = md.getColumnLabel(i);
                                String colType = md.getColumnTypeName(i);
                                fields.add(new SchemaField(colName, colType, "SQL query projection: " + colType));
                            }
                        }
                    }
                } else {
                    DatabaseMetaData metaData = conn.getMetaData();
                    String catalog = null;
                    String schema = (this.schemaName != null && !this.schemaName.isBlank()) ? this.schemaName : null;
                    String tbl = target;

                    if (tbl.contains(".")) {
                        String[] parts = tbl.split("\\.", 2);
                        schema = parts[0];
                        tbl = parts[1];
                    }

                    // Try exact name or uppercase/lowercase matching
                    try (ResultSet rs = metaData.getColumns(catalog, schema, tbl, null)) {
                        populateSchemaFields(rs, fields);
                    }

                    if (fields.isEmpty()) {
                        try (ResultSet rs = metaData.getColumns(catalog, schema, tbl.toUpperCase(), null)) {
                            populateSchemaFields(rs, fields);
                        }
                    }
                    if (fields.isEmpty()) {
                        try (ResultSet rs = metaData.getColumns(catalog, schema, tbl.toLowerCase(), null)) {
                            populateSchemaFields(rs, fields);
                        }
                    }
                }

                log.info("Retrieved schema for JDBC target '{}' with {} fields", target, fields.size());
                return new ConnectorSchema(fields);
            }
        } catch (Exception e) {
            log.error("Failed to retrieve schema for JDBC target '{}': {}", basePath, e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve JDBC schema for target: " + basePath, e);
        }
    }

    private void populateSchemaFields(ResultSet rs, List<SchemaField> fields) throws SQLException {
        while (rs.next()) {
            String colName = rs.getString("COLUMN_NAME");
            String typeName = rs.getString("TYPE_NAME");
            String remarks = rs.getString("REMARKS");
            if (remarks == null || remarks.isBlank()) {
                remarks = "Column " + colName + " of type " + typeName;
            }
            fields.add(new SchemaField(colName, typeName, remarks));
        }
    }

    @Override
    @SuppressWarnings("preview")
    public Flux<RepositoryDocument> scan(String basePath) {
        return Flux.create(sink -> {
            Runnable scanTask = () -> {
                try {
                    if (this.dataSource == null) {
                        connect();
                    }

                    String target = resolveTarget(basePath);
                    boolean isQuery = isSql(target);

                    if (multiTableParallelEnabled && !isQuery && target.contains(",")) {
                        List<String> targets = Arrays.stream(target.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isBlank())
                                .toList();

                        log.info("Scanning {} tables concurrently using StructuredTaskScope: {}", targets.size(), targets);
                        try (var multiScope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Void>awaitAll())) {
                            List<StructuredTaskScope.Subtask<Void>> subtasks = new ArrayList<>();
                            for (String singleTarget : targets) {
                                subtasks.add(multiScope.fork(decorateCallable(() -> {
                                    scanSingleTarget(singleTarget, sink);
                                    return null;
                                })));
                            }
                            multiScope.join();
                            Throwable firstError = null;
                            for (var st : subtasks) {
                                if (st.state() == StructuredTaskScope.Subtask.State.FAILED) {
                                    log.error("Multi-table scan subtask failed: {}", st.exception().getMessage(), st.exception());
                                    if (firstError == null) {
                                        firstError = st.exception();
                                    }
                                }
                            }
                            if (firstError != null && !sink.isCancelled()) {
                                sink.error(firstError);
                                return;
                            }
                        }
                    } else {
                        scanSingleTarget(target, sink);
                    }

                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                } catch (Exception e) {
                    log.error("JDBC scan failed: {}", e.getMessage(), e);
                    if (!sink.isCancelled()) {
                        sink.error(e);
                    }
                }
            };

            getOrCreateTaskExecutor().execute(decorateRunnable(scanTask));
        });
    }

    @SuppressWarnings("preview")
    private void scanSingleTarget(String target, FluxSink<RepositoryDocument> sink) throws Exception {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = dataSource.getConnection();
            boolean isQuery = isSql(target);

            String sqlToExecute;
            if (isQuery) {
                sqlToExecute = target;
            } else {
                sqlToExecute = "SELECT * FROM " + target;
            }

            // Configure engine-specific streaming cursor
            if (url.contains("postgresql")) {
                conn.setAutoCommit(false);
                stmt = conn.prepareStatement(sqlToExecute, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                stmt.setFetchSize(fetchSize);
            } else if (url.contains("mysql")) {
                stmt = conn.prepareStatement(sqlToExecute, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                stmt.setFetchSize(Integer.MIN_VALUE);
            } else {
                stmt = conn.prepareStatement(sqlToExecute, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                try {
                    stmt.setFetchSize(fetchSize);
                } catch (Exception ignored) {}
            }

            log.info("Executing JDBC scan using SQL: '{}'", sqlToExecute);
            rs = stmt.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();

            // Resolve PK columns
            Set<String> effectivePkColumns = resolvePrimaryKeys(conn, target, isQuery);

            // Auto-detect or resolve binary BLOB column
            String effectiveBlobColumn = resolveBlobColumn(md, colCount, this.blobColumnName);
            if (effectiveBlobColumn != null) {
                log.info("Using resolved BLOB column '{}' for target '{}'", effectiveBlobColumn, target);
            }

            List<RowSnapshot> batch = new ArrayList<>(batchSize);

            while (rs.next()) {
                if (sink.isCancelled()) {
                    log.info("JDBC scan cancelled by downstream subscriber for target '{}'", target);
                    break;
                }

                // Synchronously snapshot row columns on the JDBC thread
                Map<String, Object> rowMap = new LinkedHashMap<>();
                byte[] blobBytes = null;

                for (int i = 1; i <= colCount; i++) {
                    String colName = md.getColumnLabel(i);
                    if (isExcluded(colName)) {
                        continue;
                    }

                    if (effectiveBlobColumn != null && effectiveBlobColumn.equalsIgnoreCase(colName)) {
                        blobBytes = extractBytes(rs, i);
                        rowMap.put(colName, blobBytes != null ? "[BLOB: " + blobBytes.length + " bytes]" : null);
                    } else {
                        Object val = rs.getObject(i);
                        rowMap.put(colName, val);
                    }
                }

                batch.add(new RowSnapshot(rowMap, blobBytes));

                if (batch.size() >= batchSize) {
                    processBatchWithStructuredConcurrency(batch, target, effectivePkColumns, sink);
                    batch.clear();
                }
            }

            if (!batch.isEmpty() && !sink.isCancelled()) {
                processBatchWithStructuredConcurrency(batch, target, effectivePkColumns, sink);
                batch.clear();
            }

            log.info("Completed JDBC scan for target: '{}'", target);

        } finally {
            closeQuietly(rs);
            closeQuietly(stmt);
            closeQuietly(conn);
        }
    }

    @SuppressWarnings("preview")
    private void processBatchWithStructuredConcurrency(
            List<RowSnapshot> batch,
            String target,
            Set<String> effectivePkColumns,
            FluxSink<RepositoryDocument> sink) throws InterruptedException {

        if (batch.isEmpty() || sink.isCancelled()) {
            return;
        }

        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<RepositoryDocument>awaitAll())) {
            List<StructuredTaskScope.Subtask<RepositoryDocument>> subtasks = new ArrayList<>(batch.size());

            for (RowSnapshot row : batch) {
                if (sink.isCancelled()) {
                    break;
                }
                Callable<RepositoryDocument> task = decorateCallable(() ->
                        buildDocument(row.rowMap(), row.blobBytes(), target, effectivePkColumns)
                );
                subtasks.add(scope.fork(task));
            }

            scope.join();

            for (var subtask : subtasks) {
                if (sink.isCancelled()) {
                    break;
                }
                if (subtask.state() == StructuredTaskScope.Subtask.State.SUCCESS) {
                    RepositoryDocument doc = subtask.get();
                    if (doc != null) {
                        synchronized (sink) {
                            sink.next(doc);
                        }
                    }
                } else if (subtask.state() == StructuredTaskScope.Subtask.State.FAILED) {
                    log.error("Failed to build document for row in target '{}': {}", target, subtask.exception().getMessage(), subtask.exception());
                }
            }
        }
    }

    private record RowSnapshot(Map<String, Object> rowMap, byte[] blobBytes) {}

    private RepositoryDocument buildDocument(
            Map<String, Object> rowMap,
            byte[] blobBytes,
            String target,
            Set<String> effectivePkColumns) {

        // 1. Compute Primary Key / Document ID
        String docId = extractDocumentId(rowMap, target, effectivePkColumns);
        String docUri = "jdbc:" + target + "/" + docId;

        // 2. Check Soft Delete
        if (softDeleteEnabled && softDeleteColumn != null && !softDeleteColumn.isBlank()) {
            Object deleteVal = getCaseInsensitive(rowMap, softDeleteColumn);
            if (deleteVal != null && softDeleteValue.equalsIgnoreCase(String.valueOf(deleteVal).trim())) {
                log.debug("Emitting deletion tombstone for deleted row: {}", docId);
                return RepositoryDocument.createTombstone(docId, docUri);
            }
        }

        // 3. Metadata extraction (normalizing keys to both lower and original case)
        Map<String, List<String>> metadata = new HashMap<>();
        Instant lastModified = Instant.now();

        for (Map.Entry<String, Object> entry : rowMap.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            if (v != null) {
                String valStr;
                if (v instanceof Timestamp ts) {
                    valStr = ts.toInstant().toString();
                    if (hwmColumn.equalsIgnoreCase(k)) {
                        lastModified = ts.toInstant();
                    }
                } else if (v instanceof java.sql.Date d) {
                    valStr = d.toLocalDate().toString();
                } else {
                    valStr = String.valueOf(v);
                }
                metadata.put(k, List.of(valStr));
                metadata.put(k.toLowerCase(), List.of(valStr));
            }
        }

        metadata.put("jdbc_target", List.of(target));
        metadata.put("jdbc_document_id", List.of(docId));

        Object titleVal = getCaseInsensitive(rowMap, titleColumn);
        if (titleVal != null) {
            String t = String.valueOf(titleVal);
            metadata.put("name", List.of(t));
            metadata.put("title", List.of(t));
        } else {
            metadata.put("name", List.of(docId));
        }

        Object fn = getCaseInsensitive(rowMap, fileNameColumn);
        if (fn != null) {
            metadata.put("filename", List.of(String.valueOf(fn)));
            metadata.put("fileName", List.of(String.valueOf(fn)));
        }

        Object mt = getCaseInsensitive(rowMap, mimeTypeColumn);
        String explicitMime = mt != null ? String.valueOf(mt).trim() : null;
        String resolvedFileName = fn != null ? String.valueOf(fn).trim() : null;
        String detectedMime = detectMimeType(blobBytes, resolvedFileName, explicitMime);

        // 4. Security & Zero-Trust ACL mapping
        JdbcSecurityMapper.SecurityResult secResult = securityEnabled
                ? JdbcSecurityMapper.mapSecurity(rowMap, userColumns, groupColumns, tenantColumn, defaultPermission)
                : new JdbcSecurityMapper.SecurityResult(
                        org.opencrawling.core.security.SecurityConfig.createPublic(),
                        "public", List.of(), List.of(), null);

        if (secResult.tenantId() != null) {
            metadata.put("tenant_id", List.of(secResult.tenantId()));
        }

        // 5. Binary BLOB vs Tabular Narrativization classification
        InputStream contentStream;
        if (blobBytes != null) {
            metadata.put("is_blob", List.of("true"));
            metadata.put("has_blob", List.of("true"));
            metadata.put("is_tabular", List.of("false"));
            metadata.put("blob_size", List.of(String.valueOf(blobBytes.length)));
            metadata.put("mimeType", List.of(detectedMime));
            metadata.put("content_type", List.of(detectedMime));
            boolean isImage = detectedMime.startsWith("image/");
            boolean isDocument = detectedMime.startsWith("application/pdf")
                    || detectedMime.contains("word") || detectedMime.contains("document")
                    || detectedMime.contains("sheet") || detectedMime.contains("presentation")
                    || detectedMime.startsWith("text/");
            metadata.put("is_image", List.of(String.valueOf(isImage)));
            metadata.put("is_document", List.of(String.valueOf(isDocument)));
            metadata.put("media_type", List.of(isImage ? "image" : (isDocument ? "document" : "binary")));
            
            // Raw binary stream preserved directly for Tika text extraction and embeddings
            contentStream = new ByteArrayInputStream(blobBytes);
        } else {
            metadata.put("is_blob", List.of("false"));
            metadata.put("has_blob", List.of("false"));
            metadata.put("is_tabular", List.of("true"));
            metadata.put("media_type", List.of("tabular"));
            metadata.put("mimeType", List.of("text/markdown"));
            metadata.put("content_type", List.of("text/markdown"));
            
            // Standard tabular row: processed with narrativization!
            String narrative = narrativizeRow(rowMap, target, docId);
            contentStream = new ByteArrayInputStream(narrative.getBytes(StandardCharsets.UTF_8));
        }

        return new RepositoryDocument(
            docId,
            docUri,
            contentStream,
            metadata,
            secResult.aclString(),
            secResult.securityConfig(),
            lastModified,
            DocumentAction.UPSERT
        );
    }

    private String extractDocumentId(Map<String, Object> rowMap, String target, Set<String> pkColumns) {
        StringBuilder sb = new StringBuilder();
        for (String pk : pkColumns) {
            Object val = getCaseInsensitive(rowMap, pk);
            if (val != null) {
                if (!sb.isEmpty()) sb.append("_");
                sb.append(val);
            }
        }

        if (sb.isEmpty()) {
            // Fallback to first non-null column value or hash
            Object firstVal = rowMap.values().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (firstVal != null) {
                return String.valueOf(firstVal);
            }
            return String.valueOf(Math.abs(rowMap.hashCode()));
        }
        return sb.toString();
    }

    private Set<String> resolvePrimaryKeys(Connection conn, String target, boolean isQuery) {
        if (isQuery || primaryKeyColumns.size() > 1 || !primaryKeyColumns.contains("id")) {
            return this.primaryKeyColumns;
        }

        try {
            DatabaseMetaData md = conn.getMetaData();
            String tbl = target;
            String schema = (schemaName != null && !schemaName.isBlank()) ? schemaName : null;
            if (tbl.contains(".")) {
                String[] p = tbl.split("\\.", 2);
                schema = p[0];
                tbl = p[1];
            }

            Set<String> pks = new HashSet<>();
            try (ResultSet rs = md.getPrimaryKeys(null, schema, tbl)) {
                while (rs.next()) {
                    pks.add(rs.getString("COLUMN_NAME"));
                }
            }
            if (pks.isEmpty()) {
                try (ResultSet rs = md.getPrimaryKeys(null, schema, tbl.toUpperCase())) {
                    while (rs.next()) {
                        pks.add(rs.getString("COLUMN_NAME"));
                    }
                }
            }
            if (!pks.isEmpty()) {
                return pks;
            }
        } catch (Exception ignored) {}

        return this.primaryKeyColumns;
    }

    private Object getCaseInsensitive(Map<String, Object> map, String key) {
        if (map == null || key == null || key.isBlank()) return null;
        if (map.containsKey(key)) return map.get(key);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isExcluded(String colName) {
        if (excludedColumns == null || excludedColumns.isEmpty()) return false;
        return excludedColumns.stream().anyMatch(ex -> ex.equalsIgnoreCase(colName));
    }

    private String resolveTarget(String basePath) {
        if (basePath != null && !basePath.isBlank() && !basePath.equals("/") && !basePath.equals("-root-")) {
            return basePath.trim();
        }
        if (crawlMode == JdbcCrawlMode.QUERY && querySql != null && !querySql.isBlank()) {
            return querySql;
        }
        if (tableName != null && !tableName.isBlank()) {
            return tableName;
        }
        return "opencrawling_records";
    }

    private boolean isSql(String str) {
        if (str == null || str.isBlank()) return false;
        String lower = str.trim().toLowerCase();
        return lower.startsWith("select ") || lower.startsWith("with ") || lower.contains(" from ");
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {}
        }
    }
    private String resolveBlobColumn(ResultSetMetaData md, int colCount, String configuredBlobCol) {
        if (configuredBlobCol != null && !configuredBlobCol.isBlank()) {
            return configuredBlobCol.trim();
        }
        try {
            for (int i = 1; i <= colCount; i++) {
                int type = md.getColumnType(i);
                if (type == java.sql.Types.BLOB
                        || type == java.sql.Types.BINARY
                        || type == java.sql.Types.VARBINARY
                        || type == java.sql.Types.LONGVARBINARY) {
                    String name = md.getColumnLabel(i);
                    log.info("Auto-detected binary/BLOB column '{}' of SQL type {}", name, type);
                    return name;
                }
            }
            for (int i = 1; i <= colCount; i++) {
                String name = md.getColumnLabel(i).toLowerCase();
                if (name.equals("blob") || name.equals("image") || name.equals("photo")
                        || name.equals("picture") || name.equals("document") || name.equals("attachment")
                        || name.equals("file_data") || name.equals("file_content") || name.equals("data_blob")
                        || name.equals("bytes") || name.equals("raw_data")) {
                    log.info("Auto-detected binary/BLOB column by naming convention: '{}'", md.getColumnLabel(i));
                    return md.getColumnLabel(i);
                }
            }
        } catch (Exception e) {
            log.debug("Error while auto-detecting BLOB column: {}", e.getMessage());
        }
        return null;
    }

    private byte[] extractBytes(ResultSet rs, int colIndex) {
        try {
            Blob blob = rs.getBlob(colIndex);
            if (blob != null) {
                long len = blob.length();
                if (len > 0 && len <= Integer.MAX_VALUE) {
                    return blob.getBytes(1, (int) len);
                } else if (len > 0) {
                    try (InputStream is = blob.getBinaryStream()) {
                        return is.readAllBytes();
                    }
                }
                return new byte[0];
            }
        } catch (Exception ignored) {}

        try {
            byte[] bytes = rs.getBytes(colIndex);
            if (bytes != null) {
                return bytes;
            }
        } catch (Exception ignored) {}

        try (InputStream is = rs.getBinaryStream(colIndex)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (Exception ignored) {}

        try {
            Clob clob = rs.getClob(colIndex);
            if (clob != null) {
                return clob.getSubString(1, (int) clob.length()).getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}

        try {
            Object obj = rs.getObject(colIndex);
            if (obj instanceof byte[] bytes) {
                return bytes;
            }
            if (obj instanceof String str && !str.isBlank()) {
                if (str.startsWith("data:") && str.contains(";base64,")) {
                    String base64Part = str.substring(str.indexOf(";base64,") + 8).trim();
                    return java.util.Base64.getDecoder().decode(base64Part);
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String detectMimeType(byte[] bytes, String fileName, String explicitMime) {
        if (explicitMime != null && !explicitMime.isBlank()) {
            return explicitMime.trim().toLowerCase();
        }

        if (fileName != null && !fileName.isBlank()) {
            String lower = fileName.trim().toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".bmp")) return "image/bmp";
            if (lower.endsWith(".tiff") || lower.endsWith(".tif")) return "image/tiff";
            if (lower.endsWith(".pdf")) return "application/pdf";
            if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            if (lower.endsWith(".doc")) return "application/msword";
            if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            if (lower.endsWith(".txt")) return "text/plain";
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".xml")) return "application/xml";
            if (lower.endsWith(".csv")) return "text/csv";
            if (lower.endsWith(".md")) return "text/markdown";
        }

        if (bytes != null && bytes.length >= 4) {
            if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
                return "image/png";
            }
            if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
                return "image/jpeg";
            }
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
                return "image/gif";
            }
            if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
                return "image/webp";
            }
            if (bytes[0] == 'B' && bytes[1] == 'M') {
                return "image/bmp";
            }
            if ((bytes[0] == 'I' && bytes[1] == 'I' && bytes[2] == 0x2A && bytes[3] == 0x00)
                    || (bytes[0] == 'M' && bytes[1] == 'M' && bytes[2] == 0x00 && bytes[3] == 0x2A)) {
                return "image/tiff";
            }
            if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
                return "application/pdf";
            }
            if (bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 0x03 && bytes[3] == 0x04) {
                return "application/zip";
            }
        }

        return "application/octet-stream";
    }

    private String narrativizeRow(Map<String, Object> rowMap, String target, String docId) {
        if (compiledNarrativeTemplate != null) {
            try {
                Map<String, Object> context = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : rowMap.entrySet()) {
                    if (entry.getKey() != null) {
                        context.put(entry.getKey(), entry.getValue());
                        context.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                        context.put(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue());
                    }
                }
                return compiledNarrativeTemplate.execute(context);
            } catch (Exception e) {
                log.warn("Failed to execute Mustache narrativization template for doc {}: {}", docId, e.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Record: ").append(docId).append(" (Table: ").append(target).append(")\n\n");
        sb.append("This entity represents a structured tabular record with the following attributes:\n\n");
        for (Map.Entry<String, Object> entry : rowMap.entrySet()) {
            if (entry.getValue() != null) {
                String val = String.valueOf(entry.getValue());
                if (!val.isBlank() && !val.equalsIgnoreCase("null")) {
                    sb.append("- **").append(entry.getKey()).append("**: ").append(val).append("\n");
                }
            }
        }
        return sb.toString();
    }

}
