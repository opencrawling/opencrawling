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

# End-to-End Integration Test Script for OpenCrawling Java Client SDK (oc-java-client-sdk)
# Boots OpenCrawling Runtime via Docker Compose (using locally built images) and verifies all SDK services live.
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}=== OpenCrawling Java Client SDK Full Integration Test Suite ===${NC}"
echo -e "${YELLOW}================================================================================${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${CYAN}[INFO] Switched to project root: $(pwd)${NC}"

# Check prerequisites
command -v mvn >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Maven is required but not installed. Aborting.${NC}" >&2; exit 1; }

# Step 1: Run unit & mock server tests
echo -e "${CYAN}[INFO] Step 1: Compiling & running mock-server integration tests...${NC}"
mvn clean test -pl oc-java-client-sdk -Dtest='!LiveSystemIntegrationTest'

echo -e "${CYAN}[INFO] Step 2: Verifying license headers...${NC}"
mvn com.mycila:license-maven-plugin:check -pl oc-java-client-sdk

# Step 3: Docker Compose Live Container Testing
USE_DOCKER=true
if [ "$1" == "--skip-docker" ] || [ "$SKIP_DOCKER" == "true" ]; then
  USE_DOCKER=false
fi

if [ "$USE_DOCKER" = true ]; then
  if ! command -v docker >/dev/null 2>&1; then
    echo -e "${YELLOW}[WARN] Docker not detected. Skipping live container integration phase.${NC}"
  else
    echo -e "${CYAN}[INFO] Step 3: Building & booting OpenCrawling stack via Docker Compose (local images)...${NC}"

    # Helper function for docker compose
    compose() {
      if [ -f "docker-compose-decoupled.yml" ]; then
        docker compose -f docker-compose-decoupled.yml "$@"
      else
        docker compose -f docker-compose.yml -f docker-compose-apps.yml "$@"
      fi
    }

    # Ensure clean state & teardown trap
    cleanup() {
      echo -e "${CYAN}[INFO] Cleaning up Docker containers...${NC}"
      compose down --remove-orphans >/dev/null 2>&1 || true
    }
    trap cleanup EXIT

    echo -e "${CYAN}[INFO] Tearing down previous containers...${NC}"
    compose down --remove-orphans || true

    echo -e "${CYAN}[INFO] Building local microservice Docker images from source...${NC}"
    compose build

    echo -e "${CYAN}[INFO] Starting containers in background...${NC}"
    compose up -d

    echo -e "${CYAN}[INFO] Waiting for OpenCrawling Runtime REST API to become healthy (http://localhost:8080)...${NC}"
    TIMEOUT=180
    ELAPSED=0
    HEALTHY=false

    until [ "$HEALTHY" = true ] || [ $ELAPSED -ge $TIMEOUT ]; do
      HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/system/status || echo "000")
      if [ "$HTTP_CODE" = "200" ]; then
        HEALTHY=true
      else
        sleep 3
        ELAPSED=$((ELAPSED + 3))
        printf "  Waiting for REST API... (%ds elapsed, last HTTP status: %s)\r" "$ELAPSED" "$HTTP_CODE"
      fi
    done
    echo ""

    if [ "$HEALTHY" = false ]; then
      echo -e "${RED}[ERROR] Timeout reached waiting for OpenCrawling Runtime backend (8080).${NC}"
      echo -e "${YELLOW}[DIAGNOSTICS] Printing container logs:${NC}"
      compose logs --tail=50
      exit 1
    fi

    echo -e "${GREEN}[OK] OpenCrawling Runtime REST API is live and healthy!${NC}"

    # Step 4: Run Live System Integration Test via Java SDK
    echo -e "${CYAN}[INFO] Step 4: Executing LiveSystemIntegrationTest against local Docker stack...${NC}"
    export OPENCRAWLING_LIVE_TEST=true
    export OPENCRAWLING_BASE_URL="http://localhost:8080"

    mvn test -pl oc-java-client-sdk -Dtest=LiveSystemIntegrationTest

    echo -e "${GREEN}[OK] Live System SDK integration tests passed!${NC}"
  fi
fi

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: OpenCrawling Java Client SDK Integration Test Suite Completed!${NC}"
echo -e "${GREEN}Covered Services: JobClient, ConnectorClient, SystemClient, ObservabilityClient, CopilotClient, ReportClient, OpenApiClient, DiscoveryClient${NC}"
echo -e "${GREEN}================================================================================${NC}"

exit 0
