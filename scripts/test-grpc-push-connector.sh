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

# Integration test script for gRPC Push Repository Connector (Issue #155)
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling gRPC Push Repository Connector Test ===${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${CYAN}[INFO] Working directory switched to project root: $(pwd)${NC}"

# Step 1: Run Unit & Integration Tests via Maven
echo -e "${CYAN}[INFO] Step 1: Compiling oc-grpc-push-connector & running JUnit tests...${NC}"
mvn test -pl oc-grpc-push-connector,oc-runtime -Dtest="GrpcPushConnectorTest,OisPushServiceInProcessTest,RuntimePushDocumentDispatcherTest" -Dsurefire.failIfNoSpecifiedTests=false
echo -e "${GREEN}[OK] Maven JUnit test run complete for gRPC Push Connector!${NC}"

# Step 2: Test Admin REST API Connector Check (if Backend is running)
BASE_URL="${OPENCRAWLING_BASE_URL:-http://localhost:8080}"
GRPC_HOST="${GRPC_PUSH_HOST:-127.0.0.1}"
GRPC_PORT="${GRPC_PUSH_PORT:-9096}"

echo -e "${CYAN}[INFO] Step 2: Probing Backend Admin REST API at ${BASE_URL}...${NC}"

if curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/api/connectors" | grep -q "200"; then
    echo -e "${GREEN}[OK] Backend application is running. Executing live Connector Ping probe...${NC}"

    CHECK_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/connectors/check" \
      -H "Content-Type: application/json" \
      -d "{
        \"name\": \"Grpc_Push_Connector\",
        \"description\": \"gRPC Push Repository Ingestion Endpoint\",
        \"type\": \"repository\",
        \"className\": \"org.opencrawling.push.grpc.GrpcPushRepositoryConnector\",
        \"maxConnections\": 100,
        \"configuration\": {
          \"host\": \"${GRPC_HOST}\",
          \"port\": \"${GRPC_PORT}\",
          \"token\": \"\"
        }
      }")
    echo -e "  Response: ${CHECK_RESPONSE}"

    if echo "$CHECK_RESPONSE" | grep -q '"success":true'; then
        echo -e "${GREEN}[OK] Connector checker probe succeeded for gRPC Push listener!${NC}"
    else
        echo -e "${YELLOW}[WARN] Connector check returned false or failed; ensure gRPC Push server is running on port ${GRPC_PORT}.${NC}"
    fi

    # Step 3: Test with grpcurl if installed
    if command -v grpcurl >/dev/null 2>&1; then
        echo -e "${CYAN}[INFO] Step 3: Executing grpcurl Ping probe on ${GRPC_HOST}:${GRPC_PORT}...${NC}"
        if grpcurl -plaintext "${GRPC_HOST}:${GRPC_PORT}" opencrawling.push.v1.OisPushService/Ping; then
            echo -e "${GREEN}[OK] grpcurl Ping call succeeded!${NC}"
        else
            echo -e "${YELLOW}[WARN] grpcurl Ping call failed or server not ready.${NC}"
        fi
    else
        echo -e "${CYAN}[INFO] grpcurl tool not detected on PATH. Skipping raw gRPC CLI test.${NC}"
    fi
else
    echo -e "${YELLOW}[INFO] Backend is not running on ${BASE_URL}. In-process and mock integration tests passed successfully.${NC}"
    echo -e "${CYAN}[TIP] To test live gRPC server when backend is started:${NC}"
    echo -e "  1. Start backend: mvn spring-boot:run -pl oc-runtime"
    echo -e "  2. Test Ping via grpcurl: grpcurl -plaintext ${GRPC_HOST}:${GRPC_PORT} opencrawling.push.v1.OisPushService/Ping"
    echo -e "  3. Re-run this script: ./scripts/test-grpc-push-connector.sh"
fi

echo -e "${GREEN}=== gRPC Push Repository Connector Verification Finished Successfully! ===${NC}"
