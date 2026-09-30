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
# OpenCrawling - Alfresco Process Services (APS) Workflow Seeder
#
# Description:
#   Deploys a sample BPMN 2.0 workflow ('Invoice Approval') into APS 26.2,
#   publishes the Process App, and starts process instances populated with
#   process variables and task assignments for integration testing.
# ==============================================================================

set -euo pipefail

APS_HOST="${APS_HOST:-localhost}"
APS_PORT="${APS_PORT:-8088}"
APS_USERNAME="${APS_USERNAME:-admin@app.activiti.com}"
APS_PASSWORD="${APS_PASSWORD:-admin}"
APS_URL="${APS_URL:-http://${APS_HOST}:${APS_PORT}/activiti-app/api/enterprise}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

AUTH_HEADER="Basic $(printf "%s:%s" "${APS_USERNAME}" "${APS_PASSWORD}" | base64 | tr -d '\n')"

log_info "Target APS Endpoint: ${APS_URL}"

# 1. License Check & Optional Enterprise License Upload
DETECTED_LICENSE=""
if [ -n "${APS_LICENSE_FILE:-}" ] && [ -f "${APS_LICENSE_FILE}" ]; then
  DETECTED_LICENSE="${APS_LICENSE_FILE}"
elif [ -f "${PROJECT_ROOT}/activiti.lic" ]; then
  DETECTED_LICENSE="${PROJECT_ROOT}/activiti.lic"
elif [ -f "${PROJECT_ROOT}/oc-aps-repository-connector/docker/activiti.lic" ]; then
  DETECTED_LICENSE="${PROJECT_ROOT}/oc-aps-repository-connector/docker/activiti.lic"
elif [ -f "${SCRIPT_DIR}/activiti.lic" ]; then
  DETECTED_LICENSE="${SCRIPT_DIR}/activiti.lic"
elif [ -f "${HOME}/.activiti/enterprise-license/activiti.lic" ]; then
  DETECTED_LICENSE="${HOME}/.activiti/enterprise-license/activiti.lic"
fi

if [ -n "${DETECTED_LICENSE}" ]; then
  log_info "Enterprise license detected at '${DETECTED_LICENSE}'. Applying to APS..."
  UPLOAD_RES=$(curl -s -w "\n%{http_code}" -X POST "${APS_URL}/license" \
    -H "Authorization: ${AUTH_HEADER}" \
    -F "file=@${DETECTED_LICENSE}")
  UPLOAD_CODE=$(echo "${UPLOAD_RES}" | tail -n 1)
  if [ "${UPLOAD_CODE}" = "200" ] || [ "${UPLOAD_CODE}" = "201" ]; then
    log_success "Enterprise license applied successfully to APS!"
  else
    log_warn "License upload returned HTTP ${UPLOAD_CODE}: $(echo "${UPLOAD_RES}" | sed '$d')"
  fi
fi

DETECTED_TRANSFORM_LICENSE=""
if [ -n "${APS_TRANSFORM_LICENSE_FILE:-}" ] && [ -f "${APS_TRANSFORM_LICENSE_FILE}" ]; then
  DETECTED_TRANSFORM_LICENSE="${APS_TRANSFORM_LICENSE_FILE}"
elif [ -f "${PROJECT_ROOT}/transform.lic" ]; then
  DETECTED_TRANSFORM_LICENSE="${PROJECT_ROOT}/transform.lic"
elif [ -f "${PROJECT_ROOT}/oc-aps-repository-connector/docker/transform.lic" ]; then
  DETECTED_TRANSFORM_LICENSE="${PROJECT_ROOT}/oc-aps-repository-connector/docker/transform.lic"
elif [ -f "${SCRIPT_DIR}/transform.lic" ]; then
  DETECTED_TRANSFORM_LICENSE="${SCRIPT_DIR}/transform.lic"
elif [ -f "${HOME}/.activiti/enterprise-license/transform.lic" ]; then
  DETECTED_TRANSFORM_LICENSE="${HOME}/.activiti/enterprise-license/transform.lic"
fi

if [ -n "${DETECTED_TRANSFORM_LICENSE}" ]; then
  log_info "APS transform license detected at '${DETECTED_TRANSFORM_LICENSE}'."
