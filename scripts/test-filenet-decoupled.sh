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
# OpenCrawling - IBM FileNet P8 Decoupled Integration Test Script
#
# Description:
#   End-to-end test of the decoupled pipeline with the FileNet repository
#   connector. IBM FileNet P8 has no free container image, so the source is the
#   CSGQL mock (oc-filenet-repository-connector/filenet-mock), a Content
#   Services GraphQL emulator serving a generated object store with real PDF /
#   DOCX / TXT content.
#
# Pipeline Lifecycle Tested:
#   1. Prerequisites, clean state, image build
#   2. Infrastructure (CSGQL mock, Kafka, Redis, pgvector, Ollama) and consumers
#   3. Ground truth read from the mock
#   4. oc-crawler crawls the object store (incremental state on a volume)
#   5. pgvector holds every content element once, a two-element document as two
#      documents with their lineage, class properties and permission rules
#   6. MCP secure search honours the FileNet permissions (allow, a partial deny,
#      a document without readers)
#   7. The object store changes (a new version, a deletion, a new document) and
#      an incremental crawl with reconciliation brings pgvector up to date
# ==============================================================================

set -euo pipefail

COMPOSE_FILE="oc-filenet-repository-connector/docker/docker-compose-decoupled-with-filenet.yml"
LOCAL_OVERLAY="oc-filenet-repository-connector/docker/docker-compose-decoupled-with-filenet.local.yml"
MOCK_URL="${MOCK_URL:-http://localhost:8092}"
MCP_PORT="${MCP_PORT:-8080}"
TIMEOUT="${TIMEOUT:-300}"
INGEST_TIMEOUT="${INGEST_TIMEOUT:-300}"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"
SKIP_BUILD="${SKIP_BUILD:-false}"
LOCAL_BUILD="${LOCAL_BUILD:-false}"
PG="postgres-vector-decoupled-filenet"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

PASSED_COUNT=0
TOTAL_STEPS=9

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
echo -e "${BOLD}${YELLOW}=== OpenCrawling Decoupled Pipeline with IBM FileNet P8 (CSGQL mock) Integration Test ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}\n"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

compose() {
  if [ "${LOCAL_BUILD}" = true ]; then
    docker compose -f "${COMPOSE_FILE}" -f "${LOCAL_OVERLAY}" "$@"
  else
    docker compose -f "${COMPOSE_FILE}" "$@"
  fi
}

# psql helper: one value, whitespace stripped; a SQL error is reported instead of ending the script silently
sql() {
  local out
  if ! out=$(docker exec -i "${PG}" psql -v ON_ERROR_STOP=1 -U opencrawling -d opencrawling -t -A -P pager=off -c "$1" 2>&1); then
    log_error "SQL failed: ${out}" >&2
    echo 0
    return 0
  fi
  echo "$out" | tr -d '[:space:]'
}

mock() {
  curl -sf "${MOCK_URL}$1" "${@:2}"
}

# the mock's admin path of a version series ({GUID} braces URL-encoded)
series_path() {
  echo "/mock/documents/$(echo "$1" | sed 's/{/%7B/; s/}/%7D/')"
}

# the OIS id of a version series
doc_id() {
  echo "filenet://OS1/documents/$(echo "$1" | tr -d '{}')"
}

run_crawler() {
  log_info "Running oc-crawler against the CSGQL mock..."
  compose up -d --force-recreate oc-crawler >/dev/null
  local elapsed=0
  until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-filenet 2>/dev/null || echo 'false')" == "false" ]; do
    if [ $elapsed -ge "$TIMEOUT" ]; then
      log_error "Timeout waiting for oc-crawler-service-filenet."
      compose logs oc-crawler --tail 60
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  local code
  code=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-filenet 2>/dev/null || echo 1)
  if [ "$code" -ne 0 ]; then
    log_error "oc-crawler-service-filenet exited with ${code}."
    compose logs oc-crawler --tail 60
    exit 1
  fi
  SCAN_SUMMARY=$(docker logs oc-crawler-service-filenet 2>&1 | grep "FileNet scan of object store" | tail -1 || true)
  log_success "Crawl finished: ${SCAN_SUMMARY#*-- }"
  echo "${SCAN_SUMMARY}" | grep -q " 0 failed" || { log_error "The crawl reported failures."; exit 1; }
}

