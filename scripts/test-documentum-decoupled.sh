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
# OpenCrawling - OpenText Documentum Decoupled Integration Test Script
#
# Description:
#   End-to-end test of the decoupled pipeline with the Documentum repository
#   connector. OpenText Documentum has no free container image, so the source
#   is the DCTM REST mock (oc-documentum-repository-connector/documentum-mock),
#   a Documentum REST Services emulator serving a generated repository with
#   real PDF / DOCX / TXT content, in a server time zone two hours east of UTC.
#
# Pipeline Lifecycle Tested:
#   1. Prerequisites, clean state, image build
#   2. Infrastructure (DCTM REST mock, Kafka, Redis, pgvector, Ollama) and consumers
#   3. Ground truth read from the mock
#   4. oc-crawler crawls the repository (incremental state on a volume)
#   5. pgvector holds every document with content once, custom type attributes,
#      folder paths and permission rules; required-group documents are left out
#   6. MCP secure search honours the Documentum permissions (a group grant, an
#      access restriction, a document without readers)
#   7. The repository changes (a new version, a destroyed document, a new
#      document, an edited permission set) and an incremental crawl with
#      reconciliation brings pgvector up to date
# ==============================================================================

set -euo pipefail

COMPOSE_FILE="oc-documentum-repository-connector/docker/docker-compose-decoupled-with-documentum.yml"
LOCAL_OVERLAY="oc-documentum-repository-connector/docker/docker-compose-decoupled-with-documentum.local.yml"
MOCK_URL="${MOCK_URL:-http://localhost:8093}"
MCP_PORT="${MCP_PORT:-8080}"
TIMEOUT="${TIMEOUT:-300}"
INGEST_TIMEOUT="${INGEST_TIMEOUT:-300}"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"
SKIP_BUILD="${SKIP_BUILD:-false}"
LOCAL_BUILD="${LOCAL_BUILD:-false}"
PG="postgres-vector-decoupled-documentum"

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
echo -e "${BOLD}${YELLOW}=== OpenCrawling Decoupled Pipeline with OpenText Documentum (DCTM REST mock) Integration Test ===${NC}"
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

# the OIS id of a chronicle
doc_id() {
  echo "documentum://corp/documents/$1"
}

# the reference code the mock writes into a document's current content: DCTM-<n>-v<label>
code_of() {
  echo "$1" | jq -r '"DCTM-" + (.objectName | capture("(?<n>[0-9]+)$").n | tonumber | tostring) + "-v"
    + (.currentId as $c | .versions[] | select(.id == $c) | .labels[0])'
}

run_crawler() {
  log_info "Running oc-crawler against the DCTM REST mock..."
  compose up -d --force-recreate oc-crawler >/dev/null
  local elapsed=0
  until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-documentum 2>/dev/null || echo 'false')" == "false" ]; do
    if [ $elapsed -ge "$TIMEOUT" ]; then
      log_error "Timeout waiting for oc-crawler-service-documentum."
      compose logs oc-crawler --tail 60
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  local code
  code=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-documentum 2>/dev/null || echo 1)
  if [ "$code" -ne 0 ]; then
    log_error "oc-crawler-service-documentum exited with ${code}."
    compose logs oc-crawler --tail 60
    exit 1
  fi
  SCAN_SUMMARY=$(docker logs oc-crawler-service-documentum 2>&1 | grep "Documentum scan of repository" | tail -1 || true)
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
    log_info "Tearing down the decoupled Documentum Docker Compose environment..."
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
log_step 2 "Removing previous Documentum stack containers and its own volumes..."
compose down --remove-orphans >/dev/null 2>&1 || true
# only this stack's volumes: the shared Ollama model volume is kept
for v in opencrawling-pgdata-documentum opencrawling-redisdata-documentum opencrawling-kafkadata-documentum \
         opencrawling-claims-documentum opencrawling-reportsdata-documentum opencrawling-state-documentum; do
  docker volume rm "$v" >/dev/null 2>&1 || true
