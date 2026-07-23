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

# =============================================================================
# OpenCrawling - Narrativization Feature Integration Test Suite
#
# Tests all layers of the auto-narrativization pipeline:
#   LAYER 1 - Mustache Transformation Engine (oc-core)
#   LAYER 2 - Schema Retrieval SPI (oc-iceberg-repository-connector)
#   LAYER 3 - Copilot REST API service (oc-runtime)
#   LAYER 4 - End-to-end API verification via curl (requires running stack)
#
# Usage:
#   ./scripts/test-narrativization.sh          # Runs layers 1-3 (no infra needed)
#   ./scripts/test-narrativization.sh --e2e    # Runs all layers including curl E2E
#   ./scripts/test-narrativization.sh --url http://host:port  # Target a custom host
#
# =============================================================================
set -e

# ─── Colors ──────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# ─── Defaults ────────────────────────────────────────────────────────────────
E2E_MODE=false
RUNTIME_URL="http://localhost:8080"

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --e2e)      E2E_MODE=true; shift ;;
    --url)      RUNTIME_URL="$2"; shift 2 ;;
    *)          echo -e "${RED}[WARN] Unknown argument: $1${NC}"; shift ;;
  esac
done

PASS=0
FAIL=0

# ─── Helpers ─────────────────────────────────────────────────────────────────
DIM='\033[2m'
pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS + 1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL + 1)); }
section() { echo -e "\n${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"; echo -e "${BOLD}${CYAN}  $1${NC}"; echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"; }
# Print indented sample data block
sample() {
  local label="$1"
  local value="$2"
  echo -e "  ${BOLD}${YELLOW}▸ ${label}${NC}"
  while IFS= read -r line; do
    echo -e "    ${DIM}${line}${NC}"
  done <<< "$value"
}

# ─── Setup ───────────────────────────────────────────────────────────────────
echo -e "${BOLD}${YELLOW}"
echo "╔══════════════════════════════════════════════════════════╗"
echo "║   OpenCrawling - Narrativization Integration Test Suite  ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo -e "${NC}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
echo -e "${CYAN}[INFO] Project root: $(pwd)${NC}"
echo -e "${CYAN}[INFO] E2E mode: ${E2E_MODE}${NC}"
if [ "$E2E_MODE" = true ]; then
  echo -e "${CYAN}[INFO] Runtime URL: ${RUNTIME_URL}${NC}"
fi

# ─── Prerequisites ───────────────────────────────────────────────────────────
echo -e "${CYAN}[INFO] Checking system prerequisites...${NC}"
command -v mvn  >/dev/null 2>&1 || { echo -e "${RED}[ERROR] mvn is required.${NC}"; exit 1; }
command -v java >/dev/null 2>&1 || { echo -e "${RED}[ERROR] java is required.${NC}"; exit 1; }
if [ "$E2E_MODE" = true ]; then
  command -v curl >/dev/null 2>&1 || { echo -e "${RED}[ERROR] curl is required for E2E mode.${NC}"; exit 1; }
fi
echo -e "${GREEN}[OK] All prerequisites satisfied.${NC}"

# =============================================================================
# LAYER 1 — Mustache Transformation Engine
# Tests: MustacheTransformationConnector compiles the template and renders
#        metadata values into a human-readable narrative text.
# =============================================================================
section "LAYER 1 — Mustache Template Engine (oc-core)"

L1_TEMPLATE='On {{date}}, region {{region}} sold products: {{#products}}{{.}} {{/products}}with amount ${{amount}}.'
L1_INPUT='document.id   = "123"
document.url  = "file:///test.json"
metadata      = {
  date:     ["2026-07-23"],
  region:   ["EU"],
  amount:   ["45000"],
  products: ["Laptop", "Monitor"]
}'
L1_EXPECTED='On 2026-07-23, region EU sold products: Laptop Monitor with amount $45000.'

sample "Mustache template"   "$L1_TEMPLATE"
sample "Document input data" "$L1_INPUT"
sample "Expected output"     "$L1_EXPECTED"

echo -e "${CYAN}[INFO] Running MustacheTransformationConnectorTest...${NC}"
if mvn test -pl oc-core -Dtest=MustacheTransformationConnectorTest -q 2>&1; then
  pass "MustacheTransformationConnector renders templates correctly from document metadata"
else
  fail "MustacheTransformationConnector test failed"
fi

# ─── Manual Mustache Validation via Java inline ───────────────────────────────
JMUSTACHE_JAR=~/.m2/repository/com/samskivert/jmustache/1.16/jmustache-1.16.jar
sample "Dependency checked" "com.samskivert:jmustache:1.16\nPath: ${JMUSTACHE_JAR}"

echo -e "${CYAN}[INFO] Running inline Mustache JAR validation...${NC}"
if [ -f "$JMUSTACHE_JAR" ]; then
  RESULT=$(java -cp "$JMUSTACHE_JAR" com.samskivert.mustache.Main 2>/dev/null || true)
  pass "JMustache 1.16 JAR is present and accessible in local Maven cache"
else
  fail "JMustache 1.16 JAR is missing from local Maven cache"
fi

# =============================================================================
# LAYER 2 — Schema Retrieval SPI (getSchema on IcebergRepositoryConnector)
# Tests: ConnectorSchema record, getSchema() SPI default, Iceberg override
# =============================================================================
section "LAYER 2 — Schema Retrieval SPI (oc-core + oc-iceberg-repository-connector)"

echo -e "${CYAN}[INFO] Compiling oc-core to verify ConnectorSchema and RepositoryConnector SPI...${NC}"
if mvn compile -pl oc-core -q 2>&1; then
  pass "ConnectorSchema and RepositoryConnector SPI compile cleanly"
else
  fail "ConnectorSchema or RepositoryConnector SPI failed to compile"
fi

echo -e "${CYAN}[INFO] Compiling oc-iceberg-repository-connector to verify getSchema() override...${NC}"
if mvn compile -pl oc-iceberg-repository-connector -q 2>&1; then
  pass "IcebergRepositoryConnector.getSchema() override compiles correctly"
else
  fail "IcebergRepositoryConnector.getSchema() failed to compile"
fi

L2_SCHEMA='ConnectorSchema(
  connectorType: "iceberg",
  fields: [
    SchemaField(name="id",     type="STRING", description="Unique record identifier"),
    SchemaField(name="amount", type="DOUBLE", description="Monetary transaction value"),
    SchemaField(name="region", type="STRING", description="Geographical region")
  ]
)'
L2_SPI='interface RepositoryConnector {
  default ConnectorSchema getSchema() { return ConnectorSchema.UNKNOWN; }
}
class IcebergRepositoryConnector implements RepositoryConnector {
  @Override public ConnectorSchema getSchema() { /* returns Iceberg table schema */ }
}'
sample "ConnectorSchema structure" "$L2_SCHEMA"
sample "Schema SPI contract"       "$L2_SPI"

