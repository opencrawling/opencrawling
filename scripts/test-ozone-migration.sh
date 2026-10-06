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

# Dedicated test suite for OpenCrawling Migration Mode & Apache Ozone Output Connector
set -e

# ANSI Color formatting
GREEN='\033[0;32m'
CYAN='\033[0;36m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m' # No Color

echo -e "${CYAN}╔══════════════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║     OpenCrawling - Migration Mode & Apache Ozone Output Connector Suite      ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════════════════════╝${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${YELLOW}[INFO] Working directory: $(pwd)${NC}"

# Step 1: Test Pipeline Mode in oc-core
echo -e "\n${CYAN}[STEP 1] Testing PipelineMode core definitions and properties...${NC}"
mvn test -pl oc-core -Dtest=PipelineModeTest -q
echo -e "${GREEN}✔ Step 1 Passed: PipelineMode correctly distinguishes RAG and MIGRATION modes.${NC}"

# Step 2: Test JobOrchestrator and IngestionConsumer in oc-runtime
echo -e "\n${CYAN}[STEP 2] Testing JobOrchestrator and IngestionConsumer migration bypass...${NC}"
mvn test -pl oc-runtime -Dtest=JobOrchestratorMigrationTest,IngestionConsumerMigrationTest -q
echo -e "${GREEN}✔ Step 2 Passed: Narrativization and chunking/embeddings bypassed in Migration Mode.${NC}"

# Step 3: Test Apache Ozone Output Connector and Migration Writer Consumer
echo -e "\n${CYAN}[STEP 3] Testing Apache Ozone Output Connector (Migration Mode enforcement)...${NC}"
mvn test -pl oc-ozone-output-connector -Dtest=OzoneOutputConnectorTest,OzoneMigrationWriterConsumerTest -q
echo -e "${GREEN}✔ Step 3 Passed: OzoneOutputConnector strictly enforces Migration Mode & generates OIS Zero-Trust sidecars.${NC}"

# Step 4: Live container check if Apache Ozone is running in Docker
if command -v docker >/dev/null 2>&1 && [ "$(docker ps -q -f name=ozone-om 2>/dev/null)" ]; then
    echo -e "\n${CYAN}[STEP 4] Live Apache Ozone container detected. Verifying key storage...${NC}"
    OZONE_VOLUMES=$(docker exec ozone-om ozone sh volume list / 2>/dev/null || echo "[]")
    echo -e "${GREEN}✔ Active Ozone volumes: ${OZONE_VOLUMES}${NC}"
fi

echo -e "\n${GREEN}╔══════════════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║  SUCCESS: Migration Mode & Apache Ozone Output Connector Verified Cleanly!  ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════════════════════════════╝${NC}"
