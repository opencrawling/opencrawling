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

# ==============================================================================
# OpenCrawling - Ephemeral / Release Version Stamping Utility
#
# Updates all Maven reactor POMs, standalone modules (oc-connector-archetypes,
# oc-java-client-sdk, oc-mcf-migrator), archetype resource templates, and CLI
# version banners to <target-version> (e.g. 1.0.5-rc.20261117 or 1.0.5).
#
# Usage:
#   ./scripts/stamp-version.sh <target-version> [--skip-validate]
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

if [ "${1:-}" = "" ] || [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  echo "Usage: $0 <target-version> [--skip-validate]" >&2
  exit 1
fi

TARGET_VER="${1#v}"
SKIP_VALIDATE="${2:-}"
MVN_FLAGS=()
if [ -n "${MAVEN_ARGS:-}" ]; then
  read -r -a MVN_FLAGS <<< "$MAVEN_ARGS"
fi

CURRENT_VER=$(mvn ${MVN_FLAGS[@]+"${MVN_FLAGS[@]}"} help:evaluate -Dexpression=project.version -q -DforceStdout)

if [ -z "$CURRENT_VER" ]; then
  echo "[ERROR] Failed to evaluate current Maven project.version" >&2
  exit 1
fi

echo "Transitioning project version from ${CURRENT_VER} -> ${TARGET_VER}"

# Portable in-place replacement (works on both GNU sed and macOS BSD sed)
replace_in_file() {
  local expr="$1"
  local file="$2"
  local tmp_file
  tmp_file=$(mktemp)
  sed -E "$expr" "$file" > "$tmp_file"
  mv "$tmp_file" "$file"
}

if [ "$CURRENT_VER" != "$TARGET_VER" ]; then
  mvn ${MVN_FLAGS[@]+"${MVN_FLAGS[@]}"} versions:set -B -DnewVersion="${TARGET_VER}" -DgenerateBackupPoms=false
  mvn ${MVN_FLAGS[@]+"${MVN_FLAGS[@]}"} versions:set -B -DnewVersion="${TARGET_VER}" -DprocessParents=true -DgenerateBackupPoms=false -f oc-connector-archetypes/pom.xml
  mvn ${MVN_FLAGS[@]+"${MVN_FLAGS[@]}"} versions:set -B -DnewVersion="${TARGET_VER}" -DgenerateBackupPoms=false -f oc-java-client-sdk/pom.xml
  mvn ${MVN_FLAGS[@]+"${MVN_FLAGS[@]}"} versions:set -B -DnewVersion="${TARGET_VER}" -DgenerateBackupPoms=false -f oc-mcf-migrator/pom.xml
fi

replace_in_file "s|<oc-java-client-sdk\\.version>[^<]*</oc-java-client-sdk\\.version>|<oc-java-client-sdk.version>${TARGET_VER}</oc-java-client-sdk.version>|g" pom.xml
replace_in_file "s|<opencrawling-sdk\\.version>[^<]*</opencrawling-sdk\\.version>|<opencrawling-sdk.version>${TARGET_VER}</opencrawling-sdk.version>|g" oc-mcf-migrator/pom.xml

for f in \
  oc-cli/pom.xml \
  oc-runtime/pom.xml \
  oc-connector-archetypes/opencrawling-archetype-*/src/main/resources/archetype-resources/pom.xml \
  oc-cli/src/main/java/org/opencrawling/cli/commands/ArchetypeCommand.java; do
  replace_in_file "s|<version>${CURRENT_VER}</version>|<version>${TARGET_VER}</version>|g" "$f"
done

replace_in_file "s|<opencrawling\\.version>[^<]*</opencrawling\\.version>|<opencrawling.version>${TARGET_VER}</opencrawling.version>|g" \
  oc-cli/src/main/java/org/opencrawling/cli/commands/ArchetypeCommand.java
replace_in_file "s|OpenCrawling CLI v[^\" ]*|OpenCrawling CLI v${TARGET_VER}|g" \
  oc-cli/src/main/java/org/opencrawling/cli/OpenCrawlingCliCommand.java

if [ "$SKIP_VALIDATE" != "--skip-validate" ]; then
  mvn ${MVN_FLAGS[@]+"${MVN_FLAGS[@]}"} validate -B
fi

echo "[SUCCESS] Stamped OpenCrawling version: ${TARGET_VER}"
