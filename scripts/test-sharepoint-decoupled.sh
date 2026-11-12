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
# OpenCrawling - SharePoint Decoupled Integration Test Script
#
# Description:
#   End-to-end test of the decoupled pipeline with the SharePoint repository
#   connector. SharePoint Online can't run in a container, so the source is the
#   Graph mock (oc-sharepoint-repository-connector/graph-mock), an Entra ID +
#   Microsoft Graph emulator serving a site with real PDF / DOCX / TXT files.
#
# Pipeline Lifecycle Tested:
#   1. Prerequisites, clean state, image build (LOCAL_BUILD=true uses the
#      docker/local Dockerfiles with the BuildKit Maven cache)
#   2. Infrastructure (Graph mock, Kafka, Redis, pgvector, Ollama) and consumers
#   3. Ground truth read from the mock
#   4. Delta crawl of both libraries: every live file indexed once, reference
#      codes, paths, list columns, permission rules, no-reader files closed
#   5. MCP secure search honours the SharePoint permissions
#   6. Delta increment: an edit, a file delete, a folder delete, a new file
#   7. A permission-only change: delta does not see it, the tree crawl does
# ==============================================================================

set -euo pipefail

COMPOSE_FILE="oc-sharepoint-repository-connector/docker/docker-compose-decoupled-with-sharepoint.yml"
LOCAL_OVERLAY="oc-sharepoint-repository-connector/docker/docker-compose-decoupled-with-sharepoint.local.yml"
MOCK_URL="${MOCK_URL:-http://localhost:8091}"
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
PG="postgres-vector-decoupled-sharepoint"
HOST="contoso.sharepoint.com"
ENG_GROUP="0d1e6b2a-0000-4000-8000-00000000e001"

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

echo -e "\n${BOLD}${YELLOW}=== OpenCrawling Decoupled Pipeline with SharePoint (Graph mock) Integration Test ===${NC}\n"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

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
    echo "SQL-ERROR" # not a number: every numeric check on it fails
    return 0
  fi
  echo "$out" | tr -d '[:space:]'
}

mock() {
  curl -sf "${MOCK_URL}$1" "${@:2}"
}

# waits until a SQL query's number satisfies a test ("-gt 0", "-eq 0", ...)
wait_sql() {
  local description="$1" query="$2" test="$3" elapsed=0 count
  while true; do
    count=$(sql "$query")
    count=${count:-0}
    case "$count" in
      ''|*[!0-9]*) log_error "${description}: the query failed."; exit 1 ;;
    esac
    # shellcheck disable=SC2086
    if [ "$count" $test ]; then
      log_success "${description} (${count})."
      return 0
    fi
    if [ $elapsed -ge "$INGEST_TIMEOUT" ]; then
      log_error "${description}: not reached after ${INGEST_TIMEOUT}s (${count})."
      compose logs oc-ingestion-consumer --tail 30 || true
      compose logs oc-writer-consumer --tail 30 || true
      exit 1
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
}

# chunks in the 1024-dimension store matching a condition
wait_for() {
  wait_sql "$1" "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
    (SELECT count(*) FROM vector_store_1024 WHERE $2) END;" "$3"
}

doc_id() { echo "sharepoint://${HOST}/drives/$1/items/$2"; }

# the reference code (SPX-<id>-e<version>) of a file
reference_code() {
  mock "/mock/items/$1" | jq -r '.text' | grep -o 'SPX-[0-9A-Z]*-e[0-9]*' | head -1
}

# the first live file of a permission pattern in a library: "<drive> <id>"
pattern_file() {
  mock /mock/files | jq -r --argjson p "$1" --arg d "$2" '[.[] | select(.pattern == $p and .drive == $d)][0] | "\(.drive) \(.id)"'
}

