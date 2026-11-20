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
#   (`oc-alfresco-repository-connector`). It validates Basic and Ticket session
#   authentication (issue/use/logout/401-after-logout), readiness probes, folder
#   and file node scanning, exact vs substring folder exclusions, versions
#   (CURRENT/LATEST_MAJOR/ALL), tags, comments, renditions, Trashcan deletion
#   feed (/deleted-nodes), optional AFTS/SEARCH_DELTA search queries, live Java
#   connector incremental/tombstone execution (`AlfrescoLiveEndpointTest`), and
#   OpenCrawling Runtime SPI/Factory/Checker wiring.
# ==============================================================================

set -euo pipefail

# Configuration
ALFRESCO_HOST="${ALFRESCO_HOST:-localhost}"
ALFRESCO_PORT="${ALFRESCO_PORT:-8081}"
ALFRESCO_USERNAME="${ALFRESCO_USERNAME:-admin}"
ALFRESCO_PASSWORD="${ALFRESCO_PASSWORD:-admin}"
ALFRESCO_URL="${ALFRESCO_URL:-http://${ALFRESCO_HOST}:${ALFRESCO_PORT}/alfresco/api/-default-/public/alfresco/versions/1}"
API_BASE="${ALFRESCO_URL%/public/alfresco/versions/1}"
TICKETS_URL="${API_BASE}/public/authentication/versions/1/tickets"
SEARCH_URL="${API_BASE}/public/search/versions/1/search"
COMPOSE_FILE="oc-alfresco-repository-connector/alfresco-community-compose.yml"
TIMEOUT="${TIMEOUT:-300}"
START_LOCAL_CONTAINER=false
FORCE_CLEANUP="${FORCE_CLEANUP:-false}"

# State for cleanup
FOLDER_ID=""
TRASHED_IDS=()

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
  if [ -n "${FOLDER_ID}" ]; then
    log_info "Cleaning up temporary test folder node '${FOLDER_ID}' in Alfresco..."
    curl -s -o /dev/null -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${FOLDER_ID}?permanent=true" 2>/dev/null || true
    FOLDER_ID=""
  fi
  if [ "${#TRASHED_IDS[@]}" -gt 0 ]; then
    for tid in "${TRASHED_IDS[@]}"; do
      if [ -n "${tid}" ]; then
        curl -s -o /dev/null -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/deleted-nodes/${tid}" 2>/dev/null || true
      fi
    done
    TRASHED_IDS=()
  fi
  if [ "${START_LOCAL_CONTAINER}" = true ] || [ "${FORCE_CLEANUP}" = true ]; then
    log_info "Tearing down temporary Alfresco Community Docker Compose environment..."
    docker compose -f "${COMPOSE_FILE}" down --remove-orphans >/dev/null 2>&1 || true
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

# 3. Connection & Authentication Tests (Basic + Alfresco Ticket Lifecycle)
log_info "Testing Alfresco HTTP Basic authentication and root node retrieval..."
ROOT_RESPONSE=$(curl -s -w "\n%{http_code}" -H "Authorization: ${AUTH_HEADER}" -H "Accept: application/json" "${ROOT_URL}")
ROOT_HTTP_CODE=$(echo "${ROOT_RESPONSE}" | tail -n 1)
ROOT_BODY=$(echo "${ROOT_RESPONSE}" | sed '$d')

if [ "${ROOT_HTTP_CODE}" != "200" ]; then
  log_error "Failed to retrieve root node from Alfresco. HTTP Code: ${ROOT_HTTP_CODE}, Response: ${ROOT_BODY}"
  exit 1
fi

ROOT_NAME=$(echo "${ROOT_BODY}" | jq -r '.entry.name // empty')
log_success "Successfully authenticated via Basic Auth and retrieved root node: '${ROOT_NAME}' (HTTP ${ROOT_HTTP_CODE})"

