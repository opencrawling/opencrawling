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
# OpenCrawling - OASIS CMIS Repository Connector Integration Test Script
#
# Description:
#   Validates connection authentication, repository discovery, CMISQL querying,
#   and runs the Maven test suite for oc-cmis-repository-connector.
# ==============================================================================

set -euo pipefail

# Configuration
CMIS_HOST="${CMIS_HOST:-localhost}"
CMIS_PORT="${CMIS_PORT:-8080}"
CMIS_USERNAME="${CMIS_USERNAME:-admin}"
CMIS_PASSWORD="${CMIS_PASSWORD:-admin}"
CMIS_ENDPOINT_URL="${CMIS_ENDPOINT_URL:-http://${CMIS_HOST}:${CMIS_PORT}/alfresco/api/-default-/public/cmis/versions/1.1/browser}"
TIMEOUT="${TIMEOUT:-60}"

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

echo -e "${YELLOW}=== Starting OpenCrawling OASIS CMIS Repository Connector Test Suite ===${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (curl, jq, mvn)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

# Basic auth header encoding
AUTH_HEADER="Basic $(printf "%s:%s" "${CMIS_USERNAME}" "${CMIS_PASSWORD}" | base64 | tr -d '\n')"

# 2. Check if a live CMIS endpoint is available
log_info "Probing CMIS endpoint at ${CMIS_ENDPOINT_URL}..."
is_cmis_ready() {
  local http_code
  http_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 -H "Authorization: ${AUTH_HEADER}" -H "Accept: application/json" "${CMIS_ENDPOINT_URL}" 2>/dev/null || echo "000")
  if [ "$http_code" -ge "200" ] && [ "$http_code" -lt "400" ]; then
    return 0
  fi
  return 1
}

if is_cmis_ready; then
  log_success "Live CMIS endpoint is reachable at ${CMIS_ENDPOINT_URL}!"
  REPO_RESPONSE=$(curl -s -H "Authorization: ${AUTH_HEADER}" -H "Accept: application/json" "${CMIS_ENDPOINT_URL}")
  log_info "Discovered Repository Info from endpoint:"
  echo "${REPO_RESPONSE}" | jq . 2>/dev/null || echo "${REPO_RESPONSE}"
else
  log_warn "Live CMIS endpoint is not reachable at ${CMIS_ENDPOINT_URL}."
  log_info "Will execute isolated embedded mock HTTP server test suite."
fi

# 3. Run Maven Test Suite
log_info "Executing Maven test suite for oc-cmis-repository-connector..."
mvn test -pl oc-cmis-repository-connector -am

log_success "All OASIS CMIS Repository Connector tests passed successfully!"