fi

# Query APS License Status
LICENSE_INFO=$(curl -s -H "Authorization: ${AUTH_HEADER}" "${APS_URL}/license" 2>/dev/null || echo "{}")
LICENSE_STATUS=$(echo "${LICENSE_INFO}" | jq -r '.status // "unknown"' 2>/dev/null || echo "unknown")
log_info "APS Enterprise License Status: ${LICENSE_STATUS}"

if [ "${LICENSE_STATUS}" = "not-found" ] || [ "${LICENSE_STATUS}" = "invalid" ] || [ "${LICENSE_STATUS}" = "expired" ]; then
  echo -e "\n${YELLOW}================================================================================${NC}"
  echo -e "${YELLOW}[WARN] APS 26.2 is running without a valid enterprise license (status: '${LICENSE_STATUS}').${NC}"
  echo -e "${YELLOW}[WARN] Modeler operations (importing & publishing BPMN models) require 'activiti.lic'.${NC}"
  echo -e "${YELLOW}[WARN] If you have an APS enterprise license, provide it via:${NC}"
  echo -e "${YELLOW}[WARN]   export APS_LICENSE_FILE=\"/path/to/activiti.lic\"${NC}"
  echo -e "${YELLOW}[WARN] Skipping workflow deployment and seeding.${NC}"
  echo -e "${YELLOW}[WARN] OpenCrawling connector will crawl against a clean/empty repository state.${NC}"
  echo -e "${YELLOW}================================================================================${NC}\n"
  exit 0
fi

# 2. Check existing process instances
EXISTING_TOTAL=$(curl -s -X POST "${APS_URL}/historic-process-instances/query" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -d '{"size":1}' 2>/dev/null | jq -r '.total // 0' || echo "0")

if [ "${EXISTING_TOTAL}" -gt 0 ]; then
  log_success "APS already contains ${EXISTING_TOTAL} process instance(s). Seeding skipped."
  exit 0
fi

log_info "No workflow instances found in APS. Initializing workflow deployment and seeding..."

# Check if invoiceApproval process definition is already deployed
DEPLOYED_COUNT=$(curl -s "${APS_URL}/process-definitions" \
  -H "Authorization: ${AUTH_HEADER}" 2>/dev/null | jq -r '.data[] | select(.key=="invoiceApproval") | .id' 2>/dev/null | wc -l | tr -d ' ' || echo "0")

if [ "${DEPLOYED_COUNT}" -gt 0 ]; then
  log_info "Process definition 'invoiceApproval' is already deployed in APS engine. Skipping model import."
