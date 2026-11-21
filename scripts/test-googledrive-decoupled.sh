#!/usr/bin/env bash
#
# Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
# OpenCrawling - Google Drive Decoupled Pipeline Integration Test Script
#
# Description:
#   End-to-end test of the decoupled OpenCrawling pipeline with the Google Drive
#   Repository Connector (`oc-googledrive-repository-connector`). Because Google
#   Drive / Google Workspace cannot run in a local container, the source is the
#   Google Drive API v3 & OAuth2 emulator
#   (`oc-googledrive-repository-connector/googledrive-mock`), serving a Shared
#   Drive with Google Docs (exported to HTML), Google Slides (exported to plain
#   text), Google Sheets & Forms (excluded by default), real binary PDFs, plain
#   text files, an oversized file (`TOO_LARGE`), folder hierarchies, custom
#   properties, and Zero-Trust Drive permissions.
#
# Pipeline Lifecycle Tested:
#   1. Prerequisites verification (docker, docker compose, curl, jq)
#   2. Clean state & host port checks for the Google Drive decoupled stack
#   3. Build `googledrive-mock` and OpenCrawling decoupled microservice images
#      (`--local` uses `docker/local/Dockerfile.*` with BuildKit `~/.m2` cache)
#   4. Start `googledrive-mock`, pgvector (also JDBC Control Plane store),
#      Redis, Ollama (+ `mxbai-embed-large`), Kafka, and decoupled consumers
#   5. Validate Google Drive API v3 & OAuth2 contract and read mock ground truth
#   6. Initial `oc-crawler` scan (`crawlMode=changes`) -> Kafka -> Apache Tika
#      -> Ollama -> pgvector:
#      - exact indexed document count (Docs HTML, Slides text, PDF, plain text)
#      - Google Sheets & Forms excluded by default (0 rows)
#      - oversized file reported as `TOO_LARGE` (not indexed, 0 failures)
#      - structured metadata (`webViewLink`, `title`, `breadcrumbs`, `versionKey`,
#        `googledrive_prop_*`) & Zero-Trust ACLs (user, group, domain, `anyone`
#        closed by default)
#      - Changes API `startPageToken` persisted in PostgreSQL `oc_setting`
#        (shared JDBC Control Plane store)
#   7. MCP Server Zero-Trust ACL enforcement (`secureVectorSearch`,
#      `getDocumentContent`, `listAccessibleSources`) across Alice (user),
#      Support group, Domain member, Bob (restricted), and Outsider
#   8. Live Google Drive mutations + incremental Changes API sync (`changes`):
#      - edit Google Doc, add new Google Doc, delete Slides deck, trash parent
#        folder (verifying cascading descendant tombstones), and confirm
#        `startPageToken` advances in PostgreSQL `oc_setting`
#   9. Permission-only change (without Change event) reconciled by a full
#      reconciliation crawl (`crawlMode=tree`), reusing the shared state to
#      re-emit only the modified file
# ==============================================================================

set -euo pipefail

COMPOSE_FILE="oc-googledrive-repository-connector/docker/docker-compose-decoupled-with-googledrive.yml"
LOCAL_OVERLAY="${LOCAL_OVERLAY:-${COMPOSE_FILE%.yml}.local.yml}"
GOOGLEDRIVE_MOCK_PORT="${GOOGLEDRIVE_MOCK_PORT:-8095}"
MOCK_URL="${MOCK_URL:-http://localhost:${GOOGLEDRIVE_MOCK_PORT}}"
MOCK_TOKEN="${GOOGLEDRIVE_ACCESS_TOKEN:-mock-gdrive-access-token}"
MCP_PORT="${MCP_PORT:-8080}"
TIMEOUT="${TIMEOUT:-300}"
INGEST_TIMEOUT="${INGEST_TIMEOUT:-300}"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"
SKIP_BUILD="${SKIP_BUILD:-false}"
LOCAL_BUILD="${LOCAL_BUILD:-false}"

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
  SKIP_BUILD=true            Equivalent to -s, --skip-build
  LOCAL_BUILD=true           Equivalent to -l, --local-build
  KEEP_CONTAINERS=true       Equivalent to -k, --keep-containers
  GOOGLEDRIVE_MOCK_PORT=8095 Host port mapped to googledrive-mock:8080
  MCP_PORT=8080              Host port mapped to oc-mcp-server:8080
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

