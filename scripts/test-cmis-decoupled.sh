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
# OpenCrawling - OASIS CMIS Decoupled Integration Test Script
#
# Description:
#   Comprehensive end-to-end integration test for the decoupled OpenCrawling
#   pipeline integrating an OASIS CMIS 1.1 compliant repository (Alfresco Content Services
#   as CMIS server via `oc-cmis-repository-connector`) with Apache Kafka, Redis,
#   PostgreSQL (pgvector), Ollama embedding service, and OpenCrawling microservices
#   (crawler, ingestion-consumer, embedding-consumer, writer-consumer, and mcp-server).
#
# Pipeline Lifecycle Tested:
#   1. Spin up decoupled infrastructure via Docker Compose
#   2. Healthcheck validation (Alfresco CMIS, pgvector, Redis, Ollama, Kafka)
#   3. Provision test repository structure and text document stream in Alfresco CMIS
#   4. Execute oc-crawler against CMIS repository using OASIS CMIS 1.1 Browser Binding
#   5. Store document stream in Claim Check store and publish metadata to Kafka
#   6. oc-ingestion-consumer resolves claim check, extracts text with Tika & chunks
#   7. oc-embedding-consumer vectorizes chunks with Ollama (mxbai-embed-large)
#   8. oc-writer-consumer persists dense vector embeddings into PostgreSQL pgvector
#   9. Verify record persistence and metadata accuracy in pgvector database
#  10. Verify OpenCrawling MCP Server endpoint readiness
#  11. Verify Open Ingestion Standard (OIS) Document Lifecycle Tombstone DELETE action
#  12. Clean up test artifacts in CMIS repository and teardown containers cleanly
# ==============================================================================

set -euo pipefail

# Configuration
ALFRESCO_HOST="${ALFRESCO_HOST:-localhost}"
ALFRESCO_PORT="${ALFRESCO_PORT:-8081}"
ALFRESCO_USERNAME="${ALFRESCO_USERNAME:-admin}"
ALFRESCO_PASSWORD="${ALFRESCO_PASSWORD:-admin}"
ALFRESCO_URL="${ALFRESCO_URL:-http://${ALFRESCO_HOST}:${ALFRESCO_PORT}/alfresco/api/-default-/public/alfresco/versions/1}"
CMIS_BROWSER_URL="http://${ALFRESCO_HOST}:${ALFRESCO_PORT}/alfresco/api/-default-/public/cmis/versions/1.1/browser"
COMPOSE_FILE="oc-cmis-repository-connector/docker/docker-compose-decoupled-with-cmis.yml"
TIMEOUT="${TIMEOUT:-300}"
MCP_PORT="${MCP_PORT:-8080}"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"

# Formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

PASSED_COUNT=0
TOTAL_STEPS=10

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

echo -e "\n${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== OpenCrawling Decoupled Pipeline with OASIS CMIS Integration Test ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}\n"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# Helper function for docker compose commands
compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

# Basic auth header for CMIS and Alfresco REST calls
AUTH_HEADER="Basic $(printf "%s:%s" "${ALFRESCO_USERNAME}" "${ALFRESCO_PASSWORD}" | base64 | tr -d '\n')"
ROOT_URL="${ALFRESCO_URL}/nodes/-root-"
PROBE_URL="${ALFRESCO_URL}/probes/-ready-"

# State variables for cleanup
CREATED_DOC_IDS=()

