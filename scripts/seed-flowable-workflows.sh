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
# OpenCrawling - Flowable BPMN Engine Workflow Seeder
#
# Description:
#   Deploys a sample BPMN 2.0 workflow ('Invoice Approval') into Flowable REST
#   and starts process instances populated with process variables for testing.
# ==============================================================================

set -euo pipefail

FLOWABLE_HOST="${FLOWABLE_HOST:-localhost}"
FLOWABLE_PORT="${FLOWABLE_PORT:-8088}"
FLOWABLE_USERNAME="${FLOWABLE_USERNAME:-rest-admin}"
FLOWABLE_PASSWORD="${FLOWABLE_PASSWORD:-test}"
FLOWABLE_URL="${FLOWABLE_URL:-http://${FLOWABLE_HOST}:${FLOWABLE_PORT}/flowable-rest/service}"

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

log_info "Target Flowable Endpoint: ${FLOWABLE_URL}"

# 1. Check existing process instances
EXISTING_TOTAL=$(curl -s -u "${FLOWABLE_USERNAME}:${FLOWABLE_PASSWORD}" "${FLOWABLE_URL}/history/historic-process-instances?size=1" 2>/dev/null | jq -r '.total // 0' || echo "0")

if [ "${EXISTING_TOTAL}" -gt 0 ]; then
  log_success "Flowable already contains ${EXISTING_TOTAL} process instance(s). Seeding skipped."
  exit 0
fi

log_info "No workflow instances found in Flowable. Initializing workflow deployment and seeding..."

# 2. Deploy BPMN Model into Flowable
BPMN_FILE="${PROJECT_ROOT}/oc-flowable-repository-connector/src/test/resources/sample-workflows/invoiceApproval.bpmn20.xml"
if [ ! -f "${BPMN_FILE}" ]; then
  BPMN_FILE="${PROJECT_ROOT}/oc-aps-repository-connector/src/test/resources/sample-workflows/invoiceApproval.bpmn20.xml"
fi

if [ ! -f "${BPMN_FILE}" ]; then
  log_error "Missing sample workflow definition file: ${BPMN_FILE}"
  exit 1
fi

log_info "Deploying BPMN process model ('invoiceApproval') into Flowable..."
DEPLOY_RES=$(curl -s -w "\n%{http_code}" -u "${FLOWABLE_USERNAME}:${FLOWABLE_PASSWORD}" -X POST "${FLOWABLE_URL}/repository/deployments" \
  -F "file=@${BPMN_FILE}")
DEPLOY_CODE=$(echo "${DEPLOY_RES}" | tail -n 1)
DEPLOY_BODY=$(echo "${DEPLOY_RES}" | sed '$d')

if [ "${DEPLOY_CODE}" != "200" ] && [ "${DEPLOY_CODE}" != "201" ]; then
  log_error "Failed to deploy process model: ${DEPLOY_BODY} (HTTP ${DEPLOY_CODE})"
  exit 1
fi

DEPLOY_ID=$(echo "${DEPLOY_BODY}" | jq -r '.id // empty')
log_success "Deployed Process Model with Deployment ID: ${DEPLOY_ID}"

# 3. Start Sample Workflow Instances with Variables
log_info "Starting sample workflow instances with business variables..."

INSTANCE_1_PAYLOAD=$(cat <<EOF
{
  "processDefinitionKey": "invoiceApproval",
  "name": "Invoice #INV-2026-FLOWABLE-001 - ACME Industrial Supplies",
  "variables": [
    {"name": "invoiceNumber", "value": "INV-2026-FLOWABLE-001"},
    {"name": "amount", "value": 14500.50},
    {"name": "vendor", "value": "ACME Corporation"},
    {"name": "department", "value": "Engineering"},
    {"name": "priority", "value": "HIGH"},
    {"name": "approved", "value": false}
  ]
}
EOF
)

RES_1=$(curl -s -u "${FLOWABLE_USERNAME}:${FLOWABLE_PASSWORD}" -X POST "${FLOWABLE_URL}/runtime/process-instances" \
  -H "Content-Type: application/json" \
  -d "${INSTANCE_1_PAYLOAD}")
ID_1=$(echo "${RES_1}" | jq -r '.id // empty')
log_success "Started Instance 1 [ID: ${ID_1}] - Invoice #INV-2026-FLOWABLE-001 (\$14,500.50)"

INSTANCE_2_PAYLOAD=$(cat <<EOF
{
  "processDefinitionKey": "invoiceApproval",
  "name": "Invoice #INV-2026-FLOWABLE-002 - Cyberdyne AI Solutions",
  "variables": [
    {"name": "invoiceNumber", "value": "INV-2026-FLOWABLE-002"},
    {"name": "amount", "value": 89000.00},
    {"name": "vendor", "value": "Cyberdyne Systems"},
    {"name": "department", "value": "Research & Development"},
    {"name": "priority", "value": "CRITICAL"},
    {"name": "approved", "value": true}
  ]
}
EOF
)

RES_2=$(curl -s -u "${FLOWABLE_USERNAME}:${FLOWABLE_PASSWORD}" -X POST "${FLOWABLE_URL}/runtime/process-instances" \
  -H "Content-Type: application/json" \
  -d "${INSTANCE_2_PAYLOAD}")
ID_2=$(echo "${RES_2}" | jq -r '.id // empty')
log_success "Started Instance 2 [ID: ${ID_2}] - Invoice #INV-2026-FLOWABLE-002 (\$89,000.00)"

# 4. Verify Historic Process Query
FINAL_TOTAL=$(curl -s -u "${FLOWABLE_USERNAME}:${FLOWABLE_PASSWORD}" "${FLOWABLE_URL}/history/historic-process-instances?size=10" | jq -r '.total // 0')
log_success "Workflow seeding complete! Total workflow instances in Flowable: ${FINAL_TOTAL}"
exit 0
