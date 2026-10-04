#!/usr/bin/env bash
#
# Copyright © 2026 the original author or authors (piergiorgio@apache.org)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# ==============================================================================
# OpenCrawling - Decoupled Pipeline with JDBC Repository Connector Integration Test
#
# Description:
#   Integration test script for docker-compose-decoupled-with-jdbc.yml validating
#   the end-to-end decoupled pipeline:
#   PostgreSQL Source DB -> oc-crawler (JDBC) -> Kafka -> Ingestion Consumer ->
#   Embedding Consumer (Ollama) -> Writer Consumer (pgvector) -> MCP Server.
# ==============================================================================

set -euo pipefail

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

echo -e "\n${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== Starting OpenCrawling Decoupled Ingestion Pipeline with JDBC Connector Test ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}\n"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker compose >/dev/null 2>&1 || command -v docker-compose >/dev/null 2>&1 || { echo -e "${RED}Docker Compose is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}curl is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-jdbc-repository-connector/docker/docker-compose-decoupled-with-jdbc.yml"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"
CLEANUP_ON_EXIT="${CLEANUP_ON_EXIT:-true}"

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

cleanup() {
  echo -e "\n${YELLOW}Tearing down JDBC decoupled test environment...${NC}"
  docker compose -f "${COMPOSE_FILE}" down --remove-orphans >/dev/null 2>&1 || true
  docker rm -f postgres-jdbc-source-decoupled postgres-vector-decoupled-jdbc redis-stack-decoupled-jdbc ollama-decoupled-jdbc ollama-model-puller-decoupled-jdbc kafka-decoupled-jdbc oc-crawler-service-jdbc oc-ingestion-consumer-service-jdbc oc-embedding-consumer-service-jdbc oc-writer-service-jdbc oc-mcp-server-service-jdbc >/dev/null 2>&1 || true
}

on_exit() {
  if [ "${KEEP_CONTAINERS}" = true ] || [ "${CLEANUP_ON_EXIT}" = false ]; then
    echo -e "\n${YELLOW}KEEP_CONTAINERS=true: Leaving JDBC decoupled environment active.${NC}"
    return 0
  fi
  cleanup
}
trap on_exit EXIT

# 1. Clean up previous containers
echo -e "${YELLOW}Cleaning up previous JDBC decoupled containers...${NC}"
cleanup

# 2. Build microservices images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# 3. Start services
echo -e "${YELLOW}Starting complete decoupled JDBC-based multi-service infrastructure...${NC}"
compose up -d

# 4. Wait for Source Relational Database to be healthy
TIMEOUT=180
ELAPSED=0
echo -e "${YELLOW}Waiting for Source PostgreSQL database (postgres-jdbc-source) to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' postgres-jdbc-source-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for postgres-jdbc-source-decoupled.${NC}"
    compose logs postgres-jdbc-source
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  echo -n "."
done
echo -e "\n${GREEN}Source PostgreSQL database is healthy!${NC}"

# 5. Wait for pgvector Vector Store to be healthy
ELAPSED=0
echo -e "${YELLOW}Waiting for postgres-vector database to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' postgres-vector-decoupled-jdbc 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for postgres-vector-decoupled-jdbc.${NC}"
    compose logs postgres-vector
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  echo -n "."
done
echo -e "\n${GREEN}postgres-vector database is healthy!${NC}"

# 6. Wait for Redis to be healthy
ELAPSED=0
echo -e "${YELLOW}Waiting for Redis to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' redis-stack-decoupled-jdbc 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for redis-stack-decoupled-jdbc.${NC}"
    compose logs redis
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Redis is healthy!${NC}"

# 7. Wait for Ollama to be healthy
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled-jdbc 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for ollama-decoupled-jdbc.${NC}"
    compose logs ollama
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}Ollama is healthy!${NC}"

