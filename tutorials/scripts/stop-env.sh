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

# Stops the tutorial Docker Compose stack. Pass --clean to remove Docker volumes
# and provisioned tutorial state.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TUTORIAL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${TUTORIAL_DIR}/docker/docker-compose-alfresco-pgvector-mcp.yml"

if [ "${1:-}" = "--clean" ]; then
  docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans
  rm -f "${TUTORIAL_DIR}/data/.provisioned-nodes.json"
else
  docker compose -f "${COMPOSE_FILE}" down --remove-orphans
fi
