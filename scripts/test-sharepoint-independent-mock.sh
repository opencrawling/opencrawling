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
# OpenCrawling - SharePoint connector against an independent Graph mock
#
# Description:
#   A second opinion on the Graph mock in this repository: the connector runs
#   against sergey-shulyak/mock-graph-server (MIT), an independently written
#   Microsoft Graph mock with sites, drives, inherited permissions, SharePoint
#   site groups, delta, 410 resync, throttling, an admin API and an RBAC test
#   oracle (company.visibility.yaml: the files each of its 16 users can read).
#
#   1. The image, pinned by digest, runs locked down on 127.0.0.1 only.
#   2. graph-mock/conformance/record_proxy.py sits in front of it: it records
#      the traffic and answers the token endpoint that mock doesn't have.
#   3. IndependentGraphMockTest crawls every site in delta and tree mode (they
#      must agree), drives changes through the mock's admin API (new, replaced
#      and deleted files, a deleted folder, a permission grant), its throttling
#      and a reset (410), and exports the indexed permissions.
#   4. compare_acl_oracle.py holds the indexed permissions against the mock's
#      oracle, user by user: any file the index grants beyond it is a leak.
#   5. check_conformance.py checks the recorded traffic against Microsoft's
#      Graph v1.0 OpenAPI description (only the connector's side must pass:
#      the mock's own deviations from Microsoft are reported, not failed).
#
# Requirements: docker, python3, uv, mvn, java 25, curl.
# ==============================================================================

set -euo pipefail

IMAGE="${MOCK_GRAPH_SERVER_IMAGE:-ghcr.io/sergey-shulyak/mock-graph-server@sha256:bc91dc15ce520c228c5c394f80de638669f2909bd2ad4c5f973cbf05d3d7d6a1}"
CONTAINER="${MOCK_GRAPH_SERVER_CONTAINER:-oc-sharepoint-independent-mock}"
MOCK_PORT="${MOCK_PORT:-18280}"
PROXY_PORT="${PROXY_PORT:-18281}"
GRAPH_OPENAPI_SHA="${GRAPH_OPENAPI_SHA:-1fd0ef510f79f3dadb3e083bdebc9496f939a11c}"
GRAPH_DOCS_SHA="${GRAPH_DOCS_SHA:-4b7867afe88f98d2415ac2f0219debe86fcb5652}"
TIMEOUT="${TIMEOUT:-60}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

echo -e "${YELLOW}=== OpenCrawling SharePoint connector against an independent Graph mock ===${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
MODULE="oc-sharepoint-repository-connector"
CHECKS="${MODULE}/graph-mock/conformance"
WORK="$(pwd)/${MODULE}/target/independent-mock"

if ! command -v uv >/dev/null 2>&1; then
  log_warn "uv is required but not installed (install via 'brew install uv' or 'pip install uv'). Skipping test."
  exit 0
fi
for tool in docker python3 mvn curl; do
  command -v "$tool" >/dev/null 2>&1 || { log_error "$tool is required."; exit 1; }
done
for p in "$MOCK_PORT" "$PROXY_PORT"; do
  if lsof -ti "tcp:$p" -sTCP:LISTEN >/dev/null 2>&1; then
    log_error "Port $p is in use."
    exit 1
  fi
done
rm -rf "$WORK"
mkdir -p "$WORK"

