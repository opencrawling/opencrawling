#!/usr/bin/env python3
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
"""
Graph mock: an emulator of the Microsoft Entra ID token endpoint and the Microsoft Graph v1.0 SharePoint APIs, for
testing the OpenCrawling SharePoint connector without a Microsoft 365 tenant. Standard library only.

It serves a deterministic, generated site with document libraries (real PDF / DOCX / TXT files, folders, list item
columns, permissions in the grantedToV2 shape, sharing links), the delta query with delta links, deletions and 410 resync,
pre-authenticated download URLs that refuse requests carrying credentials, and mock-only admin endpoints under /mock to
change the tenant between crawls and to inject throttling. It follows the Graph documentation; it is not Microsoft's
service. See README.md.
"""

import hashlib
import io
import json
import os
import random
import re
import sys
import threading
import time
import uuid
import zipfile
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote, urlsplit

PORT = int(os.environ.get("GRAPH_PORT", "8080"))
TENANT = os.environ.get("GRAPH_TENANT", "contoso-tenant")
CLIENT_ID = os.environ.get("GRAPH_CLIENT_ID", "opencrawling-test-app")
CLIENT_SECRET = os.environ.get("GRAPH_CLIENT_SECRET", "test-secret")
HOST = os.environ.get("GRAPH_SHAREPOINT_HOST", "contoso.sharepoint.com")
SITE_PATH = "/" + os.environ.get("GRAPH_SITE_PATH", "/sites/engineering").strip("/")
FILE_COUNT = int(os.environ.get("GRAPH_FILE_COUNT", "40"))
SEED = int(os.environ.get("GRAPH_SEED", "42"))
PAGE_SIZE = int(os.environ.get("GRAPH_PAGE_SIZE", "200"))
TOKEN_SECONDS = int(os.environ.get("GRAPH_TOKEN_SECONDS", "3599"))
PUBLIC_URL = os.environ.get("GRAPH_PUBLIC_URL", "")  # base URL clients use to reach this server (for links)
DOWNLOAD_URL = os.environ.get("GRAPH_DOWNLOAD_URL", "")  # base URL of the pre-authenticated download links (SharePoint's host)
RECORD = os.environ.get("GRAPH_RECORD", "")  # append every token, Graph and download exchange to this JSON-lines file
QUIET = os.environ.get("GRAPH_QUIET", "false").lower() == "true"

SITE_ID = f"{HOST},7c0e5b0a-1111-4bd8-8f2b-2a8e9a349201,9a3f2c1d-2222-4e8a-9c0f-1a2b3c4d5e6f"
SITE_URL = f"https://{HOST}{SITE_PATH}"

# user key: display name, e-mail (UPN), SharePoint site user id, Entra object id
USERS = {
    "u-adele": ("Adele Vance", "adele.vance@contoso.com", 7, "a1d4e1e0-0000-4000-8000-0000000000a1"),
    "u-megan": ("Megan Bowen", "megan.bowen@contoso.com", 9, "a1d4e1e0-0000-4000-8000-0000000000a2"),
    "u-diego": ("Diego Siciliani", "diego.siciliani@contoso.com", 11, "a1d4e1e0-0000-4000-8000-0000000000a3"),
}
GROUP_ENG = ("0d1e6b2a-0000-4000-8000-00000000e001", "Engineering-Core-Team")
SITE_OWNERS = ("3", "Engineering Owners")
CLASSIFICATIONS = ["Internal", "Confidential", "Public"]
STATUSES = ["Draft", "In Review", "Approved"]
TOPICS = ["cloud architecture", "incident response", "data retention", "vendor onboarding", "release management"]

# ----------------------------------------------------------------------------------------------- model


