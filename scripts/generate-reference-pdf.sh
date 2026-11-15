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

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# Resolve version
VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "1.0.4")
fi
VERSION="${VERSION#v}"
VERSION="${VERSION%-SNAPSHOT}"

# Resolve Wiki directory
WIKI_DIR="${2:-}"
if [ -z "$WIKI_DIR" ]; then
  if [ -d "$PROJECT_ROOT/opencrawling.wiki" ]; then
    WIKI_DIR="$PROJECT_ROOT/opencrawling.wiki"
  elif [ -d "$PROJECT_ROOT/../opencrawling.wiki" ]; then
    WIKI_DIR="$PROJECT_ROOT/../opencrawling.wiki"
  elif [ -d "$PROJECT_ROOT/wiki" ]; then
    WIKI_DIR="$PROJECT_ROOT/wiki"
  else
    WIKI_DIR="opencrawling.wiki"
  fi
fi

# Resolve Output PDF file
OUTPUT_PDF="${3:-dist/OpenCrawling-Reference-Documentation-${VERSION}.pdf}"

echo "================================================================================"
echo "  OpenCrawling Wiki Reference Documentation PDF Generator"
echo "  Release Version: v${VERSION}"
echo "  Wiki Directory:  ${WIKI_DIR}"
echo "  Target Output:   ${OUTPUT_PDF}"
echo "================================================================================"

# Verify Python 3
command -v python3 >/dev/null 2>&1 || { echo "[ERROR] python3 is required but not installed." >&2; exit 1; }

# Install or ensure dependencies in a lightweight venv or user site
if ! python3 -c "import weasyprint, markdown" >/dev/null 2>&1; then
  VENV_DIR="${PROJECT_ROOT}/.pdf-generator-venv"
  if [ ! -d "$VENV_DIR" ]; then
    echo "[INFO] Initializing Python virtual environment at ${VENV_DIR}..."
    python3 -m venv "$VENV_DIR"
    "$VENV_DIR/bin/pip" install --quiet weasyprint markdown
  fi
  PYTHON_EXEC="$VENV_DIR/bin/python3"
else
  PYTHON_EXEC="python3"
fi

# Run generator
"$PYTHON_EXEC" "$SCRIPT_DIR/generate-reference-pdf.py" \
  --wiki-dir "$WIKI_DIR" \
  --output "$OUTPUT_PDF" \
  --version "$VERSION"

# Generate SHA256 checksum
cd dist
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$(basename "$OUTPUT_PDF")" > "$(basename "$OUTPUT_PDF").sha256"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$(basename "$OUTPUT_PDF")" > "$(basename "$OUTPUT_PDF").sha256"
fi
cd "$PROJECT_ROOT"

echo "================================================================================"
echo "  [SUCCESS] Reference documentation generated successfully:"
echo "    - $OUTPUT_PDF"
echo "    - ${OUTPUT_PDF}.sha256"
echo "================================================================================"
