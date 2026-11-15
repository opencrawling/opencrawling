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
# OpenCrawling - SharePoint Repository Connector Integration Test Script
#
# Description:
#   Starts the Graph mock (oc-sharepoint-repository-connector/graph-mock), an
#   Entra ID + Microsoft Graph emulator, on a local port and runs the Maven test
#   suite of oc-sharepoint-repository-connector with GRAPH_MOCK_URL set, so the
#   connector scenarios (SharePointMockServerTest) run over real HTTP next to the
#   MockWebServer unit tests. SharePoint Online is a cloud service: this script
#   never contacts a real tenant.
#
#   Conformance (CONFORMANCE=auto|true|false; auto = when uv is installed) checks
#   the emulator and the connector against Microsoft's own published artifacts,
#   pinned to commits so a run is reproducible:
#     - Microsoft's example payloads from the Graph documentation run through the
#       connector's code (GraphDocsExamplesTest);
#     - every recorded request and response is checked against Microsoft's Graph
#       v1.0 OpenAPI description, strictly (no undeclared property unless
#       Microsoft's examples show it), after the checker passes Microsoft's own
#       examples;
#     - the application permission the connector documents is checked against the
#       permission tables of every Graph API page it calls.
#   The spec (~45 MB) is cached in ~/.cache/opencrawling/graph-openapi.
# ==============================================================================

set -euo pipefail

# Configuration
GRAPH_MOCK_PORT="${GRAPH_MOCK_PORT:-18180}"
GRAPH_MOCK_URL="http://localhost:${GRAPH_MOCK_PORT}"
TIMEOUT="${TIMEOUT:-30}"
CONFORMANCE="${CONFORMANCE:-auto}"
# Microsoft's artifacts, pinned: microsoftgraph/msgraph-metadata (OpenAPI) and microsoftgraph/microsoft-graph-docs-contrib
GRAPH_OPENAPI_SHA="${GRAPH_OPENAPI_SHA:-1fd0ef510f79f3dadb3e083bdebc9496f939a11c}"
GRAPH_DOCS_SHA="${GRAPH_DOCS_SHA:-4b7867afe88f98d2415ac2f0219debe86fcb5652}"

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

echo -e "${YELLOW}=== Starting OpenCrawling SharePoint Repository Connector Test Suite ===${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (curl, python3, mvn)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v python3 >/dev/null 2>&1 || { log_error "python3 is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

if [ "${CONFORMANCE}" = auto ]; then
  if command -v uv >/dev/null 2>&1; then CONFORMANCE=true; else CONFORMANCE=false; log_warn "uv not found: skipping the conformance checks."; fi
fi

# 2. Start the Graph mock (stopped by its own PID on exit), recording its traffic
LOG_DIR="oc-sharepoint-repository-connector/target"
CONFORMANCE_DIR="$(pwd)/oc-sharepoint-repository-connector/target/conformance"
mkdir -p "${LOG_DIR}"
rm -rf "${CONFORMANCE_DIR}"
mkdir -p "${CONFORMANCE_DIR}"
if curl -s -o /dev/null --max-time 2 "${GRAPH_MOCK_URL}/mock/stats" 2>/dev/null; then
  log_error "Something already answers on port ${GRAPH_MOCK_PORT}; set GRAPH_MOCK_PORT to a free port."
  exit 1
fi
log_info "Starting the Graph mock on ${GRAPH_MOCK_URL} (log: ${LOG_DIR}/graph-mock.log)..."
GRAPH_PORT="${GRAPH_MOCK_PORT}" GRAPH_QUIET=true GRAPH_RECORD="${CONFORMANCE_DIR}/graph-traffic.jsonl" \
  python3 oc-sharepoint-repository-connector/graph-mock/graph_mock.py \
  >"${LOG_DIR}/graph-mock.log" 2>&1 &
