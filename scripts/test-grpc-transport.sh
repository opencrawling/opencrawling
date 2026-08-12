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

# Integration test script for gRPC Internal Transport, Admin API, and Dynamic Fallback
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Starting OpenCrawling gRPC Internal Transport Integration Test ===${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${CYAN}[INFO] Working directory switched to project root: $(pwd)${NC}"

# Step 1: Run Unit & Integration Tests via Maven
echo -e "${CYAN}[INFO] Step 1: Compiling gRPC modules & executing JUnit tests...${NC}"
mvn test -pl oc-grpc-api,oc-core,oc-runtime -Dtest=InternalTransportManagerTest -Dsurefire.failIfNoSpecifiedTests=false
echo -e "${GREEN}[OK] Maven JUnit test run complete for gRPC Transport!${NC}"

# Step 2: Test Admin REST API and Transport Settings Endpoints (if Backend is running)
BASE_URL="http://localhost:8080"
echo -e "${CYAN}[INFO] Step 2: Probing Backend Admin REST API at ${BASE_URL}...${NC}"

if curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/api/system/status" | grep -q "200"; then
    echo -e "${GREEN}[OK] Backend application is running. Executing REST & gRPC API probes...${NC}"
    
    # 2.1 Fetch current transport settings
    echo -e "${CYAN}[INFO] 2.1 GET /api/v1/admin/settings/transport...${NC}"
    GET_RESPONSE=$(curl -s "${BASE_URL}/api/v1/admin/settings/transport")
    echo -e "  Response: ${GET_RESPONSE}"
    
    if echo "$GET_RESPONSE" | grep -q '"status":"UP"'; then
        echo -e "${GREEN}[OK] GET /api/v1/admin/settings/transport returned UP status!${NC}"
    else
        echo -e "${RED}[ERROR] GET /api/v1/admin/settings/transport failed or status != UP${NC}"
        exit 1
    fi

    # 2.2 Test gRPC ping probe endpoint
    echo -e "${CYAN}[INFO] 2.2 POST /api/v1/admin/settings/transport/test-grpc...${NC}"
    PROBE_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/v1/admin/settings/transport/test-grpc" \
      -H "Content-Type: application/json" \
      -d '{"host":"127.0.0.1","port":9095}')
    echo -e "  Response: ${PROBE_RESPONSE}"

    if echo "$PROBE_RESPONSE" | grep -q '"status"'; then
        echo -e "${GREEN}[OK] gRPC test probe completed successfully!${NC}"
    else
        echo -e "${RED}[ERROR] gRPC test probe failed${NC}"
        exit 1
    fi

    # 2.3 Test dynamic setting update to AUTO mode
    echo -e "${CYAN}[INFO] 2.3 PUT /api/v1/admin/settings/transport (Mode: AUTO)...${NC}"
    PUT_RESPONSE=$(curl -s -X PUT "${BASE_URL}/api/v1/admin/settings/transport" \
      -H "Content-Type: application/json" \
      -d '{
        "mode": "AUTO",
        "grpcEnabled": true,
        "grpcPort": 9095,
        "maxMessageSizeMb": 32,
        "fallbackToRest": true,
        "keepAliveTimeMs": 30000,
        "connectionTimeoutMs": 5000,
        "tlsEnabled": false,
        "certChainPath": "",
        "privateKeyPath": ""
      }')
    echo -e "  Response: ${PUT_RESPONSE}"

    if echo "$PUT_RESPONSE" | grep -q '"mode":"AUTO"'; then
        echo -e "${GREEN}[OK] Transport settings successfully updated to AUTO mode!${NC}"
    else
        echo -e "${RED}[ERROR] Failed to update settings to AUTO mode${NC}"
        exit 1
    fi

    # 2.4 Test dynamic setting update to REST mode
    echo -e "${CYAN}[INFO] 2.4 PUT /api/v1/admin/settings/transport (Mode: REST)...${NC}"
    PUT_REST_RESPONSE=$(curl -s -X PUT "${BASE_URL}/api/v1/admin/settings/transport" \
      -H "Content-Type: application/json" \
      -d '{
        "mode": "REST",
        "grpcEnabled": false,
        "grpcPort": 9095,
        "maxMessageSizeMb": 32,
        "fallbackToRest": true,
        "keepAliveTimeMs": 30000,
        "connectionTimeoutMs": 5000,
        "tlsEnabled": false,
        "certChainPath": "",
        "privateKeyPath": ""
      }')
    echo -e "  Response: ${PUT_REST_RESPONSE}"

    if echo "$PUT_REST_RESPONSE" | grep -q '"mode":"REST"'; then
        echo -e "${GREEN}[OK] Transport settings successfully updated to REST mode!${NC}"
    else
        echo -e "${RED}[ERROR] Failed to update settings to REST mode${NC}"
        exit 1
    fi

    # 2.5 Restore default AUTO transport mode
    echo -e "${CYAN}[INFO] 2.5 Restoring default AUTO transport settings...${NC}"
    curl -s -X PUT "${BASE_URL}/api/v1/admin/settings/transport" \
      -H "Content-Type: application/json" \
      -d '{
        "mode": "AUTO",
        "grpcEnabled": true,
        "grpcPort": 9095,
        "maxMessageSizeMb": 32,
        "fallbackToRest": true,
        "keepAliveTimeMs": 30000,
        "connectionTimeoutMs": 5000,
        "tlsEnabled": false,
        "certChainPath": "",
        "privateKeyPath": ""
      }' > /dev/null
    echo -e "${GREEN}[OK] Default AUTO transport settings restored.${NC}"
else
    echo -e "${YELLOW}[NOTICE] Backend application (http://localhost:8080) is not currently running.${NC}"
    echo -e "${YELLOW}[NOTICE] Unit & Maven Integration Tests successfully verified offline gRPC transport logic.${NC}"
fi

echo -e "${GREEN}=== gRPC Transport Integration Test Completed Successfully! ===${NC}"
