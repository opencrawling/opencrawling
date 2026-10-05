--
-- Copyright © 2026 the original author or authors (piergiorgio@apache.org)
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

CREATE TABLE IF NOT EXISTS kb_articles (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content_body TEXT NOT NULL,
    file_data BYTEA,
    author VARCHAR(100) NOT NULL,
    department VARCHAR(100) NOT NULL,
    is_deleted BOOLEAN DEFAULT FALSE,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO kb_articles (id, title, content_body, file_data, author, department, is_deleted, updated_at) VALUES
('ART-001', 'PostgreSQL Decoupled Pipeline Guide', 'OpenCrawling seamlessly connects to PostgreSQL using the JDBC Repository Connector to stream records and ingest content.', NULL, 'Database Admin', 'Engineering', FALSE, NOW()),
('ART-002', 'Tabular RAG and Narrativization Architecture', 'Tabular RAG converts structured rows into coherent markdown narratives enriched with column semantics and claim check support.', NULL, 'Data Scientist', 'AI Research', FALSE, NOW()),
('ART-003', 'Legacy Obsolete Document', 'This document is flagged as soft deleted and should produce an Open Ingestion Standard tombstone delete event.', NULL, 'Auditor', 'Compliance', TRUE, NOW()),
('ART-004', 'Architecture Diagram', 'Enterprise architecture diagram image binary blob.', decode('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489', 'hex'), 'Lead Architect', 'Architecture', FALSE, NOW())
ON CONFLICT (id) DO NOTHING;