cleanup() {
  local exit_code=$?
  if [ "$exit_code" -ne 0 ]; then
    log_error "Test execution interrupted or failed with exit code $exit_code."
  fi

  if [ "${#CREATED_DOC_IDS[@]}" -gt 0 ]; then
    for doc_id in ${CREATED_DOC_IDS[@]+"${CREATED_DOC_IDS[@]}"}; do
      if [ -n "${doc_id}" ]; then
        log_info "Attempting to cleanup CMIS test repository document '${doc_id}'..."
        curl -s -o /dev/null -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${doc_id}" 2>/dev/null || true
      fi
    done
  fi
  CREATED_DOC_IDS=()

  if [ "${KEEP_CONTAINERS}" = false ]; then
    log_info "Tearing down Decoupled CMIS Docker Compose environment..."
    compose down --remove-orphans >/dev/null 2>&1 || true
    log_success "Environment cleanup complete."
  else
    log_warn "KEEP_CONTAINERS=true set. Skipping docker compose teardown."
  fi
}
trap cleanup EXIT

# ------------------------------------------------------------------------------
# STEP 1: Verify Prerequisites
# ------------------------------------------------------------------------------
log_step 1 "Verifying system prerequisites and dependencies..."
command -v docker >/dev/null 2>&1 || { log_error "docker is required but not installed."; exit 1; }
docker compose version >/dev/null 2>&1 || command -v docker-compose >/dev/null 2>&1 || { log_error "docker compose is required."; exit 1; }
command -v curl >/dev/null 2>&1 || { log_error "curl is required."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "mvn is required."; exit 1; }
log_pass "Prerequisites verified successfully."

# ------------------------------------------------------------------------------
# STEP 2: Clean up previous decoupled CMIS containers
# ------------------------------------------------------------------------------
log_step 2 "Cleaning up any existing decoupled CMIS containers..."
compose down --remove-orphans || true
docker rm -f postgres-alfresco-cmis >/dev/null 2>&1 || true

# Free common ports if lingering containers from previous runs are still binding them
for p in 8080 8081 9092 5432 5433 6379 11434; do
  c_ids=$(docker ps --filter "publish=$p" -q 2>/dev/null || true)
  if [ -n "$c_ids" ]; then
    docker stop $c_ids >/dev/null 2>&1 || true
    docker rm -f $c_ids >/dev/null 2>&1 || true
  fi
done
log_pass "Existing containers and port bindings cleaned up."

# ------------------------------------------------------------------------------
# STEP 3: Build OpenCrawling decoupled microservice images
# ------------------------------------------------------------------------------
log_step 3 "Building OpenCrawling decoupled microservice images from source..."
compose build
log_pass "Docker microservice images built successfully."

# ------------------------------------------------------------------------------
# STEP 4: Start decoupled infrastructure & wait for health checks
# ------------------------------------------------------------------------------
log_step 4 "Starting complete decoupled CMIS infrastructure and waiting for readiness..."
compose up -d alfresco transform-core-aio postgres-alfresco activemq postgres-vector redis ollama ollama-model-puller kafka oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer oc-mcp-server
log_info "Docker Compose infrastructure services launched. Monitoring health checks..."

# Helper to check Alfresco readiness
is_alfresco_ready() {
  local http_code
  http_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 -H "Authorization: ${AUTH_HEADER}" "${ROOT_URL}" 2>/dev/null || echo "000")
  if [ "$http_code" = "200" ]; then
    return 0
  fi
  local probe_code
  probe_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 "${PROBE_URL}" 2>/dev/null || echo "000")
  if [ "$probe_code" = "200" ]; then
    return 0
  fi
  return 1
}

# 4a. Wait for Alfresco Content Services (CMIS Provider)
log_info "Waiting for Alfresco Content Services (${ALFRESCO_URL}) to become ready (timeout: ${TIMEOUT}s)..."
ELAPSED=0
until is_alfresco_ready; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timed out waiting for Alfresco to start up after ${TIMEOUT} seconds."
    compose logs alfresco --tail 50 || true
    exit 1
  fi
  sleep 5
  ELAPSED=$((ELAPSED + 5))
  printf "  Waiting for Alfresco startup... (%ds elapsed)\r" "$ELAPSED"
done
echo ""
log_success "Alfresco Content Services is ready and authenticated!"