done
# another stack's containers are never removed: a port in use is an error (MCP_PORT moves the MCP server's port)
export MCP_PORT
for p in "${MCP_PORT}" 8093 9092 5432 6379 11434; do
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
log_step 3 "Building the DCTM REST mock and the OpenCrawling decoupled images (LOCAL_BUILD=${LOCAL_BUILD})..."
if [ "${SKIP_BUILD}" = true ]; then
  log_warn "SKIP_BUILD=true: using existing images."
else
  compose build
fi
log_pass "Images ready."

# ------------------------------------------------------------------------------
# STEP 4: Infrastructure
# ------------------------------------------------------------------------------
log_step 4 "Starting the DCTM REST mock, infrastructure and consumers..."
compose up -d documentum-mock postgres-vector redis ollama ollama-model-puller kafka \
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
wait_healthy documentum-mock-decoupled-documentum
wait_healthy "${PG}"
wait_healthy redis-stack-decoupled-documentum
wait_healthy ollama-decoupled-documentum

log_info "Waiting for the Ollama model puller..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-documentum 2>/dev/null || echo false)" == "false" ]; do
  if [ $ELAPSED -ge "$TIMEOUT" ]; then
    log_error "Timeout waiting for the Ollama model puller."
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ "$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-documentum)" -ne 0 ]; then
  log_error "The Ollama model puller failed."
  compose logs ollama-model-puller
  exit 1
fi
log_pass "Infrastructure is healthy."

# ------------------------------------------------------------------------------
# STEP 5: Ground truth from the mock
# ------------------------------------------------------------------------------
log_step 5 "Reading the ground truth from the DCTM REST mock..."
DOCS=$(mock /mock/documents)
REQUIRED='(.acl[1] == "oc_legal_required" or .acl[1] == "oc_region_set")'
HAS_CONTENT='((.currentId as $c | .versions[] | select(.id == $c) | .formats | length) > 0)'
# documents whose permission set has required groups are left out by default (required-group-documents=exclude)
EXPECTED=$(echo "$DOCS" | jq "[.[] | select((${REQUIRED} | not) and ${HAS_CONTENT})] | length")
# readable by finance and emea, with an access restriction taking READ from Mallory Grey (who is in emea)
RESTRICTED_DOC=$(echo "$DOCS" | jq -c "[.[] | select(.acl[1] == \"oc_finance_restricted\" and ${HAS_CONTENT})][0]")
# a permission set nobody can READ through (BROWSE only)
NOBODY_DOC=$(echo "$DOCS" | jq -c "[.[] | select(.acl[1] == \"oc_browse_only\" and ${HAS_CONTENT})][0]")
# a required group (legal)
REQUIRED_DOC=$(echo "$DOCS" | jq -c "[.[] | select(.acl[1] == \"oc_legal_required\" and ${HAS_CONTENT})][0]")
# a contract with more than one version, readable by the world (oc_public_read)
VERSIONED_DOC=$(echo "$DOCS" | jq -c "[.[] | select(.acl[1] == \"oc_public_read\" and ${HAS_CONTENT} and (.versions | length) > 1)][0]")
for d in "$RESTRICTED_DOC" "$NOBODY_DOC" "$REQUIRED_DOC" "$VERSIONED_DOC"; do
  [ "$d" != "null" ] || { log_error "The generated repository lacks a document this test needs."; exit 1; }
done
RESTRICTED_ID=$(echo "$RESTRICTED_DOC" | jq -r '.chronicleId'); RESTRICTED_CODE=$(code_of "$RESTRICTED_DOC")
NOBODY_ID=$(echo "$NOBODY_DOC" | jq -r '.chronicleId'); NOBODY_CODE=$(code_of "$NOBODY_DOC")
REQUIRED_CODE=$(code_of "$REQUIRED_DOC")
VERSIONED_ID=$(echo "$VERSIONED_DOC" | jq -r '.chronicleId'); VERSIONED_CODE=$(code_of "$VERSIONED_DOC")
log_info "Repository: $(echo "$DOCS" | jq length) documents, ${EXPECTED} with content and without required groups."
log_info "Restricted ${RESTRICTED_CODE}, without readers ${NOBODY_CODE}, required group ${REQUIRED_CODE}, versioned ${VERSIONED_CODE}."
log_pass "Ground truth recorded."

