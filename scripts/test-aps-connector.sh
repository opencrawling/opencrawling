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
# OpenCrawling - Alfresco Process Services (APS) Repository Connector Integration Test
#
# Description:
#   Dedicated integration test script for the APS Repository Connector
#   (`oc-aps-repository-connector`). It validates connection authentication,
#   readiness probes, workflow definition querying, historic process instance
#   scanning, and runs connector Maven integration tests against a live APS 26.2
#   instance running on Docker.
# ==============================================================================

set -euo pipefail

# Configuration
APS_HOST="${APS_HOST:-localhost}"
APS_PORT="${APS_PORT:-8088}"
APS_USERNAME="${APS_USERNAME:-admin@app.activiti.com}"
APS_PASSWORD="${APS_PASSWORD:-admin}"
APS_URL="${APS_URL:-http://${APS_HOST}:${APS_PORT}/activiti-app/api/enterprise}"
COMPOSE_FILE="oc-aps-repository-connector/docker/docker-compose-aps.yml"
TIMEOUT="${TIMEOUT:-240}"
START_LOCAL_CONTAINER=false
FORCE_CLEANUP="${FORCE_CLEANUP:-false}"

# Terminal Formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

echo -e "\n${BOLD}${YELLOW}================================================================================${NC}"
echo -e "${BOLD}${YELLOW}=== Starting OpenCrawling Alfresco Process Services (APS) Connector Integration Test ===${NC}"
echo -e "${BOLD}${YELLOW}================================================================================${NC}\n"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (curl, jq, docker, mvn)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }
command -v docker >/dev/null 2>&1 || { log_error "Docker is required but not installed."; exit 1; }

# Basic auth header encoding
AUTH_HEADER="Basic $(printf "%s:%s" "${APS_USERNAME}" "${APS_PASSWORD}" | base64 | tr -d '\n')"

# Auto-detect enterprise license from user home or workspace
DETECTED_LICENSE=""
if [ -n "${APS_LICENSE_FILE:-}" ] && [ -f "${APS_LICENSE_FILE}" ]; then
  DETECTED_LICENSE="${APS_LICENSE_FILE}"
elif [ -f "${HOME}/.activiti/enterprise-license/activiti.lic" ]; then
  DETECTED_LICENSE="${HOME}/.activiti/enterprise-license/activiti.lic"
elif [ -f "${SCRIPT_DIR}/../activiti.lic" ]; then
  DETECTED_LICENSE="${SCRIPT_DIR}/../activiti.lic"
elif [ -f "${SCRIPT_DIR}/../oc-aps-repository-connector/docker/activiti.lic" ]; then
  DETECTED_LICENSE="${SCRIPT_DIR}/../oc-aps-repository-connector/docker/activiti.lic"
fi

# Auto-detect transform license from user home or workspace
DETECTED_TRANSFORM_LICENSE=""
if [ -n "${APS_TRANSFORM_LICENSE_FILE:-}" ] && [ -f "${APS_TRANSFORM_LICENSE_FILE}" ]; then
  DETECTED_TRANSFORM_LICENSE="${APS_TRANSFORM_LICENSE_FILE}"
elif [ -f "${HOME}/.activiti/enterprise-license/transform.lic" ]; then
  DETECTED_TRANSFORM_LICENSE="${HOME}/.activiti/enterprise-license/transform.lic"
elif [ -f "${SCRIPT_DIR}/../transform.lic" ]; then
  DETECTED_TRANSFORM_LICENSE="${SCRIPT_DIR}/../transform.lic"
elif [ -f "${SCRIPT_DIR}/../oc-aps-repository-connector/docker/transform.lic" ]; then
  DETECTED_TRANSFORM_LICENSE="${SCRIPT_DIR}/../oc-aps-repository-connector/docker/transform.lic"
fi

TEMP_LIC_DIR=""
COMPOSE_ARGS=(-f "${COMPOSE_FILE}")
if [ -n "${DETECTED_LICENSE}" ] || [ -n "${DETECTED_TRANSFORM_LICENSE}" ]; then
  TEMP_LIC_DIR=$(mktemp -d)
  LIC_OVERRIDE="${TEMP_LIC_DIR}/docker-compose.license.override.yml"
  cat <<EOF > "${LIC_OVERRIDE}"
services:
  aps:
    volumes:
EOF
  if [ -n "${DETECTED_LICENSE}" ]; then
    log_info "Auto-detected APS enterprise license at '${DETECTED_LICENSE}'. Mounting into APS container on startup..."
    cat <<EOF >> "${LIC_OVERRIDE}"
      - "${DETECTED_LICENSE}:/home/alfresco/.activiti/enterprise-license/activiti.lic:ro"
      - "${DETECTED_LICENSE}:/usr/local/tomcat/lib/activiti.lic:ro"
EOF
  fi
  if [ -n "${DETECTED_TRANSFORM_LICENSE}" ]; then
    log_info "Auto-detected APS transform license at '${DETECTED_TRANSFORM_LICENSE}'. Mounting into APS container on startup..."
    cat <<EOF >> "${LIC_OVERRIDE}"
      - "${DETECTED_TRANSFORM_LICENSE}:/home/alfresco/.activiti/enterprise-license/transform.lic:ro"
      - "${DETECTED_TRANSFORM_LICENSE}:/usr/local/tomcat/lib/transform.lic:ro"
EOF
  fi
  COMPOSE_ARGS+=(-f "${LIC_OVERRIDE}")
