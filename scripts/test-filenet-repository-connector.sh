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
# OpenCrawling - IBM FileNet P8 Repository Connector Integration Test Script
#
# Description:
#   IBM FileNet P8 has no free container image, so the connector is tested
#   against the CSGQL mock (oc-filenet-repository-connector/filenet-mock), a
#   GraphQL server over a subset of IBM's published Content Services GraphQL
#   schema. The script:
#     1. creates the mock's Python environment (graphql-core) under target/;
#     2. runs the conformance check: the mock's schema and every GraphQL
#        document the connector sends against IBM's published schema;
#     3. starts the mock on a local port and runs the module's Maven tests,
#        including the scenario tests against the mock over real HTTP.
#   In CI (CI=true) a missing Python environment, mock or conformance check is
#   a failure; locally it is a warning.
# ==============================================================================

set -euo pipefail

MOCK_PORT="${MOCK_PORT:-18190}"
MOCK_PID=""
REQUIRE_MOCK="${REQUIRE_MOCK:-${CI:-false}}"
MODULE="oc-filenet-repository-connector"
VENV="${MODULE}/target/csgql-venv"

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

echo -e "${YELLOW}=== Starting OpenCrawling IBM FileNet P8 Repository Connector Test Suite ===${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency checks
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

stop_mock() {
  if [ -n "${MOCK_PID}" ] && kill -0 "${MOCK_PID}" 2>/dev/null; then
    kill "${MOCK_PID}" 2>/dev/null || true
    log_info "CSGQL mock (pid ${MOCK_PID}) stopped."
  fi
}
trap stop_mock EXIT

# 2. The mock's Python environment
PYTHON=""
mkdir -p "${MODULE}/target"
if command -v python3 >/dev/null 2>&1; then
  if [ ! -x "${VENV}/bin/python" ]; then
    log_info "Creating the CSGQL mock's Python environment in ${VENV}..."
    python3 -m venv "${VENV}" >/dev/null 2>&1 || true
  fi
  if [ -x "${VENV}/bin/python" ] && "${VENV}/bin/python" -m pip install -q -r "${MODULE}/filenet-mock/requirements.txt" \
      > "${MODULE}/target/csgql-pip.log" 2>&1; then
    PYTHON="${VENV}/bin/python"
    log_success "Python environment ready ($("${PYTHON}" -c 'import graphql; print("graphql-core", graphql.__version__)'))."
  else
    skip_or_fail "Could not set up the mock's Python environment (see ${MODULE}/target/csgql-pip.log)."
  fi
else
  skip_or_fail "python3 not found: the conformance check and the mock scenario tests are skipped."
fi

# 3. Conformance against IBM's published schema
if [ -n "${PYTHON}" ]; then
  log_info "Checking the mock's schema and the connector's GraphQL documents against IBM's published schema..."
  set +e
  "${PYTHON}" "${MODULE}/filenet-mock/conformance.py"
  CONFORMANCE=$?
  set -e
  case "${CONFORMANCE}" in
    0) log_success "Conformant with IBM's Content Services GraphQL schema." ;;
    2) skip_or_fail "IBM's schema could not be downloaded; the conformance check is skipped." ;;
    *) log_error "Not conformant with IBM's schema."; exit 1 ;;
  esac
fi

# 4. Start the mock (only this script's own process is stopped afterwards)
if [ -n "${PYTHON}" ]; then
  log_info "Starting the CSGQL mock on port ${MOCK_PORT}..."
  CSGQL_PORT="${MOCK_PORT}" CSGQL_QUIET=true "${PYTHON}" "${MODULE}/filenet-mock/csgql_mock.py" \
    > "${MODULE}/target/csgql-mock.log" 2>&1 &
  MOCK_PID=$!
  for _ in $(seq 1 60); do
    curl -s -o /dev/null "http://localhost:${MOCK_PORT}/content-services-graphql/ping" && break
    sleep 0.25
  done
  if curl -s -o /dev/null "http://localhost:${MOCK_PORT}/content-services-graphql/ping"; then
    log_success "CSGQL mock is up (pid ${MOCK_PID}); the scenario tests run against it."
    export CSGQL_MOCK_URL="http://localhost:${MOCK_PORT}"
  else
    stop_mock
    skip_or_fail "The CSGQL mock did not start (see ${MODULE}/target/csgql-mock.log); the scenario tests are skipped."
  fi
fi

# 5. Maven test suite
log_info "Executing Maven test suite for ${MODULE}..."
mvn test -pl "${MODULE}" -am

log_success "All IBM FileNet P8 Repository Connector tests passed successfully!"
