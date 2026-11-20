#!/usr/bin/env python3
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

"""Normalize Piergiorgio's legacy email in managed copyright headers."""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

OLD_EMAIL = b"plucidi@ziaconsulting.com"
NEW_EMAIL = b"piergiorgio@apache.org"


def tracked_files() -> list[Path]:
    output = subprocess.check_output(["git", "ls-files", "-z"])
    return [Path(name.decode()) for name in output.split(b"\0") if name]


def creation_email(path: Path) -> str:
    result = subprocess.run(
        ["git", "log", "--follow", "--diff-filter=A", "--format=%ae", "-1", "--", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def creation_emails(files: list[Path]) -> dict[str, str]:
    output = subprocess.check_output(
        ["git", "log", "--diff-filter=A", "--format=%H%x09%ae", "--name-only", "--"]
    ).decode(errors="replace")
    result: dict[str, str] = {}
    current_email = ""
    for line in output.splitlines():
        if "\t" in line:
            _, current_email = line.split("\t", 1)
        elif line:
            result.setdefault(line, current_email)
    known_files = {str(path) for path in files}
    return {path: email for path, email in result.items() if path in known_files}


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
    authors = creation_emails(files) if args.action == "restore" else {}
    changed = 0
    for path in files:
        try:
            header = next((line for line in path.read_bytes().splitlines() if b"Copyright" in line), b"")
        except OSError:
            continue

        if args.action == "normalize":
            changed += replace_in_header(path, OLD_EMAIL, NEW_EMAIL)
        elif NEW_EMAIL in header:
            # Renamed files may not appear as an add in the batched history walk.
            author = authors.get(str(path))
            if author is None:
                author = creation_email(path)
            if author == OLD_EMAIL.decode():
                changed += replace_in_header(path, NEW_EMAIL, OLD_EMAIL)

    print(f"License author email {args.action}: {changed} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
