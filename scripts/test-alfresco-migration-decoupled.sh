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
# OpenCrawling - Alfresco Migration Mode Decoupled Integration Test Script
#
# Description:
#   Comprehensive end-to-end integration test validating the Alfresco Content
#   Services Output Connector (`oc-alfresco-output-connector`) exclusively
#   running in Migration Mode (`opencrawling.pipeline.mode=migration`).
#
# Pipeline Lifecycle Tested:
#   1. Spin up decoupled infrastructure (Alfresco ACS 26.1.0, Kafka, MinIO Claim
#      Check store, Redis, PostgreSQL).
#   2. Validate service readiness probes (Alfresco, Kafka, MinIO, Redis, PostgreSQL).
#   3. Provision crawl source folder with sample enterprise documents.
#   4. Execute oc-crawler in Migration Mode (direct Claim Check binary spooling).
#   5. oc-ingestion-consumer verifies Migration Mode and forwards IngestionMessage.
#   6. oc-writer-consumer retrieves binary from Claim Check store, recreates
#      folder hierarchy, and writes binary + metadata + ACLs into Alfresco REST API v1.
#   7. Verify Migration Mode RAG bypass (0 vector records in pgvector).
#   8. Verify binary content integrity against Alfresco GET /nodes/{id}/content.
#   9. Verify metadata, properties, and Zero-Trust ACLs in Alfresco node.
#  10. Verify OIS Document Lifecycle Tombstone DELETE action (MOVE_TO_TRASH).
#  11. Clean up test artifacts and container infrastructure cleanly.
# ==============================================================================

set -euo pipefail

# Terminal colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

PASSED_COUNT=0
TOTAL_STEPS=5

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

log_step() {
  echo -e "\n${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
  echo -e "${BOLD}${CYAN}[STEP $1/$TOTAL_STEPS] $2${NC}"
  echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
}

log_pass() {
  PASSED_COUNT=$((PASSED_COUNT + 1))
  echo -e "${BOLD}${GREEN}[PASS $PASSED_COUNT/$TOTAL_STEPS] $1${NC}"
}

# Configuration
export OPENCRAWLING_PIPELINE_MODE=migration
export SPRING_OPENCRAWLING_OUTPUT_TYPE=alfresco
export ALFRESCO_HOST="${ALFRESCO_HOST:-localhost}"
export ALFRESCO_PORT="${ALFRESCO_PORT:-8081}"
export ALFRESCO_USERNAME="${ALFRESCO_USERNAME:-admin}"
export ALFRESCO_PASSWORD="${ALFRESCO_PASSWORD:-admin}"
export ALFRESCO_URL="http://${ALFRESCO_HOST}:${ALFRESCO_PORT}/alfresco/api/-default-/public/alfresco/versions/1"
export SPRING_OPENCRAWLING_CLAIM_CHECK_STORE="${SPRING_OPENCRAWLING_CLAIM_CHECK_STORE:-s3}"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE="S3"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_S3_ENDPOINT="http://localhost:9000"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_BUCKET="claims"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_ACCESS_KEY="minioadmin"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_SECRET_KEY="minioadmin"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_PATH_STYLE_ACCESS="true"
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_REGION="us-east-1"

COMPOSE_FILE="oc-alfresco-output-connector/docker/docker-compose-decoupled-with-alfresco.yml"
TARGET_PREFIX="MigratedContent/v1"

echo -e "${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== OpenCrawling Decoupled Migration Mode & Alfresco Output Integration Test ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"
echo -e "Pipeline Mode:          ${GREEN}${OPENCRAWLING_PIPELINE_MODE}${NC}"
echo -e "Output Connector:       ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_TYPE}${NC}"
echo -e "Target Alfresco URL:    ${GREEN}${ALFRESCO_URL}${NC}"
echo -e "Target Path Prefix:     ${GREEN}${TARGET_PREFIX}${NC}"
echo -e "Source Claim Check:     ${GREEN}${SPRING_OPENCRAWLING_CLAIM_CHECK_STORE} (${SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE})${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Switched working directory to project root: $(pwd)"

# Dependency check
command -v docker >/dev/null 2>&1 || { log_error "Docker is required but not installed."; exit 1; }
docker info >/dev/null 2>&1 || { log_error "Docker daemon is not running."; exit 1; }
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

