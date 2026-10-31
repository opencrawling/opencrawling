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
# OpenCrawling - OpenText Documentum Repository Connector Integration Test Script
#
# Description:
#   OpenText Documentum has no free container image, so the connector is
#   tested against the DCTM REST mock
#   (oc-documentum-repository-connector/documentum-mock): a Documentum REST
#   Services emulator with a DQL engine over typed object tables and
#   Documentum's own permission evaluation, which the security tests use as
#   ground truth. It needs only python3 (standard library). The script:
#     1. starts the mock with a non-UTC server time zone;
#     2. checks the mock against OpenText's published OpenAPI document for the
#        Documentum REST API (downloaded from developer.opentext.com; needs
#        PyYAML, which CI installs: a missing PyYAML fails in CI, an
#        unreachable portal is only a warning);
#     3. runs the module's Maven tests, including the scenario tests against
#        the mock;
#     4. restarts it as a server in another time zone that silently caps every
#        DQL page at 5 rows, and runs the scenario tests again.
#   In CI (CI=true) a missing python3 or a mock that does not start is a
#   failure; locally it is a warning.
# ==============================================================================

set -euo pipefail

MOCK_PORT="${MOCK_PORT:-18290}"
MOCK_PID=""
REQUIRE_MOCK="${REQUIRE_MOCK:-${CI:-false}}"
MODULE="oc-documentum-repository-connector"

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

echo -e "${YELLOW}=== Starting OpenCrawling OpenText Documentum Repository Connector Test Suite ===${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency checks
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

stop_mock() {
  if [ -n "${MOCK_PID}" ] && kill -0 "${MOCK_PID}" 2>/dev/null; then
    kill "${MOCK_PID}" 2>/dev/null || true
    wait "${MOCK_PID}" 2>/dev/null || true
    log_info "DCTM REST mock (pid ${MOCK_PID}) stopped."
  fi
  MOCK_PID=""
}
trap stop_mock EXIT

# Starts the mock with extra environment (only this script's own process is stopped afterwards).
start_mock() {
  mkdir -p "${MODULE}/target"
  env DCTM_PORT="${MOCK_PORT}" DCTM_QUIET=true "$@" python3 "${MODULE}/documentum-mock/dctm_mock.py" \
    > "${MODULE}/target/dctm-mock.log" 2>&1 &
  MOCK_PID=$!
  for _ in $(seq 1 60); do
    curl -s -o /dev/null "http://localhost:${MOCK_PORT}/dctm-rest/services" && return 0
    sleep 0.25
  done
  return 1
}

# 2. The mock as a server two hours east of UTC, and the full Maven test suite
MOCK_OK=false
if command -v python3 >/dev/null 2>&1; then
  log_info "Starting the DCTM REST mock on port ${MOCK_PORT} (server time zone +02:00)..."
  if start_mock DCTM_TZ_OFFSET=+02:00; then
    MOCK_OK=true
    export DCTM_MOCK_URL="http://localhost:${MOCK_PORT}"
    log_success "DCTM REST mock is up (pid ${MOCK_PID}); the scenario tests run against it."
  else
    stop_mock
    skip_or_fail "The DCTM REST mock did not start (see ${MODULE}/target/dctm-mock.log); the scenario tests are skipped."
  fi
else
  skip_or_fail "python3 not found: the mock scenario tests are skipped."
fi

# 3. The mock against OpenText's published OpenAPI document (downloaded from developer.opentext.com, not redistributed)
if [ "${MOCK_OK}" = true ]; then
  if python3 -c "import yaml" >/dev/null 2>&1; then
    log_info "Checking the mock against OpenText's published Documentum REST specification..."
    set +e
    python3 "${MODULE}/documentum-mock/conformance/check_spec.py" --mock "http://localhost:${MOCK_PORT}/dctm-rest"
    rc=$?
    set -e
    case ${rc} in
      0) log_success "The mock conforms to the published specification." ;;
      2) log_warn "The specification could not be downloaded; the conformance check is skipped." ;;
      *) log_error "The mock deviates from the published specification (see above)."; exit 1 ;;
    esac
  else
    skip_or_fail "PyYAML is not installed (pip install pyyaml): the conformance check against the published specification cannot run."
  fi
fi

log_info "Executing Maven test suite for ${MODULE}..."
mvn test -pl "${MODULE}" -am

# 4. A server five hours west of UTC that caps every DQL page at 5 rows instead of refusing larger pages
if [ "${MOCK_OK}" = true ]; then
  stop_mock
  log_info "Restarting the mock at -05:00 with items-per-page silently capped at 5..."
  if start_mock DCTM_TZ_OFFSET=-05:00 DCTM_MAX_PAGE_SIZE=5 DCTM_PAGE_OVERSIZE=cap; then
    mvn test -pl "${MODULE}" -am -Dtest=DocumentumMockServerTest -Dsurefire.failIfNoSpecifiedTests=false
    log_success "Scenario tests passed against a capped, west-of-UTC server too."
  else
    skip_or_fail "The DCTM REST mock did not restart (see ${MODULE}/target/dctm-mock.log)."
  fi
fi

log_success "All OpenText Documentum Repository Connector tests passed successfully!"
