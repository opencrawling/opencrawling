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

# End-to-End Integration Test Script for OpenCrawling CLI (oc-cli)
set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${YELLOW}================================================================================${NC}"
echo -e "${YELLOW}=== OpenCrawling CLI (oc-cli) Full Integration Test Suite ===${NC}"
echo -e "${YELLOW}================================================================================${NC}"

# Switch to project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${CYAN}[INFO] Switched to project root: $(pwd)${NC}"

# Check prerequisites
command -v java >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Java is required but not installed. Aborting.${NC}" >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo -e "${RED}[ERROR] Maven is required but not installed. Aborting.${NC}" >&2; exit 1; }

# Step 1: Maven Build & Unit Tests
echo -e "${CYAN}[INFO] Step 1: Compiling & packaging oc-cli module...${NC}"
mvn clean package -pl oc-cli

CLI_JAR="oc-cli/target/oc-cli-1.0.0-SNAPSHOT.jar"
if [ ! -f "$CLI_JAR" ]; then
    echo -e "${RED}[ERROR] CLI JAR executable not found at $CLI_JAR${NC}"
    exit 1
fi

RUN_CLI="java --enable-preview -jar $CLI_JAR"

# Step 2: Testing Help & Version Flags
echo -e "${CYAN}[INFO] Step 2: Verifying CLI binary invocation, help & version flags...${NC}"
$RUN_CLI --help > /dev/null
echo -e "${GREEN}  ✔ --help command passed.${NC}"

$RUN_CLI --version > /dev/null
echo -e "${GREEN}  ✔ --version command passed.${NC}"

# Step 3: Testing Configuration Commands
echo -e "${CYAN}[INFO] Step 3: Testing CLI configuration commands (oc config)...${NC}"
$RUN_CLI config set --server-url "http://localhost:8080" --context "cli-integration-test" > /dev/null
echo -e "${GREEN}  ✔ oc config set passed.${NC}"

CONFIG_OUT=$($RUN_CLI config context)
if [[ "$CONFIG_OUT" == *"cli-integration-test"* ]]; then
    echo -e "${GREEN}  ✔ oc config context verified active context: cli-integration-test.${NC}"
else
    echo -e "${RED}[ERROR] oc config context failed. Output: $CONFIG_OUT${NC}"
    exit 1
fi

# Step 4: Testing Archetype Scaffolding
echo -e "${CYAN}[INFO] Step 4: Testing archetype scaffolding wizard (oc archetype)...${NC}"
TEST_ARCHETYPE_DIR="oc-cli/target/cli-test-scaffold"
rm -rf "$TEST_ARCHETYPE_DIR"
$RUN_CLI archetype init --type repository --name "ScriptTestConnector" --package "com.opencrawling.test" --output-dir "$TEST_ARCHETYPE_DIR" > /dev/null

if [ -f "$TEST_ARCHETYPE_DIR/scripttest-repository-connector/pom.xml" ]; then
    echo -e "${GREEN}  ✔ oc archetype init successfully scaffolded project.${NC}"
else
    echo -e "${RED}[ERROR] oc archetype init failed to create pom.xml at expected path.${NC}"
    exit 1
fi

# Step 5: Testing Schema Offline Validator
echo -e "${CYAN}[INFO] Step 5: Testing offline JSON schema validation (oc schema)...${NC}"
TEMP_SCHEMA_FILE="oc-cli/target/test-ois-schema.json"
echo '{"id": "job-101", "name": "SharePoint Ingestion", "repositoryConnector": "SharePoint_Main"}' > "$TEMP_SCHEMA_FILE"

SCHEMA_OUT=$($RUN_CLI schema validate --file "$TEMP_SCHEMA_FILE")
if [[ "$SCHEMA_OUT" == *"valid"* ]]; then
    echo -e "${GREEN}  ✔ oc schema validate passed successfully for standard OIS document.${NC}"
else
    echo -e "${RED}[ERROR] oc schema validate failed. Output: $SCHEMA_OUT${NC}"
    exit 1
fi

echo -e "${CYAN}[INFO] Step 5b: Testing OIS tombstone DELETE action schema validation (oc schema)...${NC}"
TEMP_TOMBSTONE_FILE="oc-cli/target/test-ois-tombstone-schema.json"
echo '{"id": "doc-delete-1", "action": "DELETE", "source": {"type": "filesystem", "instance": "local"}}' > "$TEMP_TOMBSTONE_FILE"
TOMBSTONE_OUT=$($RUN_CLI schema validate --file "$TEMP_TOMBSTONE_FILE")
if [[ "$TOMBSTONE_OUT" == *"DELETE"* ]] && [[ "$TOMBSTONE_OUT" == *"valid"* ]]; then
    echo -e "${GREEN}  ✔ oc schema validate passed for tombstone DELETE payload.${NC}"
else
    echo -e "${RED}[ERROR] oc schema validate failed for tombstone DELETE payload. Output: $TOMBSTONE_OUT${NC}"
    exit 1
fi

# Step 6: Testing Subcommand Help Displays
echo -e "${CYAN}[INFO] Step 6: Verifying all subcommand help menus...${NC}"
for cmd in job connector copilot aiops system config schema; do
    $RUN_CLI $cmd --help > /dev/null
    echo -e "${GREEN}  ✔ oc $cmd --help passed.${NC}"
done

# Step 7: Live REST API Check (if backend is running)
echo -e "${CYAN}[INFO] Step 7: Testing live REST API integration if runtime is online...${NC}"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/system/status || echo "000")

if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}[INFO] Live OpenCrawling Runtime REST API detected! Testing live CLI commands...${NC}"
    $RUN_CLI system status
    $RUN_CLI connector list
    $RUN_CLI job list
    echo -e "${GREEN}  ✔ Live REST API CLI integration tests passed.${NC}"
else
    echo -e "${YELLOW}[WARN] OpenCrawling Runtime is not running on http://localhost:8080. (Skipping live API call step)${NC}"
fi

echo -e "${YELLOW}================================================================================${NC}"
echo -e "${GREEN}=== ALL OPEN CRAWLING CLI INTEGRATION TESTS COMPLETED SUCCESSFULLY! ===${NC}"
echo -e "${YELLOW}================================================================================${NC}"