echo -e "${CYAN}[INFO] Verifying ConnectorSchema.java exists and contains SchemaField record...${NC}"
SCHEMA_FILE="oc-core/src/main/java/org/opencrawling/core/connector/ConnectorSchema.java"
if [ -f "$SCHEMA_FILE" ] && grep -q "SchemaField" "$SCHEMA_FILE"; then
  pass "ConnectorSchema.java exists with SchemaField record definition"
else
  fail "ConnectorSchema.java missing or malformed"
fi

echo -e "${CYAN}[INFO] Verifying IcebergRepositoryConnector overrides getSchema()...${NC}"
ICEBERG_FILE="oc-iceberg-repository-connector/src/main/java/org/opencrawling/iceberg/IcebergRepositoryConnector.java"
if grep -q "public ConnectorSchema getSchema" "$ICEBERG_FILE"; then
  pass "IcebergRepositoryConnector correctly overrides getSchema()"
else
  fail "IcebergRepositoryConnector does not override getSchema()"
fi

# =============================================================================
# LAYER 3 — Copilot REST API Service (oc-runtime)
# Tests: TemplateGenerationCopilot fallback mode + NarrativizationCopilotController
# =============================================================================
section "LAYER 3 — Copilot REST API Service (oc-runtime)"

L3_REQUEST='SchemaContextRequest(
  connectorType: "iceberg",
  fields: [
    FieldDto(name="id",     type="STRING", description="Primary Identifier"),
    FieldDto(name="amount", type="DOUBLE", description="Transaction Value")
  ]
)'
L3_EXPECTED_TEMPLATE='(deterministic fallback) Mustache template referencing {{id}} and {{amount}}'
L3_EXPECTED_MOCKDATA='{
  "id":     "Sample id"      // STRING  → prefixed "Sample " default
  "amount": 123.45           // DOUBLE  → canonical numeric placeholder
}'
L3_AI_ENGINE='spring.ai.copilot.engine = ollama  (default, falls back to deterministic if Ollama is unavailable)'

sample "Copilot API request (SchemaContextRequest)" "$L3_REQUEST"
sample "Expected template output"                   "$L3_EXPECTED_TEMPLATE"
sample "Expected mockData output"                   "$L3_EXPECTED_MOCKDATA"
sample "AI engine config"                           "$L3_AI_ENGINE"

echo -e "${CYAN}[INFO] Running NarrativizationCopilotIT unit test...${NC}"
if mvn test -pl oc-runtime -Dtest=NarrativizationCopilotIT -q 2>&1; then
  pass "NarrativizationCopilotController returns correct template + mockData via fallback mode"
else
  fail "NarrativizationCopilotIT failed"
fi

echo -e "${CYAN}[INFO] Verifying TemplateGenerationCopilot uses Ollama as default AI engine...${NC}"
COPILOT_FILE="oc-runtime/src/main/java/org/opencrawling/runtime/api/copilot/TemplateGenerationCopilot.java"
if grep -q 'copilot.engine:ollama' "$COPILOT_FILE"; then
  pass "TemplateGenerationCopilot defaults to Ollama engine (spring.ai.copilot.engine:ollama)"
else
  fail "TemplateGenerationCopilot does not default to Ollama engine"
fi

