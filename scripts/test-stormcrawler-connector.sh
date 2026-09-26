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

# Dedicated Integration Test Script for Apache StormCrawler Repository Connector
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling Apache StormCrawler Connector Integration Test ===${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${BLUE}[INFO] Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}[ERROR] curl is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-stormcrawler-repository-connector/docker/docker-compose.yml"
NIMBUS_HOST="${STORMCRAWLER_NIMBUS_HOST:-localhost}"
NIMBUS_PORT="${STORMCRAWLER_NIMBUS_PORT:-6627}"
STORM_UI_PORT="${STORMCRAWLER_UI_PORT:-8088}"
STORM_UI_URL="${STORMCRAWLER_NIMBUS_REST_URL:-http://${NIMBUS_HOST}:${STORM_UI_PORT}}"

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

CLEANUP_REQUIRED=false

cleanup() {
  if [ "$CLEANUP_REQUIRED" = true ]; then
    echo -e "${YELLOW}Cleaning up Storm test containers...${NC}"
    compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# Check if an existing Storm UI is already running
if curl -fsSL -o /dev/null -m 2 "${STORM_UI_URL}/api/v1/cluster/summary" 2>/dev/null; then
  echo -e "${GREEN}Detected running Storm UI REST API at ${STORM_UI_URL}.${NC}"
else
  echo -e "${YELLOW}Starting Apache Storm cluster (Zookeeper, Nimbus, Supervisor, UI) via ${COMPOSE_FILE}...${NC}"
  CLEANUP_REQUIRED=true
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  compose up -d

  echo -e "${YELLOW}Waiting for Storm UI REST API to become ready at ${STORM_UI_URL}/api/v1/cluster/summary ...${NC}"
  TIMEOUT=120
  ELAPSED=0
  HEALTHY=false

  until [ "$HEALTHY" = true ]; do
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -m 3 "${STORM_UI_URL}/api/v1/cluster/summary" || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
      HEALTHY=true
      break
    fi

    if [ $ELAPSED -ge $TIMEOUT ]; then
      echo -e "${RED}[ERROR] Timeout waiting for Storm UI REST API after ${TIMEOUT}s (last HTTP status: ${HTTP_CODE}).${NC}"
      compose logs --tail 40
      exit 1
    fi
    sleep 3
    ELAPSED=$((ELAPSED + 3))
    echo -n "."
  done
  echo ""
  echo -e "${GREEN}Apache Storm UI and Nimbus REST API are ready! (HTTP 200)${NC}"
fi

# Query and display cluster summary from live Storm REST API
echo -e "${BLUE}[INFO] Querying Storm cluster summary from ${STORM_UI_URL}/api/v1/cluster/summary ...${NC}"
CLUSTER_SUMMARY=$(curl -s "${STORM_UI_URL}/api/v1/cluster/summary" || echo "{}")
echo -e "${BLUE}[INFO] Cluster response: ${CLUSTER_SUMMARY}${NC}"

# Run Maven tests for oc-stormcrawler-bolt and oc-stormcrawler-repository-connector
echo -e "${YELLOW}Running Maven tests for oc-stormcrawler-bolt and oc-stormcrawler-repository-connector...${NC}"
mvn clean test -pl oc-stormcrawler-bolt,oc-stormcrawler-repository-connector \
  -Dspring.opencrawling.connector.stormcrawler.nimbus-host="${NIMBUS_HOST}" \
  -Dspring.opencrawling.connector.stormcrawler.nimbus-port="${NIMBUS_PORT}" \
  -Dspring.opencrawling.connector.stormcrawler.nimbus-rest-url="${STORM_UI_URL}"

# Also run ConnectorCheckerServiceTest in oc-runtime
echo -e "${YELLOW}Running ConnectorCheckerServiceTest in oc-runtime...${NC}"
mvn test -pl oc-runtime -Dtest=ConnectorCheckerServiceTest

echo -e "${GREEN}=== Apache StormCrawler Connector Integration Test PASSED Successfully ===${NC}"