PG="postgres-vector-decoupled-googledrive"
DRIVE_ID="0AO_SUPPORT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

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
echo -e "${BOLD}${YELLOW}=== OpenCrawling Decoupled Pipeline with Google Drive (API v3 Mock) Test =======${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}\n"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

compose() {
  if [ "${LOCAL_BUILD}" = true ] && [ -f "${LOCAL_OVERLAY}" ]; then
    docker compose -f "${COMPOSE_FILE}" -f "${LOCAL_OVERLAY}" "$@"
  else
    docker compose -f "${COMPOSE_FILE}" "$@"
  fi
}

# psql helper: one value, whitespace stripped; SQL errors fail explicitly
sql() {
  local out
  if ! out=$(docker exec -i "${PG}" psql -v ON_ERROR_STOP=1 -U opencrawling -d opencrawling -t -A -P pager=off -c "$1" 2>&1); then
    log_error "SQL failed: ${out}" >&2
    echo "SQL-ERROR"
    return 0
  fi
  echo "$out" | tr -d '[:space:]'
}

mock() {
  curl -sf "${MOCK_URL}$1" "${@:2}"
}

doc_id() {
  echo "googledrive://drives/${DRIVE_ID}/files/$1"
}

wait_sql() {
  local description="$1" query="$2" test="$3" elapsed=0 count
  while true; do
    count=$(sql "$query")
    count=${count:-0}
    case "$count" in
      ''|*[!0-9]*) log_error "${description}: SQL query failed (${count})."; exit 1 ;;
    esac
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

wait_for() {
  wait_sql "$1" "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
    (SELECT count(*) FROM vector_store_1024 WHERE $2) END;" "$3"
}

run_crawler() {
  local mode="${1:-changes}"
  log_info "Running oc-crawler against Google Drive mock (driveId=${DRIVE_ID}, crawlMode=${mode})..."
  GOOGLEDRIVE_CRAWL_MODE="${mode}" GOOGLEDRIVE_ACCESS_TOKEN="${MOCK_TOKEN}" \
    compose up -d --no-deps --force-recreate oc-crawler >/dev/null
  local elapsed=0
  until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-googledrive 2>/dev/null || echo false)" == "false" ]; do
    if [ $elapsed -ge "$TIMEOUT" ]; then
      log_error "Timeout waiting for oc-crawler-service-googledrive."
      compose logs oc-crawler --tail 60
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  local code
  code=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-googledrive 2>/dev/null || echo 1)
  if [ "$code" -ne 0 ]; then
    log_error "oc-crawler-service-googledrive exited with ${code}."
    compose logs oc-crawler --tail 80
    exit 1
  fi
  SCAN_SUMMARY=$(docker logs oc-crawler-service-googledrive 2>&1 | grep "Google Drive scan completed" | tail -1 || true)
  log_success "Crawl finished: ${SCAN_SUMMARY#*-- }"
  echo "${SCAN_SUMMARY}" | grep -q " 0 failed" || { log_error "The crawl reported failures: ${SCAN_SUMMARY}"; exit 1; }
}

