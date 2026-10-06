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
# OpenCrawling - JDBC Repository Connector Integration Test Script
#
# Description:
#   Validates relational database connectivity, schema introspection, tabular RAG,
#   BLOB streaming, soft-delete tombstones, security ACL mapping, and executes
#   the Maven test suite for oc-jdbc-repository-connector across H2 and PostgreSQL.
# ==============================================================================

set -euo pipefail

# Configuration
POSTGRES_PORT="${POSTGRES_PORT:-5438}"
CONTAINER_NAME="opencrawling-postgres-jdbc-test"
SKIP_DOCKER="${SKIP_DOCKER:-false}"
CLEANUP_CONTAINER="${CLEANUP_CONTAINER:-true}"
TIMEOUT="${TIMEOUT:-60}"

# Terminal Formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

echo -e "\n${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== Starting OpenCrawling JDBC Repository Connector Test Suite ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}\n"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (mvn, curl, jq)..."
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }

# Cleanup trap
cleanup() {
  if [ "${CLEANUP_CONTAINER}" = true ] && command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    log_info "Cleaning up temporary PostgreSQL test container (${CONTAINER_NAME})..."
    docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# 2. Run Embedded H2 Test Suite
log_info "Executing Maven test suite for oc-jdbc-repository-connector (Embedded H2 Mode)..."
mvn clean test -pl oc-jdbc-repository-connector -am

log_success "Embedded H2 tests completed successfully!"

# 3. Optional Dockerized PostgreSQL Live Integration Test
if [ "${SKIP_DOCKER}" = false ] && command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  log_info "Docker daemon is running. Starting live PostgreSQL test instance on port ${POSTGRES_PORT}..."
  
  cleanup
  
  docker run -d --name "$CONTAINER_NAME" \
    -p "${POSTGRES_PORT}:5432" \
    -e POSTGRES_DB=testdb \
    -e POSTGRES_USER=testuser \
    -e POSTGRES_PASSWORD=testpass \
    postgres:17-alpine >/dev/null

  log_info "Waiting for PostgreSQL service to become ready..."
  ELAPSED=0
  READY=false
  until [ "$READY" = true ]; do
    if docker exec "$CONTAINER_NAME" pg_isready -U testuser -d testdb >/dev/null 2>&1; then
      READY=true
      log_success "PostgreSQL test container is ready!"
      break
    fi
    if [ $ELAPSED -ge $TIMEOUT ]; then
      log_error "Timed out waiting for PostgreSQL container."
      docker logs "$CONTAINER_NAME" --tail 30 || true
      exit 1
    fi
    sleep 2
    ELAPSED=$((ELAPSED + 2))
    echo -n "."
  done
  echo ""

  # Provision sample table with records, BLOB bytea, and soft deletes
  log_info "Provisioning sample schema 'kb_articles' in live PostgreSQL..."
  docker exec -i "$CONTAINER_NAME" psql -U testuser -d testdb << 'EOF'
CREATE TABLE kb_articles (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content_body TEXT NOT NULL,
    binary_blob BYTEA,
    author VARCHAR(100),
    department VARCHAR(100),
    is_deleted BOOLEAN DEFAULT FALSE,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO kb_articles (id, title, content_body, binary_blob, author, department, is_deleted) VALUES
('DOC-1', 'PostgreSQL RAG Guide', 'Architecture overview of tabular RAG and relational data ingestion.', decode('506f737467726553514c20424c4f42205061796c6f6164', 'hex'), 'John Doe', 'Engineering', FALSE),
('DOC-2', 'Security Protocols', 'Guidelines for role-based access control and tenant isolation.', NULL, 'Jane Smith', 'Security', FALSE),
('DOC-3', 'Deprecated Manual', 'Obsolete document flagged for deletion via soft delete tombstone.', NULL, 'Auditor', 'Legal', TRUE);
EOF

  log_success "Sample data successfully provisioned into PostgreSQL!"

  # Validate query execution
  ROW_COUNT=$(docker exec -i "$CONTAINER_NAME" psql -U testuser -d testdb -t -A -c "SELECT COUNT(*) FROM kb_articles;")
  log_info "Verified row count in PostgreSQL: ${ROW_COUNT} records."

  # Run Maven tests targeting the live PostgreSQL container
  log_info "Running connector tests targeting live PostgreSQL instance..."
  mvn test -pl oc-jdbc-repository-connector \
    -Dspring.opencrawling.connector.jdbc.url="jdbc:postgresql://localhost:${POSTGRES_PORT}/testdb" \
    -Dspring.opencrawling.connector.jdbc.driver-class-name="org.postgresql.Driver" \
    -Dspring.opencrawling.connector.jdbc.username="testuser" \
    -Dspring.opencrawling.connector.jdbc.password="testpass" \
    -Dspring.opencrawling.connector.jdbc.table-name="kb_articles" \
    -Dspring.opencrawling.connector.jdbc.id-column="id"

  log_success "Live PostgreSQL connector verification passed!"
else
  log_warn "Docker is not available or SKIP_DOCKER=true. Skipping live PostgreSQL test container."
fi

# 4. Verify ConnectorCheckerService Health Check
log_info "Verifying ConnectorCheckerService JDBC connection probing..."
mvn test -pl oc-runtime -am -Dtest=ConnectorCheckerServiceTest -Dsurefire.failIfNoSpecifiedTests=false

log_success "ConnectorCheckerService verification passed!"

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}=== All JDBC Repository Connector Integration Tests Passed Successfully! ===${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}\n"

exit 0
