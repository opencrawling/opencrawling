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
# OpenCrawling - Alfresco Repository Connector Integration Test Script
#
# Description:
#   Dedicated integration test script for the Alfresco Repository Connector
#   (`oc-alfresco-repository-connector`). It validates connection authentication,
#   readiness probes, folder and file node scanning, document content stream
#   retrieval, metadata extraction, and runs connector Maven tests.
# ==============================================================================

set -euo pipefail

# Configuration
ALFRESCO_HOST="${ALFRESCO_HOST:-localhost}"
ALFRESCO_PORT="${ALFRESCO_PORT:-8081}"
ALFRESCO_USERNAME="${ALFRESCO_USERNAME:-admin}"
ALFRESCO_PASSWORD="${ALFRESCO_PASSWORD:-admin}"
ALFRESCO_URL="${ALFRESCO_URL:-http://${ALFRESCO_HOST}:${ALFRESCO_PORT}/alfresco/api/-default-/public/alfresco/versions/1}"
COMPOSE_FILE="oc-alfresco-repository-connector/alfresco-community-compose.yml"
TIMEOUT="${TIMEOUT:-300}"
START_LOCAL_CONTAINER=false

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

echo -e "${YELLOW}=== Starting OpenCrawling Alfresco Repository Connector Integration Test ===${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (curl, jq, docker, mvn)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

# Basic auth header encoding
AUTH_HEADER="Basic $(printf "%s:%s" "${ALFRESCO_USERNAME}" "${ALFRESCO_PASSWORD}" | base64 | tr -d '\n')"

# Cleanup trap
cleanup() {
  if [ "${START_LOCAL_CONTAINER}" = true ]; then
    log_info "Tearing down temporary Alfresco Community Docker Compose environment..."
    docker compose -f "${COMPOSE_FILE}" down -v >/dev/null 2>&1 || true
    log_success "Cleanup complete."
  fi
}
trap cleanup EXIT

# 2. Service Availability Check
log_info "Checking Alfresco Content Services availability at ${ALFRESCO_URL}..."

PROBE_URL="${ALFRESCO_URL}/probes/-ready-"
ROOT_URL="${ALFRESCO_URL}/nodes/-root-"

is_alfresco_ready() {
  local http_code
  http_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 -H "Authorization: ${AUTH_HEADER}" "${ROOT_URL}" 2>/dev/null || echo "000")
  if [ "$http_code" = "200" ]; then
    return 0
  fi
  # Fallback to probe endpoint check
  local probe_code
  probe_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 "${PROBE_URL}" 2>/dev/null || echo "000")
  if [ "$probe_code" = "200" ]; then
    return 0
  fi
  return 1
}

if ! is_alfresco_ready; then
  log_warn "Alfresco Content Services is not currently reachable at ${ALFRESCO_URL}."
  command -v docker >/dev/null 2>&1 || { log_error "Docker is required to launch Alfresco test stack. Aborting."; exit 1; }

  log_info "Launching Alfresco Community stack via Docker Compose (${COMPOSE_FILE})..."
  docker compose -f "${COMPOSE_FILE}" up -d alfresco postgres-alfresco activemq transform-core-aio
  START_LOCAL_CONTAINER=true

  log_info "Waiting for Alfresco Content Services to become healthy and ready (timeout: ${TIMEOUT}s)..."
  ELAPSED=0
  until is_alfresco_ready; do
    if [ $ELAPSED -ge $TIMEOUT ]; then
      log_error "Timed out waiting for Alfresco to start up after ${TIMEOUT} seconds."
      docker compose -f "${COMPOSE_FILE}" logs alfresco --tail 50 || true
      exit 1
    fi
    sleep 5
    ELAPSED=$((ELAPSED + 5))
    echo -n "."
  done
  echo ""
  log_success "Alfresco Content Services is ready and responding!"
else
  log_success "Alfresco Content Services is already online and reachable!"
fi

# 3. Connection Test
log_info "Testing Alfresco connection and root node retrieval..."
ROOT_RESPONSE=$(curl -s -w "\n%{http_code}" -H "Authorization: ${AUTH_HEADER}" -H "Accept: application/json" "${ROOT_URL}")
ROOT_HTTP_CODE=$(echo "${ROOT_RESPONSE}" | tail -n 1)
ROOT_BODY=$(echo "${ROOT_RESPONSE}" | sed '$d')

if [ "${ROOT_HTTP_CODE}" != "200" ]; then
  log_error "Failed to retrieve root node from Alfresco. HTTP Code: ${ROOT_HTTP_CODE}, Response: ${ROOT_BODY}"
  exit 1
fi

ROOT_NAME=$(echo "${ROOT_BODY}" | jq -r '.entry.name // empty')
log_success "Successfully authenticated and retrieved root node: '${ROOT_NAME}' (HTTP ${ROOT_HTTP_CODE})"

# 4. Provision Test Folder & Document in Alfresco
TEST_FOLDER_NAME="opencrawling-it-$(date +%s)"
log_info "Creating test folder '${TEST_FOLDER_NAME}' under -root-..."

CREATE_FOLDER_PAYLOAD=$(cat <<EOF
{
  "name": "${TEST_FOLDER_NAME}",
  "nodeType": "cm:folder"
}
EOF
)

FOLDER_RES=$(curl -s -w "\n%{http_code}" -X POST "${ALFRESCO_URL}/nodes/-root-/children" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d "${CREATE_FOLDER_PAYLOAD}")

