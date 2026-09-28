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

# Integration test for docker-compose-decoupled-with-stormcrawler.yml
# Exit immediately if a command exits with a non-zero status
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling Decoupled Pipeline with Apache StormCrawler Integration Test ===${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Docker Compose is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}[ERROR] curl is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-stormcrawler-repository-connector/docker/docker-compose-decoupled-with-stormcrawler.yml"

# Helper function for docker compose commands
compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

# Clean up any existing containers
echo -e "${YELLOW}Cleaning up previous StormCrawler decoupled containers...${NC}"
compose down --remove-orphans || true

cleanup() {
  echo -e "${YELLOW}Cleaning up StormCrawler decoupled containers on exit...${NC}"
  compose down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Validate compose configuration syntax
echo -e "${YELLOW}Validating Docker Compose configuration syntax...${NC}"
compose config > /dev/null
echo -e "${GREEN}Docker Compose configuration is valid!${NC}"

# Start services
echo -e "${YELLOW}Starting decoupled StormCrawler infrastructure services...${NC}"
compose up -d zookeeper storm-nimbus storm-supervisor storm-ui postgres-vector redis kafka

# Wait for Storm UI REST API to be healthy
TIMEOUT=120
ELAPSED=0
echo -e "${YELLOW}Waiting for Apache Storm UI to become healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' storm-ui-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}[ERROR] Timeout waiting for Storm UI decoupled container.${NC}"
    compose logs storm-ui
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  echo -n "."
done
echo ""
echo -e "${GREEN}Apache Storm UI is healthy!${NC}"

# Query Storm Cluster Summary via REST
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8088/api/v1/cluster/summary" || echo "000")
if [ "$HTTP_CODE" -eq 200 ]; then
  echo -e "${GREEN}Storm REST API returned HTTP 200 OK!${NC}"
else
  echo -e "${RED}[ERROR] Expected HTTP 200 from Storm REST API, got ${HTTP_CODE}${NC}"
  exit 1
fi

echo -e "${GREEN}=== StormCrawler Decoupled Pipeline Integration Test Passed Successfully! ===${NC}"
