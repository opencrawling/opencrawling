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

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "1.0.4")
fi
VERSION="${VERSION#v}"

INPUT_MD="${2:-}"
if [ -z "$INPUT_MD" ]; then
  if [ -f "$PROJECT_ROOT/../opencrawling.wiki/User-and-Installation-Guide.md" ]; then
    INPUT_MD="$PROJECT_ROOT/../opencrawling.wiki/User-and-Installation-Guide.md"
  elif [ -f "$PROJECT_ROOT/wiki/User-and-Installation-Guide.md" ]; then
    INPUT_MD="$PROJECT_ROOT/wiki/User-and-Installation-Guide.md"
  elif [ -f "$PROJECT_ROOT/docs/User-and-Installation-Guide.md" ]; then
    INPUT_MD="$PROJECT_ROOT/docs/User-and-Installation-Guide.md"
  else
    INPUT_MD="docs/User-and-Installation-Guide.md"
  fi
fi

OUTPUT_PDF="${3:-dist/OpenCrawling-User-and-Installation-Guide.pdf}"

echo "================================================================================"
echo "  OpenCrawling PDF Documentation Generator"
echo "  Version: ${VERSION}"
echo "  Input:   ${INPUT_MD}"
echo "  Output:  ${OUTPUT_PDF}"
echo "================================================================================"

# Verify Python 3
command -v python3 >/dev/null 2>&1 || { echo "[ERROR] python3 is required but not installed." >&2; exit 1; }

# Install or ensure dependencies in a lightweight venv or user site
if ! python3 -c "import weasyprint, markdown" >/dev/null 2>&1; then
  echo "[INFO] Installing WeasyPrint and Markdown packages..."
  VENV_DIR="${PROJECT_ROOT}/.pdf-generator-venv"
  if [ ! -d "$VENV_DIR" ]; then
    python3 -m venv "$VENV_DIR"
  fi
  "$VENV_DIR/bin/pip" install --quiet weasyprint markdown
  PYTHON_EXEC="$VENV_DIR/bin/python3"
else
  PYTHON_EXEC="python3"
fi

"$PYTHON_EXEC" "$SCRIPT_DIR/generate-pdf-guide.py" \
  --input "$INPUT_MD" \
  --output "$OUTPUT_PDF" \
  --version "$VERSION"

echo "[SUCCESS] Documentation PDF generated at: ${OUTPUT_PDF}"