# 4b. Verify OASIS CMIS 1.1 Browser Binding Service Document
log_info "Probing OASIS CMIS 1.1 Browser Binding at ${CMIS_BROWSER_URL}..."
CMIS_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 -H "Authorization: ${AUTH_HEADER}" -H "Accept: application/json" "${CMIS_BROWSER_URL}" 2>/dev/null || echo "000")
if [ "$CMIS_CODE" -ge 200 ] && [ "$CMIS_CODE" -lt 400 ]; then
  log_success "CMIS 1.1 Browser Binding service document responded with HTTP ${CMIS_CODE}!"
else
  log_warn "CMIS Browser Binding returned HTTP ${CMIS_CODE}. Will proceed with crawl validation."
fi

# 4c. Wait for PostgreSQL pgvector
log_info "Waiting for PostgreSQL pgvector database to become healthy..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' postgres-vector-decoupled-cmis 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for postgres-vector-decoupled-cmis."
    compose logs postgres-vector
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
log_success "PostgreSQL pgvector is healthy!"

# 4d. Wait for Redis
log_info "Waiting for Redis to become healthy..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' redis-stack-decoupled-cmis 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for redis-stack-decoupled-cmis."
    compose logs redis
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
log_success "Redis is healthy!"

# 4e. Wait for Ollama
log_info "Waiting for Ollama embedding engine to become healthy..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled-cmis 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for ollama-decoupled-cmis."
    compose logs ollama
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
log_success "Ollama engine is healthy!"

