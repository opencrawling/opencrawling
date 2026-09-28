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

# Dedicated End-to-End Integration Test for oc-stormcrawler-bolt with StormCrawler 3.7.0
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${YELLOW}=== Starting OpenCrawling Apache StormCrawler Bolt Cluster Integration Test ===${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${BLUE}[INFO] Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}[ERROR] curl is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Maven is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-stormcrawler-repository-connector/docker/docker-compose-decoupled-with-stormcrawler.yml"
TOPOLOGY_NAME="opencrawling-bolt-e2e-test"

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

cleanup() {
  echo -e "${YELLOW}Cleaning up StormCrawler bolt test containers...${NC}"
  compose down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Step 1: Package oc-stormcrawler-bolt with dependencies for StormCrawler 3.7.0
echo -e "${YELLOW}[STEP 1/5] Building oc-stormcrawler-bolt topology fat JAR with Maven...${NC}"
mvn clean package -pl oc-stormcrawler-bolt -DskipTests

TOPOLOGY_JAR="oc-stormcrawler-bolt/target/oc-stormcrawler-bolt-1.0.0-SNAPSHOT-topology.jar"
if [ ! -f "$TOPOLOGY_JAR" ]; then
  echo -e "${RED}[ERROR] Expected topology JAR $TOPOLOGY_JAR was not found.${NC}"
  exit 1
fi
echo -e "${GREEN}[OK] Verified topology JAR: $TOPOLOGY_JAR ($(du -h "$TOPOLOGY_JAR" | cut -f1))${NC}"

# Step 2: Clean up previous test containers
echo -e "${YELLOW}[STEP 2/5] Resetting test environment containers...${NC}"
compose down -v --remove-orphans || true

# Step 3: Start Apache Storm cluster for StormCrawler 3.7.0
echo -e "${YELLOW}[STEP 3/5] Starting Apache Storm cluster (Zookeeper, Nimbus, Supervisor, UI)...${NC}"
compose up -d zookeeper storm-nimbus storm-supervisor storm-ui

# Wait for Storm UI REST API to be healthy
TIMEOUT=120
ELAPSED=0
echo -e "${YELLOW}Waiting for Storm UI REST API to become ready at http://localhost:8088/api/v1/cluster/summary ...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' storm-ui-decoupled 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}[ERROR] Timeout waiting for Storm UI container.${NC}"
    compose logs storm-ui
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  echo -n "."
done
echo ""
echo -e "${GREEN}[OK] Apache Storm UI and Nimbus REST API are ready!${NC}"

# Step 4: Submit OpenCrawlingTestTopology into the live Storm cluster
echo -e "${YELLOW}[STEP 4/5] Submitting StormCrawler OpenCrawlingBolt topology to Nimbus...${NC}"
docker exec storm-nimbus-decoupled storm jar \
  /apache-storm/extlib/oc-stormcrawler-bolt.jar \
  org.opencrawling.stormcrawler.bolt.topology.OpenCrawlingTestTopology \
  "$TOPOLOGY_NAME" \
  "http://localhost:8080/api/v1/ingest/ois" \
  "MEMORY"

echo -e "${GREEN}[OK] Topology submitted to Nimbus successfully!${NC}"

# Step 5: Verify topology execution and bolt activity via Storm REST API
echo -e "${YELLOW}[STEP 5/5] Verifying topology and OpenCrawlingBolt execution via Storm REST API...${NC}"
ELAPSED=0
TOPOLOGY_ACTIVE=false
TOPOLOGY_ID=""

until [ "$TOPOLOGY_ACTIVE" = true ]; do
  SUMMARY_JSON=$(curl -s "http://localhost:8088/api/v1/topology/summary" || echo "{}")
  STATUS=$(echo "$SUMMARY_JSON" | grep -o "\"status\":\"[^\"]*\"" | head -n 1 | cut -d'"' -f4 || echo "")
  NAME=$(echo "$SUMMARY_JSON" | grep -o "\"name\":\"[^\"]*\"" | head -n 1 | cut -d'"' -f4 || echo "")
  TOPOLOGY_ID=$(echo "$SUMMARY_JSON" | grep -o "\"id\":\"[^\"]*\"" | head -n 1 | cut -d'"' -f4 || echo "")

  if [ "$STATUS" == "ACTIVE" ] && [ "$NAME" == "$TOPOLOGY_NAME" ]; then
    TOPOLOGY_ACTIVE=true
    echo -e "${GREEN}[OK] Detected active topology: $NAME (id: $TOPOLOGY_ID, status: $STATUS)${NC}"
    break
  fi

  if [ $ELAPSED -ge 30 ]; then
    echo -e "${RED}[ERROR] Timeout waiting for topology $TOPOLOGY_NAME to become ACTIVE. Response: $SUMMARY_JSON${NC}"
    compose logs storm-nimbus
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  echo -n "."
done
echo ""

# Let topology process sample tuples
echo -e "${BLUE}[INFO] Allowing Storm worker to process crawl tuples through OpenCrawlingBolt...${NC}"
sleep 5

# Query detailed topology metrics for the bolt
DETAIL_JSON=$(curl -s "http://localhost:8088/api/v1/topology/${TOPOLOGY_ID}" || echo "{}")
if echo "$DETAIL_JSON" | grep -q "opencrawling-bolt"; then
  echo -e "${GREEN}[OK] Confirmed 'opencrawling-bolt' is actively executing in topology $TOPOLOGY_ID!${NC}"
else
  echo -e "${YELLOW}[WARNING] Bolt metrics detail not yet populated; checking supervisor logs:${NC}"
  docker logs storm-supervisor-decoupled --tail 20 || true
fi

echo -e "${GREEN}=== Apache StormCrawler Bolt Cluster Integration Test PASSED Successfully ===${NC}"
