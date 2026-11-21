#!/usr/bin/env bash
#
# Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
# OpenCrawling - Google Drive Repository Connector Test Suite
#
# Starts the lightweight Google Drive REST API v3 emulator on a local port,
# validates the Drive v3 & OAuth2 HTTP contract, and executes the Maven unit
# and runtime wiring tests for `oc-googledrive-repository-connector`.
# ==============================================================================

set -euo pipefail

MOCK_PORT="${MOCK_PORT:-${GOOGLEDRIVE_MOCK_PORT:-18490}}"
MOCK_URL="http://localhost:${MOCK_PORT}"
MOCK_TOKEN="${GOOGLEDRIVE_MOCK_TOKEN:-mock-gdrive-access-token}"
MOCK_PID=""
MODULE="oc-googledrive-repository-connector"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

echo -e "${YELLOW}=== Starting OpenCrawling Google Drive Repository Connector Test Suite ===${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

command -v curl >/dev/null 2>&1 || { log_error "curl is required."; exit 1; }
command -v python3 >/dev/null 2>&1 || { log_error "python3 is required."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "mvn is required."; exit 1; }

stop_mock() {
  if [ -n "${MOCK_PID}" ] && kill -0 "${MOCK_PID}" 2>/dev/null; then
    kill "${MOCK_PID}" 2>/dev/null || true
    wait "${MOCK_PID}" 2>/dev/null || true
  fi
}
trap stop_mock EXIT

mkdir -p "${MODULE}/target"
log_info "Starting Google Drive API v3 mock on ${MOCK_URL}..."
GOOGLEDRIVE_PORT="${MOCK_PORT}" GOOGLEDRIVE_QUIET=true GOOGLEDRIVE_MOCK_TOKEN="${MOCK_TOKEN}" \
  python3 "${MODULE}/googledrive-mock/googledrive_mock.py" \
  > "${MODULE}/target/googledrive-mock.log" 2>&1 &
MOCK_PID=$!

for _ in $(seq 1 40); do
  if curl -sf -o /dev/null --max-time 2 "${MOCK_URL}/mock/stats" 2>/dev/null; then
    break
  fi
  sleep 0.25
done

if ! curl -sf -o /dev/null --max-time 2 "${MOCK_URL}/mock/stats"; then
  log_error "Google Drive mock failed to start."
  cat "${MODULE}/target/googledrive-mock.log" || true
  exit 1
fi
log_success "Google Drive mock is up: $(curl -s "${MOCK_URL}/mock/stats")"

# Contract smoke checks against the local mock
log_info "Running Google Drive API v3 contract smoke checks..."
UNAUTH_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${MOCK_URL}/drive/v3/about")
[ "${UNAUTH_CODE}" = "401" ] || { log_error "Expected 401 without Bearer token, got ${UNAUTH_CODE}"; exit 1; }

curl -sf -X POST -d "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=test" \
  "${MOCK_URL}/token" | grep -q "${MOCK_TOKEN}"
curl -sf -H "Authorization: Bearer ${MOCK_TOKEN}" "${MOCK_URL}/drive/v3/about" | grep -q "crawler@opencrawling"
curl -sf -H "Authorization: Bearer ${MOCK_TOKEN}" "${MOCK_URL}/drive/v3/drives" | grep -q "0AO_SUPPORT"
curl -sf -H "Authorization: Bearer ${MOCK_TOKEN}" "${MOCK_URL}/drive/v3/changes/startPageToken" | grep -q "100"
curl -sf -H "Authorization: Bearer ${MOCK_TOKEN}" "${MOCK_URL}/drive/v3/files/doc-reset/export?mimeType=text/html" | grep -q "<h1>Password Reset</h1>"
curl -sf -H "Authorization: Bearer ${MOCK_TOKEN}" "${MOCK_URL}/drive/v3/files/pdf-manual?alt=media" | grep -q "Hardware Manual"
log_success "Contract smoke checks passed."

# Run connector unit & HTTP mock tests
log_info "Running Maven unit tests for ${MODULE}..."
mvn test -pl "${MODULE}" -am ${MAVEN_ARGS:-}

# Run runtime SPI, factory, checker, secret masking, UI parity, and multi-replica control-plane tests
log_info "Running OpenCrawling Runtime Google Drive wiring tests..."
mvn test -pl oc-runtime -am \
  -Dtest=ConnectorKindTest,ConnectorFactoryTest,ConnectorCheckerServiceTest,ControlPlaneGoogleDriveStateStoreTest,PlatformApiV1UnitTest,PlatformApiV1UiParityTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  ${MAVEN_ARGS:-}

log_success "All Google Drive Repository Connector tests passed successfully!"