# ------------------------------------------------------------------------------
# STEP 6: First crawl
# ------------------------------------------------------------------------------
log_step 6 "Crawling the repository and verifying pgvector..."
run_crawler
if [ "$(mock /mock/stats | jq -r '.authFailures')" -ne 0 ]; then
  log_error "The crawler failed authentication."
  exit 1
fi

wait_for "The restricted document is indexed" "content LIKE '%${RESTRICTED_CODE}%'" "-gt 0"
wait_sql "Every document with content is indexed" "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
  (SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE content LIKE '%DCTM-%') END;" "-ge ${EXPECTED}"
DISTINCT_DOCS=$(sql "SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE content LIKE '%DCTM-%';")
[ "$DISTINCT_DOCS" -eq "$EXPECTED" ] || { log_error "Expected ${EXPECTED} indexed documents, found ${DISTINCT_DOCS}."; exit 1; }
log_success "pgvector holds exactly the ${EXPECTED} documents."
REQUIRED_HITS=$(sql "SELECT count(*) FROM vector_store_1024 WHERE content LIKE '%${REQUIRED_CODE}%';")
[ "$REQUIRED_HITS" -eq 0 ] || { log_error "The required-group document ${REQUIRED_CODE} must not be indexed."; exit 1; }
log_success "The required-group document is left out."

ATTRS=$(sql "SELECT count(*) FROM vector_store_1024 WHERE metadata->'documentum.type'->>0 = 'oc_contract' AND metadata::jsonb ? 'documentum_attr_contract_number' AND metadata::jsonb ? 'documentum_attr_regions' AND metadata::jsonb ? 'documentum.folderPath';")
[ "$ATTRS" -gt 0 ] || { log_error "Custom type attributes or folder paths are missing from the metadata."; exit 1; }
log_success "Metadata carries custom type attributes (single and repeating) and folder paths."

SECURITY=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security' FROM vector_store_1024 WHERE metadata->>'documentId' = '$(doc_id "$RESTRICTED_ID")' LIMIT 1;")
echo "$SECURITY" | jq -e '.permissions | any(.identity == "emea" and .access == "read")' >/dev/null \
  || { log_error "The restricted document should be readable by the emea group: ${SECURITY}"; exit 1; }
echo "$SECURITY" | jq -e '.permissions | any(.identity == "mallory grey" and .access == "deny")' >/dev/null \
  || { log_error "The restricted document should deny Mallory Grey (an access restriction): ${SECURITY}"; exit 1; }
log_success "Permission rules: emea reads; Mallory Grey, restricted below READ, is denied."
log_pass "First crawl verified in pgvector."

# ------------------------------------------------------------------------------
# STEP 7: MCP secure search
# ------------------------------------------------------------------------------
log_step 7 "Checking MCP secure search against the Documentum permissions..."
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
INIT=$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-documentum-decoupled","version":"1.0"}}}')
MCP_SESSION=$(grep -i '^mcp-session-id:' "$MCP_HEADERS" | awk '{print $2}' | tr -d '\r')
[ -n "$(echo "$INIT" | jq -r '.result.serverInfo.name // empty')" ] || { log_error "MCP initialize failed: ${INIT}"; exit 1; }
mcp_call '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null || true