log_info "Testing Alfresco Ticket authentication lifecycle (POST /tickets -> GET /nodes/-root- -> DELETE /tickets/-me-)..."
TICKET_RES=$(curl -s -w "\n%{http_code}" -X POST "${TICKETS_URL}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d "$(jq -nc --arg u "${ALFRESCO_USERNAME}" --arg p "${ALFRESCO_PASSWORD}" '{userId:$u, password:$p}')")
TICKET_CODE=$(echo "${TICKET_RES}" | tail -n 1)
TICKET_BODY=$(echo "${TICKET_RES}" | sed '$d')

if [ "${TICKET_CODE}" != "201" ]; then
  log_error "Failed to create Alfresco ticket at ${TICKETS_URL}: ${TICKET_BODY} (HTTP ${TICKET_CODE})"
  exit 1
fi

TICKET_ID=$(echo "${TICKET_BODY}" | jq -r '.entry.id // empty')
[ -n "${TICKET_ID}" ] || { log_error "Alfresco ticket response did not include .entry.id: ${TICKET_BODY}"; exit 1; }
TICKET_AUTH_HEADER="Basic $(printf "%s" "${TICKET_ID}" | base64 | tr -d '\n')"

TICKET_ROOT_CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: ${TICKET_AUTH_HEADER}" "${ROOT_URL}")
if [ "${TICKET_ROOT_CODE}" != "200" ]; then
  log_error "Ticket authentication failed on ${ROOT_URL} (HTTP ${TICKET_ROOT_CODE})"
  exit 1
fi

TICKET_DEL_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: ${TICKET_AUTH_HEADER}" "${TICKETS_URL}/-me-")
if [ "${TICKET_DEL_CODE}" != "204" ] && [ "${TICKET_DEL_CODE}" != "200" ]; then
  log_error "Ticket logout failed on ${TICKETS_URL}/-me- (HTTP ${TICKET_DEL_CODE})"
  exit 1
fi

REVOKED_CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: ${TICKET_AUTH_HEADER}" "${ROOT_URL}")
if [ "${REVOKED_CODE}" != "401" ]; then
  log_error "Expected HTTP 401 for revoked ticket, got HTTP ${REVOKED_CODE}"
  exit 1
fi
log_success "Alfresco Ticket auth lifecycle verified (issued -> authenticated -> logged out -> HTTP 401 rejected)."

# 4. Provision Rich Test Fixture (Versions, Tags, Comments, Exact-vs-Substring Folders, Regex Filter Target, Trashcan Target)
TEST_FOLDER_NAME="opencrawling-it-$(date +%s)"
log_info "Creating test folder '${TEST_FOLDER_NAME}' under -root-..."

FOLDER_RES=$(curl -s -w "\n%{http_code}" -X POST "${ALFRESCO_URL}/nodes/-root-/children" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d "$(jq -nc --arg name "${TEST_FOLDER_NAME}" '{name:$name, nodeType:"cm:folder"}')")
FOLDER_CODE=$(echo "${FOLDER_RES}" | tail -n 1)
FOLDER_BODY=$(echo "${FOLDER_RES}" | sed '$d')

if [ "${FOLDER_CODE}" != "201" ]; then
  log_error "Failed to create test folder in Alfresco: ${FOLDER_BODY} (HTTP ${FOLDER_CODE})"
  exit 1
fi
FOLDER_ID=$(echo "${FOLDER_BODY}" | jq -r '.entry.id')
log_success "Test folder created successfully with ID: ${FOLDER_ID}"

# Helper to create a text document node and upload initial content
create_doc() {
  local parent_id="$1" name="$2" title="$3" desc="$4" content="$5" upload_query="${6:-}"
  local res code body id up_res up_code
  res=$(curl -s -w "\n%{http_code}" -X POST "${ALFRESCO_URL}/nodes/${parent_id}/children" \
    -H "Authorization: ${AUTH_HEADER}" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "$(jq -nc --arg n "$name" --arg t "$title" --arg d "$desc" \
      '{name:$n, nodeType:"cm:content", properties:{"cm:title":$t, "cm:description":$d}}')")
  code=$(echo "${res}" | tail -n 1)
  body=$(echo "${res}" | sed '$d')
  if [ "${code}" != "201" ]; then
    log_error "Failed to create document '${name}': ${body} (HTTP ${code})"
    exit 1
  fi
  id=$(echo "${body}" | jq -r '.entry.id')

  up_res=$(curl -s -w "\n%{http_code}" -X PUT "${ALFRESCO_URL}/nodes/${id}/content${upload_query}" \
    -H "Authorization: ${AUTH_HEADER}" \
    -H "Content-Type: text/plain" \
    --data-binary "${content}")
  up_code=$(echo "${up_res}" | tail -n 1)
  if [ "${up_code}" != "200" ]; then
    log_error "Failed to upload content for '${name}': $(echo "${up_res}" | sed '$d') (HTTP ${up_code})"
    exit 1
  fi
  echo "${id}"
}

# Primary versioned document (v1.0 -> major v2.0 -> minor v2.1)
TEST_DOC_NAME="opencrawling-sample.txt"
V1_CONTENT="OpenCrawling Alfresco Connector Version 1 Initial Release Stream."
V2_CONTENT="OpenCrawling Alfresco Connector Version 2 Major Release Stream."
V21_CONTENT="OpenCrawling Alfresco Connector Version 2.1 Minor Draft Stream."
DOC_TAG="opencrawling-verified"
DOC_COMMENT="Reviewed for OpenCrawling v1.0.5"

log_info "Creating versioned test document '${TEST_DOC_NAME}' (initial -> major -> minor)..."
DOC_ID=$(create_doc "${FOLDER_ID}" "${TEST_DOC_NAME}" "OpenCrawling Integration Test Title" \
  "OpenCrawling Alfresco Repository Connector Integration Test Content" "${V1_CONTENT}" "?majorVersion=true&comment=Initial+Release")

# Upload Major Release version
UP2_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  "${ALFRESCO_URL}/nodes/${DOC_ID}/content?majorVersion=true&comment=Major+Release" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: text/plain" --data-binary "${V2_CONTENT}")
[ "${UP2_CODE}" = "200" ] || { log_error "Failed to upload major version (HTTP ${UP2_CODE})"; exit 1; }

# Upload Minor Draft version
UP21_RES=$(curl -s -w "\n%{http_code}" -X PUT \
  "${ALFRESCO_URL}/nodes/${DOC_ID}/content?majorVersion=false&comment=Minor+Draft" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: text/plain" --data-binary "${V21_CONTENT}")
UP21_CODE=$(echo "${UP21_RES}" | tail -n 1)
UP21_BODY=$(echo "${UP21_RES}" | sed '$d')
[ "${UP21_CODE}" = "200" ] || { log_error "Failed to upload minor version: ${UP21_BODY} (HTTP ${UP21_CODE})"; exit 1; }
log_success "Document '${DOC_ID}' provisioned with major + minor versions."

# Add tag (requires Solr Search Services in ACS) & comment (DB-backed) on primary document
LIVE_TAG=""
TAG_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${ALFRESCO_URL}/nodes/${DOC_ID}/tags" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: application/json" \
  -d "$(jq -nc --arg t "${DOC_TAG}" '{tag:$t}')" || echo "000")
if [ "${TAG_CODE}" = "201" ]; then
  LIVE_TAG="${DOC_TAG}"
  log_success "Added tag '${LIVE_TAG}' to node '${DOC_ID}'."
else
  log_warn "POST /nodes/${DOC_ID}/tags returned HTTP ${TAG_CODE} (ACS tagging requires Solr Search Services); continuing with comment & aspect checks."
fi

COMMENT_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${ALFRESCO_URL}/nodes/${DOC_ID}/comments" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: application/json" \
  -d "$(jq -nc --arg c "${DOC_COMMENT}" '{content:$c}')")
