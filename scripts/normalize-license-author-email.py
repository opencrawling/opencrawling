#!/usr/bin/env python3
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

"""Normalize Piergiorgio's legacy email in managed copyright headers."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

OLD_EMAIL = b"plucidi@ziaconsulting.com"
NEW_EMAIL = b"piergiorgio@apache.org"
OVERRIDES = Path(".license-email-overrides.json")


def tracked_files() -> list[Path]:
    output = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"]
    )
    return [Path(name.decode()) for name in output.split(b"\0") if name]


def replace_in_header(path: Path, source: bytes, target: bytes) -> bool:
    try:
        content = path.read_bytes()
    except OSError:
        return False

    changed = False
    lines = content.splitlines(keepends=True)
    for index, line in enumerate(lines):
        if b"Copyright" in line and source in line:
            lines[index] = line.replace(source, target)
            changed = True
    if changed:
        path.write_bytes(b"".join(lines))
    return changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("normalize", "restore"))
    args = parser.parse_args()

    files = tracked_files()
    overrides = set(json.loads(OVERRIDES.read_text())) if OVERRIDES.exists() else set()
    changed = 0
    if args.action == "normalize":
        for path in files:
            if replace_in_header(path, OLD_EMAIL, NEW_EMAIL):
                overrides.add(path.as_posix())
                changed += 1
        OVERRIDES.write_text(json.dumps(sorted(overrides), indent=2) + "\n")
    else:
        for relative_path in sorted(overrides):
            path = Path(relative_path)
            changed += replace_in_header(path, NEW_EMAIL, OLD_EMAIL)

    print(f"License author email {args.action}: {changed} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