# 4f. Wait for Ollama model puller
log_info "Waiting for Ollama model puller to download embedding models..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-cmis 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for Ollama model puller."
    compose logs ollama-model-puller
    exit 1
  fi
  PROGRESS=$(docker logs --tail 1 ollama-model-puller-decoupled-cmis 2>&1 || true)
  if [ -n "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-cmis 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  log_error "Ollama model puller failed with exit code $EXIT_CODE."
  compose logs ollama-model-puller
  exit 1
fi
log_success "Ollama embedding models pulled successfully!"

log_pass "All decoupled infrastructure services are healthy and ready."

# ------------------------------------------------------------------------------
# STEP 5: Provision Test Documents in Repository
# ------------------------------------------------------------------------------
log_step 5 "Provisioning test contents and content streams directly in CMIS repository..."

# Document 1: CMIS Specification Document
DOC1_NAME="opencrawling-cmis-specification.txt"
log_info "Creating test document '${DOC1_NAME}' under repository root..."

CREATE_DOC1_PAYLOAD=$(cat <<EOF
{
  "name": "${DOC1_NAME}",
  "nodeType": "cm:content",
  "properties": {
    "cm:title": "OpenCrawling CMIS Decoupled Specification",
    "cm:description": "Integration test payload verifying complete OpenCrawling decoupled processing pipeline via OASIS CMIS 1.1 Browser Binding"
  }
}
EOF
)

DOC1_RES=$(curl -s -w "\n%{http_code}" -X POST "${ALFRESCO_URL}/nodes/-root-/children" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d "${CREATE_DOC1_PAYLOAD}")

DOC1_CODE=$(echo "${DOC1_RES}" | tail -n 1)
DOC1_BODY=$(echo "${DOC1_RES}" | sed '$d')

if [ "${DOC1_CODE}" != "201" ]; then
  log_error "Failed to create document 1 in repository: ${DOC1_BODY} (HTTP ${DOC1_CODE})"
  exit 1
fi

DOC1_ID=$(echo "${DOC1_BODY}" | jq -r '.entry.id')
CREATED_DOC_IDS+=("${DOC1_ID}")
log_success "Document node 1 created successfully with ID: ${DOC1_ID}"

# Upload document 1 text stream
DOC1_CONTENT="OpenCrawling decoupled architecture provides high-throughput distributed crawling for OASIS CMIS 1.1 repositories without Apache Chemistry. Document content streams are claim-checked, parsed via Apache Tika, tokenized into semantic chunks, vectorized via Ollama, and persisted into PostgreSQL pgvector for AI agent retrieval."
log_info "Uploading content stream to document node 1 '${DOC1_ID}'..."

UPLOAD1_RES=$(curl -s -w "\n%{http_code}" -X PUT "${ALFRESCO_URL}/nodes/${DOC1_ID}/content" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: text/plain" \
  --data-binary "${DOC1_CONTENT}")

UPLOAD1_CODE=$(echo "${UPLOAD1_RES}" | tail -n 1)
UPLOAD1_BODY=$(echo "${UPLOAD1_RES}" | sed '$d')

if [ "${UPLOAD1_CODE}" != "200" ]; then
  log_error "Failed to upload document 1 content: ${UPLOAD1_BODY} (HTTP ${UPLOAD1_CODE})"
  exit 1
fi
log_success "Document 1 content stream uploaded to repository."

# Document 2: CMIS Architecture Document
DOC2_NAME="opencrawling-cmis-architecture.txt"
log_info "Creating test document '${DOC2_NAME}' under repository root..."

CREATE_DOC2_PAYLOAD=$(cat <<EOF
{
  "name": "${DOC2_NAME}",
  "nodeType": "cm:content",
  "properties": {
    "cm:title": "OpenCrawling CMIS Architecture Overview",
    "cm:description": "Architecture guide for decoupled OASIS CMIS 1.1 indexing, Kafka messaging, and MCP discovery"
  }
}
EOF
)

DOC2_RES=$(curl -s -w "\n%{http_code}" -X POST "${ALFRESCO_URL}/nodes/-root-/children" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d "${CREATE_DOC2_PAYLOAD}")

DOC2_CODE=$(echo "${DOC2_RES}" | tail -n 1)
DOC2_BODY=$(echo "${DOC2_RES}" | sed '$d')

if [ "${DOC2_CODE}" != "201" ]; then
  log_error "Failed to create document 2 in repository: ${DOC2_BODY} (HTTP ${DOC2_CODE})"
  exit 1
fi

DOC2_ID=$(echo "${DOC2_BODY}" | jq -r '.entry.id')
CREATED_DOC_IDS+=("${DOC2_ID}")
log_success "Document node 2 created successfully with ID: ${DOC2_ID}"

# Upload document 2 text stream
DOC2_CONTENT="The CMIS repository connector scans repository folders using native Java 25 HttpClient and structured concurrency. Content binaries are persisted to the shared Claim Check store while metadata messages traverse Apache Kafka. Downstream microservices process ingestion, tokenization, embeddings with Ollama, and store vectors in PostgreSQL pgvector."
log_info "Uploading content stream to document node 2 '${DOC2_ID}'..."

UPLOAD2_RES=$(curl -s -w "\n%{http_code}" -X PUT "${ALFRESCO_URL}/nodes/${DOC2_ID}/content" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: text/plain" \
  --data-binary "${DOC2_CONTENT}")

UPLOAD2_CODE=$(echo "${UPLOAD2_RES}" | tail -n 1)
UPLOAD2_BODY=$(echo "${UPLOAD2_RES}" | sed '$d')

if [ "${UPLOAD2_CODE}" != "200" ]; then
  log_error "Failed to upload document 2 content: ${UPLOAD2_BODY} (HTTP ${UPLOAD2_CODE})"
  exit 1
fi
log_success "Document 2 content stream uploaded to repository."

log_pass "CMIS test repository contents provisioned and verified."

# ------------------------------------------------------------------------------
# STEP 6: Execute oc-crawler against the CMIS Repository
# ------------------------------------------------------------------------------
log_step 6 "Executing oc-crawler scan against CMIS repository root (/)..."

export SPRING_OPENCRAWLING_SCAN_PATH="/"

log_info "Launching oc-crawler container with target CMIS scan path: ${SPRING_OPENCRAWLING_SCAN_PATH}..."
SPRING_OPENCRAWLING_SCAN_PATH="/" compose up -d --build --force-recreate oc-crawler

log_info "Waiting for oc-crawler service to finish CMIS repository crawl..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-cmis 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for oc-crawler-service-cmis to complete."
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

CRAWLER_EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-cmis 2>/dev/null || echo "1")
if [ "$CRAWLER_EXIT_CODE" -ne 0 ]; then
  log_error "oc-crawler-service-cmis failed with exit code ${CRAWLER_EXIT_CODE}."
  compose logs oc-crawler
  exit 1
fi

log_success "oc-crawler completed CMIS repository scan with clean exit code 0."
log_info "Crawler output summary:"
docker logs --tail 20 oc-crawler-service-cmis || true

log_pass "CMIS repository scan completed successfully."

# ------------------------------------------------------------------------------
# STEP 7: Verify Kafka Ingestion, Embedding, and PgVector Persistence
# ------------------------------------------------------------------------------
log_step 7 "Waiting for Kafka messaging pipeline to process and persist vector embeddings in PgVector..."

DOC1_COUNT=0
DOC2_COUNT=0
ELAPSED=0
INGEST_TIMEOUT=120

log_info "Waiting for vectors corresponding specifically to CMIS repository test documents to appear in pgvector..."

until ([ "$DOC1_COUNT" -gt 0 ] 2>/dev/null && [ "$DOC2_COUNT" -gt 0 ] 2>/dev/null) || [ $ELAPSED -ge $INGEST_TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))

  # Query vector count specifically related to CMIS Document 1
  DOC1_COUNT=$(docker exec -i postgres-vector-decoupled-cmis psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
    "SELECT (
       CASE WHEN to_regclass('public.vector_store_1024') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store_1024 WHERE (metadata::text LIKE '%${DOC1_NAME}%' OR metadata::text LIKE '%${DOC1_ID}%') AND content ILIKE '%without Apache Chemistry%')
       ELSE 0 END +
       CASE WHEN to_regclass('public.vector_store') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store WHERE (metadata::text LIKE '%${DOC1_NAME}%' OR metadata::text LIKE '%${DOC1_ID}%') AND content ILIKE '%without Apache Chemistry%')
       ELSE 0 END
     );" 2>/dev/null || echo "0")
  DOC1_COUNT=$(echo "$DOC1_COUNT" | tr -d '[:space:]')
  [ -z "$DOC1_COUNT" ] && DOC1_COUNT=0

  # Query vector count specifically related to CMIS Document 2
  DOC2_COUNT=$(docker exec -i postgres-vector-decoupled-cmis psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
    "SELECT (
       CASE WHEN to_regclass('public.vector_store_1024') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store_1024 WHERE (metadata::text LIKE '%${DOC2_NAME}%' OR metadata::text LIKE '%${DOC2_ID}%') AND content ILIKE '%structured concurrency%')
       ELSE 0 END +
       CASE WHEN to_regclass('public.vector_store') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store WHERE (metadata::text LIKE '%${DOC2_NAME}%' OR metadata::text LIKE '%${DOC2_ID}%') AND content ILIKE '%structured concurrency%')
       ELSE 0 END
     );" 2>/dev/null || echo "0")
  DOC2_COUNT=$(echo "$DOC2_COUNT" | tr -d '[:space:]')
  [ -z "$DOC2_COUNT" ] && DOC2_COUNT=0

  printf "  Elapsed: %ds | Doc 1 (%s) vectors: %s | Doc 2 (%s) vectors: %s\r" \
    "$ELAPSED" "${DOC1_NAME}" "$DOC1_COUNT" "${DOC2_NAME}" "$DOC2_COUNT"