cleanup() {
  local exit_code=$?
  if [ "$exit_code" -ne 0 ]; then
    log_error "Test execution interrupted or failed with exit code $exit_code."
  fi
  if [ "${KEEP_CONTAINERS}" = false ]; then
    log_info "Tearing down the decoupled Google Drive Docker Compose environment..."
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
for tool in docker curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { log_error "$tool is required but not installed."; exit 1; }
done
docker compose version >/dev/null 2>&1 || { log_error "docker compose is required."; exit 1; }
log_pass "Prerequisites verified successfully."

# ------------------------------------------------------------------------------
# STEP 2: Clean state & port checks
# ------------------------------------------------------------------------------
log_step 2 "Removing previous Google Drive stack containers and its own volumes..."
compose down --remove-orphans >/dev/null 2>&1 || true
# Only remove this stack's volumes; preserve the shared Ollama model cache volume
for v in opencrawling-pgdata-googledrive opencrawling-redisdata-googledrive opencrawling-kafkadata-googledrive \
         opencrawling-claims-googledrive opencrawling-reportsdata-googledrive; do
  docker volume rm "$v" >/dev/null 2>&1 || true
done
export MCP_PORT GOOGLEDRIVE_MOCK_PORT
for p in "${MCP_PORT}" "${GOOGLEDRIVE_MOCK_PORT}" 9092 5432 6379 11434; do
  c_names=$(docker ps --filter "publish=$p" --format '{{.Names}}' 2>/dev/null | tr '\n' ' ' || true)
  if [ -n "${c_names// /}" ]; then
    log_error "Port ${p} is in use by ${c_names}; stop that stack first (or set MCP_PORT/GOOGLEDRIVE_MOCK_PORT)."
    exit 1
  fi
done
log_pass "Previous state removed; ports are free."

# ------------------------------------------------------------------------------
# STEP 3: Build images
# ------------------------------------------------------------------------------
log_step 3 "Building googledrive-mock and OpenCrawling decoupled microservice images (LOCAL_BUILD=${LOCAL_BUILD})..."
if [ "${SKIP_BUILD}" = true ]; then
  log_warn "SKIP_BUILD=true: reusing existing images."
else
  COMPOSE_BAKE=false compose build googledrive-mock oc-crawler oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer oc-mcp-server
fi
log_pass "Images ready."

# ------------------------------------------------------------------------------
# STEP 4: Start infrastructure and decoupled consumers
# ------------------------------------------------------------------------------
log_step 4 "Starting Google Drive mock, infrastructure (pgvector, Redis, Ollama, Kafka), and consumers..."
compose up -d googledrive-mock postgres-vector redis ollama ollama-model-puller kafka \
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

wait_healthy googledrive-mock-decoupled
wait_healthy "${PG}"
wait_healthy redis-stack-decoupled-googledrive
wait_healthy ollama-decoupled-googledrive

elapsed=0
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-googledrive 2>/dev/null || echo false)" == "false" ]; do
  [ $elapsed -ge "$TIMEOUT" ] && { log_error "Timeout waiting for ollama-model-puller-decoupled-googledrive."; exit 1; }
  sleep 2
  elapsed=$((elapsed + 2))
done
[ "$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-googledrive)" -eq 0 ] \
  || { log_error "Ollama model pull failed."; exit 1; }
log_success "ollama-model-puller-decoupled-googledrive completed cleanly."
log_pass "Google Drive mock and OpenCrawling decoupled infrastructure are healthy."

# ------------------------------------------------------------------------------
# STEP 5: Contract check & ground truth
# ------------------------------------------------------------------------------
log_step 5 "Validating Google Drive API v3 & OAuth2 contract and recording ground truth..."
UNAUTH_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${MOCK_URL}/drive/v3/about")
[ "${UNAUTH_CODE}" = "401" ] || { log_error "Expected 401 without Bearer token, got ${UNAUTH_CODE}"; exit 1; }
curl -sf -X POST -d "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=test" \
  "${MOCK_URL}/token" | grep -q "${MOCK_TOKEN}"
curl -sf -H "Authorization: Bearer ${MOCK_TOKEN}" "${MOCK_URL}/drive/v3/about" | grep -q "crawler@opencrawling"

STATS=$(mock /mock/stats)
EXPECTED=$(echo "$STATS" | jq -r '.expectedIndexedFiles')
INITIAL_TOKEN=$(echo "$STATS" | jq -r '.startPageToken')
[ "${EXPECTED}" -ge 6 ] || { log_error "Unexpected expectedIndexedFiles from mock: ${STATS}"; exit 1; }
log_info "Mock ground truth: expectedIndexedFiles=${EXPECTED}, startPageToken=${INITIAL_TOKEN}."
log_pass "Google Drive API v3 contract and ground truth verified."

# ------------------------------------------------------------------------------
# STEP 6: Initial crawl (changes mode) -> pgvector & shared Control Plane state
# ------------------------------------------------------------------------------
log_step 6 "Running initial Google Drive crawl (changes) and verifying pgvector content, metadata, ACLs, and JDBC state..."
run_crawler changes

echo "${SCAN_SUMMARY}" | grep -q "1 too_large" \
  || { log_error "Expected oversized file to be reported as '1 too_large': ${SCAN_SUMMARY}"; exit 1; }