[ "${COMMENT_CODE}" = "201" ] || { log_error "Failed to add comment to node '${DOC_ID}' (HTTP ${COMMENT_CODE})"; exit 1; }
log_success "Added comment '${DOC_COMMENT}' to node '${DOC_ID}'."

# Regex-excluded lock file (~$draft-lock.txt)
LOCK_DOC_ID=$(create_doc "${FOLDER_ID}" '~$draft-lock.txt' "Lock File" "Excluded by excludedNamePatterns" "Temporary lock bytes.")
log_info "Created regex-filter target '~\$draft-lock.txt' (ID: ${LOCK_DOC_ID})."

# Exact vs substring folder exclusion fixture: 'Templates' MUST be included while 'temp' MUST be excluded
TEMPLATES_FOLDER_ID=$(curl -sf -X POST "${ALFRESCO_URL}/nodes/${FOLDER_ID}/children" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: application/json" \
  -d '{"name":"Templates","nodeType":"cm:folder"}' | jq -r '.entry.id')
TEMP_FOLDER_ID=$(curl -sf -X POST "${ALFRESCO_URL}/nodes/${FOLDER_ID}/children" \
  -H "Authorization: ${AUTH_HEADER}" -H "Content-Type: application/json" \
  -d '{"name":"temp","nodeType":"cm:folder"}' | jq -r '.entry.id')

TEMPLATE_DOC_ID=$(create_doc "${TEMPLATES_FOLDER_ID}" "template-guide.txt" "Template Guide" "Must NOT be excluded by 'temp'" "Reusable enterprise template document.")
TEMP_DOC_ID=$(create_doc "${TEMP_FOLDER_ID}" "scratch.txt" "Temp Scratch" "Must be excluded by 'temp'" "Ephemeral scratch document.")
log_success "Provisioned 'Templates/template-guide.txt' (${TEMPLATE_DOC_ID}) and 'temp/scratch.txt' (${TEMP_DOC_ID})."

# Trashcan verification document (created then archived via non-permanent DELETE)
TRASH_DOC_ID=$(create_doc "${FOLDER_ID}" "opencrawling-trashed.txt" "Trashcan Probe" "Deleted to verify /deleted-nodes" "Archived document for Trashcan test.")

# 5. Verify Folder Children, Versions, Tags, Comments, Renditions, Trashcan & Optional Search Delta
log_info "Verifying folder child node scanning via REST API (with path, properties, aspects, permissions)..."
CHILDREN_RES=$(curl -s -H "Authorization: ${AUTH_HEADER}" -H "Accept: application/json" \
  "${ALFRESCO_URL}/nodes/${FOLDER_ID}/children?include=path,properties,aspectNames,permissions")

CHILD_COUNT=$(echo "${CHILDREN_RES}" | jq '.list.entries | length')
PRIMARY_ENTRY=$(echo "${CHILDREN_RES}" | jq -c --arg id "${DOC_ID}" '.list.entries[].entry | select(.id == $id)')
CHILD_NAME=$(echo "${PRIMARY_ENTRY}" | jq -r '.name // empty')
CHILD_TITLE=$(echo "${PRIMARY_ENTRY}" | jq -r '.properties."cm:title" // empty')
CHILD_PATH=$(echo "${PRIMARY_ENTRY}" | jq -r '.path.name // empty')
CHILD_INHERITED=$(echo "${PRIMARY_ENTRY}" | jq -r '.permissions.isInheritanceEnabled // empty')
DOC_MODIFIED_AT=$(echo "${PRIMARY_ENTRY}" | jq -r '.modifiedAt // empty')

if [ "${CHILD_COUNT}" -ge 3 ] && [ "${CHILD_NAME}" = "${TEST_DOC_NAME}" ] && [ -n "${CHILD_PATH}" ]; then
  log_success "Child node discovered: name='${CHILD_NAME}', path='${CHILD_PATH}', cm:title='${CHILD_TITLE}', isInheritanceEnabled='${CHILD_INHERITED}'"
else
  log_error "Child node verification failed. Response: ${CHILDREN_RES}"
  exit 1
fi

log_info "Verifying version history endpoint (/nodes/${DOC_ID}/versions)..."
VERSIONS_RES=$(curl -sf -H "Authorization: ${AUTH_HEADER}" -H "Accept: application/json" \
  "${ALFRESCO_URL}/nodes/${DOC_ID}/versions?include=properties")
VERSION_COUNT=$(echo "${VERSIONS_RES}" | jq '.list.entries | length')
TIP_VERSION=$(echo "${VERSIONS_RES}" | jq -r '.list.entries[0].entry.id // empty')
MAJOR_VERSION=$(echo "${VERSIONS_RES}" | jq -r '[.list.entries[].entry | select((.properties."cm:versionType" // "") == "MAJOR" or (.id | test("^[0-9]+\\.0$")))][0].id // empty')

if [ "${VERSION_COUNT}" -lt 2 ] || [ -z "${TIP_VERSION}" ] || [ -z "${MAJOR_VERSION}" ]; then
  log_error "Expected >= 2 versions with major + minor history for '${DOC_ID}', got: ${VERSIONS_RES}"
  exit 1
fi

MAJOR_CONTENT=$(curl -sf -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC_ID}/versions/${MAJOR_VERSION}/content")
if [ "${MAJOR_CONTENT}" != "${V2_CONTENT}" ]; then
  log_error "Version '${MAJOR_VERSION}' content mismatch. Expected '${V2_CONTENT}', got '${MAJOR_CONTENT}'"
  exit 1
fi
log_success "Version history verified (${VERSION_COUNT} versions; tip='${TIP_VERSION}', latest major='${MAJOR_VERSION}')."

log_info "Verifying comments, renditions, and tags endpoints..."
if [ -n "${LIVE_TAG}" ]; then
  TAGS_FOUND=$(curl -sf -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC_ID}/tags" | jq -r '[.list.entries[].entry.tag] | join(",")')
  echo "${TAGS_FOUND}" | grep -q "${LIVE_TAG}" || { log_error "Expected tag '${LIVE_TAG}', got '${TAGS_FOUND}'"; exit 1; }
else
  TAGS_CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC_ID}/tags")
  [ "${TAGS_CODE}" = "200" ] || { log_error "GET /nodes/${DOC_ID}/tags returned HTTP ${TAGS_CODE}"; exit 1; }
  TAGS_FOUND="(none; Solr offline)"