FOLDER_CODE=$(echo "${FOLDER_RES}" | tail -n 1)
FOLDER_BODY=$(echo "${FOLDER_RES}" | sed '$d')

if [ "${FOLDER_CODE}" != "201" ]; then
  log_error "Failed to create test folder in Alfresco: ${FOLDER_BODY} (HTTP ${FOLDER_CODE})"
  exit 1
fi

FOLDER_ID=$(echo "${FOLDER_BODY}" | jq -r '.entry.id')
log_success "Test folder created successfully with ID: ${FOLDER_ID}"

# Create test document node
TEST_DOC_NAME="opencrawling-sample.txt"
log_info "Creating test document '${TEST_DOC_NAME}' inside test folder..."

CREATE_DOC_PAYLOAD=$(cat <<EOF
{
  "name": "${TEST_DOC_NAME}",
  "nodeType": "cm:content",
  "properties": {
    "cm:title": "OpenCrawling Integration Test Title",
    "cm:description": "OpenCrawling Alfresco Repository Connector Integration Test Content"
  }
}
EOF
)

DOC_RES=$(curl -s -w "\n%{http_code}" -X POST "${ALFRESCO_URL}/nodes/${FOLDER_ID}/children" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d "${CREATE_DOC_PAYLOAD}")

DOC_CODE=$(echo "${DOC_RES}" | tail -n 1)
DOC_BODY=$(echo "${DOC_RES}" | sed '$d')

if [ "${DOC_CODE}" != "201" ]; then
  log_error "Failed to create document node: ${DOC_BODY} (HTTP ${DOC_CODE})"
  exit 1
fi

DOC_ID=$(echo "${DOC_BODY}" | jq -r '.entry.id')
log_success "Document node created successfully with ID: ${DOC_ID}"

# Upload document content stream
TEST_CONTENT="OpenCrawling Alfresco Repository Connector Integration Test Stream Content."
log_info "Uploading content stream to document node '${DOC_ID}'..."

UPLOAD_RES=$(curl -s -w "\n%{http_code}" -X PUT "${ALFRESCO_URL}/nodes/${DOC_ID}/content" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: text/plain" \
  --data-binary "${TEST_CONTENT}")

UPLOAD_CODE=$(echo "${UPLOAD_RES}" | tail -n 1)
UPLOAD_BODY=$(echo "${UPLOAD_RES}" | sed '$d')

if [ "${UPLOAD_CODE}" != "200" ]; then
  log_error "Failed to upload document content: ${UPLOAD_BODY} (HTTP ${UPLOAD_CODE})"
  exit 1
fi
log_success "Document content uploaded successfully."

# 5. Verify Folder Children Retrieval & Content Download
log_info "Verifying folder child node scanning via REST API..."
CHILDREN_RES=$(curl -s -H "Authorization: ${AUTH_HEADER}" -H "Accept: application/json" \
  "${ALFRESCO_URL}/nodes/${FOLDER_ID}/children?include=properties")

CHILD_COUNT=$(echo "${CHILDREN_RES}" | jq '.list.entries | length')
CHILD_NAME=$(echo "${CHILDREN_RES}" | jq -r '.list.entries[0].entry.name // empty')
CHILD_TITLE=$(echo "${CHILDREN_RES}" | jq -r '.list.entries[0].entry.properties."cm:title" // empty')

if [ "${CHILD_COUNT}" -ge 1 ] && [ "${CHILD_NAME}" = "${TEST_DOC_NAME}" ]; then
  log_success "Child node discovered: name='${CHILD_NAME}', cm:title='${CHILD_TITLE}'"
else
  log_error "Child node verification failed. Response: ${CHILDREN_RES}"
  exit 1
fi

log_info "Verifying content stream download for document node '${DOC_ID}'..."
DOWNLOADED_CONTENT=$(curl -s -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC_ID}/content")

if [ "${DOWNLOADED_CONTENT}" = "${TEST_CONTENT}" ]; then
  log_success "Content stream verified: '${DOWNLOADED_CONTENT}'"
else
  log_error "Downloaded content did not match uploaded content. Got: '${DOWNLOADED_CONTENT}'"
  exit 1
fi

# 6. Execute Maven Tests for Alfresco Repository Connector
log_info "Running Maven test suite for oc-alfresco-repository-connector with live endpoint properties..."
mvn clean test -pl oc-alfresco-repository-connector \
  -Dspring.opencrawling.connector.alfresco.url="${ALFRESCO_URL}" \
  -Dspring.opencrawling.connector.alfresco.username="${ALFRESCO_USERNAME}" \
  -Dspring.opencrawling.connector.alfresco.password="${ALFRESCO_PASSWORD}"

log_success "Maven connector tests passed successfully!"

# 7. Cleanup Test Node in Alfresco
log_info "Cleaning up temporary test folder node '${FOLDER_ID}' in Alfresco..."
DELETE_RES=$(curl -s -w "%{http_code}" -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${FOLDER_ID}")
if [ "${DELETE_RES}" = "204" ] || [ "${DELETE_RES}" = "200" ]; then
  log_success "Test folder cleaned up from Alfresco."
else
  log_warn "Failed to delete test folder '${FOLDER_ID}'. Status: ${DELETE_RES}"
fi

echo -e "\n=========================================================================="
log_success "All Alfresco Repository Connector Integration Tests Passed Successfully! 🎉"
echo -e "==========================================================================\n"

exit 0
