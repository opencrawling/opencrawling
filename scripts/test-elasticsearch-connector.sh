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

# Connector-level test for oc-elasticsearch-output-connector.
#
# Runs the module's unit tests plus its Testcontainers integration suites, which start real
# Elasticsearch 8.19 and 9.x nodes and exercise: index bootstrap (dense_vector, int8_hnsw), bulk
# indexing through the connector, the Kafka writer, kNN search with the security_read_keys ACL
# pre-filter, re-crawl chunk replacement, and tombstone deletes.
#
# Requires Docker and a prior `mvn install -DskipTests` (oc-core must be in the local repository).
set -euo pipefail

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker is required but not installed. Aborting.${NC}" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo -e "${RED}Docker daemon is not reachable. Aborting.${NC}" >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo -e "${RED}Maven is required but not installed. Aborting.${NC}" >&2; exit 1; }

echo -e "${YELLOW}=== Elasticsearch Output Connector Test Suite (unit + Testcontainers ITs on ES 8.x and 9.x) ===${NC}"

if mvn -B verify -pl oc-elasticsearch-output-connector -DskipITs=false -DfailIfNoTests=false; then
  echo -e "${GREEN}================================================================================${NC}"
  echo -e "${GREEN}SUCCESS: Elasticsearch Output Connector Test Suite Passed!${NC}"
  echo -e "${GREEN}================================================================================${NC}"
else
  echo -e "${RED}Elasticsearch Output Connector Test Suite failed. Reports:${NC}"
  echo "  oc-elasticsearch-output-connector/target/surefire-reports"
  echo "  oc-elasticsearch-output-connector/target/failsafe-reports"
  exit 1
fi