run_crawler() {
  log_info "Running oc-crawler ($1)..."
  SHAREPOINT_CRAWL_MODE="$1" compose up -d --force-recreate oc-crawler >/dev/null
  local elapsed=0
  until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-sharepoint 2>/dev/null || echo false)" == "false" ]; do
    if [ $elapsed -ge "$TIMEOUT" ]; then
      log_error "Timeout waiting for oc-crawler-service-sharepoint."
      compose logs oc-crawler --tail 60
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  if [ "$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-sharepoint 2>/dev/null || echo 1)" -ne 0 ]; then
    log_error "oc-crawler-service-sharepoint failed."
    compose logs oc-crawler --tail 60
    exit 1
  fi
  SCAN_SUMMARY=$(docker logs oc-crawler-service-sharepoint 2>&1 | grep "SharePoint scan of" | tail -1 || true)
  log_success "Crawl finished: ${SCAN_SUMMARY#*: }"
  echo "${SCAN_SUMMARY}" | grep -q " 0 failed" || { log_error "The crawl reported failures."; exit 1; }
}

cleanup() {
  local exit_code=$?
  [ "$exit_code" -ne 0 ] && log_error "Test execution interrupted or failed with exit code $exit_code."
  if [ "${KEEP_CONTAINERS}" = false ]; then
    log_info "Tearing down the decoupled SharePoint Docker Compose environment..."
    compose down --remove-orphans >/dev/null 2>&1 || true
    log_success "Environment cleanup complete."
  else
    log_warn "KEEP_CONTAINERS=true set. Skipping docker compose teardown."
  fi
}
trap cleanup EXIT

# ------------------------------------------------------------------------------
log_step 1 "Verifying system prerequisites..."
for tool in docker curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { log_error "$tool is required."; exit 1; }
done
docker compose version >/dev/null 2>&1 || { log_error "docker compose is required."; exit 1; }
log_pass "Prerequisites verified."

# ------------------------------------------------------------------------------
log_step 2 "Removing previous SharePoint stack containers and its own volumes..."
compose down --remove-orphans >/dev/null 2>&1 || true
# only this stack's volumes: the shared Ollama model volume is kept
for v in opencrawling-pgdata-sharepoint opencrawling-redisdata-sharepoint opencrawling-kafkadata-sharepoint \
         opencrawling-claims-sharepoint opencrawling-reportsdata-sharepoint opencrawling-state-sharepoint; do
  docker volume rm "$v" >/dev/null 2>&1 || true
done
for p in 8080 8091 9092 5432 6379 11434; do
  if [ -n "$(docker ps --filter "publish=$p" -q 2>/dev/null)" ]; then
    log_error "Port ${p} is in use by $(docker ps --filter "publish=$p" --format '{{.Names}}'); stop that stack first."
    exit 1
  fi
done
log_pass "Previous state removed; ports are free."

# ------------------------------------------------------------------------------
log_step 3 "Building the Graph mock and the OpenCrawling decoupled images (LOCAL_BUILD=${LOCAL_BUILD})..."
if [ "${SKIP_BUILD}" = true ]; then
  log_warn "SKIP_BUILD=true: using existing images."
else
  COMPOSE_BAKE=false compose build
fi
log_pass "Images ready."

# ------------------------------------------------------------------------------
log_step 4 "Starting the Graph mock, infrastructure and consumers..."
compose up -d graph-mock postgres-vector redis ollama ollama-model-puller kafka \
  oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer oc-mcp-server
for c in graph-mock-decoupled-sharepoint "${PG}" redis-stack-decoupled-sharepoint ollama-decoupled-sharepoint; do
  elapsed=0
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo starting)" == "healthy" ]; do
    [ $elapsed -ge "$TIMEOUT" ] && { log_error "Timeout waiting for $c."; docker logs "$c" --tail 30 || true; exit 1; }
    sleep 2
    elapsed=$((elapsed + 2))
  done
  log_success "$c is healthy."
done
elapsed=0
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-sharepoint 2>/dev/null || echo false)" == "false" ]; do
  [ $elapsed -ge "$TIMEOUT" ] && { log_error "Timeout waiting for the Ollama model puller."; exit 1; }
  sleep 2
  elapsed=$((elapsed + 2))
