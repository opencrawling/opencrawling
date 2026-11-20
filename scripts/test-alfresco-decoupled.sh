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
# OpenCrawling - Alfresco Content Services Decoupled Integration Test Script
#
# Description:
#   Comprehensive end-to-end integration test for the decoupled OpenCrawling
#   pipeline integrating Alfresco Content Services (`oc-alfresco-repository-connector`)
#   with Apache Kafka, Redis, PostgreSQL (pgvector), Ollama embedding service,
#   and OpenCrawling microservices (crawler, ingestion-consumer,
#   embedding-consumer, writer-consumer, and mcp-server).
#
# Pipeline Lifecycle Tested:
#   1. Verify system prerequisites and dependencies
#   2. Clean up previous containers and stale Alfresco crawl state volume
#   3. Build OpenCrawling decoupled microservice images
#   4. Start Alfresco + decoupled infrastructure and wait for health checks
#   5. Provision rich test fixture in Alfresco (versioned docs, tags, comments,
#      exact-vs-substring folder exclusion tree, regex-excluded lock file)
#   6. Execute initial oc-crawler scan using Ticket auth, incremental state,
#      selection filters, tags, comments, and web app URLs
#   7. Verify Kafka -> Tika -> Ollama -> pgvector persistence, 1024d vectors,
#      rich #425 metadata, and exact folder + regex exclusion filters
#   8. Verify incremental no-op skip, live content/version update, ACL-only
#      update, tag scope-exclusion tombstone, and real Trashcan deletion purge
#   9. Verify multi-version indexing (versionMode=all) and version-mode switch
#      tombstone cleanup (all -> current) in pgvector
#  10. Verify OpenCrawling MCP Server endpoint readiness
#  11. Verify Open Ingestion Standard (OIS) Document Lifecycle Tombstone DELETE test
#  12. Clean up test artifacts in Alfresco and teardown containers cleanly
# ==============================================================================

set -euo pipefail

# Configuration flags (CLI flags take precedence over environment variables)
SKIP_BUILD="${SKIP_BUILD:-false}"
LOCAL_BUILD="${LOCAL_BUILD:-false}"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"

show_help() {
  cat << EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  -s, --skip-build        Skip docker compose build and reuse existing images
  -l, --local, --local-build
                          Use local Dockerfiles with BuildKit ~/.m2 cache mounts
  -k, --keep, --keep-containers
                          Preserve running containers and volumes on exit/failure
  -h, --help              Display this help message and exit

Environment Variables:
  SKIP_BUILD=true         Equivalent to -s, --skip-build
  LOCAL_BUILD=true        Equivalent to -l, --local-build
  KEEP_CONTAINERS=true    Equivalent to -k, --keep-containers
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--skip-build)
      SKIP_BUILD=true
      shift
      ;;
    -l|--local|--local-build)
      LOCAL_BUILD=true
      shift
      ;;
    -k|--keep|--keep-containers)
      KEEP_CONTAINERS=true
      shift
      ;;
    -h|--help)
      show_help
      exit 0
      ;;
    *)
      shift
      ;;
  esac
done


# Configuration
ALFRESCO_HOST="${ALFRESCO_HOST:-localhost}"
ALFRESCO_PORT="${ALFRESCO_PORT:-8081}"
ALFRESCO_USERNAME="${ALFRESCO_USERNAME:-admin}"
ALFRESCO_PASSWORD="${ALFRESCO_PASSWORD:-admin}"
ALFRESCO_URL="${ALFRESCO_URL:-http://${ALFRESCO_HOST}:${ALFRESCO_PORT}/alfresco/api/-default-/public/alfresco/versions/1}"
COMPOSE_FILE="oc-alfresco-repository-connector/docker/docker-compose-decoupled-with-alfresco.yml"
LOCAL_OVERLAY="${LOCAL_OVERLAY:-${COMPOSE_FILE%.yml}.local.yml}"
TIMEOUT="${TIMEOUT:-300}"
INGEST_TIMEOUT="${INGEST_TIMEOUT:-120}"
MCP_PORT="${MCP_PORT:-8080}"
PG_CONTAINER="postgres-vector-decoupled-alfresco"

# Formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

PASSED_COUNT=0
TOTAL_STEPS=12

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
echo -e "${BOLD}${YELLOW}=== OpenCrawling Decoupled Pipeline with Alfresco Integration Test ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}\n"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# Helper function for docker compose commands
compose() {
  if [ "${LOCAL_BUILD}" = true ] && [ -f "${LOCAL_OVERLAY}" ]; then
    docker compose -f "${COMPOSE_FILE}" -f "${LOCAL_OVERLAY}" "$@"
  else
    docker compose -f "${COMPOSE_FILE}" "$@"
  fi
}

# Basic auth header for Alfresco REST API calls
AUTH_HEADER="Basic $(printf "%s:%s" "${ALFRESCO_USERNAME}" "${ALFRESCO_PASSWORD}" | base64 | tr -d '\n')"
ROOT_URL="${ALFRESCO_URL}/nodes/-root-"
PROBE_URL="${ALFRESCO_URL}/probes/-ready-"

