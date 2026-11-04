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
# OpenCrawling - OpenFGA document authorization, decoupled end-to-end test (#45)
#
#   1. Build and start the decoupled stack with OpenFGA; oc-mcp-server runs in
#      openfga mode and mirrors permissions from the documents topic.
#   2. Ingest three documents through the OIS endpoint: one for the finance
#      group (with a deny for mallory), one for alice only, one public.
#   3. OpenFGA holds their tuples, and MCP secure search returns to each
#      caller exactly the documents OpenFGA allows.
#   4. A membership written to OpenFGA (dave joins finance) changes access at
#      once, with no re-index; removing it takes access away again.
#   5. Re-ingesting a document with new permissions moves the tuples.
#   6. A deleted document loses its tuples and disappears from search.
#   7. With OpenFGA stopped, search fails instead of returning anything
#      (fail closed); it recovers when OpenFGA is back.
#   8. Authorization metrics are published.
#
# Environment: MCP_PORT (default 18080), OPENFGA_HTTP_PORT (8089), OC_IMAGE_TAG
# (openfga-45, so latest images are never replaced), SKIP_BUILD, KEEP_CONTAINERS.
# ==============================================================================

set -euo pipefail

export MCP_PORT="${MCP_PORT:-18080}"
export OPENFGA_HTTP_PORT="${OPENFGA_HTTP_PORT:-8089}"
export OC_IMAGE_TAG="${OC_IMAGE_TAG:-openfga-45}"
export OPENFGA_PRESHARED_KEY="${OPENFGA_PRESHARED_KEY:-openfga-e2e-$(date +%s)-key}"
export OPENCRAWLING_INGEST_OIS_TOKEN="${OPENCRAWLING_INGEST_OIS_TOKEN:-openfga-e2e-ois-token}"
SKIP_BUILD="${SKIP_BUILD:-false}"
KEEP_CONTAINERS="${KEEP_CONTAINERS:-false}"
TIMEOUT="${TIMEOUT:-300}"

