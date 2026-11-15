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
# OpenCrawling - Hyland OpenArch Connector Integration Test Script
#
# Description:
#   Validates OpenArch source harvesting, Parquet vector bridging (embedding.avsc),
#   Zero-Trust ACL preservation, and MCP search/fetch backend integration.
#   Probes live OpenArch service if reachable at OPENARCH_URL, otherwise runs the
#   isolated MockWebServer unit and connector verification test suite.
# ==============================================================================

set -euo pipefail

# Configuration
OPENARCH_URL="${OPENARCH_URL:-http://localhost:8080}"
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

echo -e "${YELLOW}=== Starting OpenCrawling Hyland OpenArch Integration Test Suite ===${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (curl, jq, mvn)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log_error "jq is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

# 2. Check if a live OpenArch instance is reachable
log_info "Probing Hyland OpenArch REST API at ${OPENARCH_URL}..."
HEALTH_CHECK=$(curl -s --connect-timeout 3 --max-time "${TIMEOUT}" -H "Accept: application/json" "${OPENARCH_URL}/api/status" 2>/dev/null || true)
if [ -n "${HEALTH_CHECK}" ]; then
  log_success "Live OpenArch instance detected at ${OPENARCH_URL}!"
  log_info "Status response: ${HEALTH_CHECK}"
else
  log_warn "No live OpenArch instance responding at ${OPENARCH_URL}."
  log_info "Proceeding with isolated MockWebServer and Parquet Avro test harness suite."
fi

# 3. Run Maven Test Suite for OpenArch modules
log_info "Executing Maven test suite for oc-openarch-output-connector, oc-openarch-repository-connector, and oc-runtime..."
mvn test -pl oc-openarch-output-connector,oc-openarch-repository-connector,oc-runtime -am

log_success "All Hyland OpenArch Integration tests passed successfully!"