fi

COMMENTS_FOUND=$(curl -sf -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC_ID}/comments" | jq -r '[.list.entries[].entry.content] | join(" | ")')
echo "${COMMENTS_FOUND}" | grep -q "${DOC_COMMENT}" || { log_error "Expected comment '${DOC_COMMENT}', got '${COMMENTS_FOUND}'"; exit 1; }

RENDITIONS_CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC_ID}/renditions")
[ "${RENDITIONS_CODE}" = "200" ] || { log_error "Renditions endpoint returned HTTP ${RENDITIONS_CODE}"; exit 1; }
log_success "Comments, renditions (HTTP ${RENDITIONS_CODE}), and tags (${TAGS_FOUND}) verified."

log_info "Verifying Trashcan deletion feed (/deleted-nodes)..."
TRASH_DEL_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${TRASH_DOC_ID}")
[ "${TRASH_DEL_CODE}" = "204" ] || { log_error "Failed to move '${TRASH_DOC_ID}' to Trashcan (HTTP ${TRASH_DEL_CODE})"; exit 1; }
TRASHED_IDS+=("${TRASH_DOC_ID}")

DELETED_RES=$(curl -sf -H "Authorization: ${AUTH_HEADER}" -H "Accept: application/json" "${ALFRESCO_URL}/deleted-nodes?maxItems=50")
ARCHIVED_AT=$(echo "${DELETED_RES}" | jq -r --arg id "${TRASH_DOC_ID}" '.list.entries[].entry | select(.id == $id) | .archivedAt // empty')
if [ -z "${ARCHIVED_AT}" ]; then
  log_error "Deleted node '${TRASH_DOC_ID}' did not appear in /deleted-nodes: ${DELETED_RES}"
  exit 1