PROXY_PID=""
cleanup() {
  if [ -n "$PROXY_PID" ]; then
    kill "$PROXY_PID" 2>/dev/null || true
    wait "$PROXY_PID" 2>/dev/null || true
  fi
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ------------------------------------------------------------------ the independent mock, locked down
log_info "Starting ${IMAGE%%@*} (pinned digest) on 127.0.0.1:${MOCK_PORT}..."
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --rm --name "$CONTAINER" -p "127.0.0.1:${MOCK_PORT}:8080" --read-only --tmpfs /tmp \
  --cap-drop ALL --security-opt no-new-privileges "$IMAGE" >/dev/null
elapsed=0
until curl -sf -o /dev/null -H 'Authorization: Bearer app' "http://127.0.0.1:${MOCK_PORT}/v1.0/sites/root"; do
  [ $elapsed -ge "$TIMEOUT" ] && { log_error "The mock did not start."; docker logs "$CONTAINER" | tail -20; exit 1; }
  sleep 1
  elapsed=$((elapsed + 1))
done
log_success "Mock up (revision $(docker image inspect "$IMAGE" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' | cut -c1-12))."
docker cp "$CONTAINER:/app/fixtures/company.visibility.yaml" "$WORK/company.visibility.yaml" >/dev/null

python3 -I "${CHECKS}/record_proxy.py" --port "$PROXY_PORT" --upstream "http://127.0.0.1:${MOCK_PORT}" \
  --record "$WORK/traffic.jsonl" --token app > "$WORK/proxy.log" 2>&1 &
PROXY_PID=$!
elapsed=0
until curl -sf -o /dev/null -H 'Authorization: Bearer app' "http://127.0.0.1:${PROXY_PORT}/v1.0/sites/root"; do
  [ $elapsed -ge "$TIMEOUT" ] && { log_error "The recording proxy did not start."; cat "$WORK/proxy.log"; exit 1; }
  sleep 1
  elapsed=$((elapsed + 1))
done
: > "$WORK/traffic.jsonl"

# ------------------------------------------------------------------ the scenarios
log_info "Running IndependentGraphMockTest through the recording proxy..."
MOCK_GRAPH_SERVER_URL="http://127.0.0.1:${MOCK_PORT}" MOCK_GRAPH_SERVER_PROXY="http://127.0.0.1:${PROXY_PORT}" \
MOCK_GRAPH_SERVER_OUT="$WORK" \
  mvn test -pl "$MODULE" -am -Dtest=IndependentGraphMockTest -Dsurefire.failIfNoSpecifiedTests=false ${MAVEN_ARGS:-}
curl -s "http://127.0.0.1:${MOCK_PORT}/_admin/reset" -X POST >/dev/null
curl -s "http://127.0.0.1:${MOCK_PORT}/_admin/state" > "$WORK/state.yaml"

# ------------------------------------------------------------------ the RBAC oracle
log_info "Holding the indexed permissions against the mock's RBAC oracle (company.visibility.yaml)..."
uv run -q --no-project --with pyyaml==6.0.3 python -I "${CHECKS}/compare_acl_oracle.py" --crawled "$WORK/crawled-permissions.json" \
  --oracle "$WORK/company.visibility.yaml" --state "$WORK/state.yaml" --mock "http://127.0.0.1:${MOCK_PORT}"

# ------------------------------------------------------------------ Microsoft's spec, on both sides
log_info "Checking the recorded traffic against Microsoft's Graph v1.0 OpenAPI description..."
if [ ! -d "${WORK}/docs-examples" ]; then
  python3 -I "${CHECKS}/fetch_doc_examples.py" --sha "${GRAPH_DOCS_SHA}" --out "${WORK}/docs-examples"
fi
set +e
uv run -q --no-project --with pyyaml==6.0.3 --with jsonschema==4.25.1 python -I "${CHECKS}/check_conformance.py" \
  --sha "${GRAPH_OPENAPI_SHA}" --strict --documented "${WORK}/docs-examples" "$WORK/traffic.jsonl" > "$WORK/conformance.txt"
set -e
cat "$WORK/conformance.txt"
# the connector's side: requests must exist in the API, carry client-request-id, and follow paging links verbatim
if grep -E "^        - .*(no client-request-id|paging URL not returned|query parameter .* not declared|path not in the Graph|method .* not defined)" \
    "$WORK/conformance.txt"; then
  log_error "The connector's requests deviate from Microsoft's Graph description."
  exit 1
fi
log_success "The connector's requests conform; any FAIL above is the independent mock's own deviation from Microsoft."
log_success "The SharePoint connector agrees with the independent Graph mock and its RBAC oracle."
