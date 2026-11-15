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
# OpenCrawling - M-Files Decoupled Integration Test Script
#
# Description:
#   End-to-end test of the decoupled pipeline with the M-Files repository
#   connector. M-Files Server has no container image, so the source is the MFWS
#   mock (oc-mfiles-repository-connector/mfiles-mock), an M-Files Web Service
#   emulator serving a generated vault with real PDF / DOCX / TXT files.
#
# Pipeline Lifecycle Tested:
#   1. Prerequisites, clean state, image build
#   2. Infrastructure (MFWS mock, Kafka, Redis, pgvector, Ollama) and consumers
#   3. Ground truth read from the mock (live files, deleted objects)
#   4. oc-crawler crawls the vault (search mode, incremental state on a volume)
#   5. pgvector holds every live file, single- and multi-file documents carry
#      their own reference codes, typed metadata and permission rules, and
#      deleted objects are absent
#   6. MCP secure search honours the M-Files permissions (allow and deny)
#   7. The vault changes (a file removed, a document deleted, one added) and
#      an incremental crawl brings pgvector up to date
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
COMPOSE_FILE="oc-mfiles-repository-connector/docker/docker-compose-decoupled-with-mfiles.yml"
LOCAL_OVERLAY="${LOCAL_OVERLAY:-${COMPOSE_FILE%.yml}.local.yml}"
MOCK_URL="${MOCK_URL:-http://localhost:8090}"
MCP_PORT="${MCP_PORT:-8080}"
TIMEOUT="${TIMEOUT:-300}"
INGEST_TIMEOUT="${INGEST_TIMEOUT:-300}"
VAULT_KEY="C0B3B52E-6512-4BD8-8F2B-2A8E9A349201"
PG="postgres-vector-decoupled-mfiles"

# Objects of the generated vault (MFWS_SEED=42): 1003 is a single-file PDF policy, 1009 a three-file agreement,
# 1007 a deleted object, 1002 is readable by group 5 only and denies user 15, 1004 two named users
SFD=1003
MFD=1009
DELETED=1007

# Formatting
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
echo -e "${BOLD}${YELLOW}=== OpenCrawling Decoupled Pipeline with M-Files (MFWS mock) Integration Test ===${NC}"
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

# chunks in the 1024-dimension store whose content or metadata matches a LIKE pattern
chunks_where() {
  sql "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
         (SELECT count(*) FROM vector_store_1024 WHERE $1) END;"
}

mock() {
  curl -sf "${MOCK_URL}$1" "${@:2}"
}

# the reference codes (MFX-<id>-v<n>-f<file>) of an object's current files, space-separated
reference_codes() {
  mock "/mock/objects/0/$1" | jq -r '.files[].Text' | grep -o 'MFX-[0-9]*-v[0-9]*-f[0-9]*' | tr '\n' ' '
}

run_crawler() {
  log_info "Running oc-crawler against the MFWS mock..."
  compose up -d --force-recreate oc-crawler >/dev/null
  local elapsed=0
  until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-mfiles 2>/dev/null || echo 'false')" == "false" ]; do
    if [ $elapsed -ge "$TIMEOUT" ]; then
      log_error "Timeout waiting for oc-crawler-service-mfiles."
      compose logs oc-crawler --tail 60
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  local code
  code=$(docker inspect -f '{{.State.ExitCode}}' oc-crawler-service-mfiles 2>/dev/null || echo 1)
  if [ "$code" -ne 0 ]; then
    log_error "oc-crawler-service-mfiles exited with ${code}."
    compose logs oc-crawler --tail 60
    exit 1
  fi
  SCAN_SUMMARY=$(docker logs oc-crawler-service-mfiles 2>&1 | grep "M-Files scan of vault" | tail -1 || true)
  log_success "Crawl finished: ${SCAN_SUMMARY#*-- }"
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
    log_info "Tearing down the decoupled M-Files Docker Compose environment..."
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
log_step 2 "Removing previous M-Files stack containers and its own volumes..."
compose down --remove-orphans >/dev/null 2>&1 || true
# only this stack's volumes: the shared Ollama model volume is kept
for v in opencrawling-pgdata-mfiles opencrawling-redisdata-mfiles opencrawling-kafkadata-mfiles \
         opencrawling-claims-mfiles opencrawling-reportsdata-mfiles opencrawling-state-mfiles; do
  docker volume rm "$v" >/dev/null 2>&1 || true
done
for p in 8080 8090 9092 5432 6379 11434; do
  c_ids=$(docker ps --filter "publish=$p" -q 2>/dev/null || true)
  if [ -n "$c_ids" ]; then
    log_warn "Port ${p} is used by container(s) ${c_ids}; stopping them."
    # shellcheck disable=SC2086
    docker rm -f $c_ids >/dev/null 2>&1 || true
  fi