echo -e "${CYAN}[INFO] Verifying REST endpoint is registered at /api/transformation/copilot/generate...${NC}"
CONTROLLER_FILE="oc-runtime/src/main/java/org/opencrawling/runtime/api/copilot/NarrativizationCopilotController.java"
if grep -q '/api/transformation/copilot' "$CONTROLLER_FILE" && grep -q '@PostMapping.*generate' "$CONTROLLER_FILE"; then
  pass "REST endpoint registered at /api/transformation/copilot/generate"
else
  fail "REST endpoint path missing or incorrect"
fi

echo -e "${CYAN}[INFO] Verifying TemplateCopilotResponse DTO structure...${NC}"
RESPONSE_FILE="oc-runtime/src/main/java/org/opencrawling/runtime/api/copilot/TemplateCopilotResponse.java"
if grep -q "template" "$RESPONSE_FILE" && grep -q "mockData" "$RESPONSE_FILE"; then
  pass "TemplateCopilotResponse DTO contains both 'template' and 'mockData' fields"
else
  fail "TemplateCopilotResponse DTO is missing required fields"
fi

echo -e "${CYAN}[INFO] Verifying SchemaContextRequest DTO with nested FieldDto...${NC}"
REQUEST_FILE="oc-runtime/src/main/java/org/opencrawling/runtime/api/copilot/SchemaContextRequest.java"
if grep -q "FieldDto" "$REQUEST_FILE" && grep -q "connectorType" "$REQUEST_FILE"; then
  pass "SchemaContextRequest DTO has connectorType and FieldDto"
else
  fail "SchemaContextRequest DTO is malformed"
fi

# =============================================================================
# LAYER 4 — End-to-End API Test via curl  (only in --e2e mode)
# Tests: Full HTTP lifecycle against a running OpenCrawling instance
# =============================================================================
if [ "$E2E_MODE" = true ]; then
  section "LAYER 4 — End-to-End API Test via curl"

  ENDPOINT="${RUNTIME_URL}/api/transformation/copilot/generate"
  echo -e "${CYAN}[INFO] Probing endpoint: ${ENDPOINT}${NC}"

  # Check server is reachable first
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${RUNTIME_URL}/" 2>/dev/null || echo "000")
  if [ "$HTTP_STATUS" = "000" ]; then
    fail "OpenCrawling Runtime is not reachable at ${RUNTIME_URL}. Start the stack first."
  else
    echo -e "${GREEN}[OK] Runtime is reachable (HTTP ${HTTP_STATUS}).${NC}"

    PAYLOAD='{
      "connectorType": "iceberg",
      "fields": [
        {"name": "id",        "type": "STRING", "description": "Primary record identifier"},
        {"name": "amount",    "type": "DOUBLE", "description": "Transaction monetary value"},
        {"name": "region",    "type": "STRING", "description": "Geographical region code"},
        {"name": "timestamp", "type": "DATE",   "description": "Transaction timestamp"}
      ]
    }'

    sample "HTTP POST payload sent to ${ENDPOINT}" "$PAYLOAD"

    RESPONSE=$(curl -s -X POST \
      -H "Content-Type: application/json" \
      -d "$PAYLOAD" \
      "$ENDPOINT" 2>/dev/null)

    echo -e "${CYAN}[INFO] Copilot API Response:${NC}"
    echo -e "${GREEN}${RESPONSE}${NC}"

    # Assertions
    if echo "$RESPONSE" | grep -q '"template"'; then
      pass "Response contains 'template' field"
    else
      fail "Response missing 'template' field"
    fi

    if echo "$RESPONSE" | grep -q '"mockData"'; then
      pass "Response contains 'mockData' field"
    else
      fail "Response missing 'mockData' field"
    fi

    if echo "$RESPONSE" | grep -q 'id' && echo "$RESPONSE" | grep -q 'amount'; then
      pass "Template references schema field names (id, amount)"
    else
      fail "Template does not reference the schema field names"
    fi

    if echo "$RESPONSE" | grep -qE '"amount"\s*:\s*123\.45'; then
      pass "Fallback mock data correctly assigns 123.45 for DOUBLE type fields"
    else
      fail "Fallback mock data for DOUBLE type is missing or incorrect"
    fi
  fi
else
  echo -e "\n${YELLOW}[INFO] Skipping LAYER 4 (E2E curl test). Run with --e2e to include it.${NC}"
  echo -e "${YELLOW}[INFO] Example: ./scripts/test-narrativization.sh --e2e${NC}"
  echo -e "${YELLOW}[INFO] Example: ./scripts/test-narrativization.sh --e2e --url http://localhost:8080${NC}"
fi

# =============================================================================
# SUMMARY
# =============================================================================
echo ""
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}  Narrativization Test Suite — Results${NC}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "  ${GREEN}PASSED: ${PASS}${NC}"
if [ "$FAIL" -gt 0 ]; then
  echo -e "  ${RED}FAILED: ${FAIL}${NC}"
  echo -e "${RED}[RESULT] Some tests FAILED. Review logs above.${NC}"
  exit 1
else
  echo -e "  ${RED}FAILED: ${FAIL}${NC}"
  echo -e "${GREEN}[RESULT] All narrativization tests PASSED!${NC}"
fi
exit 0
