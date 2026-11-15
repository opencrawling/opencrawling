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

# Optional helper: registers the Alfresco_Tutorial connector, tests its live
# connection, creates Alfresco_Tutorial_PGVector_Job, and optionally triggers
# execution via the OpenCrawling Admin REST API (the exact same calls made by
# oc-admin-ui on http://localhost:3000).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TUTORIAL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OPENCRAWLING_API="${OPENCRAWLING_API:-http://localhost:8080}"
RUN_JOB="${RUN_JOB:-true}"

BLUE='\033[0;34m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_ok()   { echo -e "${GREEN}[OK]${NC} $*"; }
log_err()  { echo -e "${RED}[ERROR]${NC} $*"; }

for cmd in curl jq; do
  command -v "$cmd" >/dev/null 2>&1 || { log_err "Required command '$cmd' not found."; exit 1; }
done

# 1. Test Alfresco connector connectivity
log_info "Testing Alfresco_Tutorial connectivity from OpenCrawling backend..."
CHECK_RES=$(curl -sf -X POST "${OPENCRAWLING_API}/api/connectors/check" \
  -H "Content-Type: application/json" \
  -d @"${TUTORIAL_DIR}/config/alfresco-connector.json")
echo "$CHECK_RES" | jq .
if [ "$(echo "$CHECK_RES" | jq -r '.success')" != "true" ]; then
  log_err "Alfresco connection check failed."
  exit 1
fi
log_ok "Alfresco connection check succeeded."

# 2. Save Alfresco_Tutorial connector
log_info "Saving Alfresco_Tutorial connector..."
CONN_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${OPENCRAWLING_API}/api/connectors" \
  -H "Content-Type: application/json" \
  -d @"${TUTORIAL_DIR}/config/alfresco-connector.json")
if [ "$CONN_CODE" != "201" ] && [ "$CONN_CODE" != "200" ]; then
  log_err "Failed saving connector (HTTP ${CONN_CODE})."
  exit 1
fi
log_ok "Saved Alfresco_Tutorial connector."

# 3. Create (or reuse) Alfresco_Tutorial_PGVector_Job
JOB_NAME=$(jq -r '.name' "${TUTORIAL_DIR}/config/alfresco-pgvector-job.json")
EXISTING_JOB_ID=$(curl -sf "${OPENCRAWLING_API}/api/jobs" | jq -r --arg name "$JOB_NAME" '.[] | select(.name == $name) | .id' | head -n 1)

if [ -n "$EXISTING_JOB_ID" ]; then
  log_info "Reusing existing job '${JOB_NAME}' (id=${EXISTING_JOB_ID})."
  JOB_ID="$EXISTING_JOB_ID"
else
  log_info "Creating job '${JOB_NAME}'..."
  JOB_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${OPENCRAWLING_API}/api/jobs" \
    -H "Content-Type: application/json" \
    -d @"${TUTORIAL_DIR}/config/alfresco-pgvector-job.json")
  if [ "$JOB_CODE" != "201" ] && [ "$JOB_CODE" != "200" ]; then
    log_err "Failed creating job (HTTP ${JOB_CODE})."
    exit 1
  fi
  JOB_ID=$(curl -sf "${OPENCRAWLING_API}/api/jobs" | jq -r --arg name "$JOB_NAME" '.[] | select(.name == $name) | .id' | tail -n 1)
  log_ok "Created job '${JOB_NAME}' (id=${JOB_ID})."
fi

if [ "${RUN_JOB}" != "true" ]; then
  log_info "RUN_JOB=false set. Trigger the job manually from http://localhost:3000 -> Job Management."
  exit 0
fi

# 4. Start the job and wait for completion
log_info "Starting job id=${JOB_ID}..."
START_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${OPENCRAWLING_API}/api/jobs/${JOB_ID}/start")
if [ "$START_CODE" != "200" ] && [ "$START_CODE" != "202" ]; then
  log_err "Failed starting job ${JOB_ID} (HTTP ${START_CODE})."
  exit 1
fi

log_info "Waiting for job ${JOB_ID} to finish..."
ELAPSED=0
while true; do
  JOB_STATE=$(curl -sf "${OPENCRAWLING_API}/api/jobs/${JOB_ID}")
  STATUS=$(echo "$JOB_STATE" | jq -r '.status')
  STAGE=$(echo "$JOB_STATE" | jq -r '.currentStage')
  DOCS=$(echo "$JOB_STATE" | jq -r '.documents')
  if [ "$STATUS" = "Finished" ]; then
    log_ok "Job ${JOB_ID} finished (stage=${STAGE}, documents=${DOCS})."
    break
  elif [ "$STATUS" = "Error" ]; then
    log_err "Job ${JOB_ID} ended with Error (stage=${STAGE})."
    exit 1
  fi
  if [ "$ELAPSED" -ge 120 ]; then
    log_err "Timed out waiting for job ${JOB_ID} (last status=${STATUS}, stage=${STAGE})."
    exit 1
  fi
  sleep 3
  ELAPSED=$((ELAPSED + 3))
done

log_info "Waiting 10s for decoupled Kafka consumers (ingestion -> embedding -> writer) to persist vectors..."
sleep 10
log_ok "Done. Check Reports & Audit at http://localhost:3000 or run verify-tutorial.sh."