# waits until a SQL query's number satisfies a test ("-gt 0", "-eq 0", ...)
wait_sql() {
  local description="$1" query="$2" test="$3" elapsed=0 count
  while true; do
    count=$(sql "$query")
    count=${count:-0}
    # shellcheck disable=SC2086
    if [ "$count" $test ]; then
      log_success "${description} (${count})."
      return 0
    fi
    if [ $elapsed -ge "$INGEST_TIMEOUT" ]; then
      log_error "${description}: not reached after ${INGEST_TIMEOUT}s (${count})."
      compose logs oc-ingestion-consumer --tail 30 || true
      compose logs oc-embedding-consumer --tail 30 || true
      compose logs oc-writer-consumer --tail 30 || true
      exit 1
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
}

# waits until the number of chunks matching a condition satisfies a test
wait_for() {
  wait_sql "$1" "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
    (SELECT count(*) FROM vector_store_1024 WHERE $2) END;" "$3"
}

cleanup() {
  local exit_code=$?
  if [ "$exit_code" -ne 0 ]; then
    log_error "Test execution interrupted or failed with exit code $exit_code."
  fi
  if [ "${KEEP_CONTAINERS}" = false ]; then
    log_info "Tearing down the decoupled FileNet Docker Compose environment..."
    compose down --remove-orphans >/dev/null 2>&1 || true
    log_success "Environment cleanup complete."
  else
    log_warn "KEEP_CONTAINERS=true set. Skipping docker compose teardown."
  fi
}
trap cleanup EXIT

# ------------------------------------------------------------------------------
# STEP 1: Prerequisites
# ------------------------------------------------------------------------------
log_step 1 "Verifying system prerequisites and dependencies..."
command -v docker >/dev/null 2>&1 || { log_error "docker is required but not installed."; exit 1; }
docker compose version >/dev/null 2>&1 || { log_error "docker compose is required."; exit 1; }
command -v curl >/dev/null 2>&1 || { log_error "curl is required."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required."; exit 1; }
log_pass "Prerequisites verified successfully."

# ------------------------------------------------------------------------------
# STEP 2: Clean state
# ------------------------------------------------------------------------------
log_step 2 "Removing previous FileNet stack containers and its own volumes..."
compose down --remove-orphans >/dev/null 2>&1 || true
# only this stack's volumes: the shared Ollama model volume is kept
for v in opencrawling-pgdata-filenet opencrawling-redisdata-filenet opencrawling-kafkadata-filenet \
         opencrawling-claims-filenet opencrawling-reportsdata-filenet opencrawling-state-filenet; do
  docker volume rm "$v" >/dev/null 2>&1 || true
done
# another stack's containers are never removed: a port in use is an error (MCP_PORT moves the MCP server's port)
export MCP_PORT
for p in "${MCP_PORT}" 8092 9092 5432 6379 11434; do
  c_names=$(docker ps --filter "publish=$p" --format '{{.Names}}' 2>/dev/null | tr '\n' ' ' || true)
  if [ -n "${c_names// /}" ]; then
    log_error "Port ${p} is in use by ${c_names}; stop that stack first (or set MCP_PORT for port ${MCP_PORT})."
    exit 1
  fi
done
log_pass "Previous state removed."

# ------------------------------------------------------------------------------
# STEP 3: Build images
# ------------------------------------------------------------------------------
log_step 3 "Building the CSGQL mock and the OpenCrawling decoupled images (LOCAL_BUILD=${LOCAL_BUILD})..."
if [ "${SKIP_BUILD}" = true ]; then
  log_warn "SKIP_BUILD=true: using existing images."
else
  compose build
fi
log_pass "Images ready."

# ------------------------------------------------------------------------------
# STEP 4: Infrastructure
# ------------------------------------------------------------------------------
log_step 4 "Starting the CSGQL mock, infrastructure and consumers..."
compose up -d filenet-mock postgres-vector redis ollama ollama-model-puller kafka \
  oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer oc-mcp-server

wait_healthy() {
  local container="$1" elapsed=0
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || echo starting)" == "healthy" ]; do
    if [ $elapsed -ge "$TIMEOUT" ]; then
      log_error "Timeout waiting for ${container}."
      docker logs "$container" --tail 40 || true
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  log_success "${container} is healthy."
}
wait_healthy filenet-mock-decoupled-filenet
wait_healthy "${PG}"
wait_healthy redis-stack-decoupled-filenet
wait_healthy ollama-decoupled-filenet