else
  # 3. Import BPMN Process Model into APS
  RESOURCES_DIR="${PROJECT_ROOT}/oc-aps-repository-connector/src/test/resources/sample-workflows"
  BPMN_FILE="${RESOURCES_DIR}/invoiceApproval.bpmn20.xml"

  if [ ! -f "${BPMN_FILE}" ]; then
    log_error "Missing sample workflow definition file: ${BPMN_FILE}."
    exit 1
  fi

  log_info "Importing BPMN process model ('invoiceApproval') into APS..."
  MODEL_RES=$(curl -s -w "\n%{http_code}" -X POST "${APS_URL}/process-models/import" \
    -H "Authorization: ${AUTH_HEADER}" \
    -F "file=@${BPMN_FILE}")
  MODEL_CODE=$(echo "${MODEL_RES}" | tail -n 1)
  MODEL_BODY=$(echo "${MODEL_RES}" | sed '$d')

  if echo "${MODEL_BODY}" | grep -qi "license"; then
    log_warn "Process model import restricted by APS licensing: ${MODEL_BODY}"
    log_warn "Skipping workflow seeding. Connector will run with 0 instances."
    exit 0
  fi

  if [ "${MODEL_CODE}" != "200" ] && [ "${MODEL_CODE}" != "201" ]; then
    log_error "Failed to import process model: ${MODEL_BODY} (HTTP ${MODEL_CODE})"
    exit 1
  fi

  MODEL_ID=$(echo "${MODEL_BODY}" | jq -r '.id // empty')
  log_success "Imported Process Model with ID: ${MODEL_ID}"

  # 4. Create App Definition Model and Link Process Model
  log_info "Creating and linking App Definition for 'Invoice Approval App'..."
  APP_CREATE_RES=$(curl -s -X POST "${APS_URL}/models" \
    -H "Authorization: ${AUTH_HEADER}" \
    -H "Content-Type: application/json" \
    -d '{
      "name": "Invoice Approval App",
      "modelType": 3,
      "description": "Invoice Approval App for OpenCrawling"
    }')
  APP_ID=$(echo "${APP_CREATE_RES}" | jq -r '.id // empty')

  curl -s -X PUT "${APS_URL}/app-definitions/${APP_ID}" \
    -H "Authorization: ${AUTH_HEADER}" \
    -H "Content-Type: application/json" \
    -d "{
      \"appDefinition\": {
        \"name\": \"Invoice Approval App\",
        \"definition\": {
          \"theme\": \"theme-1\",
          \"icon\": \"glyphicon-asterisk\",
          \"models\": [{\"id\": ${MODEL_ID}, \"name\": \"Invoice Approval Process\"}]
        }
      }
    }" >/dev/null
  log_success "Created and linked App Definition Model with ID: ${APP_ID}"

  # Publish App Definition
  log_info "Publishing App Definition ID ${APP_ID} into runtime process engine..."
  PUBLISH_RES=$(curl -s -w "\n%{http_code}" -X POST "${APS_URL}/app-definitions/${APP_ID}/publish" \
    -H "Authorization: ${AUTH_HEADER}" \
    -H "Content-Type: application/json" \
    -d '{"comment": "Automated deployment for OpenCrawling integration testing", "force": true}')

  PUB_CODE=$(echo "${PUBLISH_RES}" | tail -n 1)
  PUB_BODY=$(echo "${PUBLISH_RES}" | sed '$d')

  if [ "${PUB_CODE}" = "409" ] || echo "${PUB_BODY}" | grep -q "already deployed"; then
    log_warn "App definition is already published/deployed (HTTP ${PUB_CODE}). Continuing to instance seeding..."
  elif [ "${PUB_CODE}" != "200" ] && [ "${PUB_CODE}" != "201" ]; then
    log_error "Failed to publish app definition: ${PUB_BODY} (HTTP ${PUB_CODE})"
    exit 1
  else
    log_success "App Definition published and deployed to process engine!"
  fi
fi

# 5. Start Sample Workflow Instances with Variables
log_info "Starting sample workflow instances with business variables and candidate task assignments..."

# Workflow Instance 1
INSTANCE_1_PAYLOAD=$(cat <<EOF
{
  "processDefinitionKey": "invoiceApproval",
  "name": "Invoice #INV-2026-001 - ACME Industrial Supplies",
  "variables": [
    {"name": "invoiceNumber", "value": "INV-2026-001"},
    {"name": "amount", "value": 14500.50},
    {"name": "vendor", "value": "ACME Corporation"},
    {"name": "department", "value": "Engineering"},
    {"name": "priority", "value": "HIGH"},
    {"name": "approved", "value": false}
  ]
}
EOF
)

RES_1=$(curl -s -X POST "${APS_URL}/process-instances" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -d "${INSTANCE_1_PAYLOAD}")
ID_1=$(echo "${RES_1}" | jq -r '.id // empty')
log_success "Started Instance 1 [ID: ${ID_1}] - Invoice #INV-2026-001 (\$14,500.50)"

if [ -n "${ID_1}" ]; then
  log_info "Uploading sample document attachment to Instance 1 [ID: ${ID_1}]..."
  SAMPLE_DOC="/tmp/invoice-INV-2026-001.txt"
  cat <<'EOF' > "${SAMPLE_DOC}"
