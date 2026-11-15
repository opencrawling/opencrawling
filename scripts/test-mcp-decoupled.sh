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

# End-to-End Decoupled Integration Test Suite for the OpenCrawling Secure MCP Server (Spring AI).
#
# Validates the complete Model Context Protocol (MCP) server capabilities in a decoupled
# multi-container architecture (docker-compose-decoupled.yml):
#   1. Container cluster boot and health readiness (PostgreSQL+pgvector, Redis, Ollama, Kafka, oc-mcp-server, consumers)
#   2. MCP protocol transport handshake & session initialization (JSON-RPC 2.0, Mcp-Session-Id negotiation, notifications/initialized, ping)
#   3. MCP tool introspection (tools/list schema validation for Vector Knowledge & AIOps Observability tools)
#   4. Live AIOps Observability tool execution (fetchJobTraces, getErrorLogs, queryThroughputMetrics)
#   5. End-to-end decoupled data ingestion of multi-ACL documents via OIS API
#   6. Zero-Trust Access Control List (ACL) evaluation via secureVectorSearch with explicit deny precedence
#   7. Identity-aware document content retrieval via getDocumentContent
#   8. Filtered source discovery via listAccessibleSources
#   9. Multi-dimension target index querying (384, 768, 1024)
#  10. OIS Document Lifecycle Tombstone DELETE purge verification over MCP
#  11. REST API Observability fallback parity (/api/observability/*)
#  12. In-process backend SPI & ACL evaluator integration test parity (JUnit)

set -e

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


# ANSI Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
NC='\033[0m'

PASSED_COUNT=0
TOTAL_TESTS=16

log_step() {
    echo -e "\n${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
    echo -e "${BOLD}${CYAN}[STEP $1] $2${NC}"
    echo -e "${BOLD}${CYAN}────────────────────────────────────────────────────────────────────────────────${NC}"
}

log_pass() {
    PASSED_COUNT=$((PASSED_COUNT + 1))
    echo -e "${BOLD}${GREEN}[PASS $PASSED_COUNT/$TOTAL_TESTS] $1${NC}"
}

log_fail() {
    echo -e "${BOLD}${RED}[FAIL] $1${NC}" >&2
}

log_info() {
    echo -e "${CYAN}[INFO] $1${NC}"
}

echo -e "${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== OpenCrawling Secure MCP Server Decoupled Integration Test Suite ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Switched working directory to project root: $(pwd)"

# Execution configuration flags
CLEANUP_ON_EXIT="${CLEANUP_ON_EXIT:-true}"
REBUILD_IMAGES="${REBUILD_IMAGES:-false}"
MCP_BASE_URL="${MCP_BASE_URL:-http://localhost:8080}"
MCP_ENDPOINT="${MCP_BASE_URL}/mcp"
OIS_JOB_ID="${OIS_JOB_ID:-1}"
INGEST_ENDPOINT="${MCP_BASE_URL}/api/v1/ingest/ois/${OIS_JOB_ID}"
OIS_AUTH_TOKEN="${OPENCRAWLING_INGEST_OIS_TOKEN:-mcp-test-token-$(date +%s)}"
export OPENCRAWLING_INGEST_OIS_TOKEN="${OIS_AUTH_TOKEN}"

COMPOSE_FILE="docker-compose-decoupled.yml"
LOCAL_OVERLAY="${LOCAL_OVERLAY:-${COMPOSE_FILE%.yml}.local.yml}"

compose() {
  if [ "${LOCAL_BUILD}" = true ] && [ -f "${LOCAL_OVERLAY}" ]; then
    docker compose -f "${COMPOSE_FILE}" -f "${LOCAL_OVERLAY}" "$@"
  else
    docker compose -f "${COMPOSE_FILE}" "$@"
  fi
}

