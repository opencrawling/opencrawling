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

# Provisions Alfresco Content Services with test users, groups, folder hierarchy,
# sample documents from tutorials/data/alfresco/, and Zero-Trust ACLs:
#   - public/company-handbook.txt          -> GROUP_EVERYONE (ALLOWED)
#   - finance/q4-financial-forecast.txt    -> GROUP_finance (ALLOWED, inheritance disabled)
#   - engineering/zero-trust-architecture.txt -> GROUP_engineering (ALLOWED), bob (explicit DENIED)
#   - executive/board-resolution-2026.txt  -> alice only (ALLOWED, inheritance disabled)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TUTORIAL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DATA_DIR="${TUTORIAL_DIR}/data/alfresco"
STATE_FILE="${TUTORIAL_DIR}/data/.provisioned-nodes.json"

ALFRESCO_URL="${ALFRESCO_URL:-http://localhost:8081/alfresco/api/-default-/public/alfresco/versions/1}"
ALFRESCO_USERNAME="${ALFRESCO_USERNAME:-admin}"
ALFRESCO_PASSWORD="${ALFRESCO_PASSWORD:-admin}"
ROOT_FOLDER_NAME="${ROOT_FOLDER_NAME:-opencrawling-tutorial}"

BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_err()   { echo -e "${RED}[ERROR]${NC} $*"; }

for cmd in curl jq base64; do
  command -v "$cmd" >/dev/null 2>&1 || { log_err "Required command '$cmd' not found."; exit 1; }
done

AUTH_HEADER="Basic $(printf "%s:%s" "${ALFRESCO_USERNAME}" "${ALFRESCO_PASSWORD}" | base64 | tr -d '\n')"

api_call() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  if [ -n "$body" ]; then
    curl -s -w "\n%{http_code}" -X "${method}" "${ALFRESCO_URL}${path}" \
      -H "Authorization: ${AUTH_HEADER}" \
      -H "Content-Type: application/json" \
      -H "Accept: application/json" \
      -d "${body}"
  else
    curl -s -w "\n%{http_code}" -X "${method}" "${ALFRESCO_URL}${path}" \
      -H "Authorization: ${AUTH_HEADER}" \
      -H "Accept: application/json"
  fi
}

create_person() {
  local id="$1"
  local first="$2"
  local last="$3"
  local email="$4"
  local payload
  payload=$(jq -nc --arg id "$id" --arg f "$first" --arg l "$last" --arg e "$email" \
    '{id:$id, firstName:$f, lastName:$l, email:$e, password:"Password123!"}')
  local res code body
  res=$(api_call POST "/people" "$payload")
  code=$(echo "$res" | tail -n 1)
  body=$(echo "$res" | sed '$d')
  if [ "$code" = "201" ]; then
    log_ok "Created Alfresco user '${id}'."
  elif [ "$code" = "409" ]; then
    log_info "Alfresco user '${id}' already exists."
  else
    log_err "Failed creating user '${id}' (HTTP ${code}): ${body}"
    exit 1
  fi
}

create_group() {
  local id="$1"
  local display="$2"
  local payload
  payload=$(jq -nc --arg id "$id" --arg d "$display" '{id:$id, displayName:$d}')
  local res code body
  res=$(api_call POST "/groups" "$payload")
  code=$(echo "$res" | tail -n 1)
  body=$(echo "$res" | sed '$d')
  if [ "$code" = "201" ]; then
    log_ok "Created Alfresco group '${id}'."
  elif [ "$code" = "409" ]; then
    log_info "Alfresco group '${id}' already exists."
  else
    log_err "Failed creating group '${id}' (HTTP ${code}): ${body}"
    exit 1
  fi
}

add_group_member() {
  local group_id="$1"
  local user_id="$2"
  local payload
  payload=$(jq -nc --arg id "$user_id" '{id:$id, memberType:"PERSON"}')
  local res code body
  res=$(api_call POST "/groups/${group_id}/members" "$payload")
  code=$(echo "$res" | tail -n 1)
  body=$(echo "$res" | sed '$d')
  if [ "$code" = "201" ]; then
    log_ok "Added '${user_id}' to '${group_id}'."
  elif [ "$code" = "409" ]; then
    log_info "User '${user_id}' is already a member of '${group_id}'."
  else
    log_err "Failed adding '${user_id}' to '${group_id}' (HTTP ${code}): ${body}"
    exit 1
  fi
}

create_folder() {
  local parent_id="$1"
  local name="$2"
  local payload
  payload=$(jq -nc --arg n "$name" '{name:$n, nodeType:"cm:folder"}')
  local res code body
  res=$(api_call POST "/nodes/${parent_id}/children" "$payload")
  code=$(echo "$res" | tail -n 1)
  body=$(echo "$res" | sed '$d')
  if [ "$code" != "201" ]; then
    log_err "Failed creating folder '${name}' under '${parent_id}' (HTTP ${code}): ${body}"
    exit 1
  fi
  echo "$body" | jq -r '.entry.id'
}