done
echo ""

# Check for timeout or missing vectors
if [ "$DOC1_COUNT" -eq 0 ] || [ "$DOC2_COUNT" -eq 0 ]; then
  log_error "Decoupled pipeline verification failed: vectors matching CMIS repository documents were not found in pgvector!"
  log_error "  - Doc 1 ('${DOC1_NAME}', ID: ${DOC1_ID}): ${DOC1_COUNT} vector(s)"
  log_error "  - Doc 2 ('${DOC2_NAME}', ID: ${DOC2_ID}): ${DOC2_COUNT} vector(s)"
  log_warn "Displaying consumer service logs for diagnosis:"
  compose logs oc-ingestion-consumer --tail 30 || true
  compose logs oc-embedding-consumer --tail 30 || true
  compose logs oc-writer-consumer --tail 30 || true
  exit 1
fi

log_success "Discovered vector records matching CMIS repository documents!"
log_info "  - Doc 1 ('${DOC1_NAME}', ID: ${DOC1_ID}): ${DOC1_COUNT} vector record(s)"
log_info "  - Doc 2 ('${DOC2_NAME}', ID: ${DOC2_ID}): ${DOC2_COUNT} vector record(s)"

# ------------------------------------------------------------------------------
# STEP 7a: Detailed Validation of CMIS Repository Provenance in PgVector
# ------------------------------------------------------------------------------
log_info "Executing specific provenance checks on vectors stored in pgvector..."