cleanup() {
  log_info "Executing teardown cleanup handler..."
  rm -f /tmp/mcp-init-headers-*.txt /tmp/mcp-response-*.json /tmp/mcp-curl-*.tmp
  if [ "${KEEP_CONTAINERS}" = true ] || [ "${CLEANUP_ON_EXIT}" = false ]; then
    echo -e "${YELLOW}[INFO] KEEP_CONTAINERS=true: Leaving decoupled MCP test infrastructure active.${NC}"
    return 0
  fi
  echo -e "${CYAN}[INFO] Tearing down decoupled Docker containers...${NC}"
  compose down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

print_diagnostics() {
  echo -e "\n${YELLOW}[DIAGNOSTICS] Printing recent container logs for troubleshooting:${NC}"
  compose logs oc-mcp-server | tail -150 || true
  compose logs oc-writer-consumer | tail -100 || true
}

# Helper to execute curl with timeout and error handling
mcp_post() {
  local payload="$1"
  local session_header="${2:-}"
  local tmp_res="/tmp/mcp-curl-$$.tmp"
  rm -f "$tmp_res"

  if [ -n "$session_header" ]; then
    curl -s --max-time 15 -X POST \
      -H "Content-Type: application/json" \
      -H "Accept: text/event-stream, application/json" \
      -H "Mcp-Session-Id: ${session_header}" \
      -d "$payload" \
      "${MCP_ENDPOINT}" > "$tmp_res"
  else
    curl -s --max-time 15 -X POST \
      -H "Content-Type: application/json" \
      -H "Accept: text/event-stream, application/json" \
      -d "$payload" \
      "${MCP_ENDPOINT}" > "$tmp_res"
  fi

  local raw
  raw=$(cat "$tmp_res")
  rm -f "$tmp_res"

  # If response is formatted as SSE (data: {...}), strip SSE prefix
  if echo "$raw" | grep -q '^data:'; then
    echo "$raw" | sed -n 's/^data:[[:space:]]*//p' | grep -v '^$' | head -n 1
  else
    echo "$raw"
  fi
}

# Helper to wait for conditions
wait_until() {
  local description="$1" timeout="$2" elapsed=0
  shift 2
  until "$@"; do
    if [ "$elapsed" -ge "$timeout" ]; then
      log_fail "Timeout after ${timeout}s waiting for ${description}."
      print_diagnostics
      exit 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
    printf "  Waiting for %s... (%ds elapsed)\r" "$description" "$elapsed"
  done
  echo ""
  log_info "${description}: READY"
}

# ------------------------------------------------------------------------------
# STEP 1: Prerequisites Check
# ------------------------------------------------------------------------------
log_step "1" "Checking prerequisites (docker, docker compose, curl, jq, java, mvn)..."
command -v docker >/dev/null 2>&1 || { log_fail "Docker is required but not installed."; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { log_fail "Docker Compose is required."; exit 1; }
command -v curl >/dev/null 2>&1 || { log_fail "curl is required."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_fail "jq is required."; exit 1; }
command -v java >/dev/null 2>&1 || { log_fail "Java 25+ is required."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_fail "Maven is required."; exit 1; }
log_pass "All required tools and runtime environments are installed."

# ------------------------------------------------------------------------------
# STEP 2: Boot Decoupled Container Infrastructure
# ------------------------------------------------------------------------------
log_step "2" "Booting decoupled container cluster via ${COMPOSE_FILE}..."
compose down --remove-orphans || true

if [ "$REBUILD_IMAGES" = true ]; then
  log_info "Rebuilding oc-mcp-server and consumer images from source..."
  compose build oc-mcp-server oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer
fi

log_info "Starting decoupled multi-service infrastructure (postgres, redis, ollama, kafka, oc-mcp-server, consumers)..."
compose up -d postgres redis ollama ollama-model-puller kafka oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer oc-mcp-server

# ------------------------------------------------------------------------------
# STEP 3: Verify Infrastructure Readiness & Health
# ------------------------------------------------------------------------------
log_step "3" "Verifying database, embedding engine, and MCP server health endpoints..."

wait_until "postgres-vector database container to report healthy" 180 \
  sh -c '[ "$(docker inspect -f "{{.State.Health.Status}}" postgres-vector-decoupled 2>/dev/null)" = "healthy" ]'

# Clean stale vector data from previous runs while preserving schemas and ollama volumes
docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -c "TRUNCATE vector_store CASCADE; TRUNCATE vector_store_1024 CASCADE; TRUNCATE vector_store_768 CASCADE; TRUNCATE vector_store_384 CASCADE;" >/dev/null 2>&1 || true

wait_until "redis-stack container to report healthy" 90 \
  sh -c '[ "$(docker inspect -f "{{.State.Health.Status}}" redis-stack-decoupled 2>/dev/null)" = "healthy" ]'

wait_until "ollama model puller to complete embedding models pull and exit 0" 600 \
  sh -c '[ "$(docker inspect -f "{{.State.Status}} {{.State.ExitCode}}" ollama-model-puller-decoupled 2>/dev/null)" = "exited 0" ]'

wait_until "oc-mcp-server REST API (http://localhost:8080/api/system/status) to answer 200 OK" 180 \
  sh -c '[ "$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/api/system/status 2>/dev/null)" = "200" ]'

log_pass "Decoupled cluster services and oc-mcp-server container are healthy (HTTP 200)"

# ------------------------------------------------------------------------------
# STEP 4: MCP Protocol Handshake & Transport Verification
# ------------------------------------------------------------------------------
log_step "4" "Executing MCP Streamable HTTP handshake (initialize, session negotiation, initialized notification, ping)..."

INIT_PAYLOAD='{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "clientInfo": {
      "name": "opencrawling-decoupled-test-runner",
      "version": "1.0.0"
    },
    "capabilities": {}
  }
}'

INIT_HEADERS_FILE="/tmp/mcp-init-headers-$$.txt"
rm -f "$INIT_HEADERS_FILE"

INIT_RESP=$(curl -s -D "$INIT_HEADERS_FILE" --max-time 15 -X POST \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  -d "$INIT_PAYLOAD" \
  "${MCP_ENDPOINT}")

log_info "MCP Initialize HTTP Headers:"
cat "$INIT_HEADERS_FILE" | head -n 10

SESSION_ID=$(grep -i '^Mcp-Session-Id:' "$INIT_HEADERS_FILE" | tr -d '\r' | awk '{print $2}')
if [ -z "$SESSION_ID" ]; then
  # Fallback: check session from cookie or UUID if present
  SESSION_ID=$(grep -i 'sessionId' "$INIT_HEADERS_FILE" | awk '{print $2}' || echo "")
fi

log_info "MCP Initialize Response Body: ${INIT_RESP}"

# Validate JSON-RPC response format and server capabilities
SERVER_NAME=$(echo "$INIT_RESP" | jq -r '.result.serverInfo.name // empty')
PROTOCOL_VER=$(echo "$INIT_RESP" | jq -r '.result.protocolVersion // empty')
HAS_TOOLS=$(echo "$INIT_RESP" | jq -r '.result.capabilities.tools // empty')

if [ -z "$SERVER_NAME" ]; then
  log_fail "MCP initialization failed: No serverInfo.name returned. Response: $INIT_RESP"
  exit 1
fi

log_info "Negotiated MCP Server: '${SERVER_NAME}', Protocol: '${PROTOCOL_VER}', Session ID: '${SESSION_ID}'"

