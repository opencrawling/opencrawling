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
# OpenCrawling - Zammad Repository Connector Integration Test Script
#
# Description:
#   Dedicated integration test script for the Zammad Repository Connector
#   (`oc-zammad-repository-connector`). Because a real Zammad deployment
#   requires a full multi-container stack (Rails, PostgreSQL, Redis, Memcached,
#   and Elasticsearch for /api/v1/tickets/search), the connector is tested
#   against the lightweight Zammad REST API v1 emulator
#   (`oc-zammad-repository-connector/zammad-mock/zammad_mock.py`, stdlib Python 3).
#
#   The script:
#     1. optionally probes a live Zammad instance (read-only) when ZAMMAD_URL
#        and ZAMMAD_ACCESS_TOKEN are set;
#     2. starts the Zammad REST API v1 mock on a local port and validates the
#        Zammad REST API v1 contract directly (Token, OAuth2 Bearer, Basic auth,
#        HTTP 401 rejection, tickets, search envelope, articles, tags,
#        attachments, and Knowledge Base init/answers);
#     3. runs the Maven test suite for `oc-zammad-repository-connector` with
#        ZAMMAD_MOCK_URL set so unit tests and over-HTTP scenario tests
#        (`ZammadMockServerTest`) run against the emulator;
#     4. validates OpenCrawling Runtime SPI wiring for Zammad
#        (`ConnectorKindTest`, `ConnectorFactoryTest`, `ConnectorCheckerServiceTest`).
#
#   In CI (CI=true) a missing python3 or a mock that fails to start is a hard
#   failure; locally it logs a warning and runs the embedded unit tests.
# ==============================================================================

set -euo pipefail

# Configuration
MOCK_PORT="${MOCK_PORT:-${ZAMMAD_MOCK_PORT:-18390}}"
MOCK_URL="http://localhost:${MOCK_PORT}"
MOCK_PID=""
REQUIRE_MOCK="${REQUIRE_MOCK:-${CI:-false}}"
TIMEOUT="${TIMEOUT:-30}"
MODULE="oc-zammad-repository-connector"

# Optional live Zammad read-only probe settings
ZAMMAD_URL="${ZAMMAD_URL:-}"
ZAMMAD_ACCESS_TOKEN="${ZAMMAD_ACCESS_TOKEN:-}"

# Terminal Formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
skip_or_fail() {
  if [ "${REQUIRE_MOCK}" = true ]; then
    log_error "$1"
    exit 1
  fi
  log_warn "$1"
}

echo -e "${YELLOW}=== Starting OpenCrawling Zammad Repository Connector Test Suite ===${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (curl, jq, mvn)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

# 2. Optional read-only probe against a live Zammad instance
if [ -n "${ZAMMAD_URL}" ] && [ -n "${ZAMMAD_ACCESS_TOKEN}" ]; then
  LIVE_BASE="${ZAMMAD_URL%/}"
  log_info "Probing live Zammad REST API v1 at ${LIVE_BASE}/api/v1/users/me (read-only)..."
  LIVE_RES=$(curl -s -w "\n%{http_code}" --connect-timeout 5 --max-time "${TIMEOUT}" \
    -H "Authorization: Token token=${ZAMMAD_ACCESS_TOKEN}" \
    -H "Accept: application/json" \
    "${LIVE_BASE}/api/v1/users/me" 2>/dev/null || printf "\n000")
  LIVE_CODE=$(echo "${LIVE_RES}" | tail -n 1)
  LIVE_BODY=$(echo "${LIVE_RES}" | sed '$d')
  if [ "${LIVE_CODE}" = "200" ]; then
    LIVE_USER=$(echo "${LIVE_BODY}" | jq -r '.email // .login // "authenticated"')
    log_success "Live Zammad instance reachable at ${LIVE_BASE} (authenticated as '${LIVE_USER}')."
  else
    log_warn "Live Zammad probe at ${LIVE_BASE} returned HTTP ${LIVE_CODE}; continuing with local emulator suite."
  fi
else
  log_info "ZAMMAD_URL / ZAMMAD_ACCESS_TOKEN not set; running against the local Zammad REST API v1 emulator."
fi

# 3. Start the Zammad REST API v1 mock (only this script's own process is stopped on exit)
stop_mock() {
  if [ -n "${MOCK_PID}" ] && kill -0 "${MOCK_PID}" 2>/dev/null; then
    kill "${MOCK_PID}" 2>/dev/null || true
    wait "${MOCK_PID}" 2>/dev/null || true
    log_info "Zammad REST mock (pid ${MOCK_PID}) stopped."
  fi
  MOCK_PID=""
}
trap stop_mock EXIT

MOCK_OK=false
if command -v python3 >/dev/null 2>&1; then
  if curl -s -o /dev/null --max-time 2 "${MOCK_URL}/mock/stats" 2>/dev/null; then
    log_error "Something already answers on port ${MOCK_PORT}; set MOCK_PORT to a free port."
    exit 1
  fi
  mkdir -p "${MODULE}/target"
  log_info "Starting the Zammad REST API v1 mock on ${MOCK_URL} (log: ${MODULE}/target/zammad-mock.log)..."
  ZAMMAD_PORT="${MOCK_PORT}" ZAMMAD_QUIET=true \
    python3 "${MODULE}/zammad-mock/zammad_mock.py" \
    > "${MODULE}/target/zammad-mock.log" 2>&1 &
  MOCK_PID=$!

  for _ in $(seq 1 60); do
    if curl -sf -o /dev/null --max-time 2 "${MOCK_URL}/mock/stats" 2>/dev/null; then
      MOCK_OK=true
      break
    fi
    if ! kill -0 "${MOCK_PID}" 2>/dev/null; then
      break
    fi
    sleep 0.25
  done

  if [ "${MOCK_OK}" = true ]; then
    export ZAMMAD_MOCK_URL="${MOCK_URL}"
    log_success "Zammad REST API v1 mock is up (pid ${MOCK_PID}): $(curl -s "${MOCK_URL}/mock/stats" | jq -c '{tickets, articles, attachments, activeKbAnswers, expectedDocuments}')"
  else
    stop_mock
    cat "${MODULE}/target/zammad-mock.log" 2>/dev/null || true
    skip_or_fail "The Zammad REST mock did not start (see ${MODULE}/target/zammad-mock.log); over-HTTP scenario tests are skipped."
  fi