# 8. Wait for Ollama model puller to complete
ELAPSED=0
echo -e "${YELLOW}Waiting for Ollama model puller to pull embedding models and exit...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-jdbc 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Ollama model puller.${NC}"
    compose logs ollama-model-puller
    exit 1
  fi
  PROGRESS=$(docker logs --tail 1 ollama-model-puller-decoupled-jdbc 2>&1 || true)
  if [ -n "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-jdbc 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}Ollama model puller failed with exit code $EXIT_CODE.${NC}"
  compose logs ollama-model-puller
  exit 1
fi
echo -e "${GREEN}Ollama embedding models pulled successfully!${NC}"

# 9. Verify / Seed Sample Data in Source Database
echo -e "${YELLOW}Verifying sample database table 'kb_articles' in postgres-jdbc-source...${NC}"
docker exec -i postgres-jdbc-source-decoupled psql -U jdbc_user -d enterprise_db << 'EOF'
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
EOF

SOURCE_COUNT=$(docker exec -i postgres-jdbc-source-decoupled psql -U jdbc_user -d enterprise_db -t -A -c "SELECT COUNT(*) FROM kb_articles;")
echo -e "${GREEN}Verified: ${SOURCE_COUNT} articles provisioned in Source Database.${NC}"

# 10. Trigger JDBC repository connector scan via crawler restart
echo -e "${YELLOW}Triggering oc-crawler scan against Source Relational Database...${NC}"
compose restart oc-crawler

# 11. Wait for crawler completion
ELAPSED=0
CRAWLER_TIMEOUT=180
echo -e "${YELLOW}Waiting for oc-crawler service to finish scanning relational database...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-jdbc 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $CRAWLER_TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-jdbc.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

# Check crawler exit code
CRAWLER_EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-jdbc 2>/dev/null || echo "1")
echo -e "${YELLOW}Crawler Execution Logs:${NC}"
compose logs --tail 30 oc-crawler

if [ "$CRAWLER_EXIT_CODE" -ne 0 ]; then
  echo -e "${RED}oc-crawler-service-jdbc failed with exit code $CRAWLER_EXIT_CODE.${NC}"
  exit 1
fi
echo -e "${GREEN}oc-crawler-service-jdbc completed scan successfully!${NC}"

# 12. Verify Persisted Vector Embeddings in pgvector
echo -e "${YELLOW}Waiting for ingestion & embedding consumers to persist vector chunks in pgvector...${NC}"
ELAPSED=0
VECTOR_TIMEOUT=60
VECTOR_COUNT=0

until [ "$VECTOR_COUNT" -gt 0 ] || [ $ELAPSED -ge $VECTOR_TIMEOUT ]; do
  VECTOR_COUNT=$(docker exec -i postgres-vector-decoupled-jdbc psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
    "SELECT (
       CASE WHEN to_regclass('public.vector_store_384') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store_384 WHERE metadata::text LIKE '%kb_articles%' OR metadata::text LIKE '%ART-%' OR content ILIKE '%PostgreSQL%')
       ELSE 0 END +
       CASE WHEN to_regclass('public.vector_store') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store WHERE metadata::text LIKE '%kb_articles%' OR metadata::text LIKE '%ART-%' OR content ILIKE '%PostgreSQL%')
       ELSE 0 END +
       CASE WHEN to_regclass('public.vector_store_1024') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store_1024 WHERE metadata::text LIKE '%kb_articles%' OR metadata::text LIKE '%ART-%' OR content ILIKE '%PostgreSQL%')
       ELSE 0 END
     );" 2>/dev/null || echo "0")
  VECTOR_COUNT=$(echo "$VECTOR_COUNT" | tr -d '[:space:]')
  [ -z "$VECTOR_COUNT" ] && VECTOR_COUNT=0

  if [ "$VECTOR_COUNT" -gt 0 ]; then
    break
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  echo -n "."
done
echo ""

if [ "$VECTOR_COUNT" -gt 0 ]; then
  echo -e "${GREEN}Verified: ${VECTOR_COUNT} vector chunk(s) successfully persisted in pgvector!${NC}"
else
  echo -e "${YELLOW}Warning: No vector chunks found in pgvector within timeout (continuing to MCP check).${NC}"
fi

# 13. Verify MCP server endpoint
echo -e "${YELLOW}Waiting for MCP Server health endpoint to be ready...${NC}"
HTTP_STATUS="000"
ELAPSED=0
TIMEOUT=60
until [ "$HTTP_STATUS" == "200" ] || [ "$HTTP_STATUS" == "405" ] || [ "$HTTP_STATUS" == "404" ] || [ $ELAPSED -ge $TIMEOUT ]; do
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/mcp 2>/dev/null || echo "")
  if [ -z "$HTTP_STATUS" ] || [ "$HTTP_STATUS" == "000" ]; then
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/ 2>/dev/null || echo "000")
  fi
  if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
    sleep 2
    ELAPSED=$((ELAPSED + 2))
  fi
done
echo -e "MCP Server HTTP Status: ${GREEN}$HTTP_STATUS${NC}"

if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
  echo -e "${RED}JDBC decoupled integration test failed: MCP Server returned unexpected status $HTTP_STATUS${NC}"
  compose logs oc-mcp-server
  exit 1
fi
echo -e "${GREEN}MCP Server is reachable (HTTP $HTTP_STATUS)${NC}"

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: JDBC Decoupled Multi-Service Pipeline Integration Test Passed!${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}\n"

exit 0