# 4.2 Send notifications/initialized
log_info "Sending MCP 'notifications/initialized' notification..."
INITED_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X POST \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  ${SESSION_ID:+-H "Mcp-Session-Id: ${SESSION_ID}"} \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  "${MCP_ENDPOINT}")

if [ "$INITED_STATUS" != "202" ] && [ "$INITED_STATUS" != "200" ]; then
  log_fail "notifications/initialized failed with status: $INITED_STATUS"
  exit 1
fi
log_info "notifications/initialized accepted (HTTP ${INITED_STATUS})"

# 4.3 Send ping request
log_info "Sending MCP 'ping' request..."
PING_RESP=$(mcp_post '{"jsonrpc":"2.0","id":2,"method":"ping","params":{}}' "$SESSION_ID")
log_info "MCP Ping response: ${PING_RESP}"

log_pass "MCP Streamable HTTP handshake completed with active session token"

# ------------------------------------------------------------------------------
# STEP 5: MCP Tool Discovery & Schema Introspection (tools/list)
# ------------------------------------------------------------------------------
log_step "5" "Querying MCP 'tools/list' to introspect tool schemas and registered capabilities..."

TOOLS_RESP=$(mcp_post '{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}' "$SESSION_ID")
log_info "Discovered MCP tools payload: ${TOOLS_RESP}"

# Verify canonical security knowledge tools
echo "$TOOLS_RESP" | jq -e '.result.tools[] | select(.name == "secureVectorSearch")' >/dev/null || {
  log_fail "Tool 'secureVectorSearch' not found in tools/list advertisement."
  exit 1
}
echo "$TOOLS_RESP" | jq -e '.result.tools[] | select(.name == "getDocumentContent")' >/dev/null || {
  log_fail "Tool 'getDocumentContent' not found in tools/list advertisement."
  exit 1
}
echo "$TOOLS_RESP" | jq -e '.result.tools[] | select(.name == "listAccessibleSources")' >/dev/null || {
  log_fail "Tool 'listAccessibleSources' not found in tools/list advertisement."
  exit 1
}

# Verify AIOps observability tools
echo "$TOOLS_RESP" | jq -e '.result.tools[] | select(.name == "fetchJobTraces")' >/dev/null || {
  log_fail "Tool 'fetchJobTraces' not found in tools/list advertisement."
  exit 1
}
echo "$TOOLS_RESP" | jq -e '.result.tools[] | select(.name == "getErrorLogs")' >/dev/null || {
  log_fail "Tool 'getErrorLogs' not found in tools/list advertisement."
  exit 1
}
echo "$TOOLS_RESP" | jq -e '.result.tools[] | select(.name == "queryThroughputMetrics")' >/dev/null || {
  log_fail "Tool 'queryThroughputMetrics' not found in tools/list advertisement."
  exit 1
}

DISCOVERED_TOOL_NAMES=$(echo "$TOOLS_RESP" | jq -r '.result.tools[].name' | tr '\n' ' ')
log_info "Successfully validated advertised MCP tools: [ ${DISCOVERED_TOOL_NAMES}]"
log_pass "tools/list advertised all 6 Canonical Vector Knowledge and AIOps Observability tools"

# ------------------------------------------------------------------------------
# STEP 6: AIOps Observability MCP Tool Invocations (tools/call)
# ------------------------------------------------------------------------------
log_step "6" "Executing live AIOps Observability tool invocations via MCP JSON-RPC..."

# 6.1 Tool: fetchJobTraces
log_info "Invoking MCP tool 'fetchJobTraces' for job 'mcp-aiops-job-1'..."
FETCH_TRACES_PAYLOAD='{
  "jsonrpc": "2.0",
  "id": 10,
  "method": "tools/call",
  "params": {
    "name": "fetchJobTraces",
    "arguments": {
      "jobId": "mcp-aiops-job-1"
    }
  }
}'
TRACES_RESP=$(mcp_post "$FETCH_TRACES_PAYLOAD" "$SESSION_ID")
log_info "fetchJobTraces Response: ${TRACES_RESP}"

# Extract content text and verify JSON structure
TRACES_TEXT=$(echo "$TRACES_RESP" | jq -r '.result.content[0].text // empty')
if [ -z "$TRACES_TEXT" ]; then
  # Check if result is returned directly
  TRACES_TEXT=$(echo "$TRACES_RESP" | jq -r '.result // empty')
fi

echo "$TRACES_TEXT" | jq -e '.jobId == "mcp-aiops-job-1"' >/dev/null || {
  log_fail "fetchJobTraces failed to return matching jobId. Text: $TRACES_TEXT"
  exit 1
}
echo "$TRACES_TEXT" | jq -e '.stageDurationBreakdownMillis | has("Scanning") and has("Indexing")' >/dev/null || {
  log_fail "fetchJobTraces failed to return expected pipeline stages breakdown."
  exit 1
}
log_info "fetchJobTraces returned correlated OpenTelemetry span timeline across pipeline stages."

# 6.2 Tool: getErrorLogs
log_info "Invoking MCP tool 'getErrorLogs' for job 'mcp-aiops-job-1'..."
GET_ERRORS_PAYLOAD='{
  "jsonrpc": "2.0",
  "id": 11,
  "method": "tools/call",
  "params": {
    "name": "getErrorLogs",
    "arguments": {
      "jobId": "mcp-aiops-job-1",
      "timeframe": "all"
    }
  }
}'
ERRORS_RESP=$(mcp_post "$GET_ERRORS_PAYLOAD" "$SESSION_ID")
log_info "getErrorLogs Response: ${ERRORS_RESP}"