URI_DOC_RESET=$(doc_id doc-reset)
URI_SLIDES=$(doc_id slides-oncall)
URI_PDF_MANUAL=$(doc_id pdf-manual)
URI_DOC_PUBLIC=$(doc_id doc-public)
URI_RESTRICTED=$(doc_id doc-restricted)
URI_ARCHIVE=$(doc_id doc-archived-note)
URI_SHEET=$(doc_id sheet-metrics)
URI_FORM=$(doc_id form-feedback)
URI_OVERSIZED=$(doc_id pdf-oversized)

wait_for "Google Doc HTML export (doc-reset) is indexed" "content LIKE '%GDRV-DOC-RESET-V1%'" "-gt 0"
wait_for "Google Slides plain-text export (slides-oncall) is indexed" "content LIKE '%GDRV-SLIDES-ONCALL-V1%'" "-gt 0"
wait_for "Binary PDF extracted by Tika (pdf-manual) is indexed" "content LIKE '%GDRV-PDF-MANUAL-V1%'" "-gt 0"
wait_for "Restricted text file (doc-restricted) is indexed" "content LIKE '%GDRV-RESTRICTED-V1%'" "-gt 0"
wait_for "Archive sub-folder file (doc-archived-note) is indexed" "content LIKE '%GDRV-ARCHIVE-V1%'" "-gt 0"
wait_sql "All ${EXPECTED} indexable Google Drive files are indexed" "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
  (SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE metadata->>'documentId' LIKE 'googledrive://drives/${DRIVE_ID}/files/%') END;" "-ge ${EXPECTED}"

INDEXED=$(sql "SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE metadata->>'documentId' LIKE 'googledrive://drives/${DRIVE_ID}/files/%';")
[ "$INDEXED" -eq "$EXPECTED" ] || { log_error "Expected exactly ${EXPECTED} indexed documents, found ${INDEXED}."; exit 1; }
log_success "pgvector holds exactly ${INDEXED} Google Drive documents."

# Excluded by default (Google Sheets & Forms) and oversized (TOO_LARGE) must NOT be indexed
EXCLUDED_HITS=$(sql "SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' IN ('${URI_SHEET}','${URI_FORM}','${URI_OVERSIZED}');")
[ "$EXCLUDED_HITS" -eq 0 ] || { log_error "Excluded/oversized files must not be indexed (found ${EXCLUDED_HITS})."; exit 1; }
log_success "Google Sheets, Google Forms, and oversized (>50 MB) files were excluded/reported without indexing."