SEARCH_ID=10
search_as() { # <principal> <roles>: the documents (chronicle keys) secureVectorSearch returns
  SEARCH_ID=$((SEARCH_ID + 1))
  local args
  args=$(jq -nc --arg p "$1" --arg r "$2" \
    '{query:"agreement policy invoice", userPrincipal:$p, userRoles:$r, maxResults:500, minScore:0.0, dimensions:1024}')
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":${SEARCH_ID},\"method\":\"tools/call\",\"params\":{\"name\":\"secureVectorSearch\",\"arguments\":${args}}}" \
    | jq -r '.result.content[]?.text // empty' | grep -o 'documents/[0-9a-f]*' | sort -u || true
}
tool_text() { # <id> <tool> <arguments json>: the text content of a tool call
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}" \
    | jq -r '.result.content[]?.text // empty'
}
RESTRICTED_KEY="documents/${RESTRICTED_ID}"
NOBODY_KEY="documents/${NOBODY_ID}"
MEMBER=$(search_as "john doe" "emea")
MALLORY=$(search_as "mallory grey" "emea")
OUTSIDER=$(search_as "someone" "")
NOBODY_CONTENT=$(tool_text 30 getDocumentContent "$(jq -nc --arg u "$(doc_id "$NOBODY_ID")" '{documentUri:$u, userPrincipal:"someone", userRoles:"", dimensions:1024}')")
NOBODY_LISTED=$(tool_text 31 listAccessibleSources '{"userPrincipal":"someone","userRoles":"","dimensions":1024}')
rm -f "$MCP_HEADERS"
echo "$MEMBER" | grep -qx "$RESTRICTED_KEY" || { log_error "An emea member must find the restricted document."; exit 1; }
if echo "$MALLORY" | grep -qx "$RESTRICTED_KEY"; then
  log_error "Mallory Grey is restricted below READ on the restricted document but found it."
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
log_success "An emea member finds the restricted document; Mallory Grey does not; an outsider finds nothing."
log_success "A document without readers stays closed to an outsider in getDocumentContent and listAccessibleSources."
log_pass "MCP secure search enforces the Documentum permissions."

# ------------------------------------------------------------------------------
# STEP 8: Changes and an incremental crawl
# ------------------------------------------------------------------------------
log_step 8 "Changing the repository and running an incremental crawl..."
VERSIONED_V2=$(mock "/mock/documents/${VERSIONED_ID}/checkin" -X POST -d '{"major":true,"text":"Amendment two."}')
VERSIONED_CODE_V2=$(code_of "$VERSIONED_V2")
mock "/mock/documents/${RESTRICTED_ID}/destroy" -X POST -d '{}' >/dev/null
mock /mock/documents -X POST \
  -d '{"type":"oc_contract","folders":["/Corporate/Contracts"],"pattern":1,"format":"pdf","text":"Incremental NDA, reference INCR-DCTM-0001."}' >/dev/null
NEW_CODE="INCR-DCTM-0001"
# an edited permission set changes no document: the connector must notice it by itself
mock /mock/acls/dmadmin/oc_browse_only -X POST \
  -d '{"entries":[{"name":"dm_world","permit":2},{"name":"dm_owner","permit":7},{"name":"auditors","permit":3,"isGroup":true}]}' >/dev/null
log_info "Checked in ${VERSIONED_CODE_V2} over ${VERSIONED_CODE}, destroyed ${RESTRICTED_CODE}, added ${NEW_CODE}, granted auditors READ on oc_browse_only."
sleep 2
run_crawler

wait_for "The new document is indexed" "content LIKE '%${NEW_CODE}%'" "-gt 0"
wait_for "The new version (${VERSIONED_CODE_V2}) is indexed" "content LIKE '%${VERSIONED_CODE_V2}%'" "-gt 0"
wait_for "The old version (${VERSIONED_CODE}) is replaced" "content LIKE '%${VERSIONED_CODE}.%'" "-eq 0"
wait_for "The destroyed document is purged" "content LIKE '%${RESTRICTED_CODE}%'" "-eq 0"
wait_for "The edited permission set reached the index" "metadata->>'documentId' = '$(doc_id "$NOBODY_ID")' AND (metadata::jsonb)->'security'->'permissions' @> '[{\"identity\":\"auditors\",\"access\":\"read\"}]'" "-gt 0"
log_pass "The incremental crawl brought pgvector up to date."

# ------------------------------------------------------------------------------
# STEP 9: Summary
# ------------------------------------------------------------------------------
log_step 9 "Summary"
log_info "DCTM REST requests served: $(mock /mock/stats | jq -c '.requests' | cut -c1-400)"
log_pass "Documentum decoupled integration test complete."

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: All ${TOTAL_STEPS} Documentum Decoupled Integration Test Steps Passed!${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}\n"
exit 0