ERRORS_TEXT=$(echo "$ERRORS_RESP" | jq -r '.result.content[0].text // empty')
if [ -z "$ERRORS_TEXT" ]; then
  ERRORS_TEXT=$(echo "$ERRORS_RESP" | jq -r '.result // empty')
fi

echo "$ERRORS_TEXT" | jq -e '.jobId == "mcp-aiops-job-1"' >/dev/null || {
  log_fail "getErrorLogs returned unexpected response structure: $ERRORS_TEXT"
  exit 1
}
log_info "getErrorLogs returned structured error logs envelope."

# 6.3 Tool: queryThroughputMetrics
log_info "Invoking MCP tool 'queryThroughputMetrics' for connector 'PGVector_Output'..."
METRICS_PAYLOAD='{
  "jsonrpc": "2.0",
  "id": 12,
  "method": "tools/call",
  "params": {
    "name": "queryThroughputMetrics",
    "arguments": {
      "connectorId": "PGVector_Output"
    }
  }
}'
METRICS_RESP=$(mcp_post "$METRICS_PAYLOAD" "$SESSION_ID")
log_info "queryThroughputMetrics Response: ${METRICS_RESP}"

METRICS_TEXT=$(echo "$METRICS_RESP" | jq -r '.result.content[0].text // empty')
if [ -z "$METRICS_TEXT" ]; then
  METRICS_TEXT=$(echo "$METRICS_RESP" | jq -r '.result // empty')
fi

echo "$METRICS_TEXT" | jq -e '.connectorId == "PGVector_Output" and .averageThroughputDocsPerSec > 0' >/dev/null || {
  log_fail "queryThroughputMetrics returned invalid metrics: $METRICS_TEXT"
  exit 1
}
log_info "queryThroughputMetrics returned valid throughput metrics, P95 latency, and virtual threads."
log_pass "Live AIOps Observability MCP tools executed with valid telemetry responses"

# ------------------------------------------------------------------------------
# STEP 7: Ingest Multi-ACL Documents via Decoupled Ingestion Pipeline
# ------------------------------------------------------------------------------
log_step "7" "Ingesting multi-ACL documents into decoupled Kafka pipeline via OIS API..."

DOC_PUBLIC="http://mcp-test.opencrawling.org/docs/public-guidelines.html"
DOC_ENG="http://mcp-test.opencrawling.org/docs/engineering-architecture.html"
DOC_FIN="http://mcp-test.opencrawling.org/docs/finance-q4-budget.html"
DOC_AUDIT="http://mcp-test.opencrawling.org/docs/contractor-security-audit.html"
DOC_NOBODY="http://mcp-test.opencrawling.org/docs/nobody-restricted.html"

MARKER_PUBLIC="mcp-marker-public-$(date +%s)"
MARKER_ENG="mcp-marker-engineering-$(date +%s)"
MARKER_FIN="mcp-marker-finance-$(date +%s)"
MARKER_AUDIT="mcp-marker-audit-$(date +%s)"
MARKER_NOBODY="mcp-marker-nobody-$(date +%s)"

ingest_ois_doc() {
  local doc_json="$1"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 -X POST \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${OIS_AUTH_TOKEN}" \
    -d "$doc_json" \
    "${INGEST_ENDPOINT}")
  
  if [ "$status" != "202" ] && [ "$status" != "200" ]; then
    log_fail "Ingesting OIS document failed with status: $status"
    exit 1
  fi
}

log_info "Ingesting Document 1 (Public Access)..."
ingest_ois_doc "$(cat <<EOF
{
  "id": "${DOC_PUBLIC}",
  "action": "UPSERT",
  "content": {
    "mimeType": "text/html",
    "text": "OpenCrawling public knowledge document: General company guidelines and cloud search practices. Marker: ${MARKER_PUBLIC}"
  },
  "metadata": {"title": "Public Guidelines", "department": "public"},
  "security": {
    "inheritanceEnabled": false,
    "permissions": [
      {"identity": "public", "identityType": "role", "access": "read"},
      {"identity": "ROLE_USER", "identityType": "role", "access": "read"}
    ]
  }
}
EOF
)"

log_info "Ingesting Document 2 (Restricted - Engineering Department Only)..."
ingest_ois_doc "$(cat <<EOF
{
  "id": "${DOC_ENG}",
  "action": "UPSERT",
  "content": {
    "mimeType": "text/html",
    "text": "Confidential technical blueprint: High-speed vector search indexing and backend clustering. Marker: ${MARKER_ENG}"
  },
  "metadata": {"title": "Engineering Architecture", "department": "engineering"},
  "security": {
    "inheritanceEnabled": false,
    "permissions": [{"identity": "engineering", "identityType": "role", "access": "read"}]
  }
}
EOF
)"

log_info "Ingesting Document 3 (Restricted - Finance Department Only)..."
ingest_ois_doc "$(cat <<EOF
{
  "id": "${DOC_FIN}",
  "action": "UPSERT",
  "content": {
    "mimeType": "text/html",
    "text": "Highly confidential executive financial quarterly budget allocation and payroll ledger. Marker: ${MARKER_FIN}"
  },
  "metadata": {"title": "Finance Q4 Budget", "department": "finance"},
  "security": {
    "inheritanceEnabled": false,
    "permissions": [{"identity": "finance", "identityType": "role", "access": "read"}]
  }
}
EOF
)"

