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

# Verifies the end-to-end Alfresco -> PGVector -> Reports & Audit -> MCP Inspector flow:
#   1. Inspects PostgreSQL pgvector tables and Zero-Trust ACL metadata.
#   2. Verifies Reports & Audit Ledger API endpoints (/api/reports, CSV/JSON/Parquet).
#   3. Runs real MCP Inspector CLI calls against http://localhost:8080/mcp across 4 personas:
#      - guest (public only)
#      - charlie / GROUP_finance
#      - bob / GROUP_engineering (explicit DENY overrides group ALLOW)
#      - alice / GROUP_engineering,GROUP_finance (sees all including executive owner doc)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TUTORIAL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
STATE_FILE="${TUTORIAL_DIR}/data/.provisioned-nodes.json"
MCP_CONFIG="${TUTORIAL_DIR}/mcp/mcp-inspector-config.json"
OPENCRAWLING_API="${OPENCRAWLING_API:-http://localhost:8080}"
PG_CONTAINER="${PG_CONTAINER:-postgres-vector-tutorial}"

BLUE='\033[0;34m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
log_err()  { echo -e "${RED}[FAIL]${NC} $*"; }

for cmd in docker curl jq npx; do
  command -v "$cmd" >/dev/null 2>&1 || { log_err "Required command '$cmd' not found."; exit 1; }
done

if [ ! -f "${STATE_FILE}" ]; then
  log_err "Missing ${STATE_FILE}. Run provision-alfresco-data.sh first."
  exit 1
fi

HANDBOOK_ID=$(jq -r '.documents["company-handbook.txt"]' "${STATE_FILE}")
BUDGET_ID=$(jq -r '.documents["q4-financial-forecast.txt"]' "${STATE_FILE}")
ARCH_ID=$(jq -r '.documents["zero-trust-architecture.txt"]' "${STATE_FILE}")
BOARD_ID=$(jq -r '.documents["board-resolution-2026.txt"]' "${STATE_FILE}")

# ------------------------------------------------------------------------------
# 1. Inspect PGVector Storage & ACL Metadata
# ------------------------------------------------------------------------------
log_info "Checking PGVector tables (${PG_CONTAINER})..."
docker exec -i "${PG_CONTAINER}" psql -U opencrawling -d opencrawling -c "
SELECT table_name,
       (xpath('/row/cnt/text()', xml_count))[1]::text::int AS row_count
FROM (
  SELECT table_name,
         query_to_xml(format('SELECT count(*) AS cnt FROM %I', table_name), false, true, '') AS xml_count
  FROM information_schema.tables
  WHERE table_schema = 'public' AND table_name LIKE 'vector_store%'
) t
ORDER BY table_name;
"

log_info "Inspecting ingested documents & security metadata in vector_store_1024..."
docker exec -i "${PG_CONTAINER}" psql -U opencrawling -d opencrawling -c "
SELECT metadata->>'name'                 AS file_name,
       metadata->>'documentId'           AS doc_id,
       metadata->'security_read_keys'    AS read_keys,
       metadata->'security_denied_read'  AS denied_read
FROM vector_store_1024
ORDER BY file_name;
"

COUNT_1024=$(docker exec -i "${PG_CONTAINER}" psql -t -A -U opencrawling -d opencrawling -c "SELECT count(*) FROM vector_store_1024;")
if [ "${COUNT_1024:-0}" -lt 4 ]; then
  log_err "Expected at least 4 chunks in vector_store_1024, found ${COUNT_1024:-0}."
  exit 1
fi
log_ok "vector_store_1024 contains ${COUNT_1024} chunks."

# ------------------------------------------------------------------------------
# 2. Verify Reports & Audit Ledger API
# ------------------------------------------------------------------------------
log_info "Checking Reports & Audit API (${OPENCRAWLING_API}/api/reports)..."
REPORTS_JSON=$(curl -sf "${OPENCRAWLING_API}/api/reports")
echo "$REPORTS_JSON" | jq '.[0] // empty'
EXEC_ID=$(echo "$REPORTS_JSON" | jq -r '.[0].executionId // empty')
JOB_ID=$(echo "$REPORTS_JSON" | jq -r '.[0].jobId // empty')
if [ -z "$EXEC_ID" ] || [ -z "$JOB_ID" ]; then
  log_err "No execution reports found in /api/reports."
  exit 1
fi