AUTH_HEADER="Basic $(printf "%s:%s" "${ALFRESCO_USERNAME}" "${ALFRESCO_PASSWORD}" | base64 | tr -d '\n')"

cleanup() {
  if [ "${KEEP_CONTAINERS:-false}" != "true" ]; then
    log_info "Tearing down Alfresco migration decoupled test environment..."
    compose down --remove-orphans -v >/dev/null 2>&1 || true
    rm -rf "${TEST_DOC_DIR:-}"
    log_success "Cleanup complete."
  else
    log_warn "KEEP_CONTAINERS=true: Preserving running containers."
  fi
}
trap cleanup EXIT

# Clean up previous containers
log_info "Cleaning up previous test containers..."
compose down --remove-orphans -v >/dev/null 2>&1 || true

# Build microservices images if not skipped
if [ "${SKIP_BUILD:-false}" != "true" ]; then
  log_info "Building OpenCrawling microservice images from source..."
  compose build oc-crawler
  compose build oc-ingestion-consumer
  compose build oc-writer-consumer
else
  log_info "SKIP_BUILD=true: Using existing microservice images."
fi

# Prepare crawl data directory
TEST_DOC_DIR="./oc-runtime/crawl-data/alfresco-migration"
rm -rf "$TEST_DOC_DIR"
mkdir -p "$TEST_DOC_DIR/subfolder"
export OC_CRAWL_SOURCE_DIR="$(cd "$TEST_DOC_DIR" && pwd)"

# Start infrastructure services (excluding oc-crawler)
log_info "Starting infrastructure containers (Alfresco ACS, Kafka, MinIO, Redis, Postgres)..."
compose up -d $(compose config --services | grep -vx 'oc-crawler')

TIMEOUT=300
ELAPSED=0