INVOICE: INV-2026-001
Vendor: ACME Corporation
Amount: $14,500.50
Department: Engineering
Description: Industrial supplies, precision robotic tooling, and electrical components.
Status: PENDING_APPROVAL
Notes: Approved by procurement lead; awaits underwriter review.
EOF
  ATT_RES=$(curl -s -w "\n%{http_code}" -X POST "${APS_URL}/process-instances/${ID_1}/raw-content?isRelatedContent=true" \
    -H "Authorization: ${AUTH_HEADER}" \
    -F "file=@${SAMPLE_DOC};type=text/plain")
  ATT_CODE=$(echo "${ATT_RES}" | tail -n 1)
  if [ "${ATT_CODE}" = "200" ] || [ "${ATT_CODE}" = "201" ]; then
    log_success "Successfully attached invoice document to Instance 1!"
  else
    log_warn "Attachment upload returned HTTP ${ATT_CODE}: $(echo "${ATT_RES}" | sed '$d')"
  fi
  rm -f "${SAMPLE_DOC}"
fi

# Workflow Instance 2
INSTANCE_2_PAYLOAD=$(cat <<EOF
{
  "processDefinitionKey": "invoiceApproval",
  "name": "Invoice #INV-2026-002 - Global Logistics Corp",
  "variables": [
    {"name": "invoiceNumber", "value": "INV-2026-002"},
    {"name": "amount", "value": 3200.00},
    {"name": "vendor", "value": "Global Logistics Corp"},
    {"name": "department", "value": "Operations"},
    {"name": "priority", "value": "NORMAL"},
    {"name": "approved", "value": true}
  ]
}
EOF
)

RES_2=$(curl -s -X POST "${APS_URL}/process-instances" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -d "${INSTANCE_2_PAYLOAD}")
ID_2=$(echo "${RES_2}" | jq -r '.id // empty')
log_success "Started Instance 2 [ID: ${ID_2}] - Invoice #INV-2026-002 (\$3,200.00)"

# Workflow Instance 3
INSTANCE_3_PAYLOAD=$(cat <<EOF
{
  "processDefinitionKey": "invoiceApproval",
  "name": "Invoice #INV-2026-003 - Cyberdyne AI Solutions",
  "variables": [
    {"name": "invoiceNumber", "value": "INV-2026-003"},
    {"name": "amount", "value": 89000.00},
    {"name": "vendor", "value": "Cyberdyne Systems"},
    {"name": "department", "value": "Research & Development"},
    {"name": "priority", "value": "CRITICAL"},
    {"name": "approved", "value": false}
  ]
}
EOF
)

RES_3=$(curl -s -X POST "${APS_URL}/process-instances" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -d "${INSTANCE_3_PAYLOAD}")
ID_3=$(echo "${RES_3}" | jq -r '.id // empty')
log_success "Started Instance 3 [ID: ${ID_3}] - Invoice #INV-2026-003 (\$89,000.00)"

if [ -n "${ID_3}" ]; then
  log_info "Uploading sample specifications document attachment to Instance 3 [ID: ${ID_3}]..."
  SAMPLE_DOC_3="/tmp/cyberdyne-ai-spec.txt"
  cat <<'EOF' > "${SAMPLE_DOC_3}"
PURCHASE ORDER: INV-2026-003
Vendor: Cyberdyne Systems
Amount: $89,000.00
Department: Research & Development
Project: Neural Processing Units and Autonomous Cluster Expansion
Specification: Quantum Tensor Processing Modules, Model T-800
EOF
  ATT_RES_3=$(curl -s -w "\n%{http_code}" -X POST "${APS_URL}/process-instances/${ID_3}/raw-content?isRelatedContent=true" \
    -H "Authorization: ${AUTH_HEADER}" \
    -F "file=@${SAMPLE_DOC_3};type=text/plain")
  ATT_CODE_3=$(echo "${ATT_RES_3}" | tail -n 1)
  if [ "${ATT_CODE_3}" = "200" ] || [ "${ATT_CODE_3}" = "201" ]; then
    log_success "Successfully attached specifications document to Instance 3!"
  else
    log_warn "Attachment upload returned HTTP ${ATT_CODE_3}: $(echo "${ATT_RES_3}" | sed '$d')"
  fi
  rm -f "${SAMPLE_DOC_3}"
fi

# 6. Verify Historic Process Query
FINAL_TOTAL=$(curl -s -X POST "${APS_URL}/historic-process-instances/query" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -d '{"size":10}' | jq -r '.total // 0')

log_success "Workflow seeding complete! Total workflow instances in APS: ${FINAL_TOTAL}"
exit 0
