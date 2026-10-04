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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.connector.ConnectorSchema;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;

import reactor.test.StepVerifier;

public class JdbcRepositoryConnectorTest {

    private static final String H2_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
    private Connection dbConnection;

    @BeforeEach
    void setUp() throws Exception {
        dbConnection = DriverManager.getConnection(H2_URL, "sa", "");
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
            stmt.execute("""
                CREATE TABLE support_tickets (
                    id INT PRIMARY KEY,
                    title VARCHAR(255),
                    description VARCHAR(1000),
                    status VARCHAR(50),
                    owner_id VARCHAR(100),
                    department_id VARCHAR(100),
                    tenant_id VARCHAR(100),
                    is_deleted BOOLEAN DEFAULT FALSE,
                    updated_at TIMESTAMP
                )
            """);

            stmt.execute("""
                INSERT INTO support_tickets (id, title, description, status, owner_id, department_id, tenant_id, is_deleted, updated_at)
                VALUES (1, 'Login failure', 'User cannot log in with SSO', 'OPEN', 'alice', 'it_support', 'tenant_a', FALSE, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO support_tickets (id, title, description, status, owner_id, department_id, tenant_id, is_deleted, updated_at)
                VALUES (2, 'Database latency', 'Query takes 5 seconds', 'IN_PROGRESS', 'bob', 'database_team', 'tenant_a', FALSE, CURRENT_TIMESTAMP)
            """);
            stmt.execute("""
                INSERT INTO support_tickets (id, title, description, status, owner_id, department_id, tenant_id, is_deleted, updated_at)
                VALUES (3, 'Stale record', 'Obsolete ticket', 'RESOLVED', 'charlie', 'ops', 'tenant_b', TRUE, CURRENT_TIMESTAMP)
            """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dbConnection != null && !dbConnection.isClosed()) {
            dbConnection.close();
        }
    }

    @Test
    void testConnectAndGetName() throws Exception {
        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
            "support_tickets", "", Set.of("id"), "title", Set.of(),
            "", "", "", "",
            false, "updated_at", "timestamp",
            false, "is_deleted", "true",
            false, Set.of(), Set.of(), "", "read",
            100, 50, 5, 1, 10000L
        );