# Rich metadata check: webViewLink, title, breadcrumbs, versionKey, custom properties
META_OK=$(sql "SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_DOC_RESET}'
  AND metadata->'title'->>0 = 'Password Reset Procedure'
  AND metadata->'url'->>0 = 'https://docs.google.com/document/d/doc-reset/edit'
  AND metadata->'googledrive.webViewLink'->>0 = 'https://docs.google.com/document/d/doc-reset/edit'
  AND metadata->'googledrive.breadcrumbs'->>0 LIKE '%Procedures%'
  AND metadata->'googledrive.versionKey'->>0 = 'v:14'
  AND metadata->'googledrive_prop_department'->>0 = 'Support'
  AND metadata->'googledrive_prop_classification'->>0 = 'Internal';")
[ "$META_OK" -gt 0 ] || { log_error "doc-reset rich metadata verification failed."; exit 1; }
log_success "Rich Google Drive metadata (title, webViewLink, breadcrumbs, versionKey, custom properties) verified."

# Zero-Trust ACL checks in pgvector
SEC_RESET=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security' FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_DOC_RESET}' LIMIT 1;")
echo "$SEC_RESET" | jq -e '.permissions | any(.identity == "alice@example.com" and .identityType == "user" and .access == "write")' >/dev/null \
  || { log_error "doc-reset missing Alice write ACL: ${SEC_RESET}"; exit 1; }
echo "$SEC_RESET" | jq -e '.permissions | any(.identity == "support@example.com" and .identityType == "group" and .access == "read")' >/dev/null \
  || { log_error "doc-reset missing support@example.com group ACL: ${SEC_RESET}"; exit 1; }

SEC_PDF=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security' FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_PDF_MANUAL}' LIMIT 1;")
echo "$SEC_PDF" | jq -e '.permissions | any(.identity == "domain:example.com" and .identityType == "group" and .access == "read")' >/dev/null \
  || { log_error "pdf-manual missing domain:example.com ACL: ${SEC_PDF}"; exit 1; }

SEC_PUBLIC=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security'->'permissions' FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_DOC_PUBLIC}' LIMIT 1;")
[ "$(echo "$SEC_PUBLIC" | jq 'length')" -eq 0 ] \
  || { log_error "doc-public (type=anyone) must fail closed by default (allowAnyonePublic=false): ${SEC_PUBLIC}"; exit 1; }
log_success "Zero-Trust ACLs verified: user, group, domain, and fail-closed anyone-link policy."

# Shared JDBC Control Plane state check: Changes API startPageToken persisted in oc_setting
CP_STATE=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT body FROM oc_setting WHERE name LIKE 'connector-state/googledrive-%.json' LIMIT 1;")
echo "$CP_STATE" | jq -e --arg tok "${INITIAL_TOKEN}" '(.payload | fromjson).startPageToken == $tok' >/dev/null \
  || { log_error "Expected startPageToken=${INITIAL_TOKEN} persisted in PostgreSQL oc_setting, got: ${CP_STATE}"; exit 1; }
log_success "Shared JDBC Control Plane store (oc_setting) persisted Changes API startPageToken=${INITIAL_TOKEN}."
log_pass "Initial Google Drive crawl verified in pgvector and Control Plane DB."

# ------------------------------------------------------------------------------
# STEP 7: MCP Server Zero-Trust ACL Enforcement
# ------------------------------------------------------------------------------
log_step 7 "Checking MCP Server Zero-Trust ACL enforcement against Google Drive permissions..."
ELAPSED=0
until curl -s -o /dev/null --max-time 3 "http://localhost:${MCP_PORT}/" 2>/dev/null; do
  [ $ELAPSED -ge 120 ] && { log_error "MCP server not reachable."; compose logs oc-mcp-server --tail 40; exit 1; }
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

MCP="http://localhost:${MCP_PORT}/mcp"
MCP_HEADERS=$(mktemp)
MCP_SESSION=""
mcp_call() {
  curl -s --max-time 30 -D "$MCP_HEADERS" -X POST "$MCP" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' ${MCP_SESSION:+-H "Mcp-Session-Id: ${MCP_SESSION}"} -d "$1" \
    | tr -d '\r' | sed -n 's/^data: *//p; /^{/p' | grep '"jsonrpc"' | tail -1
}
INIT_RES=$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-googledrive-decoupled","version":"1.0"}}}')
MCP_SESSION=$(grep -i '^mcp-session-id:' "$MCP_HEADERS" | awk '{print $2}' | tr -d '\r')
[ -n "$(echo "$INIT_RES" | jq -r '.result.serverInfo.name // empty')" ] || { log_error "MCP initialize failed: ${INIT_RES}"; exit 1; }
mcp_call '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null || true

CALL_ID=10
tool() {
  CALL_ID=$((CALL_ID + 1))
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":${CALL_ID},\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
    | jq -r '.result.content[]?.text // empty'
}
search_as() {
  tool secureVectorSearch "$(jq -nc --arg p "$1" --arg r "$2" \
    '{query:"password reset MFA escalation hardware firmware security board runbook status", userPrincipal:$p, userRoles:$r, maxResults:200, minScore:0.0, dimensions:1024}')" \
    | grep -oE "googledrive://drives/${DRIVE_ID}/files/[A-Za-z0-9_-]+" | sort -u || true
}

ALICE_HITS=$(search_as "alice@example.com" "")
SUPPORT_HITS=$(search_as "agent@example.com" "support@example.com")
DOMAIN_HITS=$(search_as "colleague@example.com" "domain:example.com")
BOB_HITS=$(search_as "bob@example.com" "")
OUTSIDER_HITS=$(search_as "outsider@external.org" "")
ALICE_RESTRICTED_CONTENT=$(tool getDocumentContent "$(jq -nc --arg u "${URI_RESTRICTED}" '{documentUri:$u, userPrincipal:"alice@example.com", userRoles:"", dimensions:1024}')")
OUTSIDER_LISTED=$(tool listAccessibleSources '{"userPrincipal":"outsider@external.org","userRoles":"","dimensions":1024}')
rm -f "$MCP_HEADERS"

# Alice sees doc-reset, pdf-manual, doc-archived-note; NEVER doc-restricted or doc-public
for allowed in "${URI_DOC_RESET}" "${URI_PDF_MANUAL}" "${URI_ARCHIVE}"; do
  echo "$ALICE_HITS" | grep -qx "$allowed" || { log_error "Alice must find ${allowed}."; exit 1; }
done
for forbidden in "${URI_RESTRICTED}" "${URI_DOC_PUBLIC}"; do
  if echo "$ALICE_HITS" | grep -qx "$forbidden"; then
    log_error "Alice must NOT find ${forbidden}."
    exit 1
  fi
done
if echo "$ALICE_RESTRICTED_CONTENT" | grep -q "GDRV-RESTRICTED-V1"; then
  log_error "getDocumentContent leaked Bob's restricted file to Alice!"
  exit 1
fi

# Support group member sees doc-reset and slides-oncall, NOT doc-restricted
echo "$SUPPORT_HITS" | grep -qx "${URI_DOC_RESET}" || { log_error "Support group member must find doc-reset."; exit 1; }
echo "$SUPPORT_HITS" | grep -qx "${URI_SLIDES}" || { log_error "Support group member must find slides-oncall."; exit 1; }
if echo "$SUPPORT_HITS" | grep -qx "${URI_RESTRICTED}"; then
  log_error "Support group member must NOT find doc-restricted."
  exit 1
fi

# Domain member sees pdf-manual, NOT doc-restricted
echo "$DOMAIN_HITS" | grep -qx "${URI_PDF_MANUAL}" || { log_error "Domain member must find pdf-manual."; exit 1; }
if echo "$DOMAIN_HITS" | grep -qx "${URI_RESTRICTED}"; then
  log_error "Domain member must NOT find doc-restricted."
  exit 1
fi

# Bob sees doc-restricted, NOT doc-reset
echo "$BOB_HITS" | grep -qx "${URI_RESTRICTED}" || { log_error "Bob must find doc-restricted."; exit 1; }
if echo "$BOB_HITS" | grep -qx "${URI_DOC_RESET}"; then
  log_error "Bob must NOT find doc-reset."
  exit 1
fi

# Outsider sees nothing across secureVectorSearch and listAccessibleSources
[ -z "$OUTSIDER_HITS" ] || { log_error "Outsider found documents: ${OUTSIDER_HITS}"; exit 1; }
if echo "$OUTSIDER_LISTED" | grep -q "googledrive://drives/${DRIVE_ID}/"; then
  log_error "listAccessibleSources exposed Google Drive documents to outsider: ${OUTSIDER_LISTED}"
  exit 1
fi

log_success "Alice, Support group, Domain member, Bob, and Outsider ACL boundaries enforced."
log_pass "MCP Zero-Trust ACL enforcement verified."

# ------------------------------------------------------------------------------
# STEP 8: Live Google Drive mutations + Incremental Changes API sync
# ------------------------------------------------------------------------------
log_step 8 "Mutating Google Drive mock and running incremental Changes API sync (crawlMode=changes)..."

# 8a. Edit Google Doc doc-reset (V1 -> V2)
mock "/mock/files/doc-reset/modify" -X POST -H "Content-Type: application/json" \
  -d '{"content":"<html><body><h1>Password Reset v2</h1><p>Require hardware FIDO2 verification before resetting MFA. Reference GDRV-DOC-RESET-V2.</p></body></html>"}' >/dev/null

# 8b. Create new Google Doc doc-incident
NEW_ID=$(mock "/mock/files" -X POST -H "Content-Type: application/json" \
  -d '{"id":"doc-incident","name":"Incident Postmortem","mimeType":"application/vnd.google-apps.document","parentId":"folder-procedures","content":"<html><body><h1>Incident Postmortem</h1><p>Root cause analysis for DNS failover. Reference GDRV-INCIDENT-V1.</p></body></html>"}' \
  | jq -r '.id')

# 8c. Delete slides-oncall
mock "/mock/files/slides-oncall/delete" -X POST -H "Content-Type: application/json" -d '{"hard":true}' >/dev/null

# 8d. Trash folder-archive (must cascade-tombstone doc-archived-note!)
mock "/mock/files/folder-archive/delete" -X POST -H "Content-Type: application/json" -d '{"hard":false}' >/dev/null

NEW_TOKEN=$(mock /mock/stats | jq -r '.startPageToken')
log_info "Mutations queued: edited doc-reset, added ${NEW_ID}, deleted slides-oncall, trashed folder-archive (new startPageToken=${NEW_TOKEN})."

run_crawler changes
echo "${SCAN_SUMMARY}" | grep -q "(incremental)" \
  || { log_error "Expected incremental Changes API crawl using persisted JDBC state: ${SCAN_SUMMARY}"; exit 1; }

wait_for "New Google Doc (doc-incident) is indexed" "content LIKE '%GDRV-INCIDENT-V1%'" "-gt 0"
wait_for "Edited Google Doc (doc-reset v2) is indexed" "content LIKE '%GDRV-DOC-RESET-V2%'" "-gt 0"
wait_for "Old doc-reset v1 content is replaced" "content LIKE '%GDRV-DOC-RESET-V1%'" "-eq 0"
wait_for "Deleted slides-oncall is purged" "metadata->>'documentId' = '${URI_SLIDES}'" "-eq 0"
wait_for "Cascading folder trash purged doc-archived-note" "metadata->>'documentId' = '${URI_ARCHIVE}'" "-eq 0"

CP_STATE_AFTER=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT body FROM oc_setting WHERE name LIKE 'connector-state/googledrive-%.json' LIMIT 1;")
echo "$CP_STATE_AFTER" | jq -e --arg tok "${NEW_TOKEN}" '(.payload | fromjson).startPageToken == $tok' >/dev/null \
  || { log_error "Expected persisted startPageToken=${NEW_TOKEN} in oc_setting, got: ${CP_STATE_AFTER}"; exit 1; }
log_success "Incremental Changes API crawl updated pgvector and advanced JDBC startPageToken=${NEW_TOKEN}."
log_pass "Incremental Changes API sync (edit, create, delete, cascading folder trash) verified."

# ------------------------------------------------------------------------------
# STEP 9: Permission-only change reconciled by full crawl (tree mode)
# ------------------------------------------------------------------------------
log_step 9 "Permission-only change (without Change event): reconciled by full tree crawl..."
mock "/mock/files/doc-restricted/permissions" -X POST -H "Content-Type: application/json" \
  -d '{"emitChange":false,"permissions":[{"id":"p-audit","type":"group","role":"reader","emailAddress":"audit-team@example.com","displayName":"Audit Team"}]}' >/dev/null

# Changes API crawl without a Change event leaves permissions unchanged
run_crawler changes
sleep 6
AUDIT_BEFORE=$(sql "SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '${URI_RESTRICTED}' AND (metadata->'security')::text LIKE '%audit-team@example.com%';")
[ "$AUDIT_BEFORE" -eq 0 ] \
  || { log_error "Incremental changes crawl unexpectedly observed a permission-only update without a Change event."; exit 1; }
log_success "As expected, incremental changes crawl did not re-fetch when no Change event occurred."

# Full reconciliation crawl (tree mode) reuses shared JDBC state and re-emits ONLY doc-restricted
run_crawler tree
echo "${SCAN_SUMMARY}" | grep -q "1 documents," \
  || { log_error "Full reconciliation crawl should re-emit only the 1 file whose permissions changed: ${SCAN_SUMMARY}"; exit 1; }
log_success "Full tree reconciliation reused shared JDBC state and re-emitted only doc-restricted."

wait_for "Full reconciliation applied audit-team@example.com ACL" \
  "metadata->>'documentId' = '${URI_RESTRICTED}' AND (metadata->'security')::text LIKE '%audit-team@example.com%'" "-gt 0"
wait_for "Bob's revoked permission is removed" \
  "metadata->>'documentId' = '${URI_RESTRICTED}' AND (metadata->'security')::text LIKE '%bob@example.com%'" "-eq 0"
log_pass "Permission-only update reconciled by full crawl."

log_info "Google Drive mock requests served: $(mock /mock/stats | jq -c '.requests' | cut -c1-300)"
echo -e "\n${BOLD}${GREEN}SUCCESS: All ${TOTAL_STEPS} Google Drive Decoupled Integration Test Steps Passed!${NC}\n"
exit 0
