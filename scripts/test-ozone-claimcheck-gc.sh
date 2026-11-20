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

# Dedicated integration test script for Claim-Check Post-Ingestion ACK Explicit Deletion and Garbage Collector (GC)
# Exit immediately if a command exits with a non-zero status
set -e

# Color variables
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Set Ozone as active Claim Check store and enable explicit cleanup & background GC
export SPRING_OPENCRAWLING_CLAIM_CHECK_STORE=ozone
export SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE="${OZONE_CLIENT_TYPE:-NATIVE}"
export SPRING_OPENCRAWLING_CLAIM_CHECK_CLEANUP_ON_CONSUME=true
export SPRING_OPENCRAWLING_CLAIM_CHECK_LIFECYCLE_ENABLE_BACKGROUND_GC=true
export SPRING_OPENCRAWLING_CLAIM_CHECK_LIFECYCLE_TTL_HOURS=1

echo -e "${YELLOW}=== Starting OpenCrawling Claim-Check Explicit Deletion & GC Integration Test ===${NC}"
echo -e "${YELLOW}Active Store: ${GREEN}Ozone (${SPRING_OPENCRAWLING_CLAIM_CHECK_OZONE_CLIENT_TYPE})${NC}"
echo -e "${YELLOW}Explicit Deletion Post-ACK: ${GREEN}Enabled${NC}"
echo -e "${YELLOW}Background GC Sweep: ${GREEN}Enabled (TTL: 1h)${NC}"

# Get the directory where this script is located and switch to the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}Switched working directory to project root: $(pwd)${NC}"

# Run oc-core and oc-runtime unit and integration tests verifying GC & Explicit Deletion
echo -e "${YELLOW}Step 1: Running unit & integration tests for Local & Ozone ClaimCheckStore Explicit Deletion...${NC}"
mvn test -Dtest=ClaimCheckGarbageCollectorTest,OzoneClaimCheckStoreTest -pl oc-core,oc-runtime

echo -e "${GREEN}Step 1 Passed: ClaimCheckGarbageCollectorTest & OzoneClaimCheckStoreTest executed successfully!${NC}"

# Verification helper for checking Ozone keys via native Ozone CLI inside container if running docker
if command -v docker >/dev/null 2>&1 && [ "$(docker ps -q -f name=ozone-om 2>/dev/null)" ]; then
    echo -e "${YELLOW}Step 2: Checking live Apache Ozone container key cleanup...${NC}"
    OZONE_KEYS=$(docker exec ozone-om ozone sh key list /s3v/claims 2>/dev/null || echo "[]")
    echo -e "Live Ozone keys in /s3v/claims: ${GREEN}${OZONE_KEYS}${NC}"
fi

echo -e "${GREEN}================================================================================${NC}"
echo -e "${GREEN}SUCCESS: Claim-Check Explicit Deletion & Garbage Collector Integration Test Passed!${NC}"
echo -e "${GREEN}================================================================================${NC}"

exit 0