set_permissions() {
  local node_id="$1"
  local label="$2"
  local perms_json="$3"
  local res code body
  res=$(api_call PUT "/nodes/${node_id}?include=permissions" "$perms_json")
  code=$(echo "$res" | tail -n 1)
  body=$(echo "$res" | sed '$d')
  if [ "$code" != "200" ]; then
    log_err "Failed setting permissions on '${label}' (${node_id}) (HTTP ${code}): ${body}"
    exit 1
  fi
  log_ok "Applied ACLs to ${label} (${node_id})."
}

upload_file() {
  local parent_id="$1"
  local file_path="$2"
  local title="$3"
  local name
  name="$(basename "${file_path}")"
  local payload
  payload=$(jq -nc --arg n "$name" --arg t "$title" \
    '{name:$n, nodeType:"cm:content", properties:{"cm:title":$t}}')
  local res code body node_id
  res=$(api_call POST "/nodes/${parent_id}/children" "$payload")
  code=$(echo "$res" | tail -n 1)
  body=$(echo "$res" | sed '$d')
  if [ "$code" != "201" ]; then
    log_err "Failed creating file node '${name}' (HTTP ${code}): ${body}"
    exit 1
  fi
  node_id=$(echo "$body" | jq -r '.entry.id')

  local up_res up_code up_body
  up_res=$(curl -s -w "\n%{http_code}" -X PUT "${ALFRESCO_URL}/nodes/${node_id}/content" \
    -H "Authorization: ${AUTH_HEADER}" \
    -H "Content-Type: text/plain; charset=UTF-8" \
    --data-binary @"${file_path}")
  up_code=$(echo "$up_res" | tail -n 1)
  up_body=$(echo "$up_res" | sed '$d')
  if [ "$up_code" != "200" ]; then
    log_err "Failed uploading content for '${name}' (${node_id}) (HTTP ${up_code}): ${up_body}"
    exit 1
  fi
  echo "$node_id"
}

# 1. Verify Alfresco connectivity
log_info "Checking Alfresco API availability at ${ALFRESCO_URL}..."
curl -sf -H "Authorization: ${AUTH_HEADER}" "${ALFRESCO_URL}/nodes/-root-" >/dev/null || {
  log_err "Cannot reach Alfresco at ${ALFRESCO_URL}. Start the stack first with start-env.sh."
  exit 1
}

# 2. Provision Users & Groups
log_info "Provisioning Alfresco test users and groups..."
create_person "alice"   "Alice"   "Strategist" "alice@acme.local"
create_person "bob"     "Bob"     "Contractor" "bob@acme.local"
create_person "charlie" "Charlie" "Analyst"    "charlie@acme.local"

create_group "GROUP_engineering" "Engineering"
create_group "GROUP_finance"     "Finance"

add_group_member "GROUP_engineering" "alice"
add_group_member "GROUP_engineering" "bob"
add_group_member "GROUP_finance"     "alice"
add_group_member "GROUP_finance"     "charlie"

# 3. Reset any existing tutorial root folder for clean idempotent provisioning
EXISTING_RES=$(api_call GET "/nodes/-root-?relativePath=/${ROOT_FOLDER_NAME}")
EXISTING_CODE=$(echo "$EXISTING_RES" | tail -n 1)
EXISTING_BODY=$(echo "$EXISTING_RES" | sed '$d')
if [ "$EXISTING_CODE" = "200" ]; then
  EXISTING_ID=$(echo "$EXISTING_BODY" | jq -r '.entry.id')
  log_warn "Removing existing '/${ROOT_FOLDER_NAME}' (${EXISTING_ID}) before recreating fresh test data..."
  DEL_RES=$(api_call DELETE "/nodes/${EXISTING_ID}?permanent=true")
  DEL_CODE=$(echo "$DEL_RES" | tail -n 1)
  if [ "$DEL_CODE" != "204" ] && [ "$DEL_CODE" != "200" ]; then
    log_err "Failed removing existing folder ${EXISTING_ID} (HTTP ${DEL_CODE}): $(echo "$DEL_RES" | sed '$d')"
    exit 1
  fi
fi

# 4. Create root & subfolder hierarchy with explicit ACLs
log_info "Creating folder tree '/${ROOT_FOLDER_NAME}' under Company Home (-root-)..."
ROOT_ID=$(create_folder "-root-" "${ROOT_FOLDER_NAME}")
set_permissions "${ROOT_ID}" "/${ROOT_FOLDER_NAME}" '{"permissions":{"isInheritanceEnabled":false,"locallySet":[{"authorityId":"GROUP_EVERYONE","name":"Consumer","accessStatus":"ALLOWED"}]}}'

PUBLIC_DIR_ID=$(create_folder "${ROOT_ID}" "public")
set_permissions "${PUBLIC_DIR_ID}" "/${ROOT_FOLDER_NAME}/public" \
  '{"permissions":{"isInheritanceEnabled":false,"locallySet":[{"authorityId":"GROUP_EVERYONE","name":"Consumer","accessStatus":"ALLOWED"}]}}'