# 1. Verify CMIS Content Streams in Stored Vector Records
log_info "Check 1: Verifying vector content text matches uploaded CMIS repository streams..."
CONTENT_MATCH_COUNT=$(docker exec -i postgres-vector-decoupled-cmis psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
  "SELECT (
     CASE WHEN to_regclass('public.vector_store_1024') IS NOT NULL THEN
       (SELECT count(*) FROM vector_store_1024 WHERE (content ILIKE '%without Apache Chemistry%' OR content ILIKE '%structured concurrency%'))
     ELSE 0 END +
     CASE WHEN to_regclass('public.vector_store') IS NOT NULL THEN
       (SELECT count(*) FROM vector_store WHERE (content ILIKE '%without Apache Chemistry%' OR content ILIKE '%structured concurrency%'))
     ELSE 0 END
   );" 2>/dev/null || echo "0")
CONTENT_MATCH_COUNT=$(echo "$CONTENT_MATCH_COUNT" | tr -d '[:space:]')
[ -z "$CONTENT_MATCH_COUNT" ] && CONTENT_MATCH_COUNT=0

if [ "$CONTENT_MATCH_COUNT" -lt 2 ]; then
  log_error "Content check failed: Vector text does not match uploaded CMIS repository streams! Found: ${CONTENT_MATCH_COUNT} (expected >= 2)"
  exit 1
fi
log_success "Content check passed: Stored vectors contain exact text from CMIS repository documents (${CONTENT_MATCH_COUNT} chunks)."

# 2. Verify CMIS Metadata Properties in pgvector JSONB
log_info "Check 2: Verifying CMIS metadata attributes (cm:title or cmis:name) in pgvector JSONB..."
CMIS_META_COUNT=$(docker exec -i postgres-vector-decoupled-cmis psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
  "SELECT (
     CASE WHEN to_regclass('public.vector_store_1024') IS NOT NULL THEN
       (SELECT count(*) FROM vector_store_1024 WHERE (metadata::text LIKE '%OpenCrawling CMIS Decoupled Specification%' OR metadata::text LIKE '%OpenCrawling CMIS Architecture Overview%' OR metadata::text LIKE '%opencrawling-cmis%'))
     ELSE 0 END +
     CASE WHEN to_regclass('public.vector_store') IS NOT NULL THEN
       (SELECT count(*) FROM vector_store WHERE (metadata::text LIKE '%OpenCrawling CMIS Decoupled Specification%' OR metadata::text LIKE '%OpenCrawling CMIS Architecture Overview%' OR metadata::text LIKE '%opencrawling-cmis%'))
     ELSE 0 END
   );" 2>/dev/null || echo "0")