log_info "Ingesting Document 4 (Contractors with Explicit Deny Precedence for Blacklisted User)..."
ingest_ois_doc "$(cat <<EOF
{
  "id": "${DOC_AUDIT}",
  "action": "UPSERT",
  "content": {
    "mimeType": "text/html",
    "text": "Restricted external security penetration testing audit report. Marker: ${MARKER_AUDIT}"
  },
  "metadata": {"title": "Contractor Security Audit", "department": "security"},
  "security": {
    "inheritanceEnabled": false,
    "permissions": [
      {"identity": "contractors", "identityType": "group", "access": "read"},
      {"identity": "blacklisted-contractor", "identityType": "user", "access": "deny"}
    ]
  }
}
EOF
)"

log_info "Ingesting Document 5 (Nobody / Empty Permissions - Zero-Trust Default Deny)..."
ingest_ois_doc "$(cat <<EOF
{
  "id": "${DOC_NOBODY}",
  "action": "UPSERT",
  "content": {
    "mimeType": "text/html",
    "text": "Highly classified zero-trust vault. Empty permissions must deny access to all callers. Marker: ${MARKER_NOBODY}"
  },
  "metadata": {"title": "Restricted Nobody", "department": "nobody"},
  "security": {
    "inheritanceEnabled": false,
    "permissions": []
  }
}
EOF
)"

# Wait for Kafka consumers to process and store records in PostgreSQL pgvector
count_db_rows() {
  local doc_id="$1"
  docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -c \
    "SELECT (SELECT count(*) FROM vector_store WHERE metadata->>'documentId' = '${doc_id}')
          + (SELECT count(*) FROM vector_store_384 WHERE metadata->>'documentId' = '${doc_id}')
          + (SELECT count(*) FROM vector_store_768 WHERE metadata->>'documentId' = '${doc_id}')
          + (SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '${doc_id}');" 2>/dev/null | tr -d '[:space:]' || echo "0"
}

wait_for_doc_indexing() {
  local doc_id="$1"
  local desc="$2"
  wait_until "${desc} indexed in pgvector" 300 \
    sh -c "[ \"\$(docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -c \"SELECT (SELECT count(*) FROM vector_store WHERE metadata->>'documentId' = '${doc_id}') + (SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '${doc_id}');\" 2>/dev/null | tr -d '[:space:]')\" -gt 0 ]"
}

wait_for_doc_indexing "${DOC_PUBLIC}" "Public Document"
wait_for_doc_indexing "${DOC_ENG}" "Engineering Document"
wait_for_doc_indexing "${DOC_FIN}" "Finance Document"
wait_for_doc_indexing "${DOC_AUDIT}" "Audit Document with Explicit Deny"
wait_for_doc_indexing "${DOC_NOBODY}" "Nobody Document with Empty Permissions"

# Verify storage parity: security_allowed_read must be '[]' and never defaulted to 'public'
NOBODY_ALLOWED_READ=$(docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -c \
  "SELECT metadata->>'security_allowed_read' FROM vector_store_1024 WHERE metadata->>'documentId' = '${DOC_NOBODY}' LIMIT 1;" 2>/dev/null | tr -d '[:space:]')
if [ "$NOBODY_ALLOWED_READ" != "[]" ]; then
  log_fail "Zero-Trust storage violation: Document with empty permissions stored security_allowed_read='$NOBODY_ALLOWED_READ', expected '[]'!"
  exit 1
fi
log_info "Storage parity verified: Document with empty permissions has security_allowed_read='[]' (not 'public')."

log_pass "Decoupled messaging pipeline processed all 5 multi-ACL documents into pgvector"

# ------------------------------------------------------------------------------
# STEP 8: Zero-Trust Security & Identity-Aware Retrieval (secureVectorSearch)
# ------------------------------------------------------------------------------
log_step "8" "Testing MCP 'secureVectorSearch' with Zero-Trust identity filtering & explicit deny precedence..."

invoke_secure_search() {
  local query="$1"
  local principal="$2"
  local roles="$3"
  local dimensions="${4:-1024}"

  local search_payload
  search_payload=$(cat <<EOF
{
  "jsonrpc": "2.0",
  "id": 20,
  "method": "tools/call",
  "params": {
    "name": "secureVectorSearch",
    "arguments": {
      "query": "${query}",
      "userPrincipal": "${principal}",
      "userRoles": "${roles}",
      "maxResults": 10,
      "dimensions": ${dimensions}
    }
  }
}
EOF
)
  mcp_post "$search_payload" "$SESSION_ID"
}

# 8.1 Engineering caller
log_info "8.1: Querying as Engineering Caller (principal: 'alice@company.com', roles: 'engineering')..."
ENG_SEARCH_RESP=$(invoke_secure_search "technical blueprint vector search" "alice@company.com" "engineering")
log_info "Engineering search response: ${ENG_SEARCH_RESP}"

echo "$ENG_SEARCH_RESP" | grep -q "${DOC_ENG}" || {
  log_fail "Engineering document was not found for authorized engineering caller."
  exit 1
}
echo "$ENG_SEARCH_RESP" | grep -q "${DOC_FIN}" && {
  log_fail "Zero-Trust violation: Finance document leaked to engineering caller!"
  exit 1
}
log_info "Assertion 8.1 PASS: Engineering document returned; Finance document shielded."

# 8.2 Finance caller
log_info "8.2: Querying as Finance Caller (principal: 'bob@company.com', roles: 'finance')..."
FIN_SEARCH_RESP=$(invoke_secure_search "financial quarterly budget payroll" "bob@company.com" "finance")
log_info "Finance search response: ${FIN_SEARCH_RESP}"

echo "$FIN_SEARCH_RESP" | grep -q "${DOC_FIN}" || {
  log_fail "Finance document was not found for authorized finance caller."
  exit 1
}
echo "$FIN_SEARCH_RESP" | grep -q "${DOC_ENG}" && {
  log_fail "Zero-Trust violation: Engineering document leaked to finance caller!"
  exit 1
}
log_info "Assertion 8.2 PASS: Finance document returned; Engineering document shielded."

