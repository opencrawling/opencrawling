#!/usr/bin/env bash
#
# Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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


# End-to-end check of the Platform API (/api/v1, #313) against a real runtime.
#
#   scripts/test-platform-api-v1.sh                      boot the decoupled stack, check, tear down (CI)
#   scripts/test-platform-api-v1.sh --url http://host:8080 [--token TOKEN] [--crawl-path /dir]
#                                                        check a runtime that is already running
#
# With --url the script creates one job (and deletes it at the end); it starts it only with --crawl-path, a directory
# the runtime can read. A token can also come from the PLATFORM_API_TOKEN environment variable.
set -euo pipefail

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

BASE_URL=""
TOKEN="${PLATFORM_API_TOKEN:-}"
CRAWL_PATH=""
while [ $# -gt 0 ]; do
  case "$1" in
    --url) BASE_URL="${2%/}"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --crawl-path) CRAWL_PATH="$2"; shift 2 ;;
    -h|--help) sed -n '18,26p' "$0"; exit 0 ;;
    *) echo -e "${RED}Unknown option: $1${NC}"; exit 2 ;;
  esac
done

for tool in curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo -e "${RED}[ERROR] $tool is required.${NC}"; exit 1; }
done

COMPOSE_FILE="docker-compose-decoupled.yml"
compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

if [ -z "$BASE_URL" ]; then
  BASE_URL="http://localhost:8080"
  BOOTED=true
  export COMPOSE_BAKE=false
  cleanup() {
    echo -e "${CYAN}[INFO] Tearing down the stack...${NC}"
    compose down --remove-orphans >/dev/null 2>&1 || true
  }
  trap cleanup EXIT
  echo -e "${CYAN}[INFO] Building and starting the decoupled stack...${NC}"
  compose down --remove-orphans >/dev/null 2>&1 || true
  if [ "${SKIP_BUILD:-false}" != "true" ]; then
    compose build
  fi
  compose up -d
else
  BOOTED=false
fi

echo -e "${YELLOW}=== Platform API v1 end-to-end check against ${BASE_URL} ===${NC}"

AUTH=()
if [ -n "$TOKEN" ]; then
  AUTH=(-H "Authorization: Bearer ${TOKEN}")
fi

FAILURES=0
pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAILURES=$((FAILURES + 1)); }

# request METHOD PATH [BODY] [extra curl args...] -> sets STATUS, BODY, HEADERS
request() {
  local method="$1" path="$2" body="${3:-}"
  shift 3 || shift $#
  local headers_file body_file
  headers_file=$(mktemp); body_file=$(mktemp)
  local args=(-s -X "$method" -D "$headers_file" -o "$body_file" -w "%{http_code}" ${AUTH[@]+"${AUTH[@]}"} "$@")
  if [ -n "$body" ]; then
    args+=(-H "Content-Type: application/json" --data "$body")
  fi
  STATUS=$(curl "${args[@]}" "${BASE_URL}${path}" || echo "000")
  BODY=$(cat "$body_file"); HEADERS=$(cat "$headers_file")
  rm -f "$headers_file" "$body_file"
}
header() { echo "$HEADERS" | tr -d '\r' | awk -v name="$(echo "$1" | tr '[:upper:]' '[:lower:]')" -F': ' 'tolower($1)==name {print $2; exit}'; }
expect() { # expect STATUS DESCRIPTION
  if [ "$STATUS" = "$1" ]; then pass "$2 ($STATUS)"; else fail "$2: expected $1, got $STATUS: $(echo "$BODY" | head -c 300)"; fi
}

echo -e "${CYAN}[INFO] Waiting for the API...${NC}"
for _ in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w "%{http_code}" ${AUTH[@]+"${AUTH[@]}"} "${BASE_URL}/api/v1/version" || echo "000")
  [ "$code" = "200" ] && break
  sleep 3
done
if [ "$code" != "200" ]; then
  echo -e "${RED}[ERROR] ${BASE_URL}/api/v1/version answered ${code}.${NC}"
  [ "$BOOTED" = true ] && compose logs --tail=80 oc-mcp-server || true
  exit 1
fi

if [ "$BOOTED" = true ] && [ -z "$CRAWL_PATH" ]; then
  CRAWL_PATH="/tmp/platform-api-v1"
  docker exec oc-mcp-server-service sh -c "mkdir -p $CRAWL_PATH && echo 'Platform API v1 end-to-end check.' > $CRAWL_PATH/check.txt"
fi

# --- Platform ---
request GET /api/v1 ""
expect 200 "GET /api/v1 (index)"
[ "$(echo "$BODY" | jq -r '.links.jobs')" = "/api/v1/jobs" ] && pass "index links the jobs" || fail "index has no jobs link"
request GET /api/v1/version ""
expect 200 "GET /api/v1/version"
request GET /v3/api-docs/v1 ""
expect 200 "GET /v3/api-docs/v1 (OpenAPI)"
[ "$(echo "$BODY" | jq -r '.info.title')" = "OpenCrawling Platform API" ] && pass "OpenAPI title" || fail "OpenAPI title"
request GET /api/v1/me ""
expect 200 "GET /api/v1/me"