        assertThat(connector.getName()).isEqualTo("JdbcConnector");
        connector.connect();
        connector.disconnect();
    }

    @Test
    void testGetSchemaForTable() throws Exception {
        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
            "support_tickets", "", Set.of("id"), "title", Set.of(),
            "", "", "", "",
            false, "updated_at", "timestamp",
            false, "is_deleted", "true",
            false, Set.of(), Set.of(), "", "read",
            100, 50, 5, 1, 10000L
        );

        ConnectorSchema schema = connector.getSchema("support_tickets");
        assertThat(schema).isNotNull();
        assertThat(schema.fields()).isNotEmpty();

        List<String> fieldNames = schema.fields().stream().map(ConnectorSchema.SchemaField::name).map(String::toUpperCase).toList();
        assertThat(fieldNames).contains("ID", "TITLE", "STATUS", "OWNER_ID", "IS_DELETED");
        connector.disconnect();
    }

    @Test
    void testScanTableWithSoftDeleteAndSecurity() throws Exception {
        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
            "support_tickets", "", Set.of("id"), "title", Set.of(),
            "", "", "", "",
            false, "updated_at", "timestamp",
            true, "is_deleted", "true",
            true, Set.of("owner_id"), Set.of("department_id"), "tenant_id", "read",
            100, 50, 5, 1, 10000L
        );

        List<RepositoryDocument> docs = connector.scan("support_tickets").collectList().block();
        assertThat(docs).hasSize(3);

        // Document 1: Active
        RepositoryDocument doc1 = docs.stream().filter(d -> d.id().equals("1")).findFirst().orElseThrow();
        assertThat(doc1.action()).isEqualTo(DocumentAction.UPSERT);
        assertThat(doc1.metadata().get("title")).contains("Login failure");
        assertThat(doc1.metadata().get("name")).contains("Login failure");
        assertThat(doc1.metadata().get("tenant_id")).contains("tenant_a");
        assertThat(doc1.security().permissions()).hasSize(2);
        assertThat(doc1.security().permissions().stream().map(p -> p.identity())).contains("alice", "it_support");

        // Verify content stream
        try (InputStream in = doc1.contentStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("# Record: 1");
            assertThat(content).contains("Login failure");
        }

        // Document 3: Soft-deleted tombstone
        RepositoryDocument doc3 = docs.stream().filter(d -> d.id().equals("3")).findFirst().orElseThrow();
        assertThat(doc3.action()).isEqualTo(DocumentAction.DELETE);

        connector.disconnect();
    }

    @Test
    void testScanCustomSqlQueryMode() throws Exception {
        String sql = "SELECT id, title, status FROM support_tickets WHERE status = 'OPEN'";

        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.QUERY,
            "", "", Set.of("id"), "title", Set.of(),
            sql, "", "", "",
            false, "updated_at", "timestamp",
            false, "is_deleted", "true",
            false, Set.of(), Set.of(), "", "read",
            100, 50, 5, 1, 10000L
        );

        List<RepositoryDocument> docs = connector.scan(sql).collectList().block();
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).id()).isEqualTo("1");
        assertThat(docs.get(0).metadata().get("title")).contains("Login failure");

        connector.disconnect();
    }

    @Test
    void testScanWithBlobColumn() throws Exception {
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute("""
                CREATE TABLE file_records (
                    file_id INT PRIMARY KEY,
                    file_name VARCHAR(100),
                    file_data BLOB
                )
            """);

            byte[] pdfDummyBytes = "%PDF-1.4 dummy binary content".getBytes(StandardCharsets.UTF_8);
            try (PreparedStatement pstmt = dbConnection.prepareStatement("INSERT INTO file_records (file_id, file_name, file_data) VALUES (?, ?, ?)")) {
                pstmt.setInt(1, 101);
                pstmt.setString(2, "invoice.pdf");
                pstmt.setBytes(3, pdfDummyBytes);
                pstmt.executeUpdate();
            }
        }

        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
            "file_records", "", Set.of("file_id"), "file_name", Set.of(),
            "", "file_data", "file_name", "",
            false, "updated_at", "timestamp",
            false, "is_deleted", "true",
            false, Set.of(), Set.of(), "", "read",
            100, 50, 5, 1, 10000L
        );

        List<RepositoryDocument> docs = connector.scan("file_records").collectList().block();
        assertThat(docs).hasSize(1);

        RepositoryDocument doc = docs.get(0);
        assertThat(doc.id()).isEqualTo("101");
        assertThat(doc.metadata().get("filename")).contains("invoice.pdf");

        try (InputStream in = doc.contentStream()) {
            byte[] readBytes = in.readAllBytes();
            assertThat(new String(readBytes, StandardCharsets.UTF_8)).isEqualTo("%PDF-1.4 dummy binary content");
        }

        connector.disconnect();
    }

    @Test
    void testBatchProcessingWithStructuredConcurrency() throws Exception {
        // Table has 3 records. Setting batchSize = 1 forces 3 separate batches of StructuredTaskScope.
        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
            "support_tickets", "", Set.of("id"), "title", Set.of(),
            "", "", "", "",
            false, "updated_at", "timestamp",
            false, "is_deleted", "true",
            false, Set.of(), Set.of(), "", "read",
            100, 1, 5, 1, 10000L
        );

        List<RepositoryDocument> docs = connector.scan("support_tickets").collectList().block();
        assertThat(docs).hasSize(3);
        assertThat(docs.stream().map(RepositoryDocument::id).toList()).containsExactlyInAnyOrder("1", "2", "3");

        connector.disconnect();
    }

    @Test
    void testCustomSpringTaskExecutorAndDecorator() throws Exception {
        org.springframework.core.task.SimpleAsyncTaskExecutor customExecutor = new org.springframework.core.task.SimpleAsyncTaskExecutor("custom-spring-vt-");
        customExecutor.setVirtualThreads(true);

        java.util.concurrent.atomic.AtomicInteger decoratedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        org.springframework.core.task.TaskDecorator decorator = runnable -> () -> {
            decoratedCount.incrementAndGet();
            runnable.run();
        };

        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
            "support_tickets", "", Set.of("id"), "title", Set.of(),
            "", "", "", "",
            false, "updated_at", "timestamp",
            false, "is_deleted", "true",
            false, Set.of(), Set.of(), "", "read",
            100, 2, 5, 1, 10000L
        );
        connector.setTaskExecutor(customExecutor);
        connector.setTaskDecorator(decorator);

        List<RepositoryDocument> docs = connector.scan("support_tickets").collectList().block();
        assertThat(docs).hasSize(3);
        assertThat(decoratedCount.get()).isGreaterThanOrEqualTo(4);

        connector.disconnect();
    }

    @Test
    void testMultiTableConcurrentScanning() throws Exception {
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute("""
                CREATE TABLE customers (
                    cust_id INT PRIMARY KEY,
                    cust_name VARCHAR(100)
                )
            """);
            stmt.execute("INSERT INTO customers (cust_id, cust_name) VALUES (10, 'Acme Corp')");
            stmt.execute("INSERT INTO customers (cust_id, cust_name) VALUES (20, 'Global Tech')");
        }

        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
            "", "", Set.of("id", "cust_id"), "title", Set.of(),
            "", "", "", "",
            false, "updated_at", "timestamp",
            false, "is_deleted", "true",
            false, Set.of(), Set.of(), "", "read",
            100, 10, 5, 1, 10000L,
            64, true
        );

        List<RepositoryDocument> docs = connector.scan("support_tickets,customers").collectList().block();
        assertThat(docs).hasSize(5);
        List<String> ids = docs.stream().map(RepositoryDocument::id).toList();
        assertThat(ids).contains("1", "2", "3", "10", "20");

        connector.disconnect();
    }

    @Test
    void testScanImageBlobWithMagicBytesAndDirectEmbeddingMetadata() throws Exception {
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute("""
                CREATE TABLE media_assets (
                    asset_id INT PRIMARY KEY,
                    caption VARCHAR(100),
                    image_bytes BLOB
                )
            """);

            // PNG magic bytes header: 89 50 4E 47 0D 0A 1A 0A
            byte[] pngBytes = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00
            };

            try (PreparedStatement pstmt = dbConnection.prepareStatement("INSERT INTO media_assets (asset_id, caption, image_bytes) VALUES (?, ?, ?)")) {
                pstmt.setInt(1, 201);
                pstmt.setString(2, "Company Logo");
                pstmt.setBytes(3, pngBytes);
                pstmt.executeUpdate();
            }
        }

        // Test auto-detection: blobColumnName is left empty ("")
        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
            "media_assets", "", Set.of("asset_id"), "caption", Set.of(),
            "", "", "", "",
            false, "updated_at", "timestamp",
            false, "is_deleted", "true",
            false, Set.of(), Set.of(), "", "read",
            100, 50, 5, 1, 10000L
        );

        List<RepositoryDocument> docs = connector.scan("media_assets").collectList().block();
        assertThat(docs).hasSize(1);

        RepositoryDocument doc = docs.get(0);
        assertThat(doc.id()).isEqualTo("201");
        assertThat(doc.metadata().get("caption")).contains("Company Logo");
        assertThat(doc.metadata().get("is_blob")).contains("true");
        assertThat(doc.metadata().get("is_image")).contains("true");
        assertThat(doc.metadata().get("media_type")).contains("image");
        assertThat(doc.metadata().get("mimeType")).contains("image/png");

        try (InputStream in = doc.contentStream()) {
            byte[] readBytes = in.readAllBytes();
            assertThat(readBytes[0]).isEqualTo((byte) 0x89);
            assertThat(readBytes[1]).isEqualTo((byte) 0x50);
            assertThat(readBytes[2]).isEqualTo((byte) 0x4E);
            assertThat(readBytes[3]).isEqualTo((byte) 0x47);
        }

        connector.disconnect();
    }

    @Test
    void testScanTabularRecordNarrativization() throws Exception {
        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
            "support_tickets", "", Set.of("id"), "title", Set.of(),
            "", "", "", "",
            false, "updated_at", "timestamp",
            false, "is_deleted", "true",
            false, Set.of(), Set.of(), "", "read",
            100, 50, 5, 1, 10000L
        );

        List<RepositoryDocument> docs = connector.scan("support_tickets").collectList().block();
        assertThat(docs).hasSize(3);

        RepositoryDocument doc1 = docs.stream().filter(d -> d.id().equals("1")).findFirst().orElseThrow();
        assertThat(doc1.metadata().get("is_tabular")).contains("true");
        assertThat(doc1.metadata().get("is_blob")).contains("false");
        assertThat(doc1.metadata().get("media_type")).contains("tabular");

        try (InputStream in = doc1.contentStream()) {
            String narrative = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(narrative).contains("# Record: 1 (Table: support_tickets)");
            assertThat(narrative).containsIgnoringCase("- **title**: Login failure");
            assertThat(narrative).containsIgnoringCase("- **status**: OPEN");
        }

        connector.disconnect();
    }

    @Test
    void testScanWithCustomMustacheNarrativization() throws Exception {
        String template = "# Support Ticket {{id}}\nTitle: {{title}}\nStatus: {{status}}";

        JdbcRepositoryConnector connector = new JdbcRepositoryConnector(
            H2_URL, "org.h2.Driver", "sa", "", JdbcCrawlMode.TABLE,
            "support_tickets", "", Set.of("id"), "title", Set.of(),
            "", "", "", "",
            false, "updated_at", "timestamp",
            false, "is_deleted", "true",
            false, Set.of(), Set.of(), "", "read",
            100, 50, 5, 1, 10000L,
            64, true, template
        );

        List<RepositoryDocument> docs = connector.scan("support_tickets").collectList().block();
        assertThat(docs).hasSize(3);

        RepositoryDocument doc1 = docs.stream().filter(d -> d.id().equals("1")).findFirst().orElseThrow();
        try (InputStream in = doc1.contentStream()) {
            String narrative = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(narrative).isEqualTo("# Support Ticket 1\nTitle: Login failure\nStatus: OPEN");
        }

        RepositoryDocument doc2 = docs.stream().filter(d -> d.id().equals("2")).findFirst().orElseThrow();
        try (InputStream in = doc2.contentStream()) {
            String narrative = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(narrative).isEqualTo("# Support Ticket 2\nTitle: Database latency\nStatus: IN_PROGRESS");
        }

        connector.disconnect();
    }
}