log_info "Waiting for the Ollama model puller..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-filenet 2>/dev/null || echo false)" == "false" ]; do
  if [ $ELAPSED -ge "$TIMEOUT" ]; then
    log_error "Timeout waiting for the Ollama model puller."
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ "$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-filenet)" -ne 0 ]; then
  log_error "The Ollama model puller failed."
  compose logs ollama-model-puller
  exit 1
fi
log_pass "Infrastructure is healthy."

# ------------------------------------------------------------------------------
# STEP 5: Ground truth from the mock
# ------------------------------------------------------------------------------
log_step 5 "Reading the ground truth from the CSGQL mock..."
STATS=$(mock /mock/stats)
DOCS=$(mock /mock/documents)
# documents with active markings are left out by default (marked-documents=exclude)
ELEMENTS=$(echo "$STATS" | jq -r '.elements - .markedElements')
OUTSIDE='(.folders | length > 0) and (.folders | all(startswith("/Contracts") | not))'
# a single-element document readable by the Legal group and asmith, which denies view content to mallory
LEGAL_DOC=$(echo "$DOCS" | jq -c "[.[] | select(.pattern == 2 and (.elements | length) == 1 and .elements[0].type != \"reference\" and ${OUTSIDE})][0]")
# a document with a second content element (an annex)
ANNEX_DOC=$(echo "$DOCS" | jq -c '[.[] | select((.elements | length) == 2 and (.markings | length) == 0)][0]')
# a document with an active marking
MARKED_DOC=$(echo "$DOCS" | jq -c '[.[] | select((.markings | length) > 0 and (.elements | length) >= 1 and .elements[0].type != "reference")][0]')
# a document without readers
NOBODY_DOC=$(echo "$DOCS" | jq -c "[.[] | select(.pattern == 5 and (.elements | length) >= 1 and .elements[0].type != \"reference\" and ${OUTSIDE})][0]")
for d in "$LEGAL_DOC" "$ANNEX_DOC" "$NOBODY_DOC" "$MARKED_DOC"; do
  [ "$d" != "null" ] || { log_error "The generated object store lacks a document this test needs."; exit 1; }
done
LEGAL_SERIES=$(echo "$LEGAL_DOC" | jq -r '.seriesId'); LEGAL_CODE=$(echo "$LEGAL_DOC" | jq -r '.code')
ANNEX_SERIES=$(echo "$ANNEX_DOC" | jq -r '.seriesId'); ANNEX_CODE=$(echo "$ANNEX_DOC" | jq -r '.code')
NOBODY_SERIES=$(echo "$NOBODY_DOC" | jq -r '.seriesId'); NOBODY_CODE=$(echo "$NOBODY_DOC" | jq -r '.code')
MARKED_CODE=$(echo "$MARKED_DOC" | jq -r '.code')
log_info "Object store: $(echo "$STATS" | jq -r '.series') documents, $(echo "$STATS" | jq -r '.versions') versions, ${ELEMENTS} content elements of unmarked documents."
log_info "Legal document ${LEGAL_CODE}, two-element document ${ANNEX_CODE}, document without readers ${NOBODY_CODE}, marked document ${MARKED_CODE}."
log_pass "Ground truth recorded."

# ------------------------------------------------------------------------------
# STEP 6: First crawl
# ------------------------------------------------------------------------------
log_step 6 "Crawling the object store and verifying pgvector..."
run_crawler
if [ "$(mock /mock/stats | jq -r '.xsrfFailures + .authFailures')" -ne 0 ]; then
  log_error "The crawler failed authentication or the XSRF check."
  exit 1
fi

wait_for "The Legal document is indexed" "content LIKE '%${LEGAL_CODE}%'" "-gt 0"
wait_for "The two-element document's first element is indexed" "metadata->>'documentId' = '$(doc_id "$ANNEX_SERIES")'" "-gt 0"
wait_for "Its annex is indexed on its own" "content LIKE '%${ANNEX_CODE}-A%'" "-gt 0"
wait_sql "Every content element is indexed" "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
  (SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE content LIKE '%FNX-%') END;" "-ge ${ELEMENTS}"