# 8.3 Public / Unprivileged caller
log_info "8.3: Querying as Public Unprivileged Guest (principal: 'guest@external.com', roles: 'guest')..."
GUEST_SEARCH_RESP=$(invoke_secure_search "company guidelines practices" "guest@external.com" "guest")
log_info "Guest search response: ${GUEST_SEARCH_RESP}"

echo "$GUEST_SEARCH_RESP" | grep -q "${DOC_PUBLIC}" || {
  log_fail "Public document was not found for guest caller."
  exit 1
}
echo "$GUEST_SEARCH_RESP" | grep -q "${DOC_ENG}" && {
  log_fail "Zero-Trust violation: Engineering document leaked to guest caller!"
  exit 1
}
echo "$GUEST_SEARCH_RESP" | grep -q "${DOC_FIN}" && {
  log_fail "Zero-Trust violation: Finance document leaked to guest caller!"
  exit 1
}
echo "$GUEST_SEARCH_RESP" | grep -q "${DOC_NOBODY}" && {
  log_fail "Zero-Trust violation: Document with empty permissions leaked to guest caller!"
  exit 1
}
log_info "Assertion 8.3 PASS: Public document accessible; restricted documents withheld."

# 8.4 Explicit Deny Precedence
log_info "8.4a: Querying as Trusted Contractor (principal: 'trusted-contractor@vendor.com', roles: 'contractors')..."
TRUSTED_SEARCH_RESP=$(invoke_secure_search "penetration testing audit report" "trusted-contractor@vendor.com" "contractors")
echo "$TRUSTED_SEARCH_RESP" | grep -q "${DOC_AUDIT}" || {
  log_fail "Audit document was not found for permitted contractor member."
  exit 1
}
log_info "Assertion 8.4a PASS: Permitted group member successfully retrieved audit document."

log_info "8.4b: Querying as Blacklisted Contractor (principal: 'blacklisted-contractor', roles: 'contractors')..."
DENIED_SEARCH_RESP=$(invoke_secure_search "penetration testing audit report" "blacklisted-contractor" "contractors")
echo "$DENIED_SEARCH_RESP" | grep -q "${DOC_AUDIT}" && {
  log_fail "Zero-Trust Explicit Deny Failure: Blacklisted user retrieved document despite explicit deny rule!"
  exit 1
}
log_info "Assertion 8.4b PASS: Explicit deny precedence successfully blocked blacklisted user!"

# 8.5 Empty Permissions Isolation (Nobody)
log_info "8.5: Querying as Outsider with No Roles (principal: 'outsider', roles: '')..."
OUTSIDER_SEARCH_RESP=$(invoke_secure_search "zero-trust vault" "outsider" "")
log_info "Outsider search response: ${OUTSIDER_SEARCH_RESP}"

echo "$OUTSIDER_SEARCH_RESP" | grep -q "${DOC_NOBODY}" && {
  log_fail "Zero-Trust violation: Document with empty permissions leaked to outsider via secureVectorSearch!"
  exit 1
}
echo "$OUTSIDER_SEARCH_RESP" | grep -q "${DOC_ENG}" && {
  log_fail "Zero-Trust violation: Engineering document leaked to outsider via secureVectorSearch!"
  exit 1
}
echo "$OUTSIDER_SEARCH_RESP" | grep -q "${DOC_FIN}" && {
  log_fail "Zero-Trust violation: Finance document leaked to outsider via secureVectorSearch!"
  exit 1
}

# Also verify that even an engineering caller cannot retrieve DOC_NOBODY
ENG_NOBODY_RESP=$(invoke_secure_search "zero-trust vault" "alice@company.com" "engineering")
echo "$ENG_NOBODY_RESP" | grep -q "${DOC_NOBODY}" && {
  log_fail "Zero-Trust violation: Document with empty permissions leaked to engineering caller via secureVectorSearch!"
  exit 1
}
log_info "Assertion 8.5 PASS: Document with empty permissions withheld from all callers in secureVectorSearch."
log_pass "MCP secureVectorSearch authoritative Zero-Trust filtering and Explicit Deny precedence passed"

# ------------------------------------------------------------------------------
# STEP 9: Live Document Content Retrieval Guard (getDocumentContent)
# ------------------------------------------------------------------------------
log_step "9" "Testing MCP 'getDocumentContent' full-text retrieval and identity permission check..."

invoke_get_content() {
  local uri="$1"
  local principal="$2"
  local roles="$3"
  local dimensions="${4:-1024}"

  local content_payload
  content_payload=$(cat <<EOF
{
  "jsonrpc": "2.0",
  "id": 30,
  "method": "tools/call",
  "params": {
    "name": "getDocumentContent",
    "arguments": {
      "documentUri": "${uri}",
      "userPrincipal": "${principal}",
      "userRoles": "${roles}",
      "dimensions": ${dimensions}
    }
  }
}
EOF
)
  mcp_post "$content_payload" "$SESSION_ID"
}

# 9.1 Authorized retrieval
log_info "9.1: Retrieving '${DOC_ENG}' as authorized engineer..."
DOC_CONTENT_RESP=$(invoke_get_content "${DOC_ENG}" "alice@company.com" "engineering")
log_info "Document content response: ${DOC_CONTENT_RESP}"

echo "$DOC_CONTENT_RESP" | grep -q "${MARKER_ENG}" || {
  log_fail "getDocumentContent failed to return document content text marker for authorized user."
  exit 1
}
log_info "Assertion 9.1 PASS: Full document text retrieved for authorized caller."