done
[ "$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-sharepoint)" -eq 0 ] || { log_error "Model pull failed."; exit 1; }
log_pass "Infrastructure is healthy."

# ------------------------------------------------------------------------------
log_step 5 "Reading the ground truth from the Graph mock..."
STATS=$(mock /mock/stats)
LIVE_FILES=$(echo "$STATS" | jq -r '.liveFiles')
read -r GROUP_DRIVE GROUP_FILE <<< "$(pattern_file 1 'b!docs')"      # Entra group (write) + Adele (read)
read -r MEGAN_DRIVE MEGAN_FILE <<< "$(pattern_file 4 'b!docs')"      # Megan only, in the shape of Microsoft's documented permissions
MEGAN_OID=$(echo "$STATS" | jq -r '.users["megan.bowen@contoso.com"]')  # those identify her by Entra object id only
read -r NOBODY_DRIVE NOBODY_FILE <<< "$(pattern_file 5 'b!docs')"    # no permissions at all
read -r ALL_DRIVE ALL_FILE <<< "$(pattern_file 0 'b!contracts')"     # Everyone except external users
GROUP_CODE=$(reference_code "$GROUP_FILE")
log_info "Site: ${LIVE_FILES} live files in $(echo "$STATS" | jq -c '.filesPerDrive')."
log_info "Group file ${GROUP_FILE} (${GROUP_CODE}), Megan's ${MEGAN_FILE}, no-reader ${NOBODY_FILE}, everyone-internal ${ALL_FILE}."
log_pass "Ground truth recorded."

# ------------------------------------------------------------------------------
log_step 6 "Delta crawl of every document library, verified in pgvector..."
run_crawler delta
wait_sql "Every live file is indexed" "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
  (SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE metadata->>'documentId' LIKE 'sharepoint://%') END;" "-ge ${LIVE_FILES}"
INDEXED=$(sql "SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE metadata->>'documentId' LIKE 'sharepoint://%';")
[ "$INDEXED" -eq "$LIVE_FILES" ] || { log_error "Expected ${LIVE_FILES} indexed files, found ${INDEXED}."; exit 1; }
log_success "pgvector holds exactly the ${LIVE_FILES} live files (nothing from the OneDrive drive)."
wait_for "The group file's text is indexed" "content LIKE '%${GROUP_CODE}%'" "-gt 0"

ROW="metadata->>'documentId' = '$(doc_id "$GROUP_DRIVE" "$GROUP_FILE")'"
SECURITY=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c "SELECT metadata->'security' FROM vector_store_1024 WHERE ${ROW} LIMIT 1;")
echo "$SECURITY" | jq -e --arg g "$ENG_GROUP" '.permissions | any(.identity == $g and .access == "write")' >/dev/null \
  || { log_error "The group file must grant write to the Entra group: ${SECURITY}"; exit 1; }
echo "$SECURITY" | jq -e '.permissions | any(.identity == "adele.vance@contoso.com" and .access == "read")' >/dev/null \
  || { log_error "The group file must grant read to Adele: ${SECURITY}"; exit 1; }
[ "$(sql "SELECT count(*) FROM vector_store_1024 WHERE ${ROW} AND metadata->'sharepoint.path'->>0 LIKE '/%' AND metadata::jsonb ? 'sharepoint_field_ProjectCode';")" -gt 0 ] \
  || { log_error "The group file lacks its path or list columns."; exit 1; }
for f in "$NOBODY_DRIVE $NOBODY_FILE" "$ALL_DRIVE $ALL_FILE"; do
  set -- $f
  PERMS=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
    "SELECT metadata->'security'->'permissions' FROM vector_store_1024 WHERE metadata->>'documentId' = '$(doc_id "$1" "$2")' LIMIT 1;")
  [ "$(echo "$PERMS" | jq 'length')" -eq 0 ] || { log_error "File $2 must grant nothing by default: ${PERMS}"; exit 1; }
done
log_success "Permission rules: group writes, Adele reads; no-reader and everyone-internal files grant nothing by default."
log_pass "First crawl verified in pgvector."