# SQL & crawler helpers
sql() {
  docker exec -i "${PG_CONTAINER}" psql -U opencrawling -d opencrawling -t -A -P pager=off -c "$1" 2>/dev/null | tr -d '[:space:]'
}

wait_for_vector_count() {
  local description="$1" where_clause="$2" comparison="$3" elapsed=0 count=0
  while true; do
    count=$(sql "SELECT (
       CASE WHEN to_regclass('public.vector_store_1024') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store_1024 WHERE ${where_clause})
       ELSE 0 END +
       CASE WHEN to_regclass('public.vector_store') IS NOT NULL THEN
         (SELECT count(*) FROM vector_store WHERE ${where_clause})
       ELSE 0 END
     );" || echo "0")
    [ -z "$count" ] && count=0
    # shellcheck disable=SC2086
    if [ "$count" $comparison ]; then
      log_success "${description} (${count})."
      return 0
    fi
    if [ "$elapsed" -ge "${INGEST_TIMEOUT}" ]; then
      log_error "${description}: condition '${comparison}' not met after ${INGEST_TIMEOUT}s (got ${count})."
      compose logs oc-ingestion-consumer --tail 30 || true
      compose logs oc-embedding-consumer --tail 30 || true
      compose logs oc-writer-consumer --tail 30 || true
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
}

SCAN_SUMMARY=""
run_crawler() {
  local label="$1"
  log_info "Launching oc-crawler (${label})..."
  compose up -d --no-build --force-recreate oc-crawler

  local elapsed=0
  until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-alfresco 2>/dev/null || echo 'false')" == "false" ]; do
    if [ "$elapsed" -ge "${TIMEOUT}" ]; then
      log_error "Timeout waiting for oc-crawler-service-alfresco (${label}) to complete."
      compose logs oc-crawler --tail 60 || true
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done

  local exit_code
  exit_code=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-alfresco 2>/dev/null || echo "1")
  if [ "${exit_code}" -ne 0 ]; then
    log_error "oc-crawler-service-alfresco (${label}) failed with exit code ${exit_code}."
    compose logs oc-crawler --tail 80 || true
    exit 1
  fi

  SCAN_SUMMARY=$(docker logs oc-crawler-service-alfresco 2>&1 | grep "Alfresco scan of" | tail -1 || true)
  log_success "oc-crawler (${label}) completed cleanly: ${SCAN_SUMMARY#*-- }"
  if [ -n "${SCAN_SUMMARY}" ] && ! echo "${SCAN_SUMMARY}" | grep -q "0 failed"; then
    log_error "oc-crawler (${label}) reported failures: ${SCAN_SUMMARY}"
    compose logs oc-crawler --tail 80 || true
    exit 1
  fi
}

# State variables for cleanup
TEST_FOLDER_ID=""
TRASHED_DOC_IDS=()

cleanup() {
  local exit_code=$?
  if [ "$exit_code" -ne 0 ]; then
    log_error "Test execution interrupted or failed with exit code $exit_code."
  fi

  if [ -n "${TEST_FOLDER_ID}" ]; then
    log_info "Attempting to cleanup Alfresco test folder '${TEST_FOLDER_ID}'..."
    curl -s -o /dev/null -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${TEST_FOLDER_ID}?permanent=true" 2>/dev/null || true
    TEST_FOLDER_ID=""
  fi
  if [ "${#TRASHED_DOC_IDS[@]}" -gt 0 ]; then
    for doc_id in "${TRASHED_DOC_IDS[@]}"; do
      if [ -n "${doc_id}" ]; then
        curl -s -o /dev/null -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/deleted-nodes/${doc_id}" 2>/dev/null || true
      fi
    done
    TRASHED_DOC_IDS=()
  fi

  if [ "${KEEP_CONTAINERS}" = false ]; then
    log_info "Tearing down Decoupled Alfresco Docker Compose environment..."
    compose down --remove-orphans >/dev/null 2>&1 || true
    docker compose -f oc-alfresco-repository-connector/alfresco-community-compose.yml down --remove-orphans >/dev/null 2>&1 || true
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
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "mvn is required but not installed."; exit 1; }
log_pass "Prerequisites verified successfully."

# ------------------------------------------------------------------------------
# STEP 2: Clean up previous decoupled Alfresco containers & crawl state
# ------------------------------------------------------------------------------
log_step 2 "Cleaning up any existing decoupled Alfresco containers and stale crawl state..."
compose down --remove-orphans || true
docker compose -f oc-alfresco-repository-connector/alfresco-community-compose.yml down --remove-orphans || true
docker rm -f postgres-alfresco >/dev/null 2>&1 || true
# Clear previous Alfresco incremental state so tests start cleanly (keep shared Ollama models volume)
docker volume rm opencrawling-alfrescostate >/dev/null 2>&1 || true

