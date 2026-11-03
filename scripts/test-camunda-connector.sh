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

# Dedicated Integration Test Script for Camunda 8 (Zeebe & REST API v2) Repository Connector
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}=== Starting OpenCrawling Camunda 8 Connector Integration Test ===${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }

CONTAINER_NAME="opencrawling-camunda-8-test"
CAMUNDA_PORT=8089

cleanup() {
  echo -e "${YELLOW}Cleaning up Camunda 8 test container...${NC}"
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cleanup

echo -e "${YELLOW}Starting Camunda 8 container (${CONTAINER_NAME})...${NC}"
docker run -d --name "$CONTAINER_NAME" \
  -p ${CAMUNDA_PORT}:8080 \
  -p 26500:26500 \
  -e CAMUNDA_REST_ENABLED=true \
  -e ZEEBE_REST_ADDRESS="http://0.0.0.0:8080/v2" \
  camunda/zeebe:8.7.0

echo -e "${YELLOW}Waiting for Camunda 8 REST service at http://localhost:${CAMUNDA_PORT}/v2/status ...${NC}"

TIMEOUT=120
ELAPSED=0
HEALTHY=false

until [ "$HEALTHY" = true ]; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${CAMUNDA_PORT}/v2/status" || true)
  if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "204" ]; then
    HEALTHY=true
    echo -e "${GREEN}Camunda 8 REST engine is ready! (HTTP $HTTP_CODE)${NC}"
    break
  fi
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timed out waiting for Camunda 8 REST engine. HTTP Code: $HTTP_CODE${NC}"
    docker logs "$CONTAINER_NAME" --tail 30 || true
    exit 1
  fi
  sleep 3
  ELAPSED=$((ELAPSED + 3))
  echo -n "."
done

echo -e "${YELLOW}Running Maven tests for oc-camunda-repository-connector...${NC}"
mvn clean test -pl oc-camunda-repository-connector -Dspring.opencrawling.connector.camunda.url="http://localhost:${CAMUNDA_PORT}/v2" -Dspring.opencrawling.connector.camunda.username="demo" -Dspring.opencrawling.connector.camunda.password="demo"

echo -e "${GREEN}=== Camunda 8 Repository Connector Integration Test PASSED Successfully ===${NC}"
