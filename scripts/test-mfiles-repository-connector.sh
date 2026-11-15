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
# OpenCrawling - M-Files Repository Connector Integration Test Script
#
# Description:
#   Probes an M-Files Web Service (MFWS) when one is reachable (unauthenticated
#   GET /server/status, read-only) and runs the Maven test suite of
#   oc-mfiles-repository-connector (MockWebServer with MFWS response fixtures).
#   M-Files is a licensed product: this script never writes to a real server.
#   When python3 is available it also starts the MFWS mock (an M-Files Web
#   Service emulator, oc-mfiles-repository-connector/mfiles-mock) on a local
#   port and runs the scenario tests against it over real HTTP. For a read-only
#   check against a real vault, see 'Read-only live probe' in
#   oc-mfiles-repository-connector/README.md.
# ==============================================================================

set -euo pipefail

# Configuration
MFILES_URL="${MFILES_URL:-http://localhost/REST}"
TIMEOUT="${TIMEOUT:-10}"
MOCK_PORT="${MOCK_PORT:-18089}"
MOCK_PID=""

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

echo -e "${YELLOW}=== Starting OpenCrawling M-Files Repository Connector Test Suite ===${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (curl, mvn)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

# 2. Check if a live M-Files Web Service is reachable (read-only, no login)
log_info "Probing M-Files Web Service at ${MFILES_URL}..."
STATUS_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time "${TIMEOUT}" \
  -H "Accept: application/json" "${MFILES_URL}/server/status" 2>/dev/null || true)
if [ "${STATUS_CODE}" = "200" ]; then
  log_success "Live M-Files Web Service is reachable at ${MFILES_URL}!"
  log_info "For a read-only live crawl, see 'Read-only live probe' in oc-mfiles-repository-connector/README.md."
else
  log_warn "No M-Files Web Service is reachable at ${MFILES_URL} (HTTP ${STATUS_CODE:-none})."
  log_info "Will execute the isolated embedded mock HTTP server test suite."
fi

# 3. Start the MFWS mock (only this script's own process is stopped afterwards)
stop_mock() {
  if [ -n "${MOCK_PID}" ] && kill -0 "${MOCK_PID}" 2>/dev/null; then
    kill "${MOCK_PID}" 2>/dev/null || true
    log_info "MFWS mock (pid ${MOCK_PID}) stopped."
  fi
}
trap stop_mock EXIT

if command -v python3 >/dev/null 2>&1; then
  log_info "Starting the MFWS mock on port ${MOCK_PORT}..."
  mkdir -p oc-mfiles-repository-connector/target
  MFWS_PORT="${MOCK_PORT}" MFWS_QUIET=true MFWS_BEARER_TOKENS=tok-oauth \
    python3 oc-mfiles-repository-connector/mfiles-mock/mfws_mock.py > oc-mfiles-repository-connector/target/mfws-mock.log 2>&1 &
  MOCK_PID=$!
  for _ in $(seq 1 40); do
    curl -s -o /dev/null "http://localhost:${MOCK_PORT}/REST/server/status" && break
    sleep 0.25
  done
  if curl -s -o /dev/null "http://localhost:${MOCK_PORT}/REST/server/status"; then
    log_success "MFWS mock is up (pid ${MOCK_PID}); the scenario tests run against it."
    export MFILES_MOCK_URL="http://localhost:${MOCK_PORT}"
  else
    log_warn "The MFWS mock did not start (see oc-mfiles-repository-connector/target/mfws-mock.log); the scenario tests are skipped."
    stop_mock
  fi
else
  log_warn "python3 not found: the MFWS mock scenario tests are skipped."
fi

# 4. Run Maven Test Suite
log_info "Executing Maven test suite for oc-mfiles-repository-connector..."
mvn test -pl oc-mfiles-repository-connector -am

log_success "All M-Files Repository Connector tests passed successfully!"