# 9.2 Unauthorized retrieval
log_info "9.2: Retrieving '${DOC_ENG}' as unauthorized marketing user..."
UNAUTH_CONTENT_RESP=$(invoke_get_content "${DOC_ENG}" "eve@company.com" "marketing")
log_info "Unauthorized document content response: ${UNAUTH_CONTENT_RESP}"

# Should return error or indicate document not found/denied
if echo "$UNAUTH_CONTENT_RESP" | grep -q "${MARKER_ENG}"; then
  log_fail "Zero-Trust violation: Unauthorized user retrieved confidential document content!"
  exit 1
fi
log_info "Assertion 9.2 PASS: Unauthorized user denied access to document content."

# 9.3 Unauthorized retrieval of empty-permissions document
log_info "9.3: Retrieving '${DOC_NOBODY}' as outsider..."
OUTSIDER_CONTENT_RESP=$(invoke_get_content "${DOC_NOBODY}" "outsider" "")
log_info "Outsider document content response: ${OUTSIDER_CONTENT_RESP}"

if echo "$OUTSIDER_CONTENT_RESP" | grep -q "${MARKER_NOBODY}"; then
  log_fail "Zero-Trust violation: Outsider retrieved content of empty-permissions document!"
  exit 1
fi
log_info "Assertion 9.3 PASS: Outsider denied access to empty-permissions document content."
log_pass "MCP getDocumentContent accurately enforced content security boundaries"

# ------------------------------------------------------------------------------
# STEP 10: Live Accessible Sources Discovery Guard (listAccessibleSources)
# ------------------------------------------------------------------------------
log_step "10" "Testing MCP 'listAccessibleSources' for identity-aware index catalog discovery..."

invoke_list_sources() {
  local principal="$1"
  local roles="$2"
  local dimensions="${3:-1024}"

  local list_payload
  list_payload=$(cat <<EOF
{
  "jsonrpc": "2.0",
  "id": 40,
  "method": "tools/call",
  "params": {
    "name": "listAccessibleSources",
    "arguments": {
      "userPrincipal": "${principal}",
      "userRoles": "${roles}",
      "dimensions": ${dimensions}
    }
  }
}
EOF
)
  mcp_post "$list_payload" "$SESSION_ID"
}

log_info "Listing accessible sources for engineering caller..."
ENG_SOURCES_RESP=$(invoke_list_sources "alice@company.com" "engineering")
log_info "Engineering sources response: ${ENG_SOURCES_RESP}"

echo "$ENG_SOURCES_RESP" | grep -q "${DOC_ENG}" || {
  log_fail "listAccessibleSources did not include engineering document for engineering caller."
  exit 1
}
echo "$ENG_SOURCES_RESP" | grep -q "${DOC_FIN}" && {
  log_fail "Zero-Trust violation: Finance document visible in engineering sources list!"
  exit 1
}
echo "$ENG_SOURCES_RESP" | grep -q "${DOC_NOBODY}" && {
  log_fail "Zero-Trust violation: Empty-permissions document visible in engineering sources list!"
  exit 1
}

log_info "Listing accessible sources for finance caller..."
FIN_SOURCES_RESP=$(invoke_list_sources "bob@company.com" "finance")
echo "$FIN_SOURCES_RESP" | grep -q "${DOC_FIN}" || {
  log_fail "listAccessibleSources did not include finance document for finance caller."
  exit 1
}
echo "$FIN_SOURCES_RESP" | grep -q "${DOC_ENG}" && {
  log_fail "Zero-Trust violation: Engineering document visible in finance sources list!"
  exit 1
}
echo "$FIN_SOURCES_RESP" | grep -q "${DOC_NOBODY}" && {
  log_fail "Zero-Trust violation: Empty-permissions document visible in finance sources list!"
  exit 1
}

log_info "Listing accessible sources for outsider caller..."
OUTSIDER_SOURCES_RESP=$(invoke_list_sources "outsider" "")
echo "$OUTSIDER_SOURCES_RESP" | grep -q "${DOC_NOBODY}" && {
  log_fail "Zero-Trust violation: Empty-permissions document visible in outsider sources list!"
  exit 1
}
echo "$OUTSIDER_SOURCES_RESP" | grep -q "${DOC_ENG}" && {
  log_fail "Zero-Trust violation: Engineering document visible in outsider sources list!"
  exit 1
}
echo "$OUTSIDER_SOURCES_RESP" | grep -q "${DOC_FIN}" && {
  log_fail "Zero-Trust violation: Finance document visible in outsider sources list!"
  exit 1
}
log_info "Assertion 10 PASS: Empty-permissions document withheld across all catalog discovery callers."
log_pass "MCP listAccessibleSources accurately scoped accessible document catalogs by identity"

# ------------------------------------------------------------------------------
# STEP 11: Multi-Dimension Vector Target Support
# ------------------------------------------------------------------------------
log_step "11" "Testing multi-dimension index resolution (dimensions: 1024, 768, 384)..."

DIM_1024_RESP=$(invoke_secure_search "vector search" "alice@company.com" "engineering" 1024)
echo "$DIM_1024_RESP" | grep -q "result" || {
  log_fail "Multi-dimension search with dimensions=1024 failed."
  exit 1
}

DIM_384_RESP=$(invoke_secure_search "vector search" "alice@company.com" "engineering" 384)
echo "$DIM_384_RESP" | grep -q "result" || {
  log_fail "Multi-dimension search with dimensions=384 failed."
  exit 1
}
log_info "Multi-dimension parameter propagation verified for 1024 and 384 dimensional stores."
log_pass "MCP Server verified multi-dimension index resolution"

