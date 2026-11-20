#!/usr/bin/env bash
#
# Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
# oc-java-client-sdk, oc-mcf-migrator, oc-python-client-sdk), archetype resource
# templates, and CLI version banners to <target-version> (e.g. 1.0.5-rc.20261117
# or 1.0.5).
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

# Translate Maven version to PEP 440 for oc-python-client-sdk:
#   1.0.5-SNAPSHOT     -> 1.0.5.dev0
#   1.0.5-rc.20261118  -> 1.0.5rc20261118
#   1.0.5-RC1          -> 1.0.5rc1
#   1.0.5              -> 1.0.5
to_pep440() {
  local ver="$1"
  if [[ "$ver" =~ ^([0-9]+\.[0-9]+\.[0-9]+)-SNAPSHOT$ ]]; then
    echo "${BASH_REMATCH[1]}.dev0"
  elif [[ "$ver" =~ ^([0-9]+\.[0-9]+\.[0-9]+)-[Rr][Cc]\.?([0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]}rc${BASH_REMATCH[2]}"
  elif [[ "$ver" =~ ^([0-9]+\.[0-9]+\.[0-9]+)-alpha\.?([0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]}a${BASH_REMATCH[2]}"
  elif [[ "$ver" =~ ^([0-9]+\.[0-9]+\.[0-9]+)-beta\.?([0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]}b${BASH_REMATCH[2]}"
  else
    echo "$ver"
  fi
}

if [ -f oc-python-client-sdk/pyproject.toml ]; then
  PY_VER=$(to_pep440 "$TARGET_VER")
  echo "Stamping oc-python-client-sdk PEP 440 version: ${PY_VER} (Maven: ${TARGET_VER})"
  replace_in_file "s|^version = \"[^\"]*\"|version = \"${PY_VER}\"|g" \
    oc-python-client-sdk/pyproject.toml
  if [ -f oc-python-client-sdk/src/opencrawling/_version.py ]; then
    replace_in_file "s|^__version__ = \"[^\"]*\"|__version__ = \"${PY_VER}\"|g" \
      oc-python-client-sdk/src/opencrawling/_version.py
    replace_in_file "s|^__maven_version__ = \"[^\"]*\"|__maven_version__ = \"${TARGET_VER}\"|g" \
      oc-python-client-sdk/src/opencrawling/_version.py
  fi
fi

if [ "$SKIP_VALIDATE" != "--skip-validate" ]; then
  mvn ${MVN_FLAGS[@]+"${MVN_FLAGS[@]}"} validate -B
fi

echo "[SUCCESS] Stamped OpenCrawling version: ${TARGET_VER}"