curl -sf "${OPENCRAWLING_API}/api/reports/${JOB_ID}/${EXEC_ID}/documents?status=ALL&page=0&size=20" | jq '{total: .total, files: [.items[].sourceUri]}'
CSV_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${OPENCRAWLING_API}/api/reports/${JOB_ID}/${EXEC_ID}/export?format=csv")
PARQUET_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${OPENCRAWLING_API}/api/reports/${JOB_ID}/${EXEC_ID}/export?format=parquet")
if [ "$CSV_CODE" != "200" ] || [ "$PARQUET_CODE" != "200" ]; then
  log_err "Report export check failed (csv=${CSV_CODE}, parquet=${PARQUET_CODE})."
  exit 1
fi
DUCKDB_SUCCESS=$(curl -sf -X POST "${OPENCRAWLING_API}/api/reports/query" \
  -H "Content-Type: application/json" \
  -d '{"sql":"SELECT count(*) AS cnt FROM read_parquet(\"/data/reports/**/*.parquet\") WHERE status = '\''SUCCESS'\''"}' \
  | jq -r '.rows[0][0] // 0')
if [ "${DUCKDB_SUCCESS:-0}" -lt 4 ]; then
  log_err "Expected >= 4 SUCCESS rows in DuckDB Parquet query, got ${DUCKDB_SUCCESS:-0}."
  exit 1
fi
log_ok "Reports & Audit API + DuckDB Parquet query verified (jobId=${JOB_ID}, executionId=${EXEC_ID}, duckdb_success=${DUCKDB_SUCCESS})."

# ------------------------------------------------------------------------------
# 3. Verify MCP Server via MCP Inspector CLI
# ------------------------------------------------------------------------------
mcp_cli() {
  npx --yes @modelcontextprotocol/inspector --cli \
    --config "${MCP_CONFIG}" \
    --server opencrawling-alfresco \
    --format json -q "$@"
}

call_tool() {
  local tool="$1"
  local args_json="$2"
  mcp_cli --method tools/call --tool-name "$tool" --tool-args-json "$args_json"
}

log_info "Listing MCP tools via MCP Inspector CLI..."
TOOLS_JSON=$(mcp_cli --method tools/list)
TOOL_NAMES=$(echo "$TOOLS_JSON" | jq -r '[.result.tools[].name] | join(", ")')
log_info "Available MCP tools: ${TOOL_NAMES}"
for required in listAccessibleSources secureVectorSearch getDocumentContent; do
  echo "$TOOLS_JSON" | jq -e --arg t "$required" '.result.tools[] | select(.name == $t)' >/dev/null || {
    log_err "Missing expected MCP tool '${required}'."
    exit 1
  }
done
log_ok "MCP tools/list verified via MCP Inspector CLI."

# Helper to extract JSON text payload returned by Spring AI MCP tool calls
extract_text() {
  jq -r '.result.content[0].text // ""'
}

assert_contains() {
  local haystack="$1" needle="$2" label="$3"
  if ! echo "$haystack" | grep -q "${needle}"; then
    log_err "${label}: expected '${needle}' in output, got: ${haystack}"
    exit 1
  fi
}

assert_not_contains() {
  local haystack="$1" needle="$2" label="$3"
  if echo "$haystack" | grep -q "${needle}"; then
    log_err "${label}: did NOT expect '${needle}' in output, got: ${haystack}"
    exit 1
  fi
}

# Persona 1: guest (no roles) -> ONLY public handbook
log_info "Testing MCP listAccessibleSources as 'guest' (no roles)..."
GUEST_SOURCES=$(call_tool "listAccessibleSources" '{"userPrincipal":"guest","userRoles":"","dimensions":1024}' | extract_text)
assert_contains     "$GUEST_SOURCES" "$HANDBOOK_ID" "guest listAccessibleSources"
assert_not_contains "$GUEST_SOURCES" "$BUDGET_ID"   "guest listAccessibleSources"
assert_not_contains "$GUEST_SOURCES" "$ARCH_ID"     "guest listAccessibleSources"
assert_not_contains "$GUEST_SOURCES" "$BOARD_ID"    "guest listAccessibleSources"
log_ok "Persona 'guest' sees ONLY company-handbook.txt."

# Persona 2: charlie (GROUP_finance) -> handbook + finance forecast
log_info "Testing MCP secureVectorSearch as 'charlie' (GROUP_finance)..."
CHARLIE_SEARCH=$(call_tool "secureVectorSearch" '{"query":"Q4 operating budget financial forecast","userPrincipal":"charlie","userRoles":"GROUP_finance","maxResults":10,"minScore":0.0}' | extract_text)
assert_contains     "$CHARLIE_SEARCH" "$BUDGET_ID" "charlie secureVectorSearch"
assert_not_contains "$CHARLIE_SEARCH" "$ARCH_ID"   "charlie secureVectorSearch"
assert_not_contains "$CHARLIE_SEARCH" "$BOARD_ID"  "charlie secureVectorSearch"
log_ok "Persona 'charlie' (GROUP_finance) sees finance forecast and is denied engineering & executive."

# Persona 3: bob (GROUP_engineering, explicit DENY on zero-trust-architecture.txt)
log_info "Testing MCP secureVectorSearch as 'bob' (GROUP_engineering + explicit DENY)..."
BOB_SOURCES=$(call_tool "listAccessibleSources" '{"userPrincipal":"bob","userRoles":"GROUP_engineering","dimensions":1024}' | extract_text)
BOB_SEARCH=$(call_tool "secureVectorSearch" '{"query":"zero-trust architecture rotating mTLS certificates","userPrincipal":"bob","userRoles":"GROUP_engineering","maxResults":10,"minScore":0.0}' | extract_text)
assert_contains     "$BOB_SOURCES" "$HANDBOOK_ID" "bob listAccessibleSources"
assert_not_contains "$BOB_SOURCES" "$ARCH_ID"     "bob listAccessibleSources (deny precedence)"
assert_not_contains "$BOB_SEARCH"  "$ARCH_ID"     "bob secureVectorSearch (deny precedence)"
assert_not_contains "$BOB_SEARCH"  "$BOARD_ID"    "bob secureVectorSearch"
log_ok "Persona 'bob' is BLOCKED from zero-trust-architecture.txt despite GROUP_engineering membership."

# Persona 4: alice (GROUP_engineering,GROUP_finance + explicit executive owner)
log_info "Testing MCP listAccessibleSources & getDocumentContent as 'alice'..."
ALICE_SOURCES=$(call_tool "listAccessibleSources" '{"userPrincipal":"alice","userRoles":"GROUP_engineering,GROUP_finance","dimensions":1024}' | extract_text)
assert_contains "$ALICE_SOURCES" "$HANDBOOK_ID" "alice listAccessibleSources"
assert_contains "$ALICE_SOURCES" "$BUDGET_ID"   "alice listAccessibleSources"
assert_contains "$ALICE_SOURCES" "$ARCH_ID"     "alice listAccessibleSources"
assert_contains "$ALICE_SOURCES" "$BOARD_ID"    "alice listAccessibleSources"

ALICE_BOARD=$(call_tool "getDocumentContent" "$(jq -nc --arg uri "$BOARD_ID" '{documentUri:$uri,userPrincipal:"alice",userRoles:"GROUP_engineering,GROUP_finance"}')" | extract_text)
assert_contains "$ALICE_BOARD" "Project Horizon" "alice getDocumentContent(board-resolution-2026.txt)"

# Direct URI access denied for bob on board-resolution-2026.txt
set +e
BOB_BOARD_DENIED=$(call_tool "getDocumentContent" "$(jq -nc --arg uri "$BOARD_ID" '{documentUri:$uri,userPrincipal:"bob",userRoles:"GROUP_engineering"}')" 2>&1)
BOB_BOARD_EXIT=$?
set -e
if [ "$BOB_BOARD_EXIT" -eq 0 ] && echo "$BOB_BOARD_DENIED" | grep -q "Project Horizon"; then
  log_err "bob should NOT be able to retrieve board-resolution-2026.txt content!"
  exit 1
fi
log_ok "Persona 'alice' retrieves all 4 docs including board-resolution-2026.txt; direct URI fetch blocked for 'bob'."

# Test Discovery tool (if enabled)
if echo "$TOOL_NAMES" | grep -q "listRepositoryConnectors"; then
  log_info "Testing Discovery tool exploreRepositoryTree (bob vs alice)..."
  BOB_TREE=$(call_tool "exploreRepositoryTree" '{"connector":"Alfresco_Tutorial","userPrincipal":"bob","userRoles":"GROUP_engineering"}' | extract_text)
  assert_not_contains "$BOB_TREE" "board-resolution-2026.txt" "bob exploreRepositoryTree"
  assert_not_contains "$BOB_TREE" "zero-trust-architecture.txt" "bob exploreRepositoryTree"
  log_ok "Discovery tool exploreRepositoryTree enforces live Alfresco ACLs + explicit deny."
fi

echo ""
log_ok "ALL END-TO-END CHECKS PASSED!"
echo "Launch MCP Inspector Web UI with:"
echo "  npx @modelcontextprotocol/inspector --config ${MCP_CONFIG} --server opencrawling-alfresco"