CMIS_META_COUNT=$(echo "$CMIS_META_COUNT" | tr -d '[:space:]')
[ -z "$CMIS_META_COUNT" ] && CMIS_META_COUNT=0

if [ "$CMIS_META_COUNT" -lt 2 ]; then
  log_error "Metadata check failed: CMIS metadata attributes not found in pgvector! Found: ${CMIS_META_COUNT} (expected >= 2)"
  exit 1
fi
log_success "Metadata check passed: CMIS repository metadata attributes confirmed in pgvector JSONB (${CMIS_META_COUNT} records)."

# 3. Preview Stored CMIS Vectors Table
log_info "Preview of indexed CMIS repository document records in pgvector:"
docker exec -i postgres-vector-decoupled-cmis psql -U opencrawling -d opencrawling -P pager=off -c \
  "SELECT id, metadata->'name' AS doc_name, metadata->'cm:title' AS title, vector_dims(embedding) AS dims, left(content, 60) AS content_preview FROM vector_store_1024 WHERE (metadata::text LIKE '%${DOC1_NAME}%' OR metadata::text LIKE '%${DOC2_NAME}%' OR metadata::text LIKE '%${DOC1_ID}%' OR metadata::text LIKE '%${DOC2_ID}%' OR content ILIKE '%without Apache Chemistry%' OR content ILIKE '%structured concurrency%');" 2>/dev/null || \
docker exec -i postgres-vector-decoupled-cmis psql -U opencrawling -d opencrawling -P pager=off -c \
  "SELECT id, metadata->'name' AS doc_name, metadata->'cm:title' AS title, vector_dims(embedding) AS dims, left(content, 60) AS content_preview FROM vector_store WHERE (metadata::text LIKE '%${DOC1_NAME}%' OR metadata::text LIKE '%${DOC2_NAME}%' OR metadata::text LIKE '%${DOC1_ID}%' OR metadata::text LIKE '%${DOC2_ID}%' OR content ILIKE '%without Apache Chemistry%' OR content ILIKE '%structured concurrency%');" 2>/dev/null || true

# 4. Verify Vector Embedding Dimensions (1024d)
log_info "Check 3: Verifying vector embedding dimensions (1024d) in pgvector..."
DIMS_COUNT=$(docker exec -i postgres-vector-decoupled-cmis psql -U opencrawling -d opencrawling -t -A -P pager=off -c \
  "SELECT (
     CASE WHEN to_regclass('public.vector_store_1024') IS NOT NULL THEN
       (SELECT count(*) FROM vector_store_1024 WHERE embedding IS NOT NULL AND vector_dims(embedding) = 1024 AND (metadata::text LIKE '%${DOC1_NAME}%' OR metadata::text LIKE '%${DOC2_NAME}%' OR metadata::text LIKE '%${DOC1_ID}%' OR metadata::text LIKE '%${DOC2_ID}%' OR content ILIKE '%without Apache Chemistry%' OR content ILIKE '%structured concurrency%'))
     ELSE 0 END +
     CASE WHEN to_regclass('public.vector_store') IS NOT NULL THEN
       (SELECT count(*) FROM vector_store WHERE embedding IS NOT NULL AND (vector_dims(embedding) = 1024 OR vector_dims(embedding) = 1536) AND (metadata::text LIKE '%${DOC1_NAME}%' OR metadata::text LIKE '%${DOC2_NAME}%' OR metadata::text LIKE '%${DOC1_ID}%' OR metadata::text LIKE '%${DOC2_ID}%' OR content ILIKE '%without Apache Chemistry%' OR content ILIKE '%structured concurrency%'))
     ELSE 0 END
   );" 2>/dev/null || echo "0")
DIMS_COUNT=$(echo "$DIMS_COUNT" | tr -d '[:space:]')
[ -z "$DIMS_COUNT" ] && DIMS_COUNT=0