BASE="http://localhost:${MCP_PORT}"
FGA="http://localhost:${OPENFGA_HTTP_PORT}"
OIS="${BASE}/api/v1/ingest/ois/1"
RUN="$(date +%s)"
DOC_FINANCE="openfga-e2e://${RUN}/finance-report"
DOC_ALICE="openfga-e2e://${RUN}/alice-notes"
DOC_PUBLIC="openfga-e2e://${RUN}/public-faq"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
PASSED=0; TOTAL=8
step() { echo -e "\n${BOLD}${CYAN}[STEP $1/${TOTAL}] $2${NC}"; }
pass() { PASSED=$((PASSED + 1)); echo -e "${BOLD}${GREEN}[PASS ${PASSED}/${TOTAL}] $1${NC}"; }
fail() { echo -e "${RED}[FAIL] $1${NC}"; compose logs --tail 150 oc-mcp-server openfga 2>/dev/null || true; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

compose() {
  COMPOSE_BAKE=false docker compose -p openfga-e2e -f docker-compose-decoupled.yml -f docker-compose-openfga.yml \
    -f scripts/docker-compose-openfga-decoupled.test.yml "$@"
}

cleanup() {
  if [ "${KEEP_CONTAINERS}" = true ]; then
    echo -e "${YELLOW}KEEP_CONTAINERS=true: leaving the stack running.${NC}"
  else
    echo -e "\n${YELLOW}Tearing down the test stack...${NC}"
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# wait_until <description> <timeout seconds> <command...>
wait_until() {
  local description="$1" timeout="$2" elapsed=0
  shift 2
  until "$@"; do
    [ "$elapsed" -ge "$timeout" ] && fail "timeout after ${timeout}s waiting for ${description}"
    sleep 3; elapsed=$((elapsed + 3))
  done
  echo -e "${GREEN}${description}: ok${NC}"
}

for tool in curl jq docker shasum; do command -v "$tool" >/dev/null || fail "$tool is required"; done

# --- OpenFGA HTTP API ---------------------------------------------------------
fga() { # <method> <path> [body]
  curl -s --max-time 10 -X "$1" "${FGA}$2" -H "Authorization: Bearer ${OPENFGA_PRESHARED_KEY}" \
    -H 'Content-Type: application/json' ${3:+-d "$3"}
}
store_id() { fga GET "/stores?name=opencrawling" | jq -r '.stores[0].id // empty'; }
document_object() { echo "document:$(printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1)"; }
tuples_of() { # the tuples OpenFGA holds for a document, one "relation user" per line
  fga POST "/stores/${STORE}/read" "$(jq -nc --arg o "$(document_object "$1")" '{tuple_key:{object:$o}}')" \
    | jq -r '.tuples[]?.key | .relation + " " + .user' | sort
}
has_tuples() { [ -n "$(tuples_of "$1")" ]; }
has_no_tuples() { [ -z "$(tuples_of "$1")" ]; }
has_tuple() { local t; t=$(tuples_of "$1"); grep -Fqx -- "$2" <<<"$t"; }
lacks_tuple() { ! has_tuple "$1" "$2"; }

# --- OIS ingest ---------------------------------------------------------------
ingest() { # <id> <text> <permissions json array>
  local body status
  body=$(jq -nc --arg id "$1" --arg text "$2" --argjson perms "$3" \
    '{id:$id, action:"UPSERT", content:{mimeType:"text/plain", text:$text}, metadata:{title:$id},
      security:{inheritanceEnabled:false, permissions:$perms}}')
  status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${OPENCRAWLING_INGEST_OIS_TOKEN}" -d "$body" "$OIS")
  [ "$status" = 202 ] || fail "ingest of $1 answered ${status}"
}
delete_doc() {
  local status
  status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${OPENCRAWLING_INGEST_OIS_TOKEN}" -d "$(jq -nc --arg id "$1" '{id:$id, action:"DELETE"}')" "$OIS")
  [ "$status" = 202 ] || fail "delete of $1 answered ${status}"
}
rows() {
  docker exec -i postgres-vector-decoupled psql -U opencrawling -d opencrawling -t -A -c \
    "SELECT count(*) FROM vector_store_1024 WHERE metadata->>'documentId' = '$1';" 2>/dev/null | tr -d '[:space:]'
}
has_rows() { [ "$(rows "$1")" -gt 0 ] 2>/dev/null; }

# --- MCP over streamable HTTP --------------------------------------------------
MCP_HEADERS=$(mktemp)
MCP_SESSION=""
mcp_call() {
  curl -s --max-time 30 -D "$MCP_HEADERS" -X POST "${BASE}/mcp" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' ${MCP_SESSION:+-H "Mcp-Session-Id: ${MCP_SESSION}"} -d "$1" \
    | sed -n 's/^data: *//p; /^{/p' | grep '"jsonrpc"' | tail -1
}
mcp_init() {
  MCP_SESSION=""
  local init
  init=$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-openfga-decoupled","version":"1.0"}}}')
  MCP_SESSION=$(grep -i '^mcp-session-id:' "$MCP_HEADERS" | awk '{print $2}' | tr -d '\r')
  [ -n "$(echo "$init" | jq -r '.result.serverInfo.name // empty')" ] || return 1
  mcp_call '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null || true
}
CALL_ID=10
search_raw() { # <principal> <roles>: the raw JSON-RPC answer
  CALL_ID=$((CALL_ID + 1))
  local args
  args=$(jq -nc --arg p "$1" --arg r "$2" \
    '{query:"quarterly finance report notes faq", userPrincipal:$p, userRoles:$r, maxResults:50, minScore:0.0, dimensions:1024}')
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":${CALL_ID},\"method\":\"tools/call\",\"params\":{\"name\":\"secureVectorSearch\",\"arguments\":${args}}}"
}
search_as() { # <principal> <roles>: the test documents the caller finds, one per line
  search_raw "$1" "$2" | jq -r '.result.content[]?.text // empty' | grep -o "openfga-e2e://${RUN}/[a-z-]*" | sort -u || true
}
expect_found() { # <principal> <roles> <expected documents, space separated>
  local got expected
  got=$(search_as "$1" "$2" | tr '\n' ' ' | sed 's/ $//')
  expected=$(printf '%s\n' $3 | sort | tr '\n' ' ' | sed 's/ $//')
  [ "$got" = "$expected" ] || fail "caller '$1' roles '$2' found [${got}], expected [${expected}]"
  echo -e "${GREEN}  '$1' (${2:-no roles}) finds: ${got:-nothing}${NC}"
}
finds() { local f; f=$(search_as "$1" "$2"); grep -Fqx -- "$3" <<<"$f"; }
misses() { ! finds "$1" "$2" "$3"; }

# ==============================================================================
step 1 "Build and start the decoupled stack with OpenFGA (mcp on :${MCP_PORT}, OpenFGA on :${OPENFGA_HTTP_PORT})"
compose down --volumes --remove-orphans >/dev/null 2>&1 || true
SERVICES="oc-ingestion-consumer oc-embedding-consumer oc-writer-consumer oc-mcp-server"
if [ "${SKIP_BUILD}" != true ]; then
  timeout 3600 bash -c "COMPOSE_BAKE=false docker compose -p openfga-e2e -f docker-compose-decoupled.yml -f docker-compose-openfga.yml -f scripts/docker-compose-openfga-decoupled.test.yml build ${SERVICES}"
fi
compose up -d ${SERVICES} openfga
wait_until "postgres-vector healthy" 180 \
  sh -c '[ "$(docker inspect -f "{{.State.Health.Status}}" postgres-vector-decoupled 2>/dev/null)" = "healthy" ]'
compose up -d ollama-model-puller
wait_until "Ollama model pulled" 900 \
  sh -c '[ "$(docker inspect -f "{{.State.Status}} {{.State.ExitCode}}" ollama-model-puller-decoupled 2>/dev/null)" = "exited 0" ]'
wait_until "OpenFGA healthy" 180 sh -c '[ "$(docker inspect -f "{{.State.Health.Status}}" openfga 2>/dev/null)" = "healthy" ]'
wait_until "OIS endpoint up on oc-mcp-server" "$TIMEOUT" \
  sh -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST -d '{}' '${OIS}')\" = 401 ]"
STORE=$(store_id)
[ -n "$STORE" ] || fail "oc-mcp-server did not provision the OpenFGA store"
MCP_LOG=$(compose logs oc-mcp-server 2>/dev/null)
[[ "$MCP_LOG" == *"Document authorization mode 'openfga'"* ]] || fail "oc-mcp-server is not in openfga mode"
pass "stack up, OpenFGA store ${STORE} provisioned by oc-mcp-server in openfga mode"

# ==============================================================================
step 2 "Ingest three documents with different permissions"
ingest "$DOC_FINANCE" "Quarterly finance report with revenue figures. ${RUN}" \
  '[{"identity":"group:Finance","identityType":"group","access":"read"},{"identity":"mallory","identityType":"user","access":"deny"}]'
ingest "$DOC_ALICE" "Alice private notes about the finance report. ${RUN}" \
  '[{"identity":"alice","identityType":"user","access":"admin"}]'
ingest "$DOC_PUBLIC" "Public FAQ about the quarterly report. ${RUN}" \
  '[{"identity":"GROUP_EVERYONE","identityType":"public","access":"read"}]'
for doc in "$DOC_FINANCE" "$DOC_ALICE" "$DOC_PUBLIC"; do
  wait_until "chunks of ${doc} in pgvector" "$TIMEOUT" has_rows "$doc"
  wait_until "tuples of ${doc} in OpenFGA" "$TIMEOUT" has_tuples "$doc"
done
pass "documents indexed and their permissions mirrored to OpenFGA"

# ==============================================================================
step 3 "OpenFGA tuples and MCP search decisions"
has_tuple "$DOC_FINANCE" "grantee group:g_finance#member" || fail "finance grant tuple missing"
has_tuple "$DOC_FINANCE" "blocked group:u_mallory#member" || fail "mallory block tuple missing"
has_tuple "$DOC_ALICE" "grantee group:u_alice#member" || fail "admin grant must give read"
has_tuple "$DOC_PUBLIC" "grantee user:*" || fail "GROUP_EVERYONE must become public"
wait_until "MCP session" 60 mcp_init
expect_found "carol" "finance" "$DOC_FINANCE $DOC_PUBLIC"
expect_found "carol" "GROUP:FINANCE" "$DOC_FINANCE $DOC_PUBLIC"
expect_found "mallory" "finance" "$DOC_PUBLIC"
expect_found "alice" "" "$DOC_ALICE $DOC_PUBLIC"
expect_found "finance" "" "$DOC_PUBLIC"   # a user named like the group gets nothing from group:Finance (#224)
expect_found "user:Alice" "" "$DOC_ALICE $DOC_PUBLIC"
expect_found "dave" "" "$DOC_PUBLIC"
pass "each caller finds exactly what OpenFGA allows"

# ==============================================================================
step 4 "A membership change in OpenFGA takes effect without re-indexing"
MEMBERSHIP='{"user":"user:p_dave","relation":"member","object":"group:g_finance"}'
fga POST "/stores/${STORE}/write" "{\"writes\":{\"tuple_keys\":[${MEMBERSHIP}]}}" >/dev/null
wait_until "dave finds the finance report" 60 finds "dave" "" "$DOC_FINANCE"
fga POST "/stores/${STORE}/write" "{\"deletes\":{\"tuple_keys\":[${MEMBERSHIP}]}}" >/dev/null
wait_until "dave no longer finds the finance report" 60 misses "dave" "" "$DOC_FINANCE"
pass "group membership granted and revoked access with no re-index"

# ==============================================================================
step 5 "Changed permissions move the tuples"
ingest "$DOC_FINANCE" "Quarterly finance report with revenue figures. ${RUN}" \
  '[{"identity":"hr","identityType":"group","access":"read"}]'
finance_moved_to_hr() {
  has_tuple "$DOC_FINANCE" "grantee group:g_hr#member" \
    && lacks_tuple "$DOC_FINANCE" "grantee group:g_finance#member" \
    && lacks_tuple "$DOC_FINANCE" "blocked group:u_mallory#member"
}
wait_until "finance grant replaced by hr" "$TIMEOUT" finance_moved_to_hr
wait_until "finance members lose the report" 60 misses "carol" "finance" "$DOC_FINANCE"
expect_found "henry" "hr" "$DOC_FINANCE $DOC_PUBLIC"
pass "re-ingest replaced the grants and removed the stale block"

# ==============================================================================
step 6 "A deleted document loses its tuples"
delete_doc "$DOC_PUBLIC"
wait_until "tuples of the deleted document removed" "$TIMEOUT" has_no_tuples "$DOC_PUBLIC"
wait_until "deleted document gone from search" 60 misses "anyone" "" "$DOC_PUBLIC"
pass "tombstone removed the document's tuples"

# ==============================================================================
step 7 "OpenFGA unavailable: search fails closed"
compose stop openfga >/dev/null
DOWN=$(search_raw "henry" "hr")
if [[ "$DOWN" == *"openfga-e2e://${RUN}"* ]]; then
  fail "search returned documents while OpenFGA was down: ${DOWN}"
fi
echo "$DOWN" | jq -e '(.error != null) or (.result.isError == true)' >/dev/null \
  || fail "search did not report an error while OpenFGA was down: ${DOWN}"
echo -e "${GREEN}  search answered an error and no documents${NC}"
compose start openfga >/dev/null
wait_until "OpenFGA healthy again" 180 sh -c '[ "$(docker inspect -f "{{.State.Health.Status}}" openfga 2>/dev/null)" = "healthy" ]'
wait_until "search recovers" 60 finds "henry" "hr" "$DOC_FINANCE"
pass "no results without OpenFGA, normal service when it returns"

# ==============================================================================
step 8 "Authorization metrics"
COUNT=$(curl -s --max-time 10 "${BASE}/actuator/metrics/oc.authz.candidates" | jq -r '.measurements[0].value // 0')
ERRORS=$(curl -s --max-time 10 "${BASE}/actuator/metrics/oc.authz.errors" | jq -r '.measurements[0].value // 0')
awk "BEGIN {exit !(${COUNT} > 0)}" || fail "oc.authz.candidates not published (${COUNT})"
awk "BEGIN {exit !(${ERRORS} >= 1)}" || fail "the outage was not counted in oc.authz.errors (${ERRORS})"
echo -e "${GREEN}  oc.authz.candidates=${COUNT} oc.authz.errors=${ERRORS}${NC}"
pass "metrics published"

rm -f "$MCP_HEADERS"
echo -e "\n${BOLD}${GREEN}=== OpenFGA decoupled end-to-end test passed (${PASSED}/${TOTAL}) ===${NC}"