FINANCE_DIR_ID=$(create_folder "${ROOT_ID}" "finance")
set_permissions "${FINANCE_DIR_ID}" "/${ROOT_FOLDER_NAME}/finance" \
  '{"permissions":{"isInheritanceEnabled":false,"locallySet":[{"authorityId":"GROUP_finance","name":"Consumer","accessStatus":"ALLOWED"}]}}'

ENGINEERING_DIR_ID=$(create_folder "${ROOT_ID}" "engineering")
set_permissions "${ENGINEERING_DIR_ID}" "/${ROOT_FOLDER_NAME}/engineering" \
  '{"permissions":{"isInheritanceEnabled":false,"locallySet":[{"authorityId":"GROUP_engineering","name":"Collaborator","accessStatus":"ALLOWED"}]}}'

EXECUTIVE_DIR_ID=$(create_folder "${ROOT_ID}" "executive")
set_permissions "${EXECUTIVE_DIR_ID}" "/${ROOT_FOLDER_NAME}/executive" \
  '{"permissions":{"isInheritanceEnabled":false,"locallySet":[{"authorityId":"alice","name":"Coordinator","accessStatus":"ALLOWED"}]}}'

# 5. Upload sample documents and apply document-level explicit ACL / DENY rules
log_info "Uploading tutorial documents and applying document-level ACLs..."
HANDBOOK_ID=$(upload_file "${PUBLIC_DIR_ID}" "${DATA_DIR}/public/company-handbook.txt" "ACME Enterprise Handbook (Public)")
log_ok "Uploaded company-handbook.txt (${HANDBOOK_ID}) -> inherits GROUP_EVERYONE ALLOWED"

BUDGET_ID=$(upload_file "${FINANCE_DIR_ID}" "${DATA_DIR}/finance/q4-financial-forecast.txt" "ACME Q4 2026 Financial Forecast")
log_ok "Uploaded q4-financial-forecast.txt (${BUDGET_ID}) -> inherits GROUP_finance ALLOWED"

ARCH_ID=$(upload_file "${ENGINEERING_DIR_ID}" "${DATA_DIR}/engineering/zero-trust-architecture.txt" "Zero-Trust RAG Architecture Blueprint")
set_permissions "${ARCH_ID}" "zero-trust-architecture.txt" \
  '{"permissions":{"isInheritanceEnabled":true,"locallySet":[{"authorityId":"bob","name":"Consumer","accessStatus":"DENIED"}]}}'

BOARD_ID=$(upload_file "${EXECUTIVE_DIR_ID}" "${DATA_DIR}/executive/board-resolution-2026.txt" "Board Resolution 2026-11 (Alice Only)")
log_ok "Uploaded board-resolution-2026.txt (${BOARD_ID}) -> inherits alice ALLOWED only"

jq -n \
  --arg rootId "$ROOT_ID" \
  --arg publicDirId "$PUBLIC_DIR_ID" \
  --arg financeDirId "$FINANCE_DIR_ID" \
  --arg engineeringDirId "$ENGINEERING_DIR_ID" \
  --arg executiveDirId "$EXECUTIVE_DIR_ID" \
  --arg handbookId "$HANDBOOK_ID" \
  --arg budgetId "$BUDGET_ID" \
  --arg archId "$ARCH_ID" \
  --arg boardId "$BOARD_ID" \
  '{
    rootPath: "/opencrawling-tutorial",
    rootId: $rootId,
    folders: {
      public: $publicDirId,
      finance: $financeDirId,
      engineering: $engineeringDirId,
      executive: $executiveDirId
    },
    documents: {
      "company-handbook.txt": $handbookId,
      "q4-financial-forecast.txt": $budgetId,
      "zero-trust-architecture.txt": $archId,
      "board-resolution-2026.txt": $boardId
    }
  }' > "${STATE_FILE}"

echo ""
log_ok "Alfresco tutorial dataset provisioned! Node IDs saved to ${STATE_FILE}"
echo "--------------------------------------------------------------------------------"
printf "%-32s | %-36s | %s\n" "Document" "Alfresco Node ID" "Effective Read Access"
echo "--------------------------------------------------------------------------------"
printf "%-32s | %-36s | %s\n" "company-handbook.txt"        "${HANDBOOK_ID}" "ALLOW: GROUP_EVERYONE (public)"
printf "%-32s | %-36s | %s\n" "q4-financial-forecast.txt"   "${BUDGET_ID}"   "ALLOW: GROUP_finance (alice, charlie)"
printf "%-32s | %-36s | %s\n" "zero-trust-architecture.txt" "${ARCH_ID}"     "ALLOW: GROUP_engineering; DENY: bob (bob blocked, alice allowed)"
printf "%-32s | %-36s | %s\n" "board-resolution-2026.txt"   "${BOARD_ID}"    "ALLOW: alice only"
echo "--------------------------------------------------------------------------------"