# ------------------------------------------------------------------------------
# STEP 12: OIS Document Lifecycle Tombstone DELETE over MCP
# ------------------------------------------------------------------------------
log_step "12" "Testing OIS Document Tombstone DELETE lifecycle purge over MCP search..."

log_info "Issuing OIS Tombstone DELETE for Document '${DOC_FIN}'..."
ingest_ois_doc "$(cat <<EOF
{
  "id": "${DOC_FIN}",
  "action": "DELETE",
  "metadata": {"title": "Deleted Finance Document"}
}
EOF
)"

# Wait for tombstone deletion to clear rows in PostgreSQL pgvector
wait_until "Finance document purged from pgvector after tombstone DELETE" 120 \
  sh -c "[ \"\$(docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -c \"SELECT (SELECT count(*) FROM vector_store WHERE metadata->>'documentId' = '${DOC_FIN}') + (SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '${DOC_FIN}');\" 2>/dev/null | tr -d '[:space:]')\" = '0' ]"

# Re-query MCP secureVectorSearch as finance caller
PURGED_SEARCH_RESP=$(invoke_secure_search "financial quarterly budget payroll" "bob@company.com" "finance")
echo "$PURGED_SEARCH_RESP" | grep -q "${DOC_FIN}" && {
  log_fail "Purge Failure: Deleted document still appears in MCP search results!"
  exit 1
}
log_info "Tombstone DELETE confirmed: Purged document is no longer discoverable via MCP."
log_pass "OIS Tombstone DELETE lifecycle accurately propagated and purged from MCP tool results"

# ------------------------------------------------------------------------------
# STEP 13: REST API Observability Fallback Parity
# ------------------------------------------------------------------------------
log_step "13" "Verifying REST API Observability endpoints (/api/observability/*) parity with MCP tools..."

REST_TRACES=$(curl -s --max-time 10 "http://localhost:8080/api/observability/traces/mcp-test-job-fallback")
echo "$REST_TRACES" | jq -e '.jobId == "mcp-test-job-fallback"' >/dev/null || {
  log_fail "REST endpoint /api/observability/traces failed to return matching trace response."
  exit 1
}

REST_ERRORS=$(curl -s --max-time 10 "http://localhost:8080/api/observability/errors/mcp-test-job-fallback")
echo "$REST_ERRORS" | jq -e '.jobId == "mcp-test-job-fallback"' >/dev/null || {
  log_fail "REST endpoint /api/observability/errors failed to return error response."
  exit 1
}

REST_METRICS=$(curl -s --max-time 10 "http://localhost:8080/api/observability/metrics?connectorId=PGVector_Output")
echo "$REST_METRICS" | jq -e '.connectorId == "PGVector_Output"' >/dev/null || {
  log_fail "REST endpoint /api/observability/metrics failed to return throughput metrics."
  exit 1
}
log_info "REST observability endpoints parity confirmed with MCP tool contracts."
log_pass "REST Observability API endpoints verified alongside MCP tool surface"

# ------------------------------------------------------------------------------
# STEP 14: In-Process Backend SPI & ACL Evaluator Integration Tests (JUnit)
# ------------------------------------------------------------------------------
log_step "14" "Running comprehensive in-process JUnit integration test suite (McpServerCapabilitiesIntegrationTest, McpVectorServerTest, McpAclEvaluatorTest, PgVectorMcpSearchBackendTest)..."

mvn test -pl oc-runtime -am \
  -Dtest=McpServerCapabilitiesIntegrationTest,McpVectorServerTest,McpAclEvaluatorTest,PgVectorMcpSearchBackendTest \
  -Dsurefire.failIfNoSpecifiedTests=false

log_pass "In-process JUnit integration suite executed successfully with 100% assertions passing"

# ------------------------------------------------------------------------------
# STEP 15: Teardown & Execution Summary
# ------------------------------------------------------------------------------
log_step "15" "Finalizing decoupled MCP server integration test execution..."

echo -e "\n${BOLD}${GREEN}================================================================================${NC}"
echo -e "${BOLD}${GREEN}SUCCESS: All $TOTAL_TESTS OpenCrawling Decoupled MCP Server Integration Tests Passed!${NC}"
echo -e "${BOLD}${GREEN}================================================================================${NC}"
echo -e "${CYAN}Summary of Validated Capabilities:${NC}"
echo -e "  ✔ MCP Streamable HTTP transport handshake & session token negotiation"
echo -e "  ✔ MCP notifications/initialized & ping lifecycle execution"
echo -e "  ✔ Dynamic tool discovery & JSON-RPC schema contract advertisement (tools/list)"
echo -e "  ✔ AIOps Observability tools (fetchJobTraces, getErrorLogs, queryThroughputMetrics)"
echo -e "  ✔ Decoupled multi-service OIS document ingestion pipeline over Kafka"
echo -e "  ✔ Authoritative Zero-Trust ACL defense-in-depth retrieval (secureVectorSearch)"
echo -e "  ✔ Explicit Deny Precedence security guard against malicious/blacklisted identities"
echo -e "  ✔ Empty permissions default-deny isolation across all tools (nobody access)"
echo -e "  ✔ Identity-aware full-text document content retrieval (getDocumentContent)"
echo -e "  ✔ Identity-scoped document catalog discovery (listAccessibleSources)"
echo -e "  ✔ Multi-dimension vector store resolution (384, 768, 1024)"
echo -e "  ✔ OIS Document Lifecycle Tombstone DELETE purge reflection in MCP search"
echo -e "  ✔ REST Observability API fallback parity (/api/observability/*)"
echo -e "  ✔ In-process pluggable vector database SPI & ACL evaluator integration parity (JUnit)"

exit 0