def ts(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Item:
    def __init__(self, drive, iid, name, parent, folder, created, modified, author):
        self.drive, self.id, self.name, self.parent, self.folder = drive, iid, name, parent, folder
        self.created, self.modified, self.author, self.editor = created, modified, author, author
        self.version, self.text, self.ext, self.fields, self.permissions, self.deleted = 1, "", "", {}, [], False

    def etag(self):
        return f'"{{{self.id.upper()}}},{self.version}"'


class Tenant:
    def __init__(self):
        self.lock = threading.RLock()
        self.reset()

    def reset(self):
        with self.lock:
            self.rng = random.Random(SEED)
            self.drives = {
                "b!docs": {"id": "b!docs", "name": "Documents", "driveType": "documentLibrary", "webUrl": SITE_URL + "/Shared%20Documents"},
                "b!contracts": {"id": "b!contracts", "name": "Contracts", "driveType": "documentLibrary", "webUrl": SITE_URL + "/Contracts"},
                "b!onedrive": {"id": "b!onedrive", "name": "OneDrive", "driveType": "business", "webUrl": SITE_URL + "/personal"},
            }
            self.items = {}
            self.events = {d: [] for d in self.drives}  # per drive: ordered item ids, one entry per change
            self.resync_floor = {d: 0 for d in self.drives}  # delta tokens below this are rejected with 410
            self.tokens = {}
            self.requests = {}
            self.faults = []
            self.download_expiry = {}
            self.counter = 0
            self._generate()

    def _id(self):
        self.counter += 1
        return f"01SPX{self.counter:06d}"

    def _add(self, item):
        self.items[item.id] = item
        self.events[item.drive].append(item.id)

    def _generate(self):
        r = self.rng
        start = datetime(2024, 1, 1, tzinfo=timezone.utc)
        for drive in ("b!docs", "b!contracts"):
            root = Item(drive, f"root-{drive[2:]}", "root", None, True, start, start, "u-adele")
            self._add(root)
            folders = [root.id]
            for name in ["Specs", "Policies", "Archive"]:
                f = Item(drive, self._id(), name, root.id, True, start, start, "u-adele")
                self._add(f)
                folders.append(f.id)
                if name == "Specs":
                    sub = Item(drive, self._id(), "2026", f.id, True, start, start, "u-megan")
                    self._add(sub)
                    folders.append(sub.id)
        files = FILE_COUNT
        for i in range(files):
            drive = "b!docs" if i % 4 else "b!contracts"
            parents = [it.id for it in self.items.values() if it.folder and it.drive == drive]
            parent = parents[i % len(parents)]
            ext = ["pdf", "docx", "txt"][i % 3]
            created = start + timedelta(days=r.randint(0, 600))
            item = Item(drive, self._id(), f"Document {i:03d}.{ext}", parent, False, created,
                        created + timedelta(days=r.randint(0, 60)), r.choice(list(USERS)))
            item.ext = ext
            item.text = self._text(item)
            item.fields = {
                "Title": f"Document {i:03d}", "ProjectCode": f"PRJ-{1000 + i}", "DocumentStatus": STATUSES[i % 3],
                "Classification": {"Label": CLASSIFICATIONS[i % 3], "TermGuid": f"term-{i % 3}", "WssId": i % 3 + 1},
                "Reviewers": [{"LookupId": USERS[u][2], "LookupValue": USERS[u][0], "Email": USERS[u][1]} for u in ("u-adele", "u-megan")[: 1 + i % 2]],
                "Budget": round(r.uniform(1000, 250000), 2), "FileLeafRef": item.name, "ContentType": "Document", "_UIVersionString": "1.0",
            }
            item.permissions = self._permissions(i)
            self._add(item)

    def _text(self, item):
        topic = TOPICS[int(hashlib.sha1(item.id.encode()).hexdigest(), 16) % len(TOPICS)]
        return (f"{item.name}\n\nThis document describes {topic} for the engineering organisation. "
                f"Reference code SPX-{item.id}-e{item.version}.\n\n"
                f"Owners review the {topic} guidance every quarter and record decisions in the project log.")

    def _permissions(self, i):
        # Microsoft's documented permissions: an Entra user with id and display name (no e-mail), beside the site user
        def user(u, role="read"):
            name, email, sp_id, oid = USERS[u]
            return {"id": f"p-{u}", "roles": [role], "grantedToV2": {
                "user": {"id": oid, "displayName": name},
                "siteUser": {"id": str(sp_id), "displayName": name, "loginName": f"i:0#.f|membership|{email}"}}}
        pattern = i % 6
        if pattern == 0:  # the whole organisation (internal users)
            return [{"id": "p-all", "roles": ["read"], "grantedToV2": {"siteUser": {"id": "11", "displayName": "Everyone except external users",
                                                                                   "loginName": f"c:0-.f|rolemanager|spo-grid-all-users/{TENANT}"}}}]
        if pattern == 1:  # an Entra group writes, one user reads
            return [{"id": "p-eng", "roles": ["write"], "grantedToV2": {"group": {"id": GROUP_ENG[0], "displayName": GROUP_ENG[1]}}},
                    user("u-adele")]
        if pattern == 2:  # site owners (SharePoint group) and a specific-people link
            return [{"id": "p-owners", "roles": ["owner"], "grantedToV2": {"siteGroup": {"id": SITE_OWNERS[0], "displayName": SITE_OWNERS[1]}}},
                    {"id": "p-link-users", "roles": ["read"], "link": {"scope": "users", "type": "view"},
                     "grantedToIdentitiesV2": [{"user": {"id": USERS["u-megan"][3], "displayName": USERS["u-megan"][0]}}]}]
        if pattern == 3:  # only links: organization and anonymous
            return [{"id": "p-link-org", "roles": ["read"], "link": {"scope": "organization", "type": "view"}},
                    {"id": "p-link-anon", "roles": ["read"], "link": {"scope": "anonymous", "type": "view"}}]
        if pattern == 4:  # exactly the shape of Microsoft's "list permissions" example: the loginName is not a claim
            name, _, sp_id, oid = USERS["u-megan"]
            return [{"id": "p-u-megan", "roles": ["read"], "grantedToV2": {
                "user": {"id": oid, "displayName": name}, "siteUser": {"id": str(sp_id), "displayName": name, "loginName": name}}}]
        return []  # nobody

    # -- views

    def path_of(self, item):
        names = []
        cur = self.items.get(item.parent) if item.parent else None
        while cur is not None and cur.parent is not None:
            names.append(cur.name)
            cur = self.items.get(cur.parent)
        return "/drives/" + item.drive + "/root:" + ("/" + "/".join(reversed(names)) if names else "")

    def drive_item(self, item, base, expand_fields=False, with_path=True, deleted=False):
        if deleted:
            return {"id": item.id, "name": item.name, "deleted": {"state": "deleted"},
                    **({"folder": {}} if item.folder else {"file": {}}),
                    "parentReference": {"driveId": item.drive, "id": item.parent}}
        body = {"id": item.id, "name": item.name, "eTag": item.etag(), "createdDateTime": ts(item.created),
                "lastModifiedDateTime": ts(item.modified),
                "webUrl": self.drives[item.drive]["webUrl"] + self.path_of(item).split("root:", 1)[1] + "/" + quote(item.name),
                "createdBy": {"user": {"id": USERS[item.author][3], "displayName": USERS[item.author][0]}},
                "lastModifiedBy": {"user": {"id": USERS[item.editor][3], "displayName": USERS[item.editor][0]}}}
        if item.parent is None:
            body["root"] = {}
            body["folder"] = {"childCount": sum(1 for i in self.items.values() if i.parent == item.id and not i.deleted)}
            return body
        body["parentReference"] = {"driveId": item.drive, "id": item.parent}
        if with_path:
            body["parentReference"]["path"] = self.path_of(item)
        if item.folder:
            body["folder"] = {"childCount": sum(1 for i in self.items.values() if i.parent == item.id and not i.deleted)}
        else:
            content = file_bytes(item)
            body["size"] = len(content)
            body["cTag"] = item.etag().replace('"{', '"c:{')
            body["file"] = {"mimeType": MIME[item.ext]}
            body["sharepointIds"] = {"listItemUniqueId": "luid-" + item.id, "siteId": SITE_ID.split(",")[1]}
            sig = uuid.uuid4().hex
            self.download_expiry[sig] = time.time() + 3600
            # SharePoint's own shape: the site's download.aspx on the SharePoint host, pre-authenticated by tempauth
            body["@microsoft.graph.downloadUrl"] = (f"{(DOWNLOAD_URL.rstrip('/') or base)}{SITE_PATH}/_layouts/15/download.aspx"
                                                    f"?UniqueId={quote(item.drive + ':' + item.id, safe='')}&tempauth={sig}")
            if expand_fields:
                body["listItem"] = {"id": item.id[-4:], "fields": {"@odata.etag": item.etag(), **item.fields}}
        return body


def file_bytes(item):
    if item.ext == "pdf":
        return pdf_bytes(item.text)
    if item.ext == "docx":
        return docx_bytes(item.text)
    return item.text.encode("utf-8")


MIME = {"pdf": "application/pdf", "txt": "text/plain",
        "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}


def pdf_bytes(text):
    """A minimal, valid single-page PDF with the text in Helvetica (extractable by Apache Tika / PDFBox)."""
    lines = []
    for paragraph in text.split("\n"):
        while len(paragraph) > 90:
            cut = paragraph.rfind(" ", 0, 90)
            cut = cut if cut > 0 else 90
            lines.append(paragraph[:cut])
            paragraph = paragraph[cut:].lstrip()
        lines.append(paragraph)
    esc = lambda s: s.encode("latin-1", "replace").decode("latin-1").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    stream = "BT /F1 11 Tf 14 TL 50 790 Td " + " ".join(f"({esc(line)}) Tj T*" for line in lines) + " ET"
    objects = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
               f"<< /Length {len(stream.encode('latin-1'))} >>\nstream\n{stream}\nendstream",
               "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"]
    out = io.BytesIO()
    out.write(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for n, body in enumerate(objects, 1):
        offsets.append(out.tell())
        out.write(f"{n} 0 obj\n{body}\nendobj\n".encode("latin-1"))
    xref = out.tell()
    out.write(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for off in offsets:
        out.write(f"{off:010d} 00000 n \n".encode())
    out.write(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return out.getvalue()


def docx_bytes(text):
    """A minimal, valid Office Open XML word document."""
    from xml.sax.saxutils import escape
    paragraphs = "".join(f"<w:p><w:r><w:t xml:space=\"preserve\">{escape(p)}</w:t></w:r></w:p>" for p in text.split("\n"))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Default Extension="xml" ContentType="application/xml"/>'
                   '<Override PartName="/word/document.xml" '
                   'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr("_rels/.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
                   'Target="word/document.xml"/></Relationships>')
        z.writestr("word/document.xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
                   f"<w:body>{paragraphs}</w:body></w:document>")
    return out.getvalue()


# ----------------------------------------------------------------------------------------------- HTTP

class GraphError(Exception):
    def __init__(self, status, code, message, headers=None):
        super().__init__(message)
        self.status, self.code, self.message, self.headers = status, code, message, headers or {}


T = Tenant()
RECORD_LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "Graph-Mock/1.0"

    def log_message(self, fmt, *args):
        if not QUIET:
            sys.stderr.write("%s %s\n" % (self.log_date_time_string(), fmt % args))

    def base(self):
        return PUBLIC_URL.rstrip("/") if PUBLIC_URL else f"http://{self.headers.get('Host', f'localhost:{PORT}')}"

    def _send(self, status, body=None, content_type="application/json", raw=None, headers=None):
        data = raw if raw is not None else (b"" if body is None else json.dumps(body).encode("utf-8"))
        if RECORD and not self.path.startswith("/mock/"):
            self._record(status, body if raw is None else None, content_type, headers or {})
        self.send_response(status)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        if self.path.startswith("/v1.0/"):
            self.send_header("request-id", str(uuid.uuid4()))
            if self.headers.get("client-request-id"):
                self.send_header("client-request-id", self.headers.get("client-request-id"))
        if status not in (204, 302):
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def _record(self, status, body, content_type, headers):
        entry = {"method": self.command, "url": self.path, "host": self.headers.get("Host"), "status": status,
                 "authorization": bool(self.headers.get("Authorization")), "contentType": content_type,
                 "clientRequestId": self.headers.get("client-request-id"),
                 "headers": {k: v for k, v in headers.items() if k in ("Location", "Retry-After")}, "body": body}
        with RECORD_LOCK, open(RECORD, "a", encoding="utf-8") as out:
            out.write(json.dumps(entry) + "\n")

    def _error(self, e):
        # Graph's error shape, with the ids Microsoft's support asks for
        inner = {"date": ts(datetime.now(timezone.utc)), "request-id": str(uuid.uuid4())}
        if self.headers.get("client-request-id"):
            inner["client-request-id"] = self.headers.get("client-request-id")
        self._send(e.status, {"error": {"code": e.code, "message": e.message, "innerError": inner}}, headers=e.headers)

    def _form(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        return raw

    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    def _dispatch(self, method):
        parts = urlsplit(self.path)
        path = unquote(parts.path)
        query = {k: v[-1] for k, v in parse_qs(parts.query, keep_blank_values=True).items()}
        try:
            with T.lock:
                route = re.sub(r"/(01SPX\d+|root-\w+|b![\w]+)", "/{id}", path)
                T.requests[f"{method} {route}"] = T.requests.get(f"{method} {route}", 0) + 1
                for fault in T.faults:
                    if fault["remaining"] > 0 and unquote(self.path).startswith(fault["path"]):
                        fault["remaining"] -= 1
                        headers = {"Retry-After": str(fault["retryAfter"])} if fault.get("retryAfter") is not None else {}
                        raise GraphError(fault["status"], "activityLimitReached" if fault["status"] == 429 else "serviceNotAvailable",
                                         "Injected fault.", headers)
            if path.startswith("/mock/"):
                return self._mock(method, path, query)
            if method == "POST" and re.fullmatch(r"/[^/]+/oauth2/v2\.0/token", path):
                return self._token(path)
            if path.endswith("/_layouts/15/download.aspx"):
                return self._download(path, query)
            if not path.startswith("/v1.0/"):
                raise GraphError(404, "itemNotFound", "Not found.")
            self._authorize()
            with T.lock:
                return self._graph(path[len("/v1.0"):], query, unquote(parts.query))
        except GraphError as e:
            self._error(e)

    # -- auth

    def _token(self, path):
        form = {k: v[-1] for k, v in parse_qs(self._form()).items()}
        tenant = path.split("/")[1]
        if tenant != TENANT:
            return self._send(400, {"error": "invalid_request", "error_description": f"AADSTS90002: Tenant '{tenant}' not found."})
        if form.get("grant_type") != "client_credentials":
            return self._send(400, {"error": "unsupported_grant_type", "error_description": "AADSTS70003: grant_type must be client_credentials."})
        if form.get("client_id") != CLIENT_ID or form.get("client_secret") != CLIENT_SECRET:
            return self._send(401, {"error": "invalid_client", "error_description": "AADSTS7000215: Invalid client secret provided."})
        if not form.get("scope", "").endswith("/.default"):
            return self._send(400, {"error": "invalid_scope", "error_description": "AADSTS1002012: The scope must end with /.default."})
        token = uuid.uuid4().hex
        with T.lock:
            T.tokens[token] = time.time() + TOKEN_SECONDS
        self._send(200, {"token_type": "Bearer", "expires_in": TOKEN_SECONDS, "ext_expires_in": TOKEN_SECONDS, "access_token": token})

    def _authorize(self):
        auth = self.headers.get("Authorization", "")
        with T.lock:
            expiry = T.tokens.get(auth[7:]) if auth.startswith("Bearer ") else None
        if expiry is None or expiry < time.time():
            raise GraphError(401, "InvalidAuthenticationToken", "Access token has expired or is not yet valid.")

    def _download(self, path, query):
        if self.headers.get("Authorization"):
            # SharePoint download URLs are pre-authenticated; a client that forwards its Graph token there leaks it
            raise GraphError(400, "invalidRequest", "Download URLs must be called without an Authorization header.")
        drive, _, iid = query.get("UniqueId", "").partition(":")
        sig = query.get("tempauth", "")
        with T.lock:
            expiry = T.download_expiry.get(sig)
            item = T.items.get(iid)
            item = item if item is not None and item.drive == drive else None
        if expiry is None or expiry < time.time():
            raise GraphError(401, "unauthenticated", "The download URL has expired.")
        if item is None or item.deleted or item.folder:
            raise GraphError(404, "itemNotFound", "The item does not exist.")
        self._send(200, raw=file_bytes(item), content_type=MIME[item.ext])

    # -- graph

    def _item(self, drive, iid):
        item = T.items.get(iid)
        if item is None or item.drive != drive or item.deleted:
            raise GraphError(404, "itemNotFound", "The resource could not be found.")
        return item

    def _page(self, values, link_base, query, skip):
        top = min(int(query.get("$top", PAGE_SIZE)), 999)
        page = values[skip:skip + top]
        body = {"value": page}
        if skip + top < len(values):
            body["@odata.nextLink"] = f"{link_base}{'&' if '?' in link_base else '?'}$skiptoken={skip + top}"
        return body

    def _graph(self, rest, query, raw_query):
        base = self.base()
        expand = "listItem" in query.get("$expand", "")
        if rest == f"/sites/{HOST}:{SITE_PATH}" or rest == f"/sites/{SITE_ID}":
            return self._send(200, {"id": SITE_ID, "name": SITE_PATH.rsplit("/", 1)[-1], "displayName": "Engineering", "webUrl": SITE_URL})
        if rest == f"/sites/{SITE_ID}/drives":
            return self._send(200, {"value": list(T.drives.values())})
        if rest.startswith("/sites/"):
            raise GraphError(404, "itemNotFound", "Requested site could not be found.")
        m = re.fullmatch(r"/drives/([^/]+)", rest)
        if m:
            if m.group(1) not in T.drives:
                raise GraphError(404, "itemNotFound", "The drive does not exist.")
            return self._send(200, T.drives[m.group(1)])
        m = re.fullmatch(r"/drives/([^/]+)/root(?::(/.*?):?)?", rest)
        if m:
            drive, path = m.group(1), (m.group(2) or "").rstrip(":")
            item = self._item(drive, f"root-{drive[2:]}")
            for name in [p for p in path.split("/") if p]:
                item = next((i for i in T.items.values() if i.parent == item.id and i.name == name and not i.deleted), None)
                if item is None:
                    raise GraphError(404, "itemNotFound", f"The resource '{path}' could not be found.")
            return self._send(200, T.drive_item(item, base))
        m = re.fullmatch(r"/drives/([^/]+)/root/delta", rest)
        if m:
            return self._delta(m.group(1), query, expand, base)
        m = re.fullmatch(r"/drives/([^/]+)/items/([^/]+)(/children|/permissions|/listItem|/content)?", rest)
        if m:
            drive, iid, sub = m.group(1), m.group(2), m.group(3) or ""
            item = self._item(drive, iid)
            if sub == "":
                return self._send(200, T.drive_item(item, base, expand))
            if sub == "/children":
                children = sorted((i for i in T.items.values() if i.parent == iid and not i.deleted), key=lambda i: (not i.folder, i.name))
                values = [T.drive_item(i, base, expand) for i in children]
                link = f"{base}/v1.0/drives/{drive}/items/{iid}/children?$top={query.get('$top', PAGE_SIZE)}"
                if expand:
                    link += "&$expand=" + quote("listItem($expand=fields)")
                return self._send(200, self._page(values, link, query, int(query.get("$skiptoken", 0))))
            if sub == "/permissions":
                return self._send(200, {"value": item.permissions})
            if sub == "/listItem":
                return self._send(200, {"id": iid[-4:], "fields": {"@odata.etag": item.etag(), **item.fields}})
            if sub == "/content":
                return self._send(302, headers={"Location": T.drive_item(item, base)["@microsoft.graph.downloadUrl"]})
        raise GraphError(400, "invalidRequest", f"Unsupported request: GET /v1.0{rest}")

    def _delta(self, drive, query, expand, base):
        if drive not in T.drives:
            raise GraphError(404, "itemNotFound", "The drive does not exist.")
        events = T.events[drive]
        token = query.get("token")
        start = 0 if not token else int(token.split("-")[-1])
        if token and start < T.resync_floor[drive]:
            restart = f"{base}/v1.0/drives/{drive}/root/delta?$top={query.get('$top', PAGE_SIZE)}" + (
                "&$expand=" + quote("listItem($expand=fields)") if expand else "")
            raise GraphError(410, "resyncChangesApplyDifferences", "Resync required. Replace any local items with the server's version.",
                             {"Location": restart})
        if start == 0:
            # a full enumeration: the current state of every item, files listed before their folders now and then
            ids = [i.id for i in T.items.values() if i.drive == drive and not i.deleted]
            ids.sort(key=lambda x: hashlib.sha1(f"{SEED}{x}".encode()).hexdigest())
            root_id = f"root-{drive[2:]}"
            ids.remove(root_id)
            ids.insert(0, root_id)
        else:
            seen, ids = set(), []
            for iid in reversed(events[start:]):  # last occurrence wins
                if iid not in seen:
                    seen.add(iid)
                    ids.append(iid)
            ids.reverse()
        skip = int(query.get("$skiptoken", 0))
        top = min(int(query.get("$top", PAGE_SIZE)), 999)
        page_ids = ids[skip:skip + top]
        values = []
        for iid in page_ids:
            item = T.items[iid]
            values.append(T.drive_item(item, base, expand, with_path=False, deleted=item.deleted))
        body = {"value": values}
        token_now = f"{drive[2:]}-{len(events)}"
        if skip + top < len(ids):
            link = f"{base}/v1.0/drives/{drive}/root/delta?" + (f"token={token}&" if token else "") + f"$top={top}&$skiptoken={skip + top}"
            if expand:
                link += "&$expand=" + quote("listItem($expand=fields)")
            body["@odata.nextLink"] = link
        else:
            body["@odata.deltaLink"] = f"{base}/v1.0/drives/{drive}/root/delta?token={token_now}"
        self._send(200, body)

    # -- admin

    def _mock(self, method, path, query):
        with T.lock:
            if method == "GET" and path == "/mock/stats":
                files = [i for i in T.items.values() if not i.folder and not i.deleted]
                libraries = {d: 0 for d in T.drives}
                for f in files:
                    libraries[f.drive] += 1
                return self._send(200, {"site": SITE_URL, "siteId": SITE_ID, "liveFiles": len(files), "filesPerDrive": libraries,
                                        "deletedItems": sum(1 for i in T.items.values() if i.deleted), "tokensIssued": len(T.tokens),
                                        "users": {u[1]: u[3] for u in USERS.values()},
                                        "requests": T.requests})
            m = re.fullmatch(r"/mock/items/([^/]+)", path)
            if method == "GET" and m:
                item = T.items.get(m.group(1))
                if item is None:
                    raise GraphError(404, "itemNotFound", "No such item.")
                return self._send(200, {"id": item.id, "drive": item.drive, "name": item.name, "folder": item.folder, "parent": item.parent,
                                        "deleted": item.deleted, "eTag": item.etag(), "text": item.text, "permissions": item.permissions,
                                        "path": T.path_of(item).split("root:", 1)[1] + "/" + item.name})
            if method == "GET" and path == "/mock/files":
                return self._send(200, [{"id": i.id, "drive": i.drive, "name": i.name, "pattern": int(i.name[9:12]) % 6 if i.name.startswith("Document ") else None}
                                        for i in T.items.values() if not i.folder and not i.deleted])
            if method != "POST":
                raise GraphError(405, "methodNotAllowed", "Mock admin endpoints take POST.")
            body = json.loads(self._form() or "{}")
            now = datetime.now(timezone.utc).replace(microsecond=0)
            if path == "/mock/reset":
                T.reset()
                return self._send(200, {"reset": True})
            if path == "/mock/faults":
                T.faults.append({"path": body.get("path", "/v1.0/"), "status": int(body.get("status", 429)),
                                 "retryAfter": body.get("retryAfter"), "remaining": int(body.get("count", 1))})
                return self._send(200, {"faults": T.faults})
            if path == "/mock/expire-tokens":
                T.tokens.clear()
                return self._send(200, {"expired": True})
            if path == "/mock/expire-downloads":
                T.download_expiry.clear()
                return self._send(200, {"expired": True})
            m = re.fullmatch(r"/mock/drives/([^/]+)/resync", path)
            if m:
                T.resync_floor[m.group(1)] = len(T.events[m.group(1)])
                return self._send(200, {"resyncFloor": T.resync_floor[m.group(1)]})
            if path == "/mock/files":
                drive = body.get("drive", "b!docs")
                parent = body.get("parent") or f"root-{drive[2:]}"
                item = Item(drive, T._id(), body.get("name", "New file.txt"), parent, False, now, now, "u-adele")
                item.ext = item.name.rsplit(".", 1)[-1] if "." in item.name else "txt"
                item.text = body.get("text") or T._text(item)
                item.fields = {"Title": body.get("title", item.name), "DocumentStatus": "Draft"}
                item.permissions = body.get("permissions", T._permissions(4))
                T._add(item)
                return self._send(201, T.drive_item(item, self.base()))
            m = re.fullmatch(r"/mock/items/([^/]+)/(modify|delete|permissions)", path)
            if m:
                item = T.items.get(m.group(1))
                if item is None:
                    raise GraphError(404, "itemNotFound", "No such item.")
                action = m.group(2)
                if action == "delete":
                    gone = [item] + ([i for i in T.items.values() if self._under(i, item.id)] if item.folder else [])
                    for g in gone:
                        g.deleted = True
                    # Graph reports the deleted folder; its children may or may not be listed
                    T.events[item.drive].append(item.id)
                    return self._send(200, {"deleted": [g.id for g in gone]})
                if action == "permissions":
                    item.permissions = body.get("permissions", [])
                    return self._send(200, {"permissions": item.permissions})  # no delta event: like Graph without FullControl
                item.version += 1
                item.modified = now
                item.text = re.sub(r"SPX-(\w+)-e\d+", rf"SPX-\1-e{item.version}", item.text) + ("\n" + body["appendText"] if body.get("appendText") else "")
                if body.get("name"):
                    item.name = body["name"]
                T.events[item.drive].append(item.id)
                return self._send(200, T.drive_item(item, self.base()))
        raise GraphError(404, "itemNotFound", f"Unknown mock endpoint '{path}'.")

    @staticmethod
    def _under(item, folder_id):
        cur = item
        while cur is not None and cur.parent is not None:
            if cur.parent == folder_id:
                return True
            cur = T.items.get(cur.parent)
        return False


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    files = sum(1 for i in T.items.values() if not i.folder)
    print(f"Graph mock on :{PORT} (token at /{TENANT}/oauth2/v2.0/token, Graph at /v1.0): site {SITE_URL}, {files} files in "
          f"{sum(1 for d in T.drives.values() if d['driveType'] == 'documentLibrary')} document libraries, client {CLIENT_ID}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