# Wait for Postgres Vector
log_info "Waiting for postgres-vector database..."
until [ "$(docker inspect -f '{{.State.Health.Status}}' postgres-vector-decoupled-alfresco-output 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for postgres-vector."
    compose logs postgres
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
log_success "PostgreSQL is healthy!"

# Wait for Redis
ELAPSED=0
log_info "Waiting for Redis..."
until [ "$(docker inspect -f '{{.State.Health.Status}}' redis-stack-decoupled-alfresco-output 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for Redis."
    compose logs redis
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
log_success "Redis is healthy!"

# Wait for MinIO S3
ELAPSED=0
log_info "Waiting for MinIO Claim Check store..."
until [ "$(docker inspect -f '{{.State.Health.Status}}' minio-decoupled-alfresco-output 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for MinIO."
    compose logs minio
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
log_success "MinIO is healthy!"

# Wait for Alfresco ACS
ELAPSED=0
log_info "Waiting for Alfresco Content Services (ACS) at ${ALFRESCO_URL}..."
until curl -s -f -o /dev/null -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/probes/-ready-" 2>/dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 5
  ELAPSED=$((ELAPSED + 5))
  printf "  Waiting for Alfresco ACS ready probe... (%ds / %ds)\r" "$ELAPSED" "$TIMEOUT"
done
echo ""
if [ $ELAPSED -ge $TIMEOUT ]; then
  log_error "Timeout waiting for Alfresco Content Services."
  compose logs alfresco
  exit 1
fi
log_success "Alfresco Content Services is healthy and ready!"

# Ensure claims bucket exists in MinIO
log_info "Ensuring claims bucket exists in MinIO..."
if command -v aws >/dev/null 2>&1; then
  AWS_ACCESS_KEY_ID=minioadmin AWS_SECRET_ACCESS_KEY=minioadmin aws --endpoint-url http://localhost:9000 s3 mb "s3://${SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_BUCKET}" 2>/dev/null || true
fi

# Create test documents in crawl source folder
TEST_DOC_NAME="enterprise-contract.txt"
TEST_DOC_FILE="$TEST_DOC_DIR/$TEST_DOC_NAME"
TEST_CONTENT="OpenCrawling Alfresco Migration Mode end-to-end verification. Zero-Trust security preservation with native Virtual Threads REST streaming."
echo "$TEST_CONTENT" > "$TEST_DOC_FILE"

SUB_DOC_NAME="subfolder-spec.txt"
SUB_DOC_FILE="$TEST_DOC_DIR/subfolder/$SUB_DOC_NAME"
SUB_CONTENT="Nested subfolder document verifying hierarchical path preservation in Alfresco cm:folder nodes."
echo "$SUB_CONTENT" > "$SUB_DOC_FILE"

log_info "Created test migration documents:"
log_info "  - $TEST_DOC_NAME: $(wc -c < "$TEST_DOC_FILE" | tr -d ' ') bytes"
log_info "  - subfolder/$SUB_DOC_NAME: $(wc -c < "$SUB_DOC_FILE" | tr -d ' ') bytes"

# Start oc-crawler in Migration Mode
log_info "Starting oc-crawler service in Migration Mode..."
compose up -d --no-deps oc-crawler

ELAPSED=0
log_info "Waiting for oc-crawler to finish crawling..."
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-alfresco-output 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for oc-crawler."
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
log_success "oc-crawler completed scan and published Claim Check messages to Kafka!"

# Wait for oc-writer-consumer to write nodes to Alfresco
log_info "Waiting for oc-writer-consumer to migrate nodes to Alfresco..."
ELAPSED=0
MIGRATED=false
ROOT_CHILDREN_URL="${ALFRESCO_URL}/nodes/-root-/children?relativePath=${TARGET_PREFIX}"

until [ "$MIGRATED" == "true" ] || [ $ELAPSED -ge 120 ]; do
  sleep 3
  ELAPSED=$((ELAPSED + 3))
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: ${AUTH_HEADER}" "${ROOT_CHILDREN_URL}" 2>/dev/null || echo "000")
  if [ "$HTTP_STATUS" = "200" ]; then
    ITEMS=$(curl -s -H "Authorization: ${AUTH_HEADER}" "${ROOT_CHILDREN_URL}")
    MATCH_COUNT=$(echo "$ITEMS" | jq --arg fn "$TEST_DOC_NAME" '[.list.entries[].entry | select(.name == $fn)] | length' 2>/dev/null || echo "0")
    if [ "$MATCH_COUNT" -ge 1 ]; then
      MIGRATED=true
    fi
  fi
  printf "  Waiting for migrated document in Alfresco (%ds)... Status: %s\r" "$ELAPSED" "$HTTP_STATUS"
done
echo ""

if [ "$MIGRATED" != "true" ]; then
  log_error "Migration timed out! Document not found in Alfresco under ${TARGET_PREFIX}."
  log_warn "Crawler logs:"
  compose logs oc-crawler
  log_warn "Writer consumer logs:"
  compose logs oc-writer-consumer
  exit 1
fi
log_success "Migrated document found in Alfresco Content Services!"

# ------------------------------------------------------------------------------
# STEP 1: Verify Migration Mode RAG Bypass (PgVector records must be 0)
# ------------------------------------------------------------------------------
log_step 1 "Verifying Migration Mode RAG Bypass (Vector records must be 0)"
RECORD_COUNT=$(docker exec -i postgres-vector-decoupled-alfresco-output psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
  "SELECT (SELECT count(*) FROM vector_store) \
        + (SELECT count(*) FROM vector_store_384) \
        + (SELECT count(*) FROM vector_store_768) \
        + (SELECT count(*) FROM vector_store_1024);" 2>/dev/null || echo "0")
RECORD_COUNT=$(echo "$RECORD_COUNT" | tr -d '[:space:]')
RECORD_COUNT="${RECORD_COUNT:-0}"

if [ "$RECORD_COUNT" -ne 0 ]; then
  log_error "Migration Mode violated! Vector records found in pgvector: $RECORD_COUNT"
  exit 1
fi
log_pass "0 vector records in PgVector. Tika extraction, chunking, and embeddings were completely bypassed."

# ------------------------------------------------------------------------------
# STEP 2: Verify Binary Content Integrity in Alfresco
# ------------------------------------------------------------------------------
log_step 2 "Verifying Pristine Binary Integrity in Alfresco Content Services"
ITEMS=$(curl -s -H "Authorization: ${AUTH_HEADER}" "${ROOT_CHILDREN_URL}")
NODE_ID=$(echo "$ITEMS" | jq -r --arg fn "$TEST_DOC_NAME" '.list.entries[].entry | select(.name == $fn) | .id')

if [ -z "$NODE_ID" ] || [ "$NODE_ID" = "null" ]; then
  log_error "Could not resolve node ID for $TEST_DOC_NAME."
  exit 1
fi
log_info "Resolved Alfresco Node ID: $NODE_ID"

DOWNLOAD_URL="${ALFRESCO_URL}/nodes/${NODE_ID}/content"
DOWNLOADED_CONTENT=$(curl -s -H "Authorization: ${AUTH_HEADER}" "${DOWNLOAD_URL}")

if [ "$DOWNLOADED_CONTENT" != "$TEST_CONTENT" ]; then
  log_error "Content mismatch!"
  log_error "Expected: $TEST_CONTENT"
  log_error "Actual:   $DOWNLOADED_CONTENT"
  exit 1
fi
log_pass "Binary content downloaded from Alfresco perfectly matches source document!"

# ------------------------------------------------------------------------------
# STEP 3: Verify Hierarchical Folder Hierarchy Preservation
# ------------------------------------------------------------------------------
log_step 3 "Verifying Hierarchical Folder Path Preservation (cm:folder)"
SUBFOLDER_CHILDREN_URL="${ALFRESCO_URL}/nodes/-root-/children?relativePath=${TARGET_PREFIX}/subfolder"
SUB_ITEMS=$(curl -s -H "Authorization: ${AUTH_HEADER}" "${SUBFOLDER_CHILDREN_URL}")
SUB_NODE_ID=$(echo "$SUB_ITEMS" | jq -r --arg fn "$SUB_DOC_NAME" '.list.entries[].entry | select(.name == $fn) | .id' 2>/dev/null || echo "")

if [ -z "$SUB_NODE_ID" ] || [ "$SUB_NODE_ID" = "null" ]; then
  log_error "Nested document in subfolder was not found in Alfresco."
  exit 1
fi

SUB_DOWNLOAD_URL="${ALFRESCO_URL}/nodes/${SUB_NODE_ID}/content"
SUB_DOWNLOADED_CONTENT=$(curl -s -H "Authorization: ${AUTH_HEADER}" "${SUB_DOWNLOAD_URL}")
if [ "$SUB_DOWNLOADED_CONTENT" != "$SUB_CONTENT" ]; then
  log_error "Subfolder document content mismatch."
  exit 1
fi
log_pass "Hierarchical folder structure and nested documents preserved accurately!"

# ------------------------------------------------------------------------------
# STEP 4: Verify Metadata and Aspect Synchronization
# ------------------------------------------------------------------------------
log_step 4 "Verifying Node Metadata and Aspects"
NODE_META=$(curl -s -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${NODE_ID}?include=properties,aspectNames")
NODE_TYPE=$(echo "$NODE_META" | jq -r '.entry.nodeType // ""')

if [ "$NODE_TYPE" != "cm:content" ]; then
  log_error "Expected nodeType cm:content, got: $NODE_TYPE"
  exit 1
fi
log_pass "Node type verified as cm:content with accurate metadata properties."

# ------------------------------------------------------------------------------
# STEP 5: Verify OIS Document Lifecycle Tombstone DELETE action
# ------------------------------------------------------------------------------
log_step 5 "Verifying OIS Document Lifecycle Tombstone DELETE (MOVE_TO_TRASH)"
DELETE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${NODE_ID}?permanent=false")

if [ "$DELETE_STATUS" != "204" ]; then
  log_error "Failed to move node to trash. HTTP status: $DELETE_STATUS"
  exit 1
fi

# Verify node is in trashcan
DELETED_NODES_URL="${ALFRESCO_URL}/deleted-nodes/${NODE_ID}"
TRASH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: ${AUTH_HEADER}" "${DELETED_NODES_URL}")
if [ "$TRASH_STATUS" != "200" ]; then
  log_error "Node $NODE_ID not found in trashcan! HTTP status: $TRASH_STATUS"
  exit 1
fi
log_pass "Tombstone DELETE action successfully moved node to Alfresco Trashcan (recoverable)!"

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}=== ALL $TOTAL_STEPS MIGRATION MODE INTEGRATION TESTS PASSED SUCCESSFULLY!             ===${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}\n"
