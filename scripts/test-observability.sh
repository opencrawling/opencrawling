#!/usr/bin/env bash
#
# Copyright © 2026 Piergiorgio Lucidi (plucidi@ziaconsulting.com)
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

# Integration test for Jaeger, OpenTelemetry Collector and Prometheus stack
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling Observability Integration Test ===${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${CYAN}[INFO] Working directory switched to project root: $(pwd)${NC}"

# Check dependencies
echo -e "${CYAN}[INFO] Checking system prerequisites (Docker & Docker Compose)...${NC}"
command -v docker >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || docker compose version >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Docker Compose is required but not installed. Aborting.${NC}" >&2; exit 1; }
echo -e "${GREEN}[OK] All system prerequisites are satisfied!${NC}"

# Start core observability containers
echo -e "${CYAN}[INFO] Launching local observability containers (Jaeger, OTel Collector, Prometheus)...${NC}"
docker compose up -d jaeger otel-collector prometheus
echo -e "${GREEN}[OK] Observability containers launched. Checking service readiness...${NC}"

# Define timeout (in seconds)
TIMEOUT=60
ELAPSED=0

echo -e "${CYAN}[INFO] Waiting for Jaeger UI to become healthy and reachable (port 16686)...${NC}"
until curl -s http://localhost:16686/ >/dev/null 2>&1 || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  printf "  Waiting for Jaeger... (%ds elapsed)\r" "$ELAPSED"
done
echo ""

if [ $ELAPSED -ge $TIMEOUT ]; then
  echo -e "${RED}[ERROR] Timeout reached while waiting for Jaeger container to initialize.${NC}"
  echo -e "${YELLOW}[DIAGNOSTICS] Printing Jaeger logs for troubleshooting:${NC}"
  docker compose logs jaeger
  docker compose down
  exit 1
fi
echo -e "${GREEN}[OK] Jaeger is healthy and accessible at http://localhost:16686!${NC}"

# Reset elapsed timer
ELAPSED=0
echo -e "${CYAN}[INFO] Waiting for Prometheus metrics engine to become healthy and reachable (port 9090)...${NC}"
until [ "$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9090/-/healthy || echo "000")" = "200" ] || [ $ELAPSED -ge $TIMEOUT ]; do
  sleep 2
  ELAPSED=$((ELAPSED + 2))
  printf "  Waiting for Prometheus... (%ds elapsed)\r" "$ELAPSED"
done
echo ""

if [ $ELAPSED -ge $TIMEOUT ]; then
  echo -e "${RED}[ERROR] Timeout reached while waiting for Prometheus container to initialize.${NC}"
  echo -e "${YELLOW}[DIAGNOSTICS] Printing Prometheus logs for troubleshooting:${NC}"
  docker compose logs prometheus
  docker compose down
  exit 1
fi
echo -e "${GREEN}[OK] Prometheus is healthy and accessible at http://localhost:9090!${NC}"

# Run the test tracing emitter
echo -e "${CYAN}[INFO] Compiling and running 'ObservabilityIntegrationTest' via Maven to emit a sample test span...${NC}"
mvn test -pl oc-observability -Dtest=ObservabilityIntegrationTest
echo -e "${GREEN}[OK] Maven JUnit test run complete. Span emitted!${NC}"

# Wait for Jaeger to process the emitted span (as OTel Collector has internal batch/send windows)
echo -e "${CYAN}[INFO] Probing Jaeger search API for emitted trace (service: 'opencrawling-observability-integration-test-service')...${NC}"
ELAPSED=0
TIMEOUT=30
TRACE_FOUND=false

until [ "$TRACE_FOUND" = true ] || [ $ELAPSED -ge $TIMEOUT ]; do
  # Query Jaeger traces endpoint for our service
  JAEGER_RESPONSE=$(curl -s "http://localhost:16686/api/traces?service=opencrawling-observability-integration-test-service" || echo "")
  
  if echo "$JAEGER_RESPONSE" | grep -q "test-integration-span"; then
    TRACE_FOUND=true
  else
    sleep 2
    ELAPSED=$((ELAPSED + 2))
    printf "  Polling Jaeger trace store... (%ds elapsed)\r" "$ELAPSED"
  fi
done
echo ""

if [ "$TRACE_FOUND" = false ]; then
  echo -e "${RED}[ERROR] Integration test failed: Emitted trace span 'test-integration-span' not found in Jaeger trace index!${NC}"
  echo -e "${YELLOW}[DIAGNOSTICS] Printing OTel Collector logs for troubleshooting:${NC}"
  docker compose logs otel-collector
  docker compose down
  exit 1
fi

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: Observability Pipeline Integration Test Passed!${NC}"
echo -e "${GREEN}Trace successfully verified: Java SDK -> OTel Collector -> Jaeger (Port 16686)${NC}"
echo -e "${GREEN}================================================================================${NC}"

# Shut down the test infrastructure
echo -e "${CYAN}[INFO] Cleaning up test containers...${NC}"
docker compose down
echo -e "${GREEN}[OK] Test environment torn down successfully.${NC}"

exit 0
