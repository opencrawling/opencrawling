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
# OpenCrawling - Doxis Repository Connector Integration Test Script
#
# Description:
#   Probes a Doxis 4 CSB REST API when one is reachable (unauthenticated
#   GET /customers, read-only) and runs the Maven test suites of oc-doxis-client
#   and oc-doxis-repository-connector (MockWebServer with Doxis CSB 14.4.1
#   response fixtures). Doxis is a licensed product: this script never starts
#   one and never writes to one.
# ==============================================================================

set -euo pipefail

# Configuration
DOXIS_URL="${DOXIS_URL:-http://localhost:8080/restws/publicws/rest/api/v1}"
TIMEOUT="${TIMEOUT:-10}"

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

echo -e "${YELLOW}=== Starting OpenCrawling Doxis Repository Connector Test Suite ===${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (curl, jq, mvn)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

# 2. Check if a live Doxis CSB REST API is reachable (read-only, no login)
log_info "Probing Doxis CSB REST API at ${DOXIS_URL}..."
CUSTOMERS=$(curl -s --connect-timeout 3 --max-time "${TIMEOUT}" -H "Accept: application/json" "${DOXIS_URL}/customers" 2>/dev/null || true)
if [ -n "${CUSTOMERS}" ] && echo "${CUSTOMERS}" | jq -e 'type == "array"' >/dev/null 2>&1; then
  log_success "Live Doxis CSB REST API is reachable at ${DOXIS_URL}!"
  log_info "Customers (tenants) offered by the CSB:"
  echo "${CUSTOMERS}" | jq -r '.[] | (.name // .customerName // .)' 2>/dev/null || echo "${CUSTOMERS}"
  log_info "For a read-only live crawl, see 'Read-only live probe' in oc-doxis-repository-connector/README.md."
else
  log_warn "No Doxis CSB REST API is reachable at ${DOXIS_URL}."
  log_info "Will execute the isolated embedded mock HTTP server test suite."
fi

# 3. Run Maven Test Suite
log_info "Executing Maven test suite for oc-doxis-client and oc-doxis-repository-connector..."
mvn test -pl oc-doxis-client,oc-doxis-repository-connector -am

log_success "All Doxis Repository Connector tests passed successfully!"