# --- Connectors ---
request GET "/api/v1/connectors?type=repository&limit=500" ""
expect 200 "GET /api/v1/connectors?type=repository"
echo "$BODY" | jq -e '.items[] | select(.name=="FileSystem_Local")' >/dev/null && pass "FileSystem_Local is listed" || fail "FileSystem_Local missing"
request GET "/api/v1/connectors/OpenAI_Embedding_Prod" ""
if [ "$STATUS" = "200" ] && echo "$BODY" | jq -e '.configuration == null or .configuration.apiKey == "********"' >/dev/null; then
  pass "connector secrets are masked"
else
  fail "connector secrets: $STATUS $(echo "$BODY" | head -c 200)"
fi

# --- Jobs ---
NAME="platform-api-v1-check-$(date +%s)"
JOB="{\"name\":\"$NAME\",\"repositoryConnector\":\"FileSystem_Local\",\"outputConnector\":\"PGVector_Output\",\"path\":\"${CRAWL_PATH:-/tmp}\",\"pipelineMode\":\"rag\"}"
KEY="create-$NAME"
request POST /api/v1/jobs "$JOB" -H "Idempotency-Key: $KEY"
expect 201 "POST /api/v1/jobs"
JOB_ID=$(echo "$BODY" | jq -r '.id')
[ "$(header Location)" = "/api/v1/jobs/$JOB_ID" ] && pass "Location of the new job" || fail "Location: $(header Location)"
request POST /api/v1/jobs "$JOB" -H "Idempotency-Key: $KEY"
[ "$(echo "$BODY" | jq -r '.id')" = "$JOB_ID" ] && [ "$(header Idempotent-Replayed)" = "true" ] \
  && pass "a retry with the same Idempotency-Key returns the same job" || fail "idempotent replay: $STATUS $(header Idempotent-Replayed)"

request GET "/api/v1/jobs/$JOB_ID" ""
expect 200 "GET /api/v1/jobs/{id}"
ETAG=$(header ETag)
request GET "/api/v1/jobs/$JOB_ID" "" -H "If-None-Match: $ETAG"
expect 304 "GET with If-None-Match"
request PATCH "/api/v1/jobs/$JOB_ID" "" -H "Content-Type: application/merge-patch+json" -H "If-Match: $ETAG" --data "{\"name\":\"$NAME-renamed\"}"
expect 200 "PATCH (merge patch) with If-Match"
request PATCH "/api/v1/jobs/$JOB_ID" "" -H "Content-Type: application/merge-patch+json" -H "If-Match: $ETAG" --data "{\"name\":\"stale\"}"
expect 412 "PATCH with a stale If-Match"
request GET "/api/v1/jobs?q=$NAME&limit=1" ""
expect 200 "GET /api/v1/jobs?q=...&limit=1"

if [ -n "$CRAWL_PATH" ]; then
  request POST "/api/v1/jobs/$JOB_ID:start" "" -H "Idempotency-Key: start-$NAME" -H "X-Correlation-Id: platform-api-check"
  expect 202 "POST /api/v1/jobs/{id}:start"
  EXECUTION_ID=$(echo "$BODY" | jq -r '.executionId')
  echo -e "${CYAN}[INFO] Run ${EXECUTION_ID} started; waiting for it to end...${NC}"
  STATE="Running"
  for _ in $(seq 1 60); do
    request GET "/api/v1/jobs/$JOB_ID" ""
    STATE=$(echo "$BODY" | jq -r '.state.status')
    [ "$STATE" != "Running" ] && break
    sleep 3
  done
  STAGE=$(echo "$BODY" | jq -r '.state.stage')
  if [ "$STATE" = "Finished" ] || [ "$STATE" = "Error" ]; then pass "the run ended: $STATE / $STAGE"; else fail "the run did not end: $STATE / $STAGE"; fi
  for _ in $(seq 1 20); do
    request GET "/api/v1/executions?jobId=$JOB_ID" ""
    [ "$(echo "$BODY" | jq -r '.page.totalItems')" != "0" ] && break
    sleep 3
  done
  expect 200 "GET /api/v1/executions?jobId=..."
  if [ "$(echo "$BODY" | jq -r '.page.totalItems')" != "0" ]; then
    pass "the execution report is listed"
    request GET "/api/v1/executions/$EXECUTION_ID/documents?limit=10" ""
    expect 200 "GET /api/v1/executions/{id}/documents"
  else
    echo -e "${YELLOW}[WARN] No execution report for the run yet (reporting may be off in this runtime).${NC}"
  fi
fi

# --- Errors ---
request GET "/api/v1/jobs/does-not-exist-$NAME" ""
expect 404 "GET of an unknown job"
[ "$(echo "$BODY" | jq -r '.type')" = "https://github.com/opencrawling/opencrawling/wiki/Platform-API#problem-not-found" ] && [ -n "$(header X-Request-Id)" ] \
  && pass "errors are problem details with a request id" || fail "problem detail: $BODY"
request POST /api/v1/jobs "{\"name\":\"\"}"
expect 400 "POST of an invalid job"
echo "$BODY" | jq -e '.errors | length > 0' >/dev/null && pass "validation errors name the fields" || fail "no field errors"

# --- Clean up ---
request DELETE "/api/v1/jobs/$JOB_ID" ""
expect 204 "DELETE /api/v1/jobs/{id}"

if [ "$FAILURES" -gt 0 ]; then
  echo -e "${RED}=== Platform API v1 check: ${FAILURES} failure(s) ===${NC}"
  [ "$BOOTED" = true ] && compose logs --tail=80 oc-mcp-server || true
  exit 1
fi
echo -e "${GREEN}=== Platform API v1 check passed ===${NC}"