else
  skip_or_fail "python3 not found: over-HTTP Zammad mock scenario tests are skipped."
fi

# 4. Direct Zammad REST API v1 Contract Smoke Checks
if [ "${MOCK_OK}" = true ]; then
  log_info "Validating Zammad REST API v1 contract against ${MOCK_URL}..."

  # Token Auth
  ME_EMAIL=$(curl -sf -H "Authorization: Token token=zammad-test-token" -H "Accept: application/json" \
    "${MOCK_URL}/api/v1/users/me" | jq -r '.email // empty')
  [ "${ME_EMAIL}" = "crawler@acme.com" ] || { log_error "Token auth /api/v1/users/me failed: got '${ME_EMAIL}'"; exit 1; }

  # OAuth2 Bearer Auth
  OAUTH_EMAIL=$(curl -sf -H "Authorization: Bearer zammad-oauth-token" -H "Accept: application/json" \
    "${MOCK_URL}/api/v1/users/me" | jq -r '.email // empty')
  [ "${OAUTH_EMAIL}" = "crawler@acme.com" ] || { log_error "OAuth2 auth /api/v1/users/me failed: got '${OAUTH_EMAIL}'"; exit 1; }

  # Basic Auth
  BASIC_EMAIL=$(curl -sf -u "crawler@acme.com:secret" -H "Accept: application/json" \
    "${MOCK_URL}/api/v1/users/me" | jq -r '.email // empty')
  [ "${BASIC_EMAIL}" = "crawler@acme.com" ] || { log_error "Basic auth /api/v1/users/me failed: got '${BASIC_EMAIL}'"; exit 1; }

  # Invalid token must return HTTP 401
  BAD_CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Token token=invalid" "${MOCK_URL}/api/v1/users/me")
  [ "${BAD_CODE}" = "401" ] || { log_error "Expected HTTP 401 for invalid token, got ${BAD_CODE}"; exit 1; }

  # Groups, tickets, search envelope, articles, tags, attachment download, and KB endpoints
  GROUP_COUNT=$(curl -sf -H "Authorization: Token token=zammad-test-token" "${MOCK_URL}/api/v1/groups" | jq 'length')
  [ "${GROUP_COUNT}" -ge 2 ] || { log_error "Expected >=2 groups, got ${GROUP_COUNT}"; exit 1; }

  SEARCH_COUNT=$(curl -sf -H "Authorization: Token token=zammad-test-token" \
    "${MOCK_URL}/api/v1/tickets/search?query=*&sort_by=updated_at&order_by=asc&page=1&per_page=50&expand=true" \
    | jq '.assets.Ticket | length')
  [ "${SEARCH_COUNT}" -eq 4 ] || { log_error "Expected 4 tickets in search assets envelope, got ${SEARCH_COUNT}"; exit 1; }

  ATT_TEXT=$(curl -sf -H "Authorization: Token token=zammad-test-token" \
    "${MOCK_URL}/api/v1/ticket_attachment/4821/1001/501")
  echo "${ATT_TEXT}" | grep -q "ZMD-4821-ATT-PUB" || { log_error "Unexpected attachment payload: ${ATT_TEXT}"; exit 1; }

  KB_INIT_ANS=$(curl -sf -X POST -H "Authorization: Token token=zammad-test-token" -H "Content-Type: application/json" -d '{}' \
    "${MOCK_URL}/api/v1/knowledge_bases/init" | jq '.KnowledgeBaseAnswer | length')
  [ "${KB_INIT_ANS}" -ge 2 ] || { log_error "Expected >=2 KB answers in POST /knowledge_bases/init, got ${KB_INIT_ANS}"; exit 1; }

  KB_ANS_BODY=$(curl -sf -H "Authorization: Token token=zammad-test-token" \
    "${MOCK_URL}/api/v1/knowledge_bases/1/answers/78?include_contents=7800" \
    | jq -r '.assets.KnowledgeBaseAnswerTranslationContent["7800"].body // empty')
  echo "${KB_ANS_BODY}" | grep -q "ZMD-KB-78-INT" || { log_error "Unexpected KB answer payload: ${KB_ANS_BODY}"; exit 1; }

  # Reset mock counters before Maven test execution
  curl -sf -X POST -H "Content-Type: application/json" -d '{}' "${MOCK_URL}/mock/reset" >/dev/null
  log_success "Zammad REST API v1 contract smoke checks passed."
fi

# 5. Run Maven Test Suite for oc-zammad-repository-connector (unit + ZammadMockServerTest)
log_info "Executing Maven test suite for ${MODULE}..."
mvn test -pl "${MODULE}" -am

# 6. Validate OpenCrawling Runtime Zammad SPI, ConnectorFactory & Connection Checker wiring
log_info "Validating OpenCrawling Runtime wiring for Zammad (ConnectorKindTest, ConnectorFactoryTest, ConnectorCheckerServiceTest)..."
mvn test -pl oc-runtime -am -Dtest=ConnectorKindTest,ConnectorFactoryTest,ConnectorCheckerServiceTest -Dsurefire.failIfNoSpecifiedTests=false

log_success "All Zammad Repository Connector tests passed successfully!"