done
log_pass "Previous state removed."

# ------------------------------------------------------------------------------
# STEP 3: Build images
# ------------------------------------------------------------------------------
log_step 3 "Building the MFWS mock and the OpenCrawling decoupled images..."
if [ "${SKIP_BUILD}" = true ]; then
  log_warn "SKIP_BUILD=true: using existing images."
else
  compose build
fi
log_pass "Images ready."

# ------------------------------------------------------------------------------
# STEP 4: Infrastructure
# ------------------------------------------------------------------------------
log_step 4 "Starting the MFWS mock, infrastructure and consumers..."
compose up -d mfiles-mock postgres-vector redis ollama ollama-model-puller kafka \
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
wait_healthy mfiles-mock-decoupled-mfiles
wait_healthy "${PG}"
wait_healthy redis-stack-decoupled-mfiles
wait_healthy ollama-decoupled-mfiles

log_info "Waiting for the Ollama model puller..."
ELAPSED=0
until [ "$(docker inspect -f '{{.State.Running}}' ollama-model-puller-decoupled-mfiles 2>/dev/null || echo false)" == "false" ]; do
  if [ $ELAPSED -ge "$TIMEOUT" ]; then
    log_error "Timeout waiting for the Ollama model puller."
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ "$(docker inspect -f '{{.State.ExitCode}}' ollama-model-puller-decoupled-mfiles)" -ne 0 ]; then
  log_error "The Ollama model puller failed."
  compose logs ollama-model-puller
  exit 1
fi
log_pass "Infrastructure is healthy."

# ------------------------------------------------------------------------------
# STEP 5: Ground truth from the mock
# ------------------------------------------------------------------------------
log_step 5 "Reading the ground truth from the MFWS mock..."
STATS=$(mock /mock/stats)
LIVE_FILES=$(echo "$STATS" | jq -r '.liveFiles')
DELETED_OBJECTS=$(echo "$STATS" | jq -r '.deletedObjects')
log_info "Vault: $(echo "$STATS" | jq -r '.objects') objects, ${LIVE_FILES} live files, $(echo "$STATS" | jq -r '.multiFileDocuments') multi-file documents, ${DELETED_OBJECTS} deleted objects."
read -r -a SFD_CODES <<< "$(reference_codes "$SFD")"
read -r -a MFD_CODES <<< "$(reference_codes "$MFD")"
DELETED_CODE=$(reference_codes "$DELETED" | awk '{print $1}')
[ "${#SFD_CODES[@]}" -eq 1 ] || { log_error "Object ${SFD} should have one file."; exit 1; }
[ "${#MFD_CODES[@]}" -eq 3 ] || { log_error "Object ${MFD} should have three files."; exit 1; }
[ "$(mock /mock/objects/0/$DELETED | jq -r '.objectVersion.Deleted')" == "true" ] || { log_error "Object ${DELETED} should be deleted."; exit 1; }
log_info "Single-file ${SFD}: ${SFD_CODES[*]}; multi-file ${MFD}: ${MFD_CODES[*]}; deleted ${DELETED}: ${DELETED_CODE}"
log_pass "Ground truth recorded."

# ------------------------------------------------------------------------------
# STEP 6: First crawl
# ------------------------------------------------------------------------------
log_step 6 "Crawling the vault and verifying pgvector..."
run_crawler
echo "${SCAN_SUMMARY}" | grep -q " 0 failed" || { log_error "The crawl reported failures."; exit 1; }
if [ "$(mock /mock/stats | jq -r '.activeSessions')" -ne 0 ]; then
  log_error "The crawler left an MFWS session open."
  exit 1
fi
log_success "The crawler ended its MFWS session."

wait_for "Single-file document ${SFD} is indexed" "content LIKE '%${SFD_CODES[0]}%'" "-gt 0"
for code in "${MFD_CODES[@]}"; do
  wait_for "Multi-file document ${MFD}, file ${code##*-f}, is indexed on its own" "content LIKE '%${code}%'" "-gt 0"
done
wait_sql "Every live file is indexed" "SELECT CASE WHEN to_regclass('public.vector_store_1024') IS NULL THEN 0 ELSE
  (SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE metadata->'mfiles.vaultGuid'->>0 = '${VAULT_KEY}'
   AND content LIKE '%MFX-%') END;" "-ge ${LIVE_FILES}"