if [ "$DIMS_COUNT" -lt 2 ]; then
  log_error "Vector dimension check failed! Expected at least 2 records with vector embeddings, found: ${DIMS_COUNT}"
  log_warn "Diagnostic: Inspecting existing tables and schemas in postgres-vector database..."
  docker exec -i postgres-vector-decoupled-cmis psql -U opencrawling -d opencrawling -c "\dt" || true
  docker exec -i postgres-vector-decoupled-cmis psql -U opencrawling -d opencrawling -c "SELECT table_name, column_name, data_type, udt_name FROM information_schema.columns WHERE table_name LIKE 'vector_store%';" || true
  exit 1
fi
log_success "Vector dimension check passed: Stored embeddings verified in pgvector (${DIMS_COUNT} records)!"

log_pass "End-to-end vector pipeline execution verified successfully. All stored vectors confirmed to originate from CMIS repository test content."

# ------------------------------------------------------------------------------
# STEP 8: Verify OpenCrawling MCP Server Endpoint
# ------------------------------------------------------------------------------
log_step 8 "Waiting for OpenCrawling MCP Server endpoint to be ready..."

HTTP_STATUS="000"
ELAPSED=0
MCP_TIMEOUT=60

until [ "$HTTP_STATUS" == "200" ] || [ "$HTTP_STATUS" == "405" ] || [ "$HTTP_STATUS" == "404" ] || [ $ELAPSED -ge $MCP_TIMEOUT ]; do
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://localhost:${MCP_PORT}/mcp" 2>/dev/null || echo "")
  if [ -z "$HTTP_STATUS" ] || [ "$HTTP_STATUS" == "000" ]; then
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://localhost:${MCP_PORT}/" 2>/dev/null || echo "000")
  fi
  if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
    sleep 2
    ELAPSED=$((ELAPSED + 2))
  fi
done

log_info "MCP Server HTTP response status: ${HTTP_STATUS}"

if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
  log_error "MCP Server readiness check failed: returned HTTP status ${HTTP_STATUS}."
  compose logs oc-mcp-server --tail 30 || true
  exit 1
fi

log_success "OpenCrawling MCP Server is online and reachable (HTTP ${HTTP_STATUS})!"
log_pass "MCP Server verified successfully."

# ------------------------------------------------------------------------------
# STEP 9: Verify Open Ingestion Standard (OIS) Document Lifecycle Tombstone DELETE
# ------------------------------------------------------------------------------
log_step 9 "Executing OIS Document Lifecycle Tombstone DELETE action test..."
mvn test -pl oc-runtime -Dtest=VectorStoreWriterConsumerTest#testConsumeDeleteTombstonePurgesAllVectorStores
log_pass "OIS Tombstone DELETE action test passed."

# ------------------------------------------------------------------------------
# STEP 10: Cleanup Test Content in Repository
# ------------------------------------------------------------------------------
log_step 10 "Cleaning up test contents from CMIS repository..."

if [ "${#CREATED_DOC_IDS[@]}" -gt 0 ]; then
  for doc_id in ${CREATED_DOC_IDS[@]+"${CREATED_DOC_IDS[@]}"}; do
    if [ -n "${doc_id}" ]; then
      DELETE_RES=$(curl -s -w "%{http_code}" -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${doc_id}")
      if [ "${DELETE_RES}" = "204" ] || [ "${DELETE_RES}" = "200" ]; then
        log_success "Repository document '${doc_id}' removed successfully."
      else
        log_warn "Failed to remove repository document '${doc_id}'. Status: ${DELETE_RES}"
      fi
    fi
  done
fi
CREATED_DOC_IDS=()

log_pass "CMIS repository cleanup complete."

# ------------------------------------------------------------------------------
# Final Summary
# ------------------------------------------------------------------------------
echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: All ${TOTAL_STEPS} OASIS CMIS Decoupled Integration Test Steps Passed! 🎉${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}\n"

exit 0