# Free common ports if lingering containers from previous runs are still binding them
for p in 8080 8081 9092 5432 5433 6379 11434; do
  c_ids=$(docker ps --filter "publish=$p" -q 2>/dev/null || true)
  if [ -n "$c_ids" ]; then
    docker stop $c_ids >/dev/null 2>&1 || true
    docker rm -f $c_ids >/dev/null 2>&1 || true
  fi
done
log_pass "Existing containers, stale crawl state, and port bindings cleaned up."

# ------------------------------------------------------------------------------
# STEP 3: Build OpenCrawling decoupled microservice images
# ------------------------------------------------------------------------------
log_step 3 "Building OpenCrawling decoupled microservice images from source..."
if [ "${SKIP_BUILD}" = true ]; then
  echo -e "${YELLOW:-}SKIP_BUILD=true: Skipping compose build and using existing images.${NC:-}"
else
  echo -e "${YELLOW:-}Building OpenCrawling decoupled microservice images from source (LOCAL_BUILD=${LOCAL_BUILD})...${NC:-}"
  compose build
fi
log_pass "Docker microservice images built successfully."

# ------------------------------------------------------------------------------
# STEP 4: Start decoupled infrastructure & wait for health checks
# ------------------------------------------------------------------------------
log_step 4 "Starting complete decoupled Alfresco infrastructure and waiting for readiness..."
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

# 4a. Wait for Alfresco Content Services
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

# 4b. Wait for PostgreSQL pgvector
log_info "Waiting for PostgreSQL pgvector database to become healthy..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' "${PG_CONTAINER}" 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for ${PG_CONTAINER}."
    compose logs postgres-vector
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
log_success "PostgreSQL pgvector is healthy!"

# 4c. Wait for Redis
log_info "Waiting for Redis to become healthy..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' redis-stack-decoupled-alfresco 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for redis-stack-decoupled-alfresco."
    compose logs redis
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
log_success "Redis is healthy!"

# 4d. Wait for Ollama
log_info "Waiting for Ollama embedding engine to become healthy..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' ollama-decoupled-alfresco 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for ollama-decoupled-alfresco."
    compose logs ollama
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
log_success "Ollama engine is healthy!"

# 4e. Wait for Ollama model puller to finish pulling models and exit
log_info "Waiting for Ollama model puller to download embedding models..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-alfresco 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    log_error "Timeout waiting for Ollama model puller."
    compose logs ollama-model-puller
    exit 1
  fi
  PROGRESS=$(docker logs --tail 1 ollama-model-puller-decoupled-alfresco 2>&1 || true)
  if [ -n "$PROGRESS" ]; then
    printf "  Progress: %s\r" "$PROGRESS"
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo ""

EXIT_CODE=$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-alfresco 2>/dev/null || echo "1")
if [ "$EXIT_CODE" -ne 0 ]; then
  log_error "Ollama model puller failed with exit code $EXIT_CODE."
  compose logs ollama-model-puller
  exit 1
fi
log_success "Ollama embedding models pulled successfully!"

log_pass "All decoupled infrastructure services are healthy and ready."

# ------------------------------------------------------------------------------
# STEP 5: Provision Rich Test Fixture in Alfresco Repository
# ------------------------------------------------------------------------------
log_step 5 "Provisioning versioned documents, tags, comments, and folder-filter fixture in Alfresco..."