DISTINCT_DOCS=$(sql "SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE content LIKE '%FNX-%';")
[ "$DISTINCT_DOCS" -eq "$ELEMENTS" ] || { log_error "Expected ${ELEMENTS} indexed elements, found ${DISTINCT_DOCS}."; exit 1; }
log_success "pgvector holds exactly the ${ELEMENTS} content elements."
MARKED_HITS=$(sql "SELECT count(*) FROM vector_store_1024 WHERE content LIKE '%${MARKED_CODE}%';")
[ "$MARKED_HITS" -eq 0 ] || { log_error "The marked document ${MARKED_CODE} must not be indexed."; exit 1; }
log_success "The marked document is left out."

PARENT=$(sql "SELECT DISTINCT metadata->'filenet.parentId'->>0 FROM vector_store_1024 WHERE content LIKE '%${ANNEX_CODE}-A%';")
[ "$PARENT" == "$(doc_id "$ANNEX_SERIES")" ] || { log_error "Unexpected filenet.parentId '${PARENT}'."; exit 1; }
PROPS=$(sql "SELECT count(*) FROM vector_store_1024 WHERE metadata->'filenet.class'->>0 = 'Contract' AND metadata::jsonb ? 'filenet_prop_contractNumber' AND metadata::jsonb ? 'filenet.folderPath';")
[ "$PROPS" -gt 0 ] || { log_error "Class properties or folder paths are missing from the metadata."; exit 1; }
log_success "Metadata carries the element lineage (parentId), class properties and folder paths."

SECURITY=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security' FROM vector_store_1024 WHERE metadata->>'documentId' = '$(doc_id "$LEGAL_SERIES")' LIMIT 1;")
echo "$SECURITY" | jq -e '.permissions | any(.identity == "legal" and .access == "read")' >/dev/null \
  || { log_error "The Legal document should be readable by the legal group: ${SECURITY}"; exit 1; }
echo "$SECURITY" | jq -e '.permissions | any(.identity == "mallory" and .access == "deny")' >/dev/null \
  || { log_error "The Legal document should deny mallory (a deny of view content only): ${SECURITY}"; exit 1; }
log_success "Permission rules: the legal group reads; mallory, denied view content only, is denied."
log_pass "First crawl verified in pgvector."

# ------------------------------------------------------------------------------
# STEP 7: MCP secure search
# ------------------------------------------------------------------------------
log_step 7 "Checking MCP secure search against the FileNet permissions..."
ELAPSED=0
until curl -s -o /dev/null --max-time 3 "http://localhost:${MCP_PORT}/" 2>/dev/null; do
  [ $ELAPSED -ge 120 ] && { log_error "MCP server not reachable."; compose logs oc-mcp-server --tail 40; exit 1; }
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

# MCP over streamable HTTP: POST /mcp, the session in Mcp-Session-Id, responses as JSON or as SSE "data:" lines
MCP="http://localhost:${MCP_PORT}/mcp"
MCP_HEADERS=$(mktemp)
MCP_SESSION=""
mcp_call() {
  curl -s --max-time 30 -D "$MCP_HEADERS" -X POST "$MCP" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' ${MCP_SESSION:+-H "Mcp-Session-Id: ${MCP_SESSION}"} -d "$1" \
    | sed -n 's/^data: *//p; /^{/p' | grep '"jsonrpc"' | tail -1
}
INIT=$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-filenet-decoupled","version":"1.0"}}}')
MCP_SESSION=$(grep -i '^mcp-session-id:' "$MCP_HEADERS" | awk '{print $2}' | tr -d '\r')
[ -n "$(echo "$INIT" | jq -r '.result.serverInfo.name // empty')" ] || { log_error "MCP initialize failed: ${INIT}"; exit 1; }
mcp_call '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null || true