DISTINCT_DOCS=$(sql "SELECT count(DISTINCT metadata->>'documentId') FROM vector_store_1024 WHERE metadata->'mfiles.vaultGuid'->>0 = '${VAULT_KEY}' AND content LIKE '%MFX-%';")
[ "$DISTINCT_DOCS" -eq "$LIVE_FILES" ] || { log_error "Expected ${LIVE_FILES} indexed files, found ${DISTINCT_DOCS}."; exit 1; }
log_success "pgvector holds exactly the ${LIVE_FILES} live files."

[ "$(chunks_where "content LIKE '%${DELETED_CODE}%'")" -eq 0 ] || { log_error "Deleted object ${DELETED} was indexed."; exit 1; }
log_success "Deleted object ${DELETED} is not indexed."

PARENT=$(sql "SELECT DISTINCT metadata->'mfiles.parentId'->>0 FROM vector_store_1024 WHERE content LIKE '%${MFD_CODES[1]}%';")
[ "$PARENT" == "mfiles://${VAULT_KEY}/objects/0/${MFD}" ] || { log_error "Unexpected mfiles.parentId '${PARENT}'."; exit 1; }
CLASS=$(sql "SELECT DISTINCT metadata->'mfiles.className'->>0 FROM vector_store_1024 WHERE content LIKE '%${SFD_CODES[0]}%';")
[ "$CLASS" == "Policy" ] || { log_error "Unexpected mfiles.className '${CLASS}'."; exit 1; }
PROPS=$(sql "SELECT count(*) FROM vector_store_1024 WHERE content LIKE '%${MFD_CODES[0]}%' AND metadata::jsonb ? 'mfiles_prop_contractValue' AND metadata::jsonb ? 'mfiles_prop_customer';")
[ "$PROPS" -gt 0 ] || { log_error "Typed M-Files properties are missing from the metadata."; exit 1; }
log_success "Metadata carries the M-Files lineage (parentId), class and typed properties."

