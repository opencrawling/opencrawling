#!/usr/bin/env bash
#
# Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

# Integration test script for Flowable Repository Connector using docker compose / Flowable REST container
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling Flowable Connector Integration Test ===${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }

CONTAINER_NAME="opencrawling-flowable-test"
FLOWABLE_PORT=8088

cleanup() {
  echo -e "${YELLOW}Cleaning up Flowable test container...${NC}"
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cleanup

echo -e "${YELLOW}Starting Flowable REST container (${CONTAINER_NAME})...${NC}"
docker run -d --name "$CONTAINER_NAME" \
  -p ${FLOWABLE_PORT}:8080 \
  flowable/flowable-rest:7.1.0

echo -e "${YELLOW}Waiting for Flowable REST service to be healthy at http://localhost:${FLOWABLE_PORT}/flowable-rest/service ...${NC}"

TIMEOUT=120
ELAPSED=0
HEALTHY=false

until [ "$HEALTHY" = true ]; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -u rest-admin:test "http://localhost:${FLOWABLE_PORT}/flowable-rest/service/history/historic-process-instances?size=1" || true)
  if [ "$HTTP_CODE" -eq 200 ]; then
    HEALTHY=true
    break
  fi

  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for Flowable REST service (last HTTP status: ${HTTP_CODE}).${NC}"
    docker logs "$CONTAINER_NAME" --tail 50
    exit 1
  fi
  sleep 3
  ELAPSED=$((ELAPSED + 3))
done

echo -e "${GREEN}Flowable REST service is online and healthy!${NC}"

echo -e "${YELLOW}Running Maven integration tests for oc-flowable-repository-connector...${NC}"
mvn test -pl oc-flowable-repository-connector \
  -Dspring.opencrawling.connector.flowable.url="http://localhost:${FLOWABLE_PORT}/flowable-rest/service" \
  -Dspring.opencrawling.connector.flowable.username="rest-admin" \
  -Dspring.opencrawling.connector.flowable.password="test" \
  -Dspring.opencrawling.connector.flowable.history-level="audit"

echo -e "${GREEN}=== Flowable Repository Connector Integration Test Passed Successfully! ===${NC}"
