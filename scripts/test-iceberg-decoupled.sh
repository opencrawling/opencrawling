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

# End-to-end decoupled integration test for Apache Iceberg Lakehouse Output Connector.
# Exercises decoupled ingestion pipeline with Kafka, Claim Check store, Iceberg REST Catalog,
# and S3-compatible object storage (MinIO) writing Parquet tables with Zero-Trust OIS security.
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

export SPRING_OPENCRAWLING_OUTPUT_TYPE=iceberg
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_CATALOG_TYPE=rest
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_CATALOG_NAME=opencrawling_lakehouse
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_CATALOG_URI="http://localhost:8181"
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_WAREHOUSE="s3://warehouse"
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_NAMESPACE="enterprise_data"
export SPRING_OPENCRAWLING_OUTPUT_ICEBERG_TABLE_NAME="documents"

echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}=== OpenCrawling Decoupled Apache Iceberg Lakehouse Integration Test         ===${NC}"
echo -e "${YELLOW}================================================================================${NC}"
echo -e "Output Connector:       ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_TYPE}${NC}"
echo -e "Iceberg Catalog Type:   ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_CATALOG_TYPE}${NC}"
echo -e "Iceberg Catalog URI:    ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_CATALOG_URI}${NC}"
echo -e "Target Namespace:       ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_NAMESPACE}${NC}"
echo -e "Target Table:           ${GREEN}${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_TABLE_NAME}${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Check dependencies
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo -e "${RED}curl is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo -e "${RED}jq is required but not installed. Aborting.${NC}" >&2; exit 1; }

COMPOSE_FILE="oc-iceberg-output-connector/docker/docker-compose-decoupled-with-iceberg.yml"

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

cleanup() {
  echo -e "\n${YELLOW}Tearing down Iceberg decoupled test environment...${NC}"
  compose down --remove-orphans >/dev/null 2>&1 || true
  rm -f "${TEST_FILE:-}"
}
trap cleanup EXIT

# Clean up previous containers
echo -e "${YELLOW}Cleaning up previous Iceberg decoupled containers...${NC}"
compose down --remove-orphans || true

# Build microservices images
echo -e "${YELLOW}Building OpenCrawling decoupled microservice images from source...${NC}"
compose build

# Start services
echo -e "${YELLOW}Starting complete decoupled Iceberg-based multi-service infrastructure...${NC}"
compose up -d

TIMEOUT=180
ELAPSED=0

# Wait for MinIO
echo -e "${YELLOW}Waiting for MinIO object storage to be healthy...${NC}"
until [ "$(docker inspect -f '{{.State.Health.Status}}' minio-decoupled-iceberg 2>/dev/null || echo 'starting')" == "healthy" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for MinIO decoupled container.${NC}"
    compose logs minio
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}MinIO object storage is healthy!${NC}"

# Ensure 'warehouse' bucket exists in MinIO
echo -e "${YELLOW}Creating 'warehouse' bucket in MinIO...${NC}"
docker exec minio-decoupled-iceberg mc alias set local http://localhost:9000 admin password >/dev/null 2>&1 || true
docker exec minio-decoupled-iceberg mc mb local/warehouse >/dev/null 2>&1 || true
echo -e "${GREEN}MinIO warehouse bucket ready!${NC}"

# Wait for Iceberg REST Catalog
ELAPSED=0
echo -e "${YELLOW}Waiting for Iceberg REST Catalog to be reachable...${NC}"
until curl -s "http://localhost:8181/v1/config" > /dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
if [ $ELAPSED -ge $TIMEOUT ]; then
  echo -e "${RED}Timeout waiting for Iceberg REST Catalog.${NC}"
  compose logs iceberg-rest
  exit 1
fi
echo -e "${GREEN}Iceberg REST Catalog is healthy and responding!${NC}"

# Create sample test document
TEST_DOC_DIR="./oc-runtime/data"
mkdir -p "$TEST_DOC_DIR"
TEST_FILE="$TEST_DOC_DIR/iceberg-decoupled-integration-test.txt"
echo "OpenCrawling decoupled integration test for Apache Iceberg Lakehouse. Entity Vendor: Acme Corp. Revenue: 1500000." > "$TEST_FILE"
echo -e "${GREEN}Created test document: $TEST_FILE${NC}"

# Restart crawler to trigger directory scan and Kafka publication
echo -e "${YELLOW}Restarting crawler service to trigger directory scan...${NC}"
compose restart oc-crawler

# Wait for crawler completion
ELAPSED=0
echo -e "${YELLOW}Waiting for oc-crawler service to finish execution...${NC}"
until [ "$(docker inspect -f '{{.State.Running}}' oc-crawler-service-iceberg 2>/dev/null || echo 'false')" == "false" ]; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo -e "${RED}Timeout waiting for oc-crawler-service-iceberg.${NC}"
    compose logs oc-crawler
    exit 1
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done
echo -e "${GREEN}oc-crawler-service-iceberg finished directory scanning!${NC}"

# Wait for Kafka writer consumer to commit Iceberg table snapshot
echo -e "${YELLOW}Waiting for Kafka writer consumer to commit snapshot to Iceberg table...${NC}"
TABLE_SNAPSHOTS=0
ELAPSED=0
TIMEOUT=120
until [ "$TABLE_SNAPSHOTS" -gt 0 ] 2>/dev/null || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 3
  ELAPSED=$((ELAPSED + 3))

  TABLE_INFO=$(curl -s "http://localhost:8181/v1/namespaces/${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_NAMESPACE}/tables/${SPRING_OPENCRAWLING_OUTPUT_ICEBERG_TABLE_NAME}" 2>/dev/null || echo "{}")
  TABLE_SNAPSHOTS=$(echo "$TABLE_INFO" | jq '.metadata.snapshots | length // 0' 2>/dev/null || echo "0")

  if [ -z "$TABLE_SNAPSHOTS" ] || [ "$TABLE_SNAPSHOTS" == "null" ]; then
    TABLE_SNAPSHOTS=0
  fi
  printf "  Elapsed: %ds, Committed Snapshots in Iceberg table: %s\r" "$ELAPSED" "$TABLE_SNAPSHOTS"
done
echo ""

echo -e "${YELLOW}Verifying Apache Iceberg table state...${NC}"
echo -e "Iceberg snapshots count: ${GREEN}$TABLE_SNAPSHOTS${NC}"

if [ "$TABLE_SNAPSHOTS" -eq 0 ]; then
  echo -e "${RED}Iceberg decoupled integration test failed: 0 snapshots committed in Iceberg table!${NC}"
  echo -e "${YELLOW}Printing consumer service logs for diagnosis...${NC}"
  compose logs oc-writer-consumer
  exit 1
fi

# Verify OIS Security fields and schema
SCHEMA_FIELDS=$(echo "$TABLE_INFO" | jq -r '.metadata.schemas[0].fields[].name' 2>/dev/null || true)
echo -e "Iceberg table fields: ${CYAN}$(echo "$SCHEMA_FIELDS" | tr '\n' ' ')${NC}"

if ! echo "$SCHEMA_FIELDS" | grep -q "security_allowed_read"; then
  echo -e "${RED}Missing required OIS security column 'security_allowed_read'!${NC}"
  exit 1
fi

echo -e "\n${GREEN}================================================================================${NC}"
echo -e "${GREEN}=== Decoupled Apache Iceberg Integration Test PASSED Successfully!           ===${NC}"
echo -e "${GREEN}================================================================================${NC}"