fi

# Cleanup trap
cleanup() {
  if [ -n "${TEMP_LIC_DIR}" ] && [ -d "${TEMP_LIC_DIR}" ]; then
    rm -rf "${TEMP_LIC_DIR}"
  fi
  if [ "${START_LOCAL_CONTAINER}" = true ] || [ "${FORCE_CLEANUP}" = true ]; then
    log_info "Tearing down temporary APS Docker environment..."
    docker compose "${COMPOSE_ARGS[@]}" down >/dev/null 2>&1 || true
    log_success "Cleanup complete."
  fi
}
trap cleanup EXIT

# 2. Service Availability Check
log_info "Checking Alfresco Process Services availability at ${APS_URL}..."

PROBE_URL="${APS_URL}/profile"

is_aps_ready() {
  local http_code
  http_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 -H "Authorization: ${AUTH_HEADER}" "${PROBE_URL}" 2>/dev/null || echo "000")
  if [ "$http_code" = "200" ]; then
    return 0
  fi
  # Fallback check against query endpoint
  local query_code
  query_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 \
    -H "Authorization: ${AUTH_HEADER}" \
    -H "Content-Type: application/json" \
    -X POST -d '{"size":1}' "${APS_URL}/historic-process-instances/query" 2>/dev/null || echo "000")
  if [ "$query_code" = "200" ]; then
    return 0
  fi
  return 1
}

if ! is_aps_ready; then
  log_warn "APS 26.2 is not currently reachable at ${APS_URL}."
  log_info "Launching Alfresco Process Services 26.2 stack via Docker Compose (${COMPOSE_FILE})..."
  docker compose "${COMPOSE_ARGS[@]}" up -d
  START_LOCAL_CONTAINER=true

  log_info "Waiting for APS 26.2 REST service to become healthy and ready (timeout: ${TIMEOUT}s)..."
  ELAPSED=0
  until is_aps_ready; do
    if [ $ELAPSED -ge $TIMEOUT ]; then
      log_error "Timed out waiting for APS 26.2 to start up after ${TIMEOUT} seconds."
      docker compose -f "${COMPOSE_FILE}" logs aps --tail 50 || true
      exit 1
    fi
    sleep 5
    ELAPSED=$((ELAPSED + 5))
    echo -n "."
  done
  echo ""
  log_success "Alfresco Process Services 26.2 is ready and responding!"
else
  log_success "Alfresco Process Services 26.2 is already online and reachable!"
fi

# 3. Connection & Profile Validation
log_info "Testing APS 26.2 connection and authenticated user profile..."
PROFILE_RESPONSE=$(curl -s -w "\n%{http_code}" -H "Authorization: ${AUTH_HEADER}" -H "Accept: application/json" "${PROBE_URL}")
PROFILE_HTTP_CODE=$(echo "${PROFILE_RESPONSE}" | tail -n 1)
PROFILE_BODY=$(echo "${PROFILE_RESPONSE}" | sed '$d')

if [ "${PROFILE_HTTP_CODE}" = "200" ]; then
  USER_EMAIL=$(echo "${PROFILE_BODY}" | jq -r '.email // .username // "admin"')
  log_success "Successfully authenticated to APS as '${USER_EMAIL}' (HTTP ${PROFILE_HTTP_CODE})"
else
  log_warn "Profile probe returned HTTP ${PROFILE_HTTP_CODE}, validating via query API..."
fi

# 4. Deploy Sample Workflow & Seed Process Instances with Variables
log_info "Deploying sample BPMN workflow ('invoiceApproval') and seeding process instances..."
APS_URL="${APS_URL}" APS_USERNAME="${APS_USERNAME}" APS_PASSWORD="${APS_PASSWORD}" \
  "${SCRIPT_DIR}/seed-aps-workflows.sh"

log_info "Verifying APS Historic Process Instances Query REST endpoint..."
QUERY_RES=$(curl -s -w "\n%{http_code}" -X POST "${APS_URL}/historic-process-instances/query" \
  -H "Authorization: ${AUTH_HEADER}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"size":10}')

QUERY_CODE=$(echo "${QUERY_RES}" | tail -n 1)
QUERY_BODY=$(echo "${QUERY_RES}" | sed '$d')

if [ "${QUERY_CODE}" != "200" ]; then
  log_error "Failed to query process instances from APS: ${QUERY_BODY} (HTTP ${QUERY_CODE})"
  exit 1
fi

TOTAL_INSTANCES=$(echo "${QUERY_BODY}" | jq -r '.total // 0')
log_success "Query verified! Total available process instances in APS: ${TOTAL_INSTANCES}"

# 5. Execute Maven Integration Tests for APS Repository Connector
log_info "Running Maven integration test suite (ApsRepositoryConnectorIT) with live endpoint properties..."
mvn clean test -pl oc-aps-repository-connector \
  -Dtest="*Test,*IT" \
  -Daps.live.test=true \
  -Dspring.opencrawling.connector.aps.url="${APS_URL}" \
  -Dspring.opencrawling.connector.aps.username="${APS_USERNAME}" \
  -Dspring.opencrawling.connector.aps.password="${APS_PASSWORD}"

log_success "Maven connector unit and integration tests passed successfully!"

echo -e "\n=========================================================================="
log_success "All Alfresco Process Services Connector Integration Tests Passed Successfully! 🎉"
echo -e "==========================================================================\n"

exit 0
