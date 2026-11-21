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

"""Lightweight Google Drive REST API v3 & OAuth2 emulator (stdlib Python 3) for OpenCrawling tests."""

import copy
import hashlib
import io
import json
import os
import re
import threading
import urllib.parse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("GOOGLEDRIVE_PORT", "18490"))
QUIET = os.environ.get("GOOGLEDRIVE_QUIET", "false").lower() == "true"
VALID_TOKEN = os.environ.get("GOOGLEDRIVE_MOCK_TOKEN", "mock-gdrive-access-token")

LOCK = threading.Lock()
PARENT_RE = re.compile(r"'([^']+)'\s+in\s+parents")


def pdf_bytes(text):
    """Minimal, valid single-page PDF (PDF 1.4, Helvetica) extractable by Apache Tika / PDFBox."""
    lines = []
    for paragraph in text.split("\n"):
        while len(paragraph) > 90:
            cut = paragraph.rfind(" ", 0, 90)
            cut = cut if cut > 0 else 90
            lines.append(paragraph[:cut])
            paragraph = paragraph[cut:].lstrip()
        lines.append(paragraph)
    esc = lambda s: (
        s.encode("latin-1", "replace")
        .decode("latin-1")
        .replace("\\", "\\\\")
        .replace("(", "\\(")
        .replace(")", "\\)")
    )
    stream = "BT /F1 11 Tf 14 TL 50 790 Td " + " ".join(f"({esc(line)}) Tj T*" for line in lines) + " ET"
    objects = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        f"<< /Length {len(stream.encode('latin-1'))} >>\nstream\n{stream}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
    ]
    out = io.BytesIO()
    out.write(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for idx, body in enumerate(objects, 1):
        offsets.append(out.tell())
        out.write(f"{idx} 0 obj\n{body}\nendobj\n".encode("latin-1"))
    xref = out.tell()
    out.write(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode("ascii"))
    for off in offsets:
        out.write(f"{off:010d} 00000 n \n".encode("ascii"))
    out.write(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode("ascii"))
    return out.getvalue()


def _now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _initial_state():
    manual_pdf = pdf_bytes(
        "Hardware Manual diagnostic instructions for enterprise server firmware recovery. "
        "Reference GDRV-PDF-MANUAL-V1."
    )
    restricted_txt = (
        b"Confidential executive board minutes covering quarterly security audit roadmap. "
        b"Reference GDRV-RESTRICTED-V1."
    )
    archive_txt = (
        b"Legacy datacenter runbook archived under Procedures/Archive. "
        b"Reference GDRV-ARCHIVE-V1."
    )
    return {
        "drives": {
            "0AO_SUPPORT": {"id": "0AO_SUPPORT", "name": "Support Knowledge Base"},
        },
        "files": {
            "0AO_SUPPORT": {
                "id": "0AO_SUPPORT",
                "name": "Support Knowledge Base",
                "mimeType": "application/vnd.google-apps.folder",
                "driveId": "0AO_SUPPORT",
                "parents": [],
                "trashed": False,
                "modifiedTime": "2026-11-20T09:00:00Z",
            },
            "folder-procedures": {
                "id": "folder-procedures",
                "name": "Procedures",
                "mimeType": "application/vnd.google-apps.folder",
                "driveId": "0AO_SUPPORT",
                "parents": ["0AO_SUPPORT"],
                "trashed": False,
                "modifiedTime": "2026-11-20T09:00:00Z",
            },
            "folder-restricted": {
                "id": "folder-restricted",
                "name": "Restricted",
                "mimeType": "application/vnd.google-apps.folder",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-procedures"],
                "trashed": False,
                "modifiedTime": "2026-11-20T09:00:00Z",
            },
            "folder-archive": {
                "id": "folder-archive",
                "name": "Archive",
                "mimeType": "application/vnd.google-apps.folder",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-procedures"],
                "trashed": False,
                "modifiedTime": "2026-11-20T09:00:00Z",
            },
            "doc-reset": {
                "id": "doc-reset",
                "name": "Password Reset Procedure",
                "mimeType": "application/vnd.google-apps.document",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-procedures"],
                "webViewLink": "https://docs.google.com/document/d/doc-reset/edit",
                "version": "14",
                "createdTime": "2026-10-01T08:00:00Z",
                "modifiedTime": "2026-11-20T10:30:00Z",
                "trashed": False,
                "owners": [{"emailAddress": "alice@example.com", "displayName": "Alice"}],
                "lastModifyingUser": {"emailAddress": "bob@example.com", "displayName": "Bob"},
                "properties": {"department": "Support", "classification": "Internal"},
            },
            "slides-oncall": {
                "id": "slides-oncall",
                "name": "On-Call Escalation Deck",
                "mimeType": "application/vnd.google-apps.presentation",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-procedures"],
                "webViewLink": "https://docs.google.com/presentation/d/slides-oncall/edit",
                "version": "5",
                "createdTime": "2026-10-15T08:00:00Z",
                "modifiedTime": "2026-11-20T11:00:00Z",
                "trashed": False,
                "owners": [{"emailAddress": "alice@example.com", "displayName": "Alice"}],
            },
            "sheet-metrics": {
                "id": "sheet-metrics",
                "name": "Quarterly Ticket Metrics",
                "mimeType": "application/vnd.google-apps.spreadsheet",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-procedures"],
                "webViewLink": "https://docs.google.com/spreadsheets/d/sheet-metrics/edit",
                "version": "2",
                "trashed": False,
            },
            "form-feedback": {
                "id": "form-feedback",
                "name": "Customer Satisfaction Survey",
                "mimeType": "application/vnd.google-apps.form",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-procedures"],
                "webViewLink": "https://docs.google.com/forms/d/form-feedback/edit",
                "version": "1",
                "trashed": False,
            },
            "pdf-manual": {
                "id": "pdf-manual",
                "name": "Hardware_Manual.pdf",
                "mimeType": "application/pdf",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-procedures"],
                "webViewLink": "https://drive.google.com/file/d/pdf-manual/view",
                "version": "3",
                "headRevisionId": "rev-3",
                "md5Checksum": hashlib.md5(manual_pdf).hexdigest(),
                "size": str(len(manual_pdf)),
                "createdTime": "2026-10-10T08:00:00Z",
                "modifiedTime": "2026-11-20T12:00:00Z",
                "trashed": False,
                "owners": [{"emailAddress": "alice@example.com", "displayName": "Alice"}],
            },
            "doc-public": {
                "id": "doc-public",
                "name": "Public Status Page Notes",
                "mimeType": "application/vnd.google-apps.document",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-procedures"],
                "webViewLink": "https://docs.google.com/document/d/doc-public/edit",
                "version": "4",
                "createdTime": "2026-10-12T08:00:00Z",
                "modifiedTime": "2026-11-20T12:15:00Z",
                "trashed": False,
                "owners": [{"emailAddress": "alice@example.com", "displayName": "Alice"}],
            },
            "doc-restricted": {
                "id": "doc-restricted",
                "name": "Executive_Board_Minutes.txt",
                "mimeType": "text/plain",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-restricted"],
                "webViewLink": "https://drive.google.com/file/d/doc-restricted/view",
                "version": "7",
                "headRevisionId": "rev-7",
                "md5Checksum": hashlib.md5(restricted_txt).hexdigest(),
                "size": str(len(restricted_txt)),
                "createdTime": "2026-10-18T08:00:00Z",
                "modifiedTime": "2026-11-20T12:30:00Z",
                "trashed": False,
                "owners": [{"emailAddress": "bob@example.com", "displayName": "Bob"}],
            },
            "doc-archived-note": {
                "id": "doc-archived-note",
                "name": "Legacy_Runbook.txt",
                "mimeType": "text/plain",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-archive"],
                "webViewLink": "https://drive.google.com/file/d/doc-archived-note/view",
                "version": "2",
                "headRevisionId": "rev-2",
                "md5Checksum": hashlib.md5(archive_txt).hexdigest(),
                "size": str(len(archive_txt)),
                "createdTime": "2026-10-05T08:00:00Z",
                "modifiedTime": "2026-11-20T08:00:00Z",
                "trashed": False,
                "owners": [{"emailAddress": "alice@example.com", "displayName": "Alice"}],
            },
            "pdf-oversized": {
                "id": "pdf-oversized",
                "name": "Firmware_Archive_Huge.pdf",
                "mimeType": "application/pdf",
                "driveId": "0AO_SUPPORT",
                "parents": ["folder-procedures"],
                "webViewLink": "https://drive.google.com/file/d/pdf-oversized/view",
                "version": "1",
                "size": "104857600",
                "createdTime": "2026-10-20T08:00:00Z",
                "modifiedTime": "2026-11-20T12:45:00Z",
                "trashed": False,
                "owners": [{"emailAddress": "alice@example.com", "displayName": "Alice"}],
            },
        },
        "permissions": {
            "0AO_SUPPORT": [
                {"id": "p1", "type": "group", "role": "reader", "emailAddress": "support@example.com", "displayName": "Support"},
            ],
            "folder-procedures": [
                {"id": "p1", "type": "group", "role": "reader", "emailAddress": "support@example.com", "displayName": "Support"},
            ],
            "folder-restricted": [
                {"id": "p-bob", "type": "user", "role": "reader", "emailAddress": "bob@example.com", "displayName": "Bob"},
            ],
            "folder-archive": [
                {"id": "p-alice", "type": "user", "role": "reader", "emailAddress": "alice@example.com", "displayName": "Alice"},
            ],
            "doc-reset": [
                {"id": "p1", "type": "user", "role": "writer", "emailAddress": "alice@example.com", "displayName": "Alice"},
                {"id": "p2", "type": "group", "role": "reader", "emailAddress": "support@example.com", "displayName": "Support"},
            ],
            "slides-oncall": [
                {"id": "p2", "type": "group", "role": "reader", "emailAddress": "support@example.com", "displayName": "Support"},
            ],
            "pdf-manual": [
                {"id": "p1", "type": "user", "role": "reader", "emailAddress": "alice@example.com", "displayName": "Alice"},
                {"id": "p3", "type": "domain", "role": "reader", "domain": "example.com", "displayName": "Example Corp"},
            ],
            "doc-public": [
                {"id": "p-any", "type": "anyone", "role": "reader"},
            ],
            "doc-restricted": [
                {"id": "p-bob", "type": "user", "role": "reader", "emailAddress": "bob@example.com", "displayName": "Bob"},
            ],
            "doc-archived-note": [
                {"id": "p-alice", "type": "user", "role": "reader", "emailAddress": "alice@example.com", "displayName": "Alice"},
            ],
            "pdf-oversized": [
                {"id": "p1", "type": "user", "role": "reader", "emailAddress": "alice@example.com", "displayName": "Alice"},
            ],
        },
        "exports": {
            "doc-reset|text/html": (
                "<html><body><h1>Password Reset</h1>"
                "<p>Verify user identity before resetting MFA credentials. Reference GDRV-DOC-RESET-V1.</p>"
                "</body></html>"
            ),
            "slides-oncall|text/plain": (
                "Slide 1: On-Call Escalation\n"
                "Page Tier-2 engineering for P1 security outages. Reference GDRV-SLIDES-ONCALL-V1."
            ),
            "doc-public|text/html": (
                "<html><body><h1>Public Status Page Notes</h1>"
                "<p>External status communication draft with anyone-link permission. Reference GDRV-DOC-PUBLIC-V1.</p>"
                "</body></html>"
            ),
        },
        "binaries": {
            "pdf-manual": manual_pdf,
            "doc-restricted": restricted_txt,
            "doc-archived-note": archive_txt,
        },
        "startPageToken": 100,
        "changes": [],
        "nextGeneratedId": 1,
        "requests": {},
    }


STATE = _initial_state()


def _record_change(file_id, removed=False, file_obj=None):
    seq = STATE["startPageToken"]
    change = {
        "seq": seq,
        "kind": "drive#change",
        "changeType": "file",
        "type": "file",
        "fileId": file_id,
        "removed": removed,
        "driveId": (file_obj or {}).get("driveId", "0AO_SUPPORT"),
    }
    if not removed and file_obj is not None:
        change["file"] = copy.deepcopy(file_obj)
    STATE["changes"].append(change)
    STATE["startPageToken"] = seq + 1
    return seq


def _count_request(path):
    STATE["requests"][path] = STATE["requests"].get(path, 0) + 1


class GoogleDriveMockHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        if not QUIET:
            super().log_message(fmt, *args)

    def _send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_bytes(self, status, content_type, data):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _require_auth(self):
        auth = self.headers.get("Authorization", "")
        if auth != f"Bearer {VALID_TOKEN}":
            self._send_json(401, {"error": {"code": 401, "message": "Invalid Credentials"}})
            return False
        return True

    def _read_json_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        return json.loads(raw) if raw.strip() else {}

    def do_POST(self):
        global STATE
        parsed = urllib.parse.urlparse(self.path)

        if parsed.path == "/token":
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length).decode("utf-8") if length > 0 else ""
            params = urllib.parse.parse_qs(raw)
            grant = params.get("grant_type", [""])[0]
            if grant in ("urn:ietf:params:oauth:grant-type:jwt-bearer", "refresh_token"):
                self._send_json(200, {"access_token": VALID_TOKEN, "token_type": "Bearer", "expires_in": 3600})
                return
            self._send_json(400, {"error": "unsupported_grant_type"})
            return

        with LOCK:
            if parsed.path == "/mock/reset":
                STATE = _initial_state()
                self._send_json(200, {"status": "reset", "startPageToken": str(STATE["startPageToken"])})
                return

            if parsed.path == "/mock/files":
                body = self._read_json_body()
                fid = body.get("id") or f"doc-gen-{STATE['nextGeneratedId']}"
                STATE["nextGeneratedId"] += 1
                mime = body.get("mimeType", "application/vnd.google-apps.document")
                drive_id = body.get("driveId", "0AO_SUPPORT")
                parents = body.get("parents") or [body.get("parentId", "folder-procedures")]
                name = body.get("name", f"{fid}.txt")
                content = body.get("content", f"Generated content for {fid}")
                export_mime = body.get("exportMimeType", "text/html" if mime == "application/vnd.google-apps.document" else "text/plain")
                now = _now_iso()
                file_obj = {
                    "id": fid,
                    "name": name,
                    "mimeType": mime,
                    "driveId": drive_id,
                    "parents": parents,
                    "webViewLink": body.get("webViewLink", f"https://docs.google.com/document/d/{fid}/edit"),
                    "version": "1",
                    "createdTime": now,
                    "modifiedTime": now,
                    "trashed": False,
                    "owners": [{"emailAddress": "alice@example.com", "displayName": "Alice"}],
                }
                if mime.startswith("application/vnd.google-apps."):
                    STATE["exports"][f"{fid}|{export_mime}"] = content
                else:
                    raw_bytes = pdf_bytes(content) if mime == "application/pdf" else content.encode("utf-8")
                    STATE["binaries"][fid] = raw_bytes
                    file_obj["size"] = str(len(raw_bytes))
                    file_obj["md5Checksum"] = hashlib.md5(raw_bytes).hexdigest()
                    file_obj["headRevisionId"] = "rev-1"
                STATE["files"][fid] = file_obj
                STATE["permissions"][fid] = body.get(
                    "permissions",
                    [{"id": f"p-{fid}", "type": "user", "role": "reader", "emailAddress": "alice@example.com", "displayName": "Alice"}],
                )
                seq = _record_change(fid, removed=False, file_obj=file_obj)
                self._send_json(200, {"id": fid, "seq": seq, "startPageToken": str(STATE["startPageToken"])})
                return

            if parsed.path.startswith("/mock/files/") and parsed.path.endswith("/modify"):
                fid = urllib.parse.unquote(parsed.path[len("/mock/files/"):-len("/modify")])
                file_obj = STATE["files"].get(fid)
                if not file_obj:
                    self._send_json(404, {"error": f"Unknown file {fid}"})
                    return
                body = self._read_json_body()
                new_ver = str(int(file_obj.get("version", "1")) + 1)
                file_obj["version"] = new_ver
                file_obj["modifiedTime"] = _now_iso()
                if "name" in body:
                    file_obj["name"] = body["name"]
                if "content" in body:
                    mime = file_obj.get("mimeType", "")
                    if mime.startswith("application/vnd.google-apps."):
                        export_mime = body.get(
                            "exportMimeType",
                            "text/html" if mime == "application/vnd.google-apps.document" else "text/plain",
                        )
                        STATE["exports"][f"{fid}|{export_mime}"] = body["content"]
                    else:
                        raw_bytes = pdf_bytes(body["content"]) if mime == "application/pdf" else body["content"].encode("utf-8")
                        STATE["binaries"][fid] = raw_bytes
                        file_obj["size"] = str(len(raw_bytes))
                        file_obj["md5Checksum"] = hashlib.md5(raw_bytes).hexdigest()
                        file_obj["headRevisionId"] = f"rev-{new_ver}"
                seq = _record_change(fid, removed=False, file_obj=file_obj)
                self._send_json(200, {"id": fid, "version": new_ver, "seq": seq, "startPageToken": str(STATE["startPageToken"])})
                return

            if parsed.path.startswith("/mock/files/") and parsed.path.endswith("/delete"):
                fid = urllib.parse.unquote(parsed.path[len("/mock/files/"):-len("/delete")])
                file_obj = STATE["files"].get(fid)
                if not file_obj:
                    self._send_json(404, {"error": f"Unknown file {fid}"})
                    return
                body = self._read_json_body()
                hard = bool(body.get("hard", False))
                if hard:
                    STATE["files"].pop(fid, None)
                    seq = _record_change(fid, removed=True, file_obj=file_obj)
                else:
                    file_obj["trashed"] = True
                    file_obj["version"] = str(int(file_obj.get("version", "1")) + 1)
                    file_obj["modifiedTime"] = _now_iso()
                    seq = _record_change(fid, removed=False, file_obj=file_obj)
                self._send_json(200, {"id": fid, "hard": hard, "seq": seq, "startPageToken": str(STATE["startPageToken"])})
                return

            if parsed.path.startswith("/mock/files/") and parsed.path.endswith("/move"):
                fid = urllib.parse.unquote(parsed.path[len("/mock/files/"):-len("/move")])
                file_obj = STATE["files"].get(fid)
                if not file_obj:
                    self._send_json(404, {"error": f"Unknown file {fid}"})
                    return
                body = self._read_json_body()
                file_obj["parents"] = body.get("parents", [])
                if "driveId" in body:
                    file_obj["driveId"] = body["driveId"]
                file_obj["version"] = str(int(file_obj.get("version", "1")) + 1)
                file_obj["modifiedTime"] = _now_iso()
                seq = _record_change(fid, removed=False, file_obj=file_obj)
                self._send_json(200, {"id": fid, "parents": file_obj["parents"], "seq": seq, "startPageToken": str(STATE["startPageToken"])})
                return

            if parsed.path.startswith("/mock/files/") and parsed.path.endswith("/permissions"):
                fid = urllib.parse.unquote(parsed.path[len("/mock/files/"):-len("/permissions")])
                file_obj = STATE["files"].get(fid)
                if not file_obj:
                    self._send_json(404, {"error": f"Unknown file {fid}"})
                    return
                body = self._read_json_body()
                STATE["permissions"][fid] = body.get("permissions", [])
                seq = None
                if body.get("emitChange", False):
                    file_obj["version"] = str(int(file_obj.get("version", "1")) + 1)
                    file_obj["modifiedTime"] = _now_iso()
                    seq = _record_change(fid, removed=False, file_obj=file_obj)
                self._send_json(200, {"id": fid, "permissions": len(STATE["permissions"][fid]), "seq": seq, "startPageToken": str(STATE["startPageToken"])})
                return

        self._send_json(404, {"error": {"code": 404, "message": f"Unknown POST {parsed.path}"}})

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)

        if parsed.path == "/mock/stats":
            with LOCK:
                excluded_mimes = {
                    "application/vnd.google-apps.folder",
                    "application/vnd.google-apps.spreadsheet",
                    "application/vnd.google-apps.form",
                }
                live_indexable = [
                    f for f in STATE["files"].values()
                    if not f.get("trashed")
                    and f.get("mimeType") not in excluded_mimes
                    and int(f.get("size", "0")) <= 52428800
                ]
                self._send_json(200, {
                    "drives": len(STATE["drives"]),
                    "files": len(STATE["files"]),
                    "expectedIndexedFiles": len(live_indexable),
                    "startPageToken": str(STATE["startPageToken"]),
                    "requests": STATE["requests"],
                })
            return

        if not self._require_auth():
            return

        with LOCK:
            _count_request(parsed.path)

            if parsed.path == "/drive/v3/about":
                self._send_json(200, {
                    "user": {"displayName": "OpenCrawling SA", "emailAddress": "crawler@opencrawling.iam.gserviceaccount.com"},
                    "storageQuota": {"limit": "10737418240", "usage": "1048576"},
                })
                return

            if parsed.path == "/drive/v3/drives":
                self._send_json(200, {"drives": list(STATE["drives"].values())})
                return

            if parsed.path.startswith("/drive/v3/drives/"):
                drive_id = urllib.parse.unquote(parsed.path[len("/drive/v3/drives/"):])
                drive = STATE["drives"].get(drive_id)
                if not drive:
                    self._send_json(404, {"error": {"code": 404, "message": "Drive not found"}})
                else:
                    self._send_json(200, drive)
                return

            if parsed.path == "/drive/v3/changes/startPageToken":
                self._send_json(200, {"startPageToken": str(STATE["startPageToken"])})
                return

            if parsed.path == "/drive/v3/changes":
                raw_token = params.get("pageToken", ["0"])[0]
                try:
                    token_num = int(raw_token)
                except ValueError:
                    self._send_json(400, {"error": {"code": 400, "message": "Invalid pageToken"}})
                    return
                matching = [
                    {k: v for k, v in c.items() if k != "seq"}
                    for c in STATE["changes"]
                    if c["seq"] >= token_num
                ]
                self._send_json(200, {
                    "changes": matching,
                    "newStartPageToken": str(STATE["startPageToken"]),
                })
                return

            if parsed.path == "/drive/v3/files":
                q = params.get("q", [""])[0]
                drive_filter = params.get("driveId", [None])[0]
                m = PARENT_RE.search(q)
                parent_id = m.group(1) if m else None
                out = []
                for f in STATE["files"].values():
                    if f.get("trashed"):
                        continue
                    if drive_filter and f.get("driveId") != drive_filter:
                        continue
                    if parent_id is None or parent_id in f.get("parents", []):
                        out.append(f)
                self._send_json(200, {"files": out})
                return

            if parsed.path.startswith("/drive/v3/files/") and parsed.path.endswith("/permissions"):
                fid = urllib.parse.unquote(parsed.path[len("/drive/v3/files/"):-len("/permissions")])
                self._send_json(200, {"permissions": STATE["permissions"].get(fid, [])})
                return

            if parsed.path.startswith("/drive/v3/files/") and parsed.path.endswith("/export"):
                fid = urllib.parse.unquote(parsed.path[len("/drive/v3/files/"):-len("/export")])
                mime = params.get("mimeType", ["text/plain"])[0]
                text = STATE["exports"].get(f"{fid}|{mime}", f"Exported {fid} as {mime}")
                self._send_bytes(200, mime, text.encode("utf-8"))
                return

            if parsed.path.startswith("/drive/v3/files/"):
                fid = urllib.parse.unquote(parsed.path[len("/drive/v3/files/"):])
                if params.get("alt", [""])[0] == "media":
                    data = STATE["binaries"].get(fid)
                    if data is None:
                        self._send_json(404, {"error": {"code": 404, "message": "Binary not found"}})
                    else:
                        self._send_bytes(200, "application/octet-stream", data)
                    return
                f = STATE["files"].get(fid)
                if not f:
                    self._send_json(404, {"error": {"code": 404, "message": "File not found"}})
                else:
                    self._send_json(200, f)
                return

        self._send_json(404, {"error": {"code": 404, "message": f"Unknown GET {parsed.path}"}})


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), GoogleDriveMockHandler)
    if not QUIET:
        print(f"Google Drive API v3 mock listening on :{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