# 1002: object ACL (groups 5 and 7, deny user 15) restricted by the named ACL "Legal only" (group 5)
SECURITY=$(docker exec -i "${PG}" psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->'security' FROM vector_store_1024 WHERE metadata->>'documentId' = 'mfiles://${VAULT_KEY}/objects/0/1002' LIMIT 1;")
echo "$SECURITY" | jq -e '.permissions | any(.identity == "mfiles:group:5" and .access == "read")' >/dev/null \
  || { log_error "1002 should be readable by mfiles:group:5: ${SECURITY}"; exit 1; }
echo "$SECURITY" | jq -e '.permissions | any(.identity == "mfiles:user:15" and .access == "deny")' >/dev/null \
  || { log_error "1002 should deny mfiles:user:15: ${SECURITY}"; exit 1; }
echo "$SECURITY" | jq -e '.permissions | any(.identity == "mfiles:group:7") | not' >/dev/null \
  || { log_error "1002 must not grant mfiles:group:7 (excluded by the named ACL): ${SECURITY}"; exit 1; }
log_success "Permission rules of 1002: group 5 reads, user 15 denied, group 7 excluded by the named ACL."
log_pass "First crawl verified in pgvector."

# ------------------------------------------------------------------------------
# STEP 7: MCP secure search
# ------------------------------------------------------------------------------
log_step 7 "Checking MCP secure search against the M-Files permissions..."
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
INIT=$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-mfiles-decoupled","version":"1.0"}}}')
MCP_SESSION=$(grep -i '^mcp-session-id:' "$MCP_HEADERS" | awk '{print $2}' | tr -d '\r')
[ -n "$(echo "$INIT" | jq -r '.result.serverInfo.name // empty')" ] || { log_error "MCP initialize failed: ${INIT}"; exit 1; }
mcp_call '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null || true
TOOLS=$(mcp_call '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | jq -r '.result.tools[].name' | tr '\n' ' ')
log_info "MCP session ${MCP_SESSION:-none}; tools: ${TOOLS}"
echo " ${TOOLS} " | grep -q " secureVectorSearch " || { log_error "The MCP server does not expose secureVectorSearch."; exit 1; }

# the M-Files objects secureVectorSearch returns for a principal and roles
SEARCH_ID=10
search_as() {
  SEARCH_ID=$((SEARCH_ID + 1))
  local args
  args=$(jq -nc --arg p "$1" --arg r "$2" \
    '{query:"agreement policy invoice", userPrincipal:$p, userRoles:$r, maxResults:200, minScore:0.0, dimensions:1024}')
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":${SEARCH_ID},\"method\":\"tools/call\",\"params\":{\"name\":\"secureVectorSearch\",\"arguments\":${args}}}" \
    | jq -r '.result.content[]?.text // empty' | grep -o 'objects/0/[0-9]*' | sort -u || true
}
LEGAL=$(search_as "mfiles:user:12" "mfiles:group:5")
DENIED=$(search_as "mfiles:user:15" "mfiles:group:5")
OUTSIDER=$(search_as "mfiles:user:99" "")

# a document no permission grants to anybody (pattern "no entries": 1005) must stay closed to an outsider in every tool
NOBODY="mfiles://${VAULT_KEY}/objects/0/1005"
tool_text() { # <id> <tool> <arguments json>: the text content of a tool call
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}" \
    | jq -r '.result.content[]?.text // empty'
}
NOBODY_CONTENT=$(tool_text 30 getDocumentContent "$(jq -nc --arg u "$NOBODY" '{documentUri:$u, userPrincipal:"mfiles:user:99", userRoles:"", dimensions:1024}')")
NOBODY_LISTED=$(tool_text 31 listAccessibleSources '{"userPrincipal":"mfiles:user:99","userRoles":"","dimensions":1024}')
rm -f "$MCP_HEADERS"
log_info "Legal member (user 12, group 5) finds: $(echo $LEGAL)"
log_info "User 15 (group 5, denied on 1002) finds: $(echo $DENIED)"
echo "$LEGAL" | grep -qx "objects/0/1002" || { log_error "A legal-department member must find 1002."; exit 1; }
if echo "$DENIED" | grep -qx "objects/0/1002"; then
  log_error "User 15 is denied on 1002 but found it."
  exit 1
fi
[ -z "$OUTSIDER" ] || { log_error "A principal without permissions found: $(echo $OUTSIDER)"; exit 1; }
if echo "$NOBODY_CONTENT" | grep -q "MFX-1005-"; then
  log_error "getDocumentContent returned 1005, which no permission grants, to an outsider."
  exit 1
fi
if echo "$NOBODY_LISTED" | grep -q "objects/0/1005"; then
  log_error "listAccessibleSources listed 1005, which no permission grants, to an outsider."
  exit 1
fi
log_success "A document without readers stays closed to an outsider in getDocumentContent and listAccessibleSources."
log_success "Legal member finds 1002; the denied user 15 does not; an outsider finds nothing."
log_pass "MCP secure search enforces the M-Files permissions."

# ------------------------------------------------------------------------------
# STEP 8: Vault changes and an incremental crawl
# ------------------------------------------------------------------------------
log_step 8 "Changing the vault and running an incremental crawl..."
REMOVED_FILE=$(mock "/mock/objects/0/${MFD}" | jq -r '.files[0].ID')
mock "/mock/objects/0/${MFD}/modify" -X POST -d "{\"removeFileIds\":[${REMOVED_FILE}],\"appendText\":\"Amendment two.\"}" >/dev/null
mock "/mock/objects/0/${SFD}/delete" -X POST -d '{}' >/dev/null
NEW_ID=$(mock /mock/objects -X POST -d '{"classId":14,"title":"Incremental NDA","text":"Incremental NDA for the decoupled test, reference INCR-NDA-0001."}' | jq -r '.ObjVer.ID')
read -r -a MFD_CODES_V2 <<< "$(reference_codes "$MFD")"
log_info "Removed file ${REMOVED_FILE} from ${MFD} (now ${MFD_CODES_V2[*]}), deleted ${SFD}, added ${NEW_ID}."
sleep 2
run_crawler
echo "${SCAN_SUMMARY}" | grep -q "finished: 3 documents" || log_warn "Expected 3 documents in the incremental run: ${SCAN_SUMMARY}"

wait_for "The new document ${NEW_ID} is indexed" "content LIKE '%INCR-NDA-0001%'" "-gt 0"
for code in "${MFD_CODES_V2[@]}"; do
  wait_for "The new version of ${MFD} (${code}) is indexed" "content LIKE '%${code}%'" "-gt 0"
done
wait_for "The removed file ${REMOVED_FILE} is purged" "metadata->>'documentId' = 'mfiles://${VAULT_KEY}/objects/0/${MFD}/files/${REMOVED_FILE}'" "-eq 0"
wait_for "The deleted document ${SFD} is purged" "content LIKE '%MFX-${SFD}-%'" "-eq 0"
for code in "${MFD_CODES[@]:1}"; do
  wait_for "The old version (${code}) is replaced" "content LIKE '%${code}%'" "-eq 0"
done
log_pass "The incremental crawl brought pgvector up to date."

# ------------------------------------------------------------------------------
# STEP 9: Summary
# ------------------------------------------------------------------------------
log_step 9 "Summary"
log_info "MFWS requests served: $(mock /mock/stats | jq -c '.requests | to_entries | map({(.key): .value}) | add' | cut -c1-400)"
log_pass "M-Files decoupled integration test complete."

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: All ${TOTAL_STEPS} M-Files Decoupled Integration Test Steps Passed!${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}\n"
exit 0