# ------------------------------------------------------------------------------
log_step 7 "Checking MCP secure search against the SharePoint permissions..."
MCP="http://localhost:${MCP_PORT}/mcp"
elapsed=0
until curl -s -o /dev/null --max-time 3 "http://localhost:${MCP_PORT}/" 2>/dev/null; do
  [ $elapsed -ge 120 ] && { log_error "MCP server not reachable."; exit 1; }
  sleep 2
  elapsed=$((elapsed + 2))
done
MCP_HEADERS=$(mktemp)
MCP_SESSION=""
mcp_call() {
  curl -s --max-time 30 -D "$MCP_HEADERS" -X POST "$MCP" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' ${MCP_SESSION:+-H "Mcp-Session-Id: ${MCP_SESSION}"} -d "$1" \
    | tr -d '\r' | sed -n 's/^data: *//p; /^{/p' | grep '"jsonrpc"' | tail -1
}
mcp_call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-sharepoint-decoupled","version":"1.0"}}}' >/dev/null
MCP_SESSION=$(grep -i '^mcp-session-id:' "$MCP_HEADERS" | awk '{print $2}' | tr -d '\r')
mcp_call '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null || true
CALL_ID=10
tool() { # <tool> <arguments json>: the text content
  CALL_ID=$((CALL_ID + 1))
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":${CALL_ID},\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
    | jq -r '.result.content[]?.text // empty'
}
search_as() {
  tool secureVectorSearch "$(jq -nc --arg p "$1" --arg r "$2" '{query:"engineering guidance", userPrincipal:$p, userRoles:$r, maxResults:500, minScore:0.0, dimensions:1024}')" \
    | grep -o 'items/[0-9A-Z]*' | sort -u || true
}
ADELE=$(search_as "adele.vance@contoso.com" "${ENG_GROUP}")
MEGAN=$(search_as "${MEGAN_OID}" "")
OUTSIDER=$(search_as "someone@fabrikam.com" "")
NOBODY_CONTENT=$(tool getDocumentContent "$(jq -nc --arg u "$(doc_id "$NOBODY_DRIVE" "$NOBODY_FILE")" '{documentUri:$u, userPrincipal:"someone@fabrikam.com", userRoles:"", dimensions:1024}')")
NOBODY_LISTED=$(tool listAccessibleSources '{"userPrincipal":"someone@fabrikam.com","userRoles":"","dimensions":1024}')
rm -f "$MCP_HEADERS"
echo "$ADELE" | grep -qx "items/${GROUP_FILE}" || { log_error "Adele (Engineering group) must find ${GROUP_FILE}."; exit 1; }
echo "$MEGAN" | grep -qx "items/${MEGAN_FILE}" || { log_error "Megan must find ${MEGAN_FILE}."; exit 1; }
if echo "$MEGAN" | grep -qx "items/${GROUP_FILE}"; then log_error "Megan must not find ${GROUP_FILE}."; exit 1; fi
[ -z "$OUTSIDER" ] || { log_error "An outsider found: $(echo $OUTSIDER)"; exit 1; }
if echo "$NOBODY_CONTENT" | grep -q "SPX-${NOBODY_FILE}-"; then log_error "getDocumentContent returned the no-reader file."; exit 1; fi
if echo "$NOBODY_LISTED" | grep -q "items/${NOBODY_FILE}"; then log_error "listAccessibleSources listed the no-reader file."; exit 1; fi
log_success "Adele (by e-mail and group) and Megan (by object id) find exactly their files; an outsider finds nothing; the no-reader file stays closed."
log_pass "MCP secure search enforces the SharePoint permissions."

