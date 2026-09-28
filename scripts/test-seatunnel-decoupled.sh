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

# Integration test for Apache SeaTunnel v2.3.13 Output Connector
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling Apache SeaTunnel v2.3.13 Integration Test ===${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}curl is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-seatunnel-output-connector/docker/docker-compose.yml"

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

echo -e "${YELLOW}Cleaning up previous SeaTunnel containers...${NC}"
compose down --remove-orphans || true

cleanup() {
  echo -e "${YELLOW}Cleaning up test environment...${NC}"
  compose down --remove-orphans || true
}
trap cleanup EXIT

echo -e "${YELLOW}Starting SeaTunnel Zeta Cluster v2.3.13...${NC}"
compose up -d

echo -e "${YELLOW}Waiting for SeaTunnel Zeta cluster to become healthy...${NC}"
SEATUNNEL_READY=false
for i in {1..30}; do
  if curl -s "http://localhost:8080/overview" > /dev/null 2>&1; then
    SEATUNNEL_READY=true
    echo -e "${GREEN}SeaTunnel Zeta cluster is responsive (HTTP 200 on /overview).${NC}"
    break
  fi
  echo "Attempt $i/30: SeaTunnel not ready yet. Waiting 2s..."
  sleep 2
done

if [ "$SEATUNNEL_READY" = false ]; then
  echo -e "${RED}SeaTunnel cluster failed to start within timeout.${NC}"
  compose logs
  exit 1
fi

echo -e "${YELLOW}Querying SeaTunnel cluster overview...${NC}"
OVERVIEW_RESP=$(curl -s "http://localhost:8080/overview")
echo "Overview response: $OVERVIEW_RESP"

echo -e "${YELLOW}Querying SeaTunnel jobs endpoint...${NC}"
JOBS_RESP=$(curl -s "http://localhost:8080/jobs")
echo "Jobs response: $JOBS_RESP"

# Verify Apache SeaTunnel Output Connector and lifecycle actions
echo -e "${YELLOW}Executing Apache SeaTunnel Output Connector unit & integration tests...${NC}"
mvn test -pl oc-seatunnel-output-connector
echo -e "${GREEN}Apache SeaTunnel Output Connector tests passed!${NC}"

echo -e "${GREEN}=== Apache SeaTunnel v2.3.13 Integration Test Completed Successfully! ===${NC}"