fi
log_success "Trashcan /deleted-nodes feed verified (node '${TRASH_DOC_ID}' archivedAt='${ARCHIVED_AT}')."

log_info "Verifying Alfresco Search REST API v1 (AFTS + SEARCH_DELTA cm:modified range syntax)..."
SEARCH_PAYLOAD=$(cat <<EOF
{
  "query": {
    "language": "afts",
    "query": "(TYPE:'cm:content') AND cm:modified:[\"1970-01-01T00:00:00Z\" TO MAX]"
  },
  "paging": {
    "maxItems": 10,
    "skipCount": 0
  },
  "include": ["path", "properties", "aspectNames", "permissions"],
  "sort": [
    { "type": "FIELD", "field": "cm:modified", "ascending": true }
  ]
}
EOF
)
SEARCH_HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${SEARCH_URL}" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d "${SEARCH_PAYLOAD}" || echo "000")

if [ "${SEARCH_HTTP_CODE}" = "200" ]; then
  log_success "Alfresco Search REST API (AFTS + SEARCH_DELTA cm:modified sort) is online and responsive (HTTP 200)!"
else
  log_warn "Alfresco Search REST API returned HTTP ${SEARCH_HTTP_CODE} (Solr search subsystem is optional in local compose)."
fi

log_info "Verifying current content stream download for document node '${DOC_ID}'..."
DOWNLOADED_CONTENT=$(curl -s -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${DOC_ID}/content")
if [ "${DOWNLOADED_CONTENT}" = "${V21_CONTENT}" ]; then
  log_success "Content stream verified: '${DOWNLOADED_CONTENT}'"
else
  log_error "Downloaded content did not match uploaded content. Got: '${DOWNLOADED_CONTENT}'"
  exit 1
fi

# 6. Execute Maven Connector Tests (Unit + In-Process + Live AlfrescoLiveEndpointTest) & Runtime SPI Wiring
log_info "Running Maven test suite for oc-alfresco-repository-connector (including live AlfrescoLiveEndpointTest)..."
mvn test -pl oc-alfresco-repository-connector \
  -Dspring.opencrawling.connector.alfresco.url="${ALFRESCO_URL}" \
  -Dspring.opencrawling.connector.alfresco.username="${ALFRESCO_USERNAME}" \
  -Dspring.opencrawling.connector.alfresco.password="${ALFRESCO_PASSWORD}" \
  -Dalfresco.live.folderId="${FOLDER_ID}" \
  -Dalfresco.live.docId="${DOC_ID}" \
  -Dalfresco.live.tag="${LIVE_TAG}" \
  -Dalfresco.live.comment="${DOC_COMMENT}" \
  -Dalfresco.live.majorVersion="${MAJOR_VERSION}" \
  -Dalfresco.live.tipVersion="${TIP_VERSION}" \
  -Dalfresco.live.docModifiedAt="${DOC_MODIFIED_AT}"
log_success "Maven connector unit + live integration tests passed successfully!"

log_info "Validating OpenCrawling Runtime wiring for Alfresco (ConnectorKindTest, ConnectorFactoryTest, ConnectorCheckerServiceTest)..."
mvn test -pl oc-alfresco-repository-connector,oc-runtime \
  -Dtest=ConnectorKindTest,ConnectorFactoryTest,ConnectorCheckerServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
log_success "OpenCrawling Runtime Alfresco wiring tests passed!"

# 7. Cleanup Test Nodes in Alfresco (explicit before trap)
log_info "Cleaning up temporary test folder '${FOLDER_ID}' and Trashcan probe '${TRASH_DOC_ID}'..."
DELETE_RES=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/${FOLDER_ID}?permanent=true")
if [ "${DELETE_RES}" = "204" ] || [ "${DELETE_RES}" = "200" ]; then
  FOLDER_ID=""
  log_success "Test folder permanently removed from Alfresco."
else
  log_warn "Failed to delete test folder '${FOLDER_ID}'. Status: ${DELETE_RES}"
fi

PURGE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/deleted-nodes/${TRASH_DOC_ID}")
if [ "${PURGE_CODE}" = "204" ] || [ "${PURGE_CODE}" = "200" ]; then
  TRASHED_IDS=()
  log_success "Trashcan probe '${TRASH_DOC_ID}' purged cleanly."
fi

echo -e "\n=========================================================================="
log_success "All Alfresco Repository Connector Integration Tests Passed Successfully! 🎉"
echo -e "==========================================================================\n"

exit 0