# ------------------------------------------------------------------------------
log_step 8 "Changing the site and running an incremental delta crawl..."
read -r EDIT_DRIVE EDIT_FILE <<< "$(mock /mock/files | jq -r '[.[] | select(.pattern == 4 and .drive == "b!docs")][1] | "\(.drive) \(.id)"')"
read -r DEL_DRIVE DEL_FILE <<< "$(mock /mock/files | jq -r '[.[] | select(.pattern == 2 and .drive == "b!docs")][0] | "\(.drive) \(.id)"')"
OLD_CODE=$(reference_code "$EDIT_FILE")
mock "/mock/items/${EDIT_FILE}/modify" -X POST -d '{"appendText":"Second edition."}' >/dev/null
NEW_CODE=$(reference_code "$EDIT_FILE")
mock "/mock/items/${DEL_FILE}/delete" -X POST -d '{}' >/dev/null
POLICIES=$(mock "/mock/items/01SPX000003" | jq -r 'select(.name == "Policies") | .id')
[ -n "$POLICIES" ] || { log_error "The Documents/Policies folder is not where the generated site puts it."; exit 1; }
IN_POLICIES=$(sql "SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE metadata->'sharepoint.path'->>0 LIKE '/Policies/%' AND metadata->'sharepoint.driveName'->>0 = 'Documents';")
mock "/mock/items/${POLICIES}/delete" -X POST -d '{}' >/dev/null
NEW_ID=$(mock /mock/files -X POST -d '{"drive":"b!docs","name":"Release notes.txt","text":"Release notes, reference SPX-RELEASE-NOTES."}' | jq -r '.id')
log_info "Edited ${EDIT_FILE} (${OLD_CODE} -> ${NEW_CODE}), deleted ${DEL_FILE} and the Policies folder (${IN_POLICIES} indexed files), added ${NEW_ID}."
run_crawler delta
wait_for "The new file is indexed" "content LIKE '%SPX-RELEASE-NOTES%'" "-gt 0"
wait_for "The edit is indexed" "content LIKE '%${NEW_CODE}%'" "-gt 0"
wait_for "The old version is replaced" "content LIKE '%${OLD_CODE}%'" "-eq 0"
wait_for "The deleted file is purged" "metadata->>'documentId' = '$(doc_id "$DEL_DRIVE" "$DEL_FILE")'" "-eq 0"
wait_for "The deleted folder's files are purged" "metadata->'sharepoint.path'->>0 LIKE '/Policies/%' AND metadata->'sharepoint.driveName'->>0 = 'Documents'" "-eq 0"
log_pass "The delta increment brought pgvector up to date."

# ------------------------------------------------------------------------------
log_step 9 "Permission-only change: invisible to delta, reconciled by a tree crawl..."
ROW="metadata->>'documentId' = '$(doc_id "$MEGAN_DRIVE" "$MEGAN_FILE")'"
mock "/mock/items/${MEGAN_FILE}/permissions" -X POST \
  -d '{"permissions":[{"id":"p","roles":["read"],"grantedToV2":{"group":{"id":"NEW-AUDIT-GROUP","displayName":"Auditors"}}}]}' >/dev/null
run_crawler delta
sleep 10
[ "$(sql "SELECT count(*) FROM vector_store_1024 WHERE ${ROW} AND (metadata->'security')::text LIKE '%new-audit-group%';")" -eq 0 ] \
  || { log_error "Delta unexpectedly saw a permission-only change."; exit 1; }
log_success "As expected, the delta crawl did not see the permission-only change."
run_crawler tree
echo "${SCAN_SUMMARY}" | grep -q "finished: 1 documents," \
  || { log_error "The tree crawl must re-emit only the file whose permissions changed: ${SCAN_SUMMARY#*: }"; exit 1; }
log_success "The tree crawl reused the delta crawls' state and re-emitted only that file."
wait_for "The tree crawl applied the new permissions" "${ROW} AND (metadata->'security')::text LIKE '%new-audit-group%'" "-gt 0"
wait_for "Megan's old grant is gone" "${ROW} AND (metadata->'security')::text LIKE '%${MEGAN_OID}%'" "-eq 0"
log_pass "The tree crawl reconciled the permissions."

log_info "Graph requests served: $(mock /mock/stats | jq -c '.requests' | cut -c1-300)"
echo -e "\n${BOLD}${GREEN}SUCCESS: All ${TOTAL_STEPS} SharePoint Decoupled Integration Test Steps Passed!${NC}\n"
exit 0