SEARCH_ID=10
search_as() { # <principal> <roles>: the documents (series keys) secureVectorSearch returns
  SEARCH_ID=$((SEARCH_ID + 1))
  local args
  args=$(jq -nc --arg p "$1" --arg r "$2" \
    '{query:"agreement policy invoice", userPrincipal:$p, userRoles:$r, maxResults:500, minScore:0.0, dimensions:1024}')
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":${SEARCH_ID},\"method\":\"tools/call\",\"params\":{\"name\":\"secureVectorSearch\",\"arguments\":${args}}}" \
    | jq -r '.result.content[]?.text // empty' | grep -o 'documents/[0-9A-F-]*' | sort -u || true
}
tool_text() { # <id> <tool> <arguments json>: the text content of a tool call
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}" \
    | jq -r '.result.content[]?.text // empty'
}
LEGAL_KEY="documents/$(echo "$LEGAL_SERIES" | tr -d '{}')"
NOBODY_KEY="documents/$(echo "$NOBODY_SERIES" | tr -d '{}')"
MEMBER=$(search_as "jdoe" "legal")
MALLORY=$(search_as "mallory" "legal")
OUTSIDER=$(search_as "someone" "")
NOBODY_CONTENT=$(tool_text 30 getDocumentContent "$(jq -nc --arg u "$(doc_id "$NOBODY_SERIES")" '{documentUri:$u, userPrincipal:"someone", userRoles:"", dimensions:1024}')")
NOBODY_LISTED=$(tool_text 31 listAccessibleSources '{"userPrincipal":"someone","userRoles":"","dimensions":1024}')
rm -f "$MCP_HEADERS"
echo "$MEMBER" | grep -qx "$LEGAL_KEY" || { log_error "A legal-group member must find the Legal document."; exit 1; }
if echo "$MALLORY" | grep -qx "$LEGAL_KEY"; then
  log_error "mallory is denied view content on the Legal document but found it."
  exit 1
fi
[ -z "$OUTSIDER" ] || { log_error "A principal without permissions found: $(echo $OUTSIDER)"; exit 1; }
if echo "$NOBODY_CONTENT" | grep -q "${NOBODY_CODE}"; then
  log_error "getDocumentContent returned a document without readers to an outsider."
  exit 1
fi
if echo "$NOBODY_LISTED" | grep -q "$NOBODY_KEY"; then
  log_error "listAccessibleSources listed a document without readers to an outsider."
  exit 1
fi
log_success "A legal-group member finds the Legal document; mallory does not; an outsider finds nothing."
log_success "A document without readers stays closed to an outsider in getDocumentContent and listAccessibleSources."
log_pass "MCP secure search enforces the FileNet permissions."

# ------------------------------------------------------------------------------
# STEP 8: Changes and an incremental crawl
# ------------------------------------------------------------------------------
log_step 8 "Changing the object store and running an incremental crawl..."
mock "$(series_path "$ANNEX_SERIES")/checkin" -X POST -d '{"text":"Amendment two."}' >/dev/null
ANNEX_CODE_V2=$(mock "$(series_path "$ANNEX_SERIES")" | jq -r '.code')
mock "$(series_path "$LEGAL_SERIES")/delete" -X POST -d '{}' >/dev/null
NEW_SERIES=$(mock /mock/documents -X POST -d '{"class":"Document","title":"Incremental NDA","text":"Incremental NDA, reference INCR-FN-0001."}' | jq -r '.seriesId')
log_info "Checked in ${ANNEX_CODE_V2} over ${ANNEX_CODE}, deleted ${LEGAL_CODE}, added $(doc_id "$NEW_SERIES")."
sleep 2
run_crawler

wait_for "The new document is indexed" "content LIKE '%INCR-FN-0001%'" "-gt 0"
wait_for "The new version (${ANNEX_CODE_V2}) is indexed" "content LIKE '%${ANNEX_CODE_V2}%'" "-gt 0"
wait_for "The old version (${ANNEX_CODE}, both elements) is replaced" "content LIKE '%${ANNEX_CODE}%'" "-eq 0"
wait_for "The deleted document is purged" "content LIKE '%${LEGAL_CODE}%'" "-eq 0"
log_pass "The incremental crawl brought pgvector up to date."

# ------------------------------------------------------------------------------
# STEP 9: Summary
# ------------------------------------------------------------------------------
log_step 9 "Summary"
log_info "CSGQL requests served: $(mock /mock/stats | jq -c '.requests' | cut -c1-400)"
log_pass "FileNet decoupled integration test complete."

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: All ${TOTAL_STEPS} FileNet Decoupled Integration Test Steps Passed!${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}\n"
exit 0