TEST_FOLDER_NAME="opencrawling-decoupled-$(date +%s)"
FOLDER_RES=$(curl -s -w "\n%{http_code}" -X POST "${ALFRESCO_URL}/nodes/-root-/children" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: application/json" -H "Accept: application/json" \
  -d "$(jq -nc --arg n "${TEST_FOLDER_NAME}" '{name:$n, nodeType:"cm:folder"}')")
FOLDER_CODE=$(echo "${FOLDER_RES}" | tail -n 1)
FOLDER_BODY=$(echo "${FOLDER_RES}" | sed '$d')
if [ "${FOLDER_CODE}" != "201" ]; then
  log_error "Failed to create test root folder in Alfresco: ${FOLDER_BODY} (HTTP ${FOLDER_CODE})"
  exit 1
fi
TEST_FOLDER_ID=$(echo "${FOLDER_BODY}" | jq -r '.entry.id')
log_success "Created scoped Alfresco test folder '${TEST_FOLDER_NAME}' (ID: ${TEST_FOLDER_ID})."

create_alfresco_doc() {
  local parent_id="$1" name="$2" title="$3" desc="$4" content="$5" query="${6:-}"
  local res code body id up_res up_code
  res=$(curl -s -w "\n%{http_code}" -X POST "${ALFRESCO_URL}/nodes/${parent_id}/children" \
    -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: application/json" -H "Accept: application/json" \
    -d "$(jq -nc --arg n "$name" --arg t "$title" --arg d "$desc" \
      '{name:$n, nodeType:"cm:content", properties:{"cm:title":$t, "cm:description":$d}}')")
  code=$(echo "${res}" | tail -n 1)
  body=$(echo "${res}" | sed '$d')
  if [ "${code}" != "201" ]; then
    log_error "Failed to create document '${name}': ${body} (HTTP ${code})"
    exit 1
  fi
  id=$(echo "${body}" | jq -r '.entry.id')

  up_res=$(curl -s -w "\n%{http_code}" -X PUT "${ALFRESCO_URL}/nodes/${id}/content${query}" \
    -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: text/plain" \
    --data-binary "${content}")
  up_code=$(echo "${up_res}" | tail -n 1)
  if [ "${up_code}" != "200" ]; then
    log_error "Failed to upload content for '${name}': $(echo "${up_res}" | sed '$d') (HTTP ${up_code})"
    exit 1
  fi
  echo "${id}"
}

# Document 1: Specification Document (versioned + tagged + commented + explicit ACL for later ACL-only update)
DOC1_NAME="opencrawling-alfresco-specification.txt"
DOC1_CONTENT="OpenCrawling decoupled architecture provides high-throughput distributed crawling for Alfresco Content Services repositories. ALFRESCO-SPEC-V1-MARKER. Document content streams are claim-checked, parsed via Apache Tika, tokenized into semantic chunks, vectorized via Ollama, and persisted into PostgreSQL pgvector for AI agent retrieval."
DOC1_ID=$(create_alfresco_doc "${TEST_FOLDER_ID}" "${DOC1_NAME}" \
  "OpenCrawling Alfresco Decoupled Specification" \
  "Integration test payload verifying complete OpenCrawling decoupled processing pipeline" \
  "${DOC1_CONTENT}" "?majorVersion=true&comment=Initial+Release")
log_success "Document 1 created (ID: ${DOC1_ID})."

# Restrict Doc 1 initially to admin only so we can test ACL-only change detection later
ACL1_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${ALFRESCO_URL}/nodes/${DOC1_ID}" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: application/json" \
  -d '{"permissions":{"isInheritanceEnabled":false,"locallySet":[{"authorityId":"admin","name":"Coordinator","accessStatus":"ALLOWED"}]}}')
[ "${ACL1_CODE}" = "200" ] || { log_error "Failed to set initial ACL on Doc 1 (HTTP ${ACL1_CODE})"; exit 1; }

DOC1_TAG="opencrawling-verified"
DOC1_COMMENT="Reviewed for OpenCrawling v1.0.5"
curl -sf -X POST "${ALFRESCO_URL}/nodes/${DOC1_ID}/tags" -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" -d "$(jq -nc --arg t "${DOC1_TAG}" '{tag:$t}')" >/dev/null
curl -sf -X POST "${ALFRESCO_URL}/nodes/${DOC1_ID}/comments" -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" -d "$(jq -nc --arg c "${DOC1_COMMENT}" '{content:$c}')" >/dev/null
log_success "Tagged Doc 1 with '${DOC1_TAG}', added comment, and set explicit ACL."

# Document 2: Architecture Document (later deleted to test real Trashcan + tombstone purge)
DOC2_NAME="opencrawling-alfresco-architecture.txt"
DOC2_CONTENT="The Alfresco repository connector scans Company Home nodes using Java 25 HttpClient and structured concurrency. Content binaries are persisted to the shared Claim Check store while metadata messages traverse Apache Kafka. Downstream microservices process ingestion, tokenization, embeddings with Ollama, and store vectors in PostgreSQL pgvector."
DOC2_ID=$(create_alfresco_doc "${TEST_FOLDER_ID}" "${DOC2_NAME}" \
  "OpenCrawling Alfresco Architecture Overview" \
  "Architecture guide for decoupled Alfresco indexing, Kafka messaging, and MCP discovery" \
  "${DOC2_CONTENT}")
log_success "Document 2 created (ID: ${DOC2_ID})."

# Lock file excluded by excludedNamePatterns (^~\$.*)
LOCK_NAME='~$temporary-lock.txt'
LOCK_ID=$(create_alfresco_doc "${TEST_FOLDER_ID}" "${LOCK_NAME}" "Lock File" "Must be filtered by excludedNamePatterns" \
  "LOCK-FILE-MUST-NOT-BE-INDEXED transient office lock bytes.")
log_info "Created regex-excluded lock file '${LOCK_NAME}' (ID: ${LOCK_ID})."

# Folder exclusion fixture: 'Templates' MUST be indexed while 'temp' MUST be excluded
TEMPLATES_ID=$(curl -sf -X POST "${ALFRESCO_URL}/nodes/${TEST_FOLDER_ID}/children" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: application/json" \
  -d '{"name":"Templates","nodeType":"cm:folder"}' | jq -r '.entry.id')
TEMP_ID=$(curl -sf -X POST "${ALFRESCO_URL}/nodes/${TEST_FOLDER_ID}/children" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: application/json" \
  -d '{"name":"temp","nodeType":"cm:folder"}' | jq -r '.entry.id')

TEMPLATE_DOC_NAME="opencrawling-template-note.txt"
TEMPLATE_DOC_ID=$(create_alfresco_doc "${TEMPLATES_ID}" "${TEMPLATE_DOC_NAME}" \
  "Alfresco Template Guide" "Verifies Templates is NOT matched by excludedFolders=temp" \
  "ALFRESCO-TEMPLATES-INCLUDED-MARKER standard operating template stored inside Templates folder.")
TEMP_DOC_NAME="opencrawling-temp-scratch.txt"
TEMP_DOC_ID=$(create_alfresco_doc "${TEMP_ID}" "${TEMP_DOC_NAME}" \
  "Alfresco Temp Scratch" "Verifies temp folder IS excluded" \
  "ALFRESCO-TEMP-EXCLUDED-MARKER ephemeral file inside excluded temp folder.")
log_success "Provisioned Templates/${TEMPLATE_DOC_NAME} (${TEMPLATE_DOC_ID}) and temp/${TEMP_DOC_NAME} (${TEMP_DOC_ID})."

# Verify direct downloads match uploads
[ "$(curl -s -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC1_ID}/content")" = "${DOC1_CONTENT}" ] \
  || { log_error "Downloaded content did not match uploaded content for document 1!"; exit 1; }
[ "$(curl -s -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC2_ID}/content")" = "${DOC2_CONTENT}" ] \
  || { log_error "Downloaded content did not match uploaded content for document 2!"; exit 1; }
log_pass "Alfresco test fixture provisioned and verified."

# ------------------------------------------------------------------------------
# STEP 6: Execute Initial oc-crawler Scan (Ticket Auth + Incremental + Filters)
# ------------------------------------------------------------------------------
log_step 6 "Executing initial oc-crawler scan with Ticket auth, incremental state, and selection filters..."

export SPRING_OPENCRAWLING_SCAN_PATH="-root-"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_AUTH_TYPE="ticket"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_CRAWL_MODE="folder"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_ROOT_FOLDER_ID="${TEST_FOLDER_ID}"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_INCREMENTAL_ENABLED="true"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_DELETION_TRACKING="reconcile"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_VERSION_MODE="current"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_EXCLUDED_FOLDERS="Data Dictionary,temp"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_EXCLUDED_NAME_PATTERNS='^~\$.*'
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_EXCLUDED_TAGS="archived"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_INCLUDE_TAGS="true"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_INCLUDE_COMMENTS="true"
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_WEB_APP_URL="http://localhost:8081/workspace"

run_crawler "initial ticket-auth full scan"

docker logs oc-crawler-service-alfresco 2>&1 | grep -q "auth: TICKET" \
  || { log_error "Expected oc-crawler to authenticate with auth: TICKET"; compose logs oc-crawler --tail 40; exit 1; }
echo "${SCAN_SUMMARY}" | grep -q "3 documents, 0 unchanged, 0 tombstones" \
  || { log_error "Expected 3 documents (Doc1, Doc2, Templates/note) on initial scan, got: ${SCAN_SUMMARY}"; exit 1; }

log_pass "Initial Alfresco repository scan completed with Ticket auth (3 documents, 0 unchanged, 0 failed)."

# ------------------------------------------------------------------------------
# STEP 7: Verify Kafka Ingestion, Embedding, PgVector Persistence, #425 Metadata & Filters
# ------------------------------------------------------------------------------
log_step 7 "Verifying pgvector persistence, rich #425 metadata, embeddings, and selection filters..."

wait_for_vector_count "Doc 1 ('${DOC1_NAME}') vectors persisted" \
  "metadata->>'documentId' = '${DOC1_ID}' AND content ILIKE '%Alfresco Content Services%'" "-gt 0"
wait_for_vector_count "Doc 2 ('${DOC2_NAME}') vectors persisted" \
  "metadata->>'documentId' = '${DOC2_ID}' AND content ILIKE '%structured concurrency%'" "-gt 0"
wait_for_vector_count "Templates doc ('${TEMPLATE_DOC_NAME}') persisted (not falsely excluded by 'temp')" \
  "metadata->>'documentId' = '${TEMPLATE_DOC_ID}' AND content LIKE '%ALFRESCO-TEMPLATES-INCLUDED-MARKER%'" "-gt 0"

# 1. Verify Alfresco Metadata Properties (cm:title, nodeType, alfresco_node_id, alfresco_node_type)
ALFRESCO_META_COUNT=$(sql "SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' IN ('${DOC1_ID}', '${DOC2_ID}') AND metadata::text LIKE '%cm:content%' AND metadata::text LIKE '%alfresco_node_id%' AND metadata::text LIKE '%alfresco_node_type%' AND (metadata::text LIKE '%OpenCrawling Alfresco Decoupled Specification%' OR metadata::text LIKE '%OpenCrawling Alfresco Architecture Overview%');")
[ "${ALFRESCO_META_COUNT:-0}" -ge 2 ] \
  || { log_error "Core Alfresco metadata check failed (found ${ALFRESCO_META_COUNT:-0}, expected >= 2)."; exit 1; }
log_success "Core Alfresco provenance metadata confirmed (${ALFRESCO_META_COUNT} chunks)."

# 2. Verify new #425 enriched metadata on Doc 1 (path, tags, comments, web_url, version_label)
ENRICHED_COUNT=$(sql "SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '${DOC1_ID}'
  AND metadata->>'alfresco_path' LIKE '%${TEST_FOLDER_NAME}%'
  AND metadata->>'alfresco_tags' LIKE '%${DOC1_TAG}%'
  AND metadata->>'alfresco_comments' LIKE '%${DOC1_COMMENT}%'
  AND metadata->>'alfresco_web_url' LIKE '%http://localhost:8081/workspace/#/libraries/viewer/${DOC1_ID}%'
  AND metadata->'alfresco_version_label'->>0 IS NOT NULL;")
[ "${ENRICHED_COUNT:-0}" -gt 0 ] \
  || { log_error "Enriched #425 metadata check failed on Doc 1 (path/tags/comments/web_url/version_label)."; exit 1; }
log_success "Enriched #425 metadata verified on Doc 1 (path, tags, comments, web_url, version_label)."

# 3. Verify excluded lock file and excluded 'temp' folder were NOT indexed
FILTERED_COUNT=$(sql "SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' IN ('${LOCK_ID}', '${TEMP_DOC_ID}') OR content LIKE '%LOCK-FILE-MUST-NOT-BE-INDEXED%' OR content LIKE '%ALFRESCO-TEMP-EXCLUDED-MARKER%';")
[ "${FILTERED_COUNT:-0}" -eq 0 ] \
  || { log_error "Excluded lock file or temp/ file leaked into pgvector (${FILTERED_COUNT} chunks)!"; exit 1; }
log_success "Selection filters verified: '~\$temporary-lock.txt' and 'temp/' excluded while 'Templates/' indexed."

# 4. Verify 1024d embeddings
DIMS_COUNT=$(sql "SELECT count(*) FROM vector_store_1024 WHERE embedding IS NOT NULL AND vector_dims(embedding) = 1024 AND metadata->>'documentId' IN ('${DOC1_ID}', '${DOC2_ID}', '${TEMPLATE_DOC_ID}');")
[ "${DIMS_COUNT:-0}" -ge 3 ] \
  || { log_error "Vector dimension check failed! Expected >= 3 records with 1024d embeddings, found: ${DIMS_COUNT:-0}"; exit 1; }
log_success "Vector dimension check passed (${DIMS_COUNT} 1024d records)."

log_pass "End-to-end vector pipeline, #425 metadata, and selection filters verified."

# ------------------------------------------------------------------------------
# STEP 8: Incremental Skip, Version/Content Update, ACL Update, Tag Scope Exclusion & Trashcan Tombstone
# ------------------------------------------------------------------------------
log_step 8 "Verifying incremental no-op skip, live mutations, ACL-only updates, and real deletion tombstones..."

# 8a. Re-run with no repository changes -> must skip all 3 documents
run_crawler "incremental no-op check"
echo "${SCAN_SUMMARY}" | grep -q "0 documents, 3 unchanged, 0 tombstones" \
  || { log_error "Expected 0 documents and 3 unchanged on no-op incremental run, got: ${SCAN_SUMMARY}"; exit 1; }
log_success "Incremental no-op verified (0 documents re-crawled, 3 unchanged)."

# 8b. Live mutation 1: ACL-only update on Doc 1 (no content change!) -> must trigger re-index via aclFingerprint
ACL2_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "${ALFRESCO_URL}/nodes/${DOC1_ID}" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: application/json" \
  -d '{"permissions":{"isInheritanceEnabled":false,"locallySet":[{"authorityId":"admin","name":"Coordinator","accessStatus":"ALLOWED"},{"authorityId":"GROUP_ALFRESCO_ADMINISTRATORS","name":"Consumer","accessStatus":"ALLOWED"}]}}')
[ "${ACL2_CODE}" = "200" ] || { log_error "Failed to update ACL on Doc 1 (HTTP ${ACL2_CODE})"; exit 1; }

run_crawler "incremental ACL-only change detection"
echo "${SCAN_SUMMARY}" | grep -q "1 documents, 2 unchanged, 0 tombstones" \
  || { log_error "Expected 1 re-indexed doc on ACL-only change, got: ${SCAN_SUMMARY}"; exit 1; }
wait_for_vector_count "Doc 1 ACL updated in pgvector with GROUP_ALFRESCO_ADMINISTRATORS" \
  "metadata->>'documentId' = '${DOC1_ID}' AND metadata::text LIKE '%GROUP_ALFRESCO_ADMINISTRATORS%'" "-gt 0"

# 8c. Live mutations 2, 3 & 4:
#   - Update Doc 1 to Major Release v2.0
#   - Tag Templates doc as 'archived' (leaves excludedTags scope -> tombstone)
#   - Delete Doc 2 to Alfresco Trashcan (vanishes from folder -> tombstone)
DOC1_V2_CONTENT="OpenCrawling Alfresco Specification Major Release Version 2. ALFRESCO-INCR-V2-UPDATED. Incremental updates replace stale chunks atomically."
V2_UP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  "${ALFRESCO_URL}/nodes/${DOC1_ID}/content?majorVersion=true&comment=Major+Release+2.0" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: text/plain" --data-binary "${DOC1_V2_CONTENT}")
[ "${V2_UP_CODE}" = "200" ] || { log_error "Failed to upload Doc 1 v2.0 (HTTP ${V2_UP_CODE})"; exit 1; }

curl -sf -X POST "${ALFRESCO_URL}/nodes/${TEMPLATE_DOC_ID}/tags" -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" -d '{"tag":"archived"}' >/dev/null

DEL2_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC2_ID}")
[ "${DEL2_CODE}" = "204" ] || { log_error "Failed to delete Doc 2 (HTTP ${DEL2_CODE})"; exit 1; }
TRASHED_DOC_IDS+=("${DOC2_ID}")

# Confirm Doc 2 appears in Alfresco /deleted-nodes Trashcan
TRASH_CHECK=$(curl -sf -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/deleted-nodes?maxItems=50" \
  | jq -r --arg id "${DOC2_ID}" '.list.entries[].entry | select(.id == $id) | .id // empty')
[ "${TRASH_CHECK}" = "${DOC2_ID}" ] || { log_error "Deleted Doc 2 '${DOC2_ID}' did not appear in Alfresco /deleted-nodes!"; exit 1; }
log_success "Confirmed Doc 2 is archived in Alfresco Trashcan (/deleted-nodes)."

run_crawler "incremental update + scope exclusion + deletion tombstones"
echo "${SCAN_SUMMARY}" | grep -q "1 documents, 0 unchanged, 2 tombstones" \
  || { log_error "Expected 1 updated doc and 2 tombstones (Doc 2 deleted + Template doc archived), got: ${SCAN_SUMMARY}"; exit 1; }

wait_for_vector_count "Doc 1 updated to v2 content in pgvector" \
  "metadata->>'documentId' = '${DOC1_ID}' AND content LIKE '%ALFRESCO-INCR-V2-UPDATED%'" "-gt 0"
wait_for_vector_count "Doc 1 stale v1 content replaced in pgvector" \
  "metadata->>'documentId' = '${DOC1_ID}' AND content LIKE '%ALFRESCO-SPEC-V1-MARKER%'" "-eq 0"
wait_for_vector_count "Deleted Doc 2 purged from pgvector via Kafka DELETE tombstone" \
  "metadata->>'documentId' = '${DOC2_ID}'" "-eq 0"
wait_for_vector_count "Archived Templates doc purged from pgvector via scope-exclusion tombstone" \
  "metadata->>'documentId' = '${TEMPLATE_DOC_ID}'" "-eq 0"

log_pass "Incremental skip, ACL-only re-index, v2 update, tag exclusion, and real deletion tombstones verified."

# ------------------------------------------------------------------------------
# STEP 9: Multi-Version Indexing (versionMode=all) & Version-Mode Switch Cleanup
# ------------------------------------------------------------------------------
log_step 9 "Verifying multi-version indexing (versionMode=all) and version-mode switch tombstones..."

VERSIONS_JSON=$(curl -sf -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC1_ID}/versions?include=properties")
NON_EMPTY_VERSIONS=$(echo "${VERSIONS_JSON}" | jq -r '.list.entries[].entry | select((.content.sizeInBytes // 0) > 0) | .id')
VERSION_V2=$(echo "${NON_EMPTY_VERSIONS}" | sed -n '1p')
VERSION_V1=$(echo "${NON_EMPTY_VERSIONS}" | sed -n '2p')
if [ -z "${VERSION_V2}" ] || [ -z "${VERSION_V1}" ]; then
  log_error "Expected at least 2 non-empty versions on Doc 1, got: ${VERSIONS_JSON}"
  exit 1
fi
log_info "Detected non-empty Alfresco versions on Doc 1: latest='${VERSION_V2}', previous='${VERSION_V1}'."

# Switch to versionMode=all on the same incremental state file
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_VERSION_MODE="all"
run_crawler "versionMode=all multi-version indexing"

wait_for_vector_count "Doc 1 latest version (${DOC1_ID};v=${VERSION_V2}) indexed" \
  "metadata->>'documentId' = '${DOC1_ID};v=${VERSION_V2}' AND content LIKE '%ALFRESCO-INCR-V2-UPDATED%'" "-gt 0"
wait_for_vector_count "Doc 1 historical version (${DOC1_ID};v=${VERSION_V1}) indexed" \
  "metadata->>'documentId' = '${DOC1_ID};v=${VERSION_V1}' AND content LIKE '%ALFRESCO-SPEC-V1-MARKER%'" "-gt 0"
wait_for_vector_count "Previous unversioned Doc 1 (${DOC1_ID}) tombstoned on switch to versionMode=all" \
  "metadata->>'documentId' = '${DOC1_ID}'" "-eq 0"

# Switch back to versionMode=current on the same incremental state file -> tombstones all ;v=... entries
export SPRING_OPENCRAWLING_CONNECTOR_ALFRESCO_VERSION_MODE="current"
run_crawler "versionMode=current tombstone cleanup"

wait_for_vector_count "Unversioned Doc 1 (${DOC1_ID}) restored in pgvector" \
  "metadata->>'documentId' = '${DOC1_ID}' AND content LIKE '%ALFRESCO-INCR-V2-UPDATED%'" "-gt 0"
wait_for_vector_count "Historical versioned IDs (${DOC1_ID};v=*) tombstoned on switch back to current" \
  "metadata->>'documentId' LIKE '${DOC1_ID};v=%'" "-eq 0"

log_pass "Multi-version indexing (ALL) and version-mode switch tombstone cleanup verified."

# ------------------------------------------------------------------------------
# STEP 10: Verify OpenCrawling MCP Server Endpoint
# ------------------------------------------------------------------------------
log_step 10 "Waiting for OpenCrawling MCP Server endpoint to be ready..."

HTTP_STATUS="000"
ELAPSED=0
MCP_TIMEOUT=60

until [ "$HTTP_STATUS" == "200" ] || [ "$HTTP_STATUS" == "400" ] || [ "$HTTP_STATUS" == "405" ] || [ "$HTTP_STATUS" == "404" ] || [ $ELAPSED -ge $MCP_TIMEOUT ]; do
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://localhost:${MCP_PORT}/mcp" 2>/dev/null || echo "")
  if [ -z "$HTTP_STATUS" ] || [ "$HTTP_STATUS" == "000" ]; then
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://localhost:${MCP_PORT}/" 2>/dev/null || echo "000")
  fi
  if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "400" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
    sleep 2
    ELAPSED=$((ELAPSED + 2))
  fi
done

log_info "MCP Server HTTP response status: ${HTTP_STATUS}"

if [ "$HTTP_STATUS" != "200" ] && [ "$HTTP_STATUS" != "400" ] && [ "$HTTP_STATUS" != "405" ] && [ "$HTTP_STATUS" != "404" ]; then
  log_error "MCP Server readiness check failed: returned HTTP status ${HTTP_STATUS}."
  compose logs oc-mcp-server --tail 30 || true
  exit 1
fi

log_success "OpenCrawling MCP Server is online and reachable (HTTP ${HTTP_STATUS})!"
log_pass "MCP Server verified successfully."

# ------------------------------------------------------------------------------
# STEP 11: Verify Open Ingestion Standard (OIS) Document Lifecycle Tombstone DELETE
# ------------------------------------------------------------------------------
log_step 11 "Executing OIS Document Lifecycle Tombstone DELETE action test..."
mvn test -pl oc-alfresco-repository-connector,oc-runtime \
  -Dtest=VectorStoreWriterConsumerTest#testConsumeDeleteTombstonePurgesTheDocumentChunksFromAllVectorStores \
  -Dsurefire.failIfNoSpecifiedTests=false
log_pass "OIS Tombstone DELETE action test passed."

# ------------------------------------------------------------------------------
# STEP 12: Cleanup Test Content in Alfresco Repository
# ------------------------------------------------------------------------------
log_step 12 "Cleaning up test contents and Trashcan entries from Alfresco repository..."

if [ -n "${TEST_FOLDER_ID}" ]; then
  DELETE_RES=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${TEST_FOLDER_ID}?permanent=true")
  if [ "${DELETE_RES}" = "204" ] || [ "${DELETE_RES}" = "200" ]; then
    log_success "Alfresco test folder '${TEST_FOLDER_ID}' permanently removed."
    TEST_FOLDER_ID=""
  else
    log_warn "Failed to remove test folder '${TEST_FOLDER_ID}'. Status: ${DELETE_RES}"
  fi
fi

if [ "${#TRASHED_DOC_IDS[@]}" -gt 0 ]; then
  for doc_id in "${TRASHED_DOC_IDS[@]}"; do
    if [ -n "${doc_id}" ]; then
      PURGE_RES=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/deleted-nodes/${doc_id}")
      if [ "${PURGE_RES}" = "204" ] || [ "${PURGE_RES}" = "200" ]; then
        log_success "Purged trashed document '${doc_id}' from Alfresco Trashcan."
      fi
    fi
  done
  TRASHED_DOC_IDS=()
fi

log_pass "Alfresco repository cleanup complete."

# ------------------------------------------------------------------------------
# Final Summary
# ------------------------------------------------------------------------------
echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: All ${TOTAL_STEPS} Alfresco Decoupled Integration Test Steps Passed! 🎉${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}\n"

exit 0