MOCK_PID=$!
cleanup() {
  kill "${MOCK_PID}" 2>/dev/null || true
  wait "${MOCK_PID}" 2>/dev/null || true
}
trap cleanup EXIT

elapsed=0
until curl -sf -o /dev/null --max-time 2 "${GRAPH_MOCK_URL}/mock/stats" 2>/dev/null; do
  if ! kill -0 "${MOCK_PID}" 2>/dev/null || [ $elapsed -ge "$TIMEOUT" ]; then
    log_error "The Graph mock did not start."
    cat "${LOG_DIR}/graph-mock.log" || true
    exit 1
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done
log_success "Graph mock is up: $(curl -s "${GRAPH_MOCK_URL}/mock/stats" | tr -d '\n' | cut -c1-120)"

CHECKS="oc-sharepoint-repository-connector/graph-mock/conformance"
DOCS_EXAMPLES=""
if [ "${CONFORMANCE}" = true ]; then
  log_info "Fetching Microsoft's example payloads from the Graph documentation (${GRAPH_DOCS_SHA:0:12})..."
  python3 -I "${CHECKS}/fetch_doc_examples.py" --sha "${GRAPH_DOCS_SHA}" --out "${CONFORMANCE_DIR}/docs-examples"
  DOCS_EXAMPLES="${CONFORMANCE_DIR}/docs-examples"
fi

# 3. Run Maven Test Suite
log_info "Executing Maven test suite for oc-sharepoint-repository-connector (GRAPH_MOCK_URL=${GRAPH_MOCK_URL})..."
GRAPH_MOCK_URL="${GRAPH_MOCK_URL}" GRAPH_DOCS_EXAMPLES="${DOCS_EXAMPLES}" \
  mvn test -pl oc-sharepoint-repository-connector -am ${MAVEN_ARGS:-}

# 4. Conformance with Microsoft's published Graph artifacts
if [ "${CONFORMANCE}" = true ]; then
  UV_RUN="uv run -q --no-project --with pyyaml==6.0.3 --with jsonschema==4.25.1 python -I"
  log_info "Checking the conformance checker against Microsoft's own examples (OpenAPI ${GRAPH_OPENAPI_SHA:0:12})..."
  ${UV_RUN} "${CHECKS}/check_conformance.py" --sha "${GRAPH_OPENAPI_SHA}" --strict --documented "${DOCS_EXAMPLES}" \
    --examples "${DOCS_EXAMPLES}"/exchanges/*.json | tail -3
  log_info "Checking that the checker rejects a hand-built paging URL and a request without client-request-id..."
  if NEGATIVE=$(${UV_RUN} "${CHECKS}/check_conformance.py" --sha "${GRAPH_OPENAPI_SHA}" --strict "${CHECKS}/selftest-negative.jsonl" 2>&1); then
    log_error "The conformance checker accepted a recording it must reject."
    exit 1
  fi
  echo "${NEGATIVE}" | grep -q "paging URL not returned by Graph: /v1.0/drives/b!docs/root/delta?\$top=5&\$skiptoken=10" \
    && echo "${NEGATIVE}" | grep -q "no client-request-id header" \
    || { log_error "The checker failed the negative recording for the wrong reasons:"; echo "${NEGATIVE}"; exit 1; }
  log_info "Checking every recorded request and response against Microsoft's Graph v1.0 OpenAPI description (strict)..."
  ${UV_RUN} "${CHECKS}/check_conformance.py" --sha "${GRAPH_OPENAPI_SHA}" --strict --documented "${DOCS_EXAMPLES}" \
    "${CONFORMANCE_DIR}/graph-traffic.jsonl"
  log_info "Checking the documented application permission against Microsoft's permission tables..."
  python3 -I "${CHECKS}/documented_permissions.py" --sha "${GRAPH_DOCS_SHA}" "${CONFORMANCE_DIR}/graph-traffic.jsonl"
fi

log_success "All SharePoint Repository Connector tests passed successfully!"
