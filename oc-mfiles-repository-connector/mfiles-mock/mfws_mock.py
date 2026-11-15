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
MFWS mock: an emulator of the M-Files Web Service (REST API) for testing the OpenCrawling M-Files connector without
an M-Files Server. Standard library only.

It serves a deterministic, generated vault (documents with real PDF / DOCX / TXT files, multi-file documents, versions,
deleted objects, customers, typed properties, value lists, views and permissions) under /REST, the VAF change feed of
a Vault Application Framework app, and mock-only admin endpoints under /mock to change the vault and inject faults.

It follows the public MFWS reference (developer.m-files.com). Where the reference is silent, notably the
/objects/{type}/{id}/{version}/permissions payload, the mock uses the shape the connector expects; it verifies the
connector against an MFWS-shaped server, not M-Files' exact behaviour. See README.md.
"""

import hashlib
import io
import json
import os
import random
import re
import sys
import threading
import uuid
import zipfile
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit

# ----------------------------------------------------------------------------------------------- configuration

PORT = int(os.environ.get("MFWS_PORT", "8080"))
VAULT_GUID = os.environ.get("MFWS_VAULT_GUID", "{C0B3B52E-6512-4BD8-8F2B-2A8E9A349201}").upper()
VAULT_NAME = os.environ.get("MFWS_VAULT_NAME", "Contracts (mock)")
USERS = dict(u.split(":", 1) for u in os.environ.get("MFWS_USERS", "crawler:secret").split(",") if ":" in u)
BEARER_TOKENS = {t for t in os.environ.get("MFWS_BEARER_TOKENS", "").split(",") if t}
OBJECT_COUNT = int(os.environ.get("MFWS_OBJECT_COUNT", "600"))
CUSTOMER_COUNT = int(os.environ.get("MFWS_CUSTOMER_COUNT", "20"))
SEED = int(os.environ.get("MFWS_SEED", "42"))
DEFAULT_LIMIT = int(os.environ.get("MFWS_DEFAULT_LIMIT", "500"))
VIEW_LIMIT = int(os.environ.get("MFWS_VIEW_LIMIT", "500"))
VAF_PATH = "/" + os.environ.get("MFWS_VAF_PATH", "/vaf/indexer").strip("/")
PERMISSIONS = os.environ.get("MFWS_PERMISSIONS", "acl").lower()  # "acl" or "absent" (a server without the resource)
QUIET = os.environ.get("MFWS_QUIET", "false").lower() == "true"

BRACED = VAULT_GUID if VAULT_GUID.startswith("{") else "{" + VAULT_GUID + "}"

# ----------------------------------------------------------------------------------------------- vault structure

DOCUMENT, CUSTOMER = 0, 103
OBJECT_TYPES = [
    {"ID": DOCUMENT, "Name": "Document", "NamePlural": "Documents", "RealObjectType": True},
    {"ID": CUSTOMER, "Name": "Customer", "NamePlural": "Customers", "RealObjectType": True},
]
CLASSES = {
    14: ("Non-Disclosure Agreement", DOCUMENT),
    22: ("Master Services Agreement", DOCUMENT),
    5: ("Invoice", DOCUMENT),
    40: ("Policy", DOCUMENT),
    31: ("Customer", CUSTOMER),
}
DOCUMENT_CLASSES = [14, 22, 5, 40]
# MFDataType: 1 Text, 2 Integer, 3 Floating, 5 Date, 7 Timestamp, 8 Boolean, 9 Lookup, 10 MultiSelectLookup, 13 MultiLineText
PROPERTY_DEFS = [
    (0, "Name or title", 1, 0), (20, "Created", 7, 0), (21, "Last modified", 7, 0), (22, "Single file", 8, 0),
    (23, "Last modified by", 9, 6), (25, "Created by", 9, 6), (38, "Workflow", 9, 7), (39, "State", 9, 8),
    (100, "Class", 9, 1), (1020, "Customer", 9, CUSTOMER), (1021, "Contract Value", 3, 0), (1022, "Effective Date", 5, 0),
    (1023, "Signed", 8, 0), (1024, "Tags", 10, 200), (1025, "Department", 9, 201), (1026, "Notes", 13, 0),
    (1027, "Country", 1, 0),
]
USER_ACCOUNTS = {12: "sarah.connor", 14: "alex.vance", 15: "maya.collins", 16: "jordan.lee", 20: "crawler"}
USER_GROUPS = {1: "All internal users", 5: "legal-department", 6: "finance", 7: "executive-management", 8: "sales"}
VALUE_LISTS = {
    6: USER_ACCOUNTS,   # M-Files built-in value list 6: users
    16: USER_GROUPS,    # built-in value list 16: user groups
    200: {1: "Legal", 2: "Vendor", 3: "Confidential", 4: "Renewal", 5: "Board"},
    201: {1: "Legal", 2: "Finance", 3: "Procurement", 4: "Sales", 5: "IT"},
    7: {1: "Contract approval"},
    8: {1: "Draft", 2: "In review", 3: "Approved", 4: "Archived"},
}
VIEWS = {101: "Documents by class", 102: "Customers", 103: "Modified in 2026", 104: "Large contracts"}
PSEUDO_PROJECT_MANAGER = 1001  # a metadata-driven pseudo-user: resolves to the object's creator

TOPICS = {
    14: ["confidential information", "non-disclosure obligations", "permitted disclosures", "return of materials"],
    22: ["service levels", "statement of work", "limitation of liability", "termination for convenience"],
    5: ["amount due", "payment terms", "purchase order", "value added tax"],
    40: ["information security", "acceptable use", "data retention", "incident reporting"],
}
COUNTRIES = ["Portugal", "Germany", "United Arab Emirates", "Spain", "France", "Egypt"]

# ----------------------------------------------------------------------------------------------- the vault


def ts(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def now():
    return datetime.now(timezone.utc).replace(microsecond=0)


class File:
    def __init__(self, fid, name, ext, text):
        self.id, self.name, self.ext, self.text = fid, name, ext, text


class Version:
    def __init__(self, number, modified, modified_by, files, values):
        self.number, self.modified, self.modified_by, self.files, self.values = number, modified, modified_by, files, values


class Obj:
    def __init__(self, otype, oid, cls, title, created, created_by, acl):
        self.type, self.id, self.cls, self.title = otype, oid, cls, title
        self.created, self.created_by, self.acl = created, created_by, acl
        self.versions, self.deleted, self.deleted_at = [], False, None

    @property
    def latest(self):
        return self.versions[-1]

    def last_modified(self):
        return self.deleted_at or self.latest.modified


class Vault:
    """The generated vault, its VAF change log and the mock's bookkeeping. Every access holds the lock."""

    def __init__(self):
        self.lock = threading.RLock()
        self.reset()

    def reset(self):
        with self.lock:
            self.rng = random.Random(SEED)
            self.objects = {}
            self.events = []
            self.tokens = {}
            self.sessions_ended = 0
            self.requests = {}
            self.faults = []
            self.next_file_id = 1
            self._generate()

    # -- generation

    def _acl(self, i):
        # every pattern names its components; the "named" component restricts the object's own ACL
        patterns = [
            {"custom": [(1, True, "Allow")]},
            {"custom": [(5, True, "Allow"), (12, False, "Allow")]},
            {"custom": [(5, True, "Allow"), (7, True, "Allow"), (15, False, "Deny")], "named": [(5, True, "Allow")]},
            {"custom": [(6, True, "Allow")], "pseudo": [(PSEUDO_PROJECT_MANAGER, "Allow")]},
            {"custom": [(14, False, "Allow"), (12, False, "Allow")], "inactive": [(14, False, "Allow")]},
            {"custom": []},
        ]
        return patterns[i % len(patterns)]

    def _file(self, obj_id, version, cls, ext, title, n):
        fid = self.next_file_id
        self.next_file_id += 1
        return File(fid, title if n == 0 else f"{title} - Exhibit {chr(64 + n)}", ext,
                    self._text(obj_id, version, fid, cls, title, n))

    def _text(self, obj_id, version, fid, cls, title, n):
        topics = TOPICS[cls]
        topic = topics[(obj_id + n) % len(topics)]
        return (f"{title}{' - Exhibit ' + chr(64 + n) if n else ''}\n\n"
                f"This {CLASSES[cls][0].lower()} sets out the {topic} agreed between the parties. "
                f"It covers {topics[(obj_id + 1) % len(topics)]} and {topics[(obj_id + 2) % len(topics)]}. "
                f"Reference code MFX-{obj_id}-v{version}-f{fid}.\n\n"
                f"Both parties acknowledge the {topic} described in this document and agree to review it annually.")

    def _values(self, cls, customer_ids):
        r = self.rng
        values = {1025: r.randint(1, 5), 1026: "Generated by the MFWS mock for OpenCrawling tests."}
        if cls in (14, 22, 5):
            values[1020] = r.choice(customer_ids)
            values[1021] = round(r.uniform(1_000, 250_000), 2)
            values[1022] = (datetime(2019, 1, 1) + timedelta(days=r.randint(0, 2700))).strftime("%Y-%m-%d")
            values[1023] = r.random() < 0.7
            values[1024] = sorted(r.sample(range(1, 6), r.randint(1, 3)))
            values[38] = 1
            values[39] = r.randint(1, 4)
        return values

    def _generate(self):
        r = self.rng
        start = datetime(2019, 1, 1, tzinfo=timezone.utc)
        span = int((datetime(2026, 9, 30, tzinfo=timezone.utc) - start).total_seconds())
        customer_ids = list(range(1, CUSTOMER_COUNT + 1))
        names = ["Cyberdyne Systems", "Tyrell Corporation", "Initech", "Umbrella Retail", "Soylent Foods", "Wayne Logistics",
                 "Stark Components", "Acme Holdings", "Globex", "Hooli Cloud"]
        for cid in customer_ids:
            created = start + timedelta(seconds=r.randint(0, span))
            obj = Obj(CUSTOMER, cid, 31, f"{names[(cid - 1) % len(names)]} {(cid - 1) // len(names) + 1}", created,
                      r.choice(list(USER_ACCOUNTS)), self._acl(0))
            obj.versions.append(Version(1, created, obj.created_by, [], {1027: r.choice(COUNTRIES)}))
            self.objects[(CUSTOMER, cid)] = obj
        bulk = start + timedelta(seconds=r.randint(0, span))  # one bulk import: several objects in the same second
        for i in range(OBJECT_COUNT):
            oid = 1000 + i
            cls = DOCUMENT_CLASSES[i % len(DOCUMENT_CLASSES)]
            title = f"{CLASSES[cls][0]} {oid}"
            created = bulk if i % 25 == 0 else start + timedelta(seconds=r.randint(0, span))
            obj = Obj(DOCUMENT, oid, cls, title, created, r.choice(list(USER_ACCOUNTS)), self._acl(i))
            multi = cls == 22 and i % 3 == 0
            ext = ["pdf", "docx", "txt"][i % 3]
            modified = created
            for number in range(1, 1 + (1 if i % 4 else r.randint(2, 4))):
                if number > 1:
                    modified = min(modified + timedelta(days=r.randint(1, 200)), datetime(2026, 10, 1, tzinfo=timezone.utc))
                files = [self._file(oid, number, cls, ext, title, n) for n in range(3 if multi else 1)]
                if multi:
                    files[1].ext, files[2].ext = "pdf", "txt"
                obj.versions.append(Version(number, modified, r.choice(list(USER_ACCOUNTS)), files,
                                            self._values(cls, customer_ids)))
            self.objects[(DOCUMENT, oid)] = obj
            if i % 20 == 7:
                obj.deleted = True
                obj.deleted_at = modified + timedelta(hours=1)
        for obj in sorted(self.objects.values(), key=lambda o: o.versions[0].modified):
            for v in obj.versions:
                self.events.append((v.modified, obj.type, obj.id, v.number, "UPDATED"))
            if obj.deleted:
                self.events.append((obj.deleted_at, obj.type, obj.id, obj.latest.number, "DELETED"))
        self.events.sort(key=lambda e: e[0])

    # -- views of the data

    def lookup(self, item, display):
        return {"Item": item, "Version": -1, "DisplayValue": display, "Deleted": False, "Hidden": False}

    def object_version(self, obj, version=None):
        v = version or obj.latest
        return {
            "ObjVer": {"ID": obj.id, "Type": obj.type, "Version": v.number},
            "Title": obj.title, "DisplayID": str(obj.id), "Class": obj.cls, "Deleted": obj.deleted,
            "CreatedUtc": ts(obj.created),
            "LastModifiedUtc": ts(obj.last_modified() if v is obj.latest else v.modified),
            "SingleFile": len(v.files) == 1, "ObjectCheckedOut": False, "ObjectVersionFlags": 0,
            "Files": [{"ID": f.id, "Name": f.name, "Extension": f.ext, "Version": 1, "ChangeTimeUtc": ts(v.modified)}
                      for f in v.files],
        }

    def properties(self, obj, v):
        def tv(dt, **kw):
            return dict({"DataType": dt, "HasValue": True}, **kw)

        def lk(item, display):
            return tv(9, Lookup=self.lookup(item, display), DisplayValue=display)

        props = [
            (0, tv(1, Value=obj.title, DisplayValue=obj.title)),
            (20, tv(7, Value=ts(obj.created), DisplayValue=ts(obj.created))),
            (21, tv(7, Value=ts(v.modified), DisplayValue=ts(v.modified))),
            (22, tv(8, Value=len(v.files) == 1, DisplayValue="Yes" if len(v.files) == 1 else "No")),
            (23, lk(v.modified_by, USER_ACCOUNTS[v.modified_by])),
            (25, lk(obj.created_by, USER_ACCOUNTS[obj.created_by])),
            (100, lk(obj.cls, CLASSES[obj.cls][0])),
        ]
        for pd, value in sorted(v.values.items()):
            if pd in (38, 39, 1025):
                vl = {38: 7, 39: 8, 1025: 201}[pd]
                props.append((pd, lk(value, VALUE_LISTS[vl][value])))
            elif pd == 1020:
                customer = self.objects[(CUSTOMER, value)]
                props.append((pd, lk(value, customer.title)))
            elif pd == 1021:
                props.append((pd, tv(3, Value=value, DisplayValue=f"{value:,.2f}")))
            elif pd == 1022:
                props.append((pd, tv(5, Value=value + "T00:00:00Z", DisplayValue=value)))
            elif pd == 1023:
                props.append((pd, tv(8, Value=value, DisplayValue="Yes" if value else "No")))
            elif pd == 1024:
                props.append((pd, tv(10, Lookups=[self.lookup(t, VALUE_LISTS[200][t]) for t in value],
                                     DisplayValue="; ".join(VALUE_LISTS[200][t] for t in value))))
            else:
                dt = 13 if pd == 1026 else 1
                props.append((pd, tv(dt, Value=value, DisplayValue=value)))
        props.append((1099, {"DataType": 1, "HasValue": False, "Value": None, "DisplayValue": ""}))
        return [{"PropertyDef": pd, "TypedValue": t} for pd, t in props]

    def permissions(self, obj):
        """The payload shape of a real M-Files server (verified): numeric permission codes (1 = allow; the mock uses 2 for
        deny, an assumption, the connector treats every code other than 1 as a deny), IsPsudoUser (M-Files' spelling),
        the effective AccessControlList with its CustomComponent and AutomaticComponents, and the NamedACL definition
        block, which a client must not read as part of the effective permissions."""
        def entry(i, group, permission, pseudo=False):
            code = 1 if permission == "Allow" else 2
            return {"ChangePermissionsPermission": code, "EditPermission": code, "ReadPermission": code,
                    "DeletePermission": code, "IsGroup": group, "UserOrGroupID": i, "IsPsudoUser": pseudo,
                    "HasConcreteUserOrGruopID": not pseudo}

        def component(entries, active=True):
            return {"AccessControlEntries": entries, "CanDeactivate": False, "CurrentUserBinding": -2147483648,
                    "HasCurrentUser": False, "HasCurrentUserBinding": False, "HasNamedACLLink": False,
                    "HasPseudoUsers": any(e["IsPsudoUser"] for e in entries), "IsActive": active, "NamedACLLink": -1}

        custom = [entry(i, g, p) for i, g, p in obj.acl.get("custom", [])]
        custom += [entry(pid, False, p, pseudo=True) for pid, p in obj.acl.get("pseudo", [])]
        automatic = []
        if "named" in obj.acl:  # a restricting component, e.g. a workflow state's permissions
            automatic.append(component([entry(i, g, p) for i, g, p in obj.acl["named"]]))
        if "inactive" in obj.acl:  # switched off: it must not restrict anything
            automatic.append(component([entry(i, g, p) for i, g, p in obj.acl["inactive"]], active=False))
        acl = {"IsUnSpecified": False, "CheckedOutToUserID": -2147483648, "HasCheckedOutToUserID": False,
               "IsFullyAuthoritative": False, "AutomaticComponents": automatic, "CustomComponent": component(custom)}
        # the named ACL's own definition; deliberately different from the effective list, which a client must use instead
        named_definition = {"ID": 3, "Name": "Legal only", "IsNamedACL": True, "HasAutomaticComponent": False,
                            "IsUnSpecified": False,
                            "AccessControlList": {"AutomaticComponents": [], "CustomComponent": component([entry(8, True, "Allow")])}}
        return {"AccessControlList": acl, "CustomACL": True, "NamedACL": named_definition,
                "ObjVer": {"Version": obj.latest.number, "VersionType": 4, "ID": obj.id, "Type": obj.type}}

    def effective_tokens(self, obj):
        """What a VAF app computes inside the vault: pseudo-users resolved, components intersected, denies separate."""
        allow = {(i, g) for i, g, p in obj.acl.get("custom", []) if p == "Allow"}
        allow |= {(obj.created_by, False) for _, p in obj.acl.get("pseudo", []) if p == "Allow"}
        if "named" in obj.acl:
            allow &= {(i, g) for i, g, p in obj.acl["named"] if p == "Allow"}
        deny = {(i, g) for comp in ("custom", "named") for i, g, p in obj.acl.get(comp, []) if p == "Deny"}
        allow -= deny
        token = lambda i, g: f"mfiles:{'group' if g else 'user'}:{i}"
        return sorted(token(i, g) for i, g in allow), sorted(token(i, g) for i, g in deny)

    # -- search

    def search(self, params):
        include_deleted = False
        conditions = []
        quick = None
        object_type = None
        for key, value in params:
            if key == "limit" or key == "_method":
                continue
            if key == "q":
                quick = value.lower()
            elif key == "o":
                object_type = int(value)
            elif key == "d":
                include_deleted = value in ("include", "true")
            else:
                m = re.fullmatch(r"p(\d+)([><*^]?)", key)
                if not m:
                    raise MfwsError(400, f"Unsupported search parameter '{key}'.")
                conditions.append((int(m.group(1)), m.group(2), value))
        hits = []
        for obj in self.objects.values():
            if obj.deleted and not include_deleted:
                continue
            if object_type is not None and obj.type != object_type:
                continue
            if quick and quick not in obj.title.lower() and not any(quick in f.text.lower() for f in obj.latest.files):
                continue
            if all(self._matches(obj, pd, op, value) for pd, op, value in conditions):
                hits.append(obj)
        # MFWS does not return results in last-modified order; neither does the mock
        hits.sort(key=lambda o: hashlib.sha1(f"{SEED}:{o.type}:{o.id}".encode()).hexdigest())
        return hits

    def _matches(self, obj, pd, op, value):
        if pd in (20, 21):
            actual = obj.created if pd == 20 else obj.last_modified()
            bound = parse_time(value)
            return {"": actual == bound, ">": actual >= bound, "<": actual <= bound}.get(op, False)
        if pd == 0:
            actual = obj.title
        elif pd == 100:
            actual = obj.cls
        else:
            actual = obj.latest.values.get(pd)
        if actual is None:
            return False
        if isinstance(actual, list):
            return all(int(x) in actual for x in value.split(","))
        a, v = str(actual).lower(), value.lower()
        if op == "*":
            return v in a
        if op == "^":
            return a.startswith(v)
        if op in (">", "<"):
            try:
                return float(actual) >= float(value) if op == ">" else float(actual) <= float(value)
            except ValueError:
                return a >= v if op == ">" else a <= v
        return a == v

    # -- views

    def view_items(self, path):
        levels = [p for p in path.split("/") if p]
        if not levels:
            return [{"FolderContentItemType": 1, "View": {"ID": vid, "Name": name}} for vid, name in VIEWS.items()
                    if vid != 104]
        head = levels[0].lower()
        live = [o for o in self.objects.values() if not o.deleted]
        if head == "v101" and len(levels) == 1:
            return [{"FolderContentItemType": 2, "PropertyFolder": {
                "DataType": 9, "HasValue": True, "Lookup": self.lookup(c, CLASSES[c][0]), "DisplayValue": CLASSES[c][0]}}
                for c in DOCUMENT_CLASSES]
        if head == "v101" and len(levels) == 2 and levels[1].upper().startswith("L"):
            cls = int(levels[1][1:])
            return self._objects_items([o for o in live if o.cls == cls])
        if head == "v102" and len(levels) == 1:
            return self._objects_items([o for o in live if o.type == CUSTOMER])
        if head == "v103" and len(levels) == 1:
            recent = [o for o in live if o.last_modified().year == 2026]
            return [{"FolderContentItemType": 1, "View": {"ID": 104, "Name": VIEWS[104]}}] + self._objects_items(recent)
        if head == "v103" and len(levels) == 2 and levels[1].lower() == "v104":
            return self._objects_items([o for o in live if o.latest.values.get(1021, 0) > 100_000])
        raise MfwsError(404, f"The view path '{path}' does not exist.")

    def _objects_items(self, objs):
        objs = sorted(objs, key=lambda o: (o.type, o.id))
        return [{"FolderContentItemType": 4, "ObjectVersion": self.object_version(o)} for o in objs]

    # -- VAF

    def normalized(self, obj, number):
        v = next((x for x in obj.versions if x.number == number), obj.latest)
        allow, deny = self.effective_tokens(obj)
        metadata = {}
        for prop in self.properties(obj, v):
            pd = prop["PropertyDef"]
            name = next((n for i, n, _, _ in PROPERTY_DEFS if i == pd), None)
            t = prop["TypedValue"]
            if not name or not t.get("HasValue", True):
                continue
            words = re.split(r"[^0-9A-Za-z]+", name)
            key = "mfiles_prop_" + words[0].lower() + "".join(w[:1].upper() + w[1:] for w in words[1:] if w)
            if "Lookups" in t:
                metadata[key] = [lk["DisplayValue"] for lk in t["Lookups"]]
            elif "Lookup" in t:
                metadata[key] = t["Lookup"]["DisplayValue"]
            else:
                metadata[key] = t.get("Value")
        first = v.files[0] if v.files else None
        return {
            "versionString": f"v{v.number}@{ts(v.modified)}", "title": obj.title, "classId": obj.cls,
            "lastModifiedUtc": ts(v.modified), "mimeType": MIME.get(first.ext) if first else None,
            "file": {"name": f"{first.name}.{first.ext}",
                     "downloadUrl": f"objects/{obj.type}/{obj.id}/{v.number}/files/{first.id}/content"} if first else None,
            "metadata": metadata, "aclTokens": allow, "denyTokens": deny,
        }


def parse_time(value):
    value = value.strip()
    if len(value) == 10:
        return datetime.fromisoformat(value).replace(tzinfo=timezone.utc)
    dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


# ----------------------------------------------------------------------------------------------- file formats

MIME = {"pdf": "application/pdf", "txt": "text/plain; charset=utf-8",
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
                   'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
                   '</Types>')
        z.writestr("_rels/.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" '
                   'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
                   'Target="word/document.xml"/></Relationships>')
        z.writestr("word/document.xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                   '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
                   f"<w:body>{paragraphs}</w:body></w:document>")
    return out.getvalue()


def file_bytes(f):
    if f.ext == "pdf":
        return pdf_bytes(f.text)
    if f.ext == "docx":
        return docx_bytes(f.text)
    return f.text.encode("utf-8")


# ----------------------------------------------------------------------------------------------- HTTP


class MfwsError(Exception):
    def __init__(self, status, message, name="MFilesException"):
        super().__init__(message)
        self.status, self.message, self.name = status, message, name


VAULT = Vault()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "MFWS-Mock/1.0"

    def log_message(self, fmt, *args):
        if not QUIET:
            sys.stderr.write("%s %s\n" % (self.log_date_time_string(), fmt % args))

    # -- plumbing

    def _send(self, status, body=None, content_type="application/json; charset=utf-8", raw=None):
        data = raw if raw is not None else (b"" if body is None else json.dumps(body).encode("utf-8"))
        self.send_response(status)
        if status != 204:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data and self.command != "HEAD":
            self.wfile.write(data)

    def _error(self, e):
        self._send(e.status, {"Status": e.status, "URL": self.path, "Method": self.command, "Message": e.message,
                              "Exception": {"Name": e.name, "Message": e.message}})

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            raise MfwsError(400, "The request body is not valid JSON.")

    def _params(self, query):
        params = []
        for part in query.split("&"):
            if not part:
                continue
            key, _, value = part.partition("=")
            params.append((unquote(key), unquote(value.replace("+", " "))))
        return params

    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    def do_DELETE(self):
        self._dispatch("DELETE")

    def do_PUT(self):
        self._dispatch("PUT")

    def _dispatch(self, method):
        parts = urlsplit(self.path)
        path, params = unquote(parts.path), self._params(parts.query)
        if method == "POST" and ("_method", "DELETE") in params:
            method = "DELETE"
        try:
            with VAULT.lock:
                route = re.sub(r"/\d+", "/{n}", path)
                VAULT.requests[f"{method} {route}"] = VAULT.requests.get(f"{method} {route}", 0) + 1
                full = unquote(self.path)
                for fault in VAULT.faults:
                    if fault["remaining"] > 0 and full.startswith(fault["path"]):
                        fault["remaining"] -= 1
                        if fault["expire"]:
                            VAULT.tokens.clear()  # a 403 because every session expired, as after a server restart
                        raise MfwsError(fault["status"], "Injected fault.")
            if path.startswith("/mock/"):
                return self._mock(method, path, params)
            if not path.startswith("/REST/") and path != "/REST":
                raise MfwsError(404, "Not found.")
            rest = path[len("/REST"):] or "/"
            if rest.startswith(VAF_PATH + "/"):
                self._session()
                return self._vaf(rest[len(VAF_PATH):], params)
            return self._rest(method, rest, params)
        except MfwsError as e:
            self._error(e)

    # -- authentication

    def _session(self):
        """The caller's session: a token, credential headers or a bearer token; vault calls need a vault-scoped user."""
        token = self.headers.get("X-Authentication")
        if token:
            with VAULT.lock:
                session = VAULT.tokens.get(token)
            if session is None:
                raise MfwsError(403, "The authentication token has expired or is not valid.", "UnauthorizedAccessException")
            if not session["user"]:
                raise MfwsError(403, "Authentication token does not contain user information.", "UnauthorizedAccessException")
            if not session["vault"]:
                raise MfwsError(403, "The authentication token is not vault-specific.", "UnauthorizedAccessException")
            return session
        user, password = self.headers.get("X-Username"), self.headers.get("X-Password")
        if user is not None:
            name = user.split("\\")[-1]
            if USERS.get(name) != password:
                raise MfwsError(403, "Login to application failed.", "UnauthorizedAccessException")
            self._check_vault(self.headers.get("X-Vault"))
            return {"user": name, "vault": BRACED}
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            if auth[7:] not in BEARER_TOKENS:
                raise MfwsError(401, "The access token is not valid.", "UnauthorizedAccessException")
            self._check_vault(self.headers.get("X-Vault"))
            return {"user": "oauth-client", "vault": BRACED}
        raise MfwsError(401, "Authentication required.", "UnauthorizedAccessException")

    @staticmethod
    def _check_vault(guid):
        if not guid or guid.upper().strip("{}") != BRACED.strip("{}"):
            raise MfwsError(404, f"The vault '{guid}' does not exist.")

    def _authenticate(self):
        body = self._body()
        user = (body.get("Username") or "").split("\\")[-1]
        password = body.get("Password") or ""
        vault = body.get("VaultGuid")
        if user or password:
            if USERS.get(user) != password:
                raise MfwsError(403, "Login to application failed.", "UnauthorizedAccessException")
        else:
            user = None  # M-Files issues an anonymous token for empty credentials
        if vault:
            self._check_vault(vault)
        token = uuid.uuid4().hex + uuid.uuid4().hex
        with VAULT.lock:
            VAULT.tokens[token] = {"user": user, "vault": BRACED if vault else None, "session": body.get("SessionID")}
        self._send(200, {"Value": token})

    # -- REST

    def _rest(self, method, rest, params):
        if method == "POST" and rest == "/server/authenticationtokens":
            return self._authenticate()
        if method == "GET" and rest == "/server/status":
            return self._send(200, {"Status": "OK", "Version": "MFWS-Mock 1.0"})
        if method == "GET" and rest == "/server/vaults":
            return self._send(200, [{"Name": VAULT_NAME, "GUID": BRACED}])
        session = self._session()
        if method == "DELETE" and rest == "/session":
            with VAULT.lock:
                VAULT.tokens.pop(self.headers.get("X-Authentication"), None)
                VAULT.sessions_ended += 1
            return self._send(204)
        if method != "GET":
            raise MfwsError(405, f"{method} is not supported on {rest}.")
        with VAULT.lock:
            return self._read(rest, params, session)

    def _read(self, rest, params, session):
        v = VAULT
        if rest == "/session/vault":
            return self._send(200, {"Name": VAULT_NAME, "GUID": BRACED, "Authentication": None})
        if rest == "/session/userid":
            return self._send(200, next((i for i, n in USER_ACCOUNTS.items() if n == session["user"]), 0))
        if rest == "/structure/objecttypes":
            return self._send(200, OBJECT_TYPES)
        if rest == "/structure/properties":
            return self._send(200, [{"ID": i, "Name": n, "DataType": dt, "ValueList": vl, "BasedOnValueList": vl > 0,
                                     "AllObjectTypes": True, "ObjectType": 0} for i, n, dt, vl in PROPERTY_DEFS])
        if rest == "/structure/classes":
            return self._send(200, [{"ID": c, "Name": n, "ObjectType": t} for c, (n, t) in CLASSES.items()])
        m = re.fullmatch(r"/valuelists/(\d+)/items", rest)
        if m:
            items = VALUE_LISTS.get(int(m.group(1)))
            if items is None:
                raise MfwsError(404, "The value list does not exist.")
            return self._send(200, {"Items": [{"ID": i, "Name": n, "ValueListID": int(m.group(1))} for i, n in items.items()],
                                    "MoreResults": False})
        if rest == "/objects" or rest == "/objects/":
            limit = next((int(val) for key, val in params if key == "limit"), DEFAULT_LIMIT)
            hits = v.search(params)
            return self._send(200, {"Items": [v.object_version(o) for o in hits[:limit]], "MoreResults": len(hits) > limit})
        m = re.fullmatch(r"/views(/.*)?/items", rest)
        if m:
            items = v.view_items(m.group(1) or "")
            return self._send(200, {"Path": (m.group(1) or "").strip("/"), "Items": items[:VIEW_LIMIT],
                                    "MoreResults": len(items) > VIEW_LIMIT})
        m = re.fullmatch(r"/objects/(\d+)/(\d+)/history", rest)
        if m:
            obj = self._object(m)
            return self._send(200, [v.object_version(obj, ver) for ver in reversed(obj.versions)])
        m = re.fullmatch(r"/objects/(\d+)/(\d+)/(latest|\d+)(/.*)?", rest)
        if m:
            obj = self._object(m)
            ver = obj.latest if m.group(3) == "latest" else next((x for x in obj.versions if x.number == int(m.group(3))), None)
            if ver is None:
                raise MfwsError(404, "The object version does not exist.")
            sub = m.group(4) or ""
            if sub == "":
                body = v.object_version(obj, ver)
                if ("include", "properties") in params:
                    body["Properties"] = v.properties(obj, ver)
                return self._send(200, body)
            if sub == "/properties":
                return self._send(200, v.properties(obj, ver))
            if sub == "/files":
                return self._send(200, v.object_version(obj, ver)["Files"])
            if sub == "/permissions":
                if PERMISSIONS == "absent":
                    raise MfwsError(404, "Not found.")
                return self._send(200, v.permissions(obj))
            fm = re.fullmatch(r"/files/(\d+)/content", sub)
            if fm:
                f = next((x for x in ver.files if x.id == int(fm.group(1))), None)
                if f is None:
                    raise MfwsError(404, "The file does not exist.")
                return self._send(200, raw=file_bytes(f), content_type=MIME.get(f.ext, "application/octet-stream"))
        raise MfwsError(404, f"The resource '{rest}' does not exist.")

    @staticmethod
    def _object(match):
        obj = VAULT.objects.get((int(match.group(1)), int(match.group(2))))
        if obj is None:
            raise MfwsError(404, "The object does not exist.")
        return obj

    # -- VAF

    def _vaf(self, sub, params):
        args = dict(params)
        with VAULT.lock:
            if sub == "/changes":
                start = int(args.get("since") or 0)
                size = int(args.get("pageSize") or 100)
                page = VAULT.events[start:start + size]
                return self._send(200, {
                    "changes": [{"objectType": t, "objectId": i, "version": n, "changeType": c} for _, t, i, n, c in page],
                    "nextCursor": str(start + len(page)), "hasMore": start + len(page) < len(VAULT.events)})
            m = re.fullmatch(r"/object/(\d+)/(\d+)/(\d+)/normalized", sub)
            if m:
                return self._send(200, VAULT.normalized(self._object(m), int(m.group(3))))
        raise MfwsError(404, f"The VAF resource '{sub}' does not exist.")

    # -- mock administration

    def _mock(self, method, path, params):
        v = VAULT
        with v.lock:
            if method == "GET" and path == "/mock/stats":
                live = [o for o in v.objects.values() if not o.deleted]
                return self._send(200, {
                    "vault": BRACED, "objects": len(v.objects), "liveObjects": len(live),
                    "deletedObjects": len(v.objects) - len(live),
                    "liveFiles": sum(len(o.latest.files) for o in live if o.type == DOCUMENT),
                    "multiFileDocuments": sum(1 for o in live if len(o.latest.files) > 1),
                    "events": len(v.events), "activeSessions": len(v.tokens), "sessionsEnded": v.sessions_ended,
                    "requests": v.requests})
            if method == "GET" and path.startswith("/mock/objects/"):
                m = re.fullmatch(r"/mock/objects/(\d+)/(\d+)", path)
                obj = self._object(m)
                return self._send(200, {"objectVersion": v.object_version(obj), "permissions": v.permissions(obj),
                                        "effectiveTokens": v.effective_tokens(obj),
                                        "files": [{"ID": f.id, "Name": f.name, "Extension": f.ext, "Text": f.text}
                                                  for f in obj.latest.files]})
            if method != "POST":
                raise MfwsError(405, "Mock admin endpoints take POST.")
            body = self._body()
            if path == "/mock/reset":
                v.reset()
                return self._send(200, {"reset": True})
            if path == "/mock/expire-tokens":
                count = len(v.tokens)
                v.tokens.clear()
                return self._send(200, {"expired": count})
            if path == "/mock/faults":
                v.faults.append({"path": body.get("path", "/REST/"), "status": int(body.get("status", 503)),
                                 "remaining": int(body.get("count", 1)), "expire": bool(body.get("expireTokens", False))})
                return self._send(200, {"faults": v.faults})
            if path == "/mock/objects":
                cls = int(body.get("classId", 14))
                oid = max(o.id for o in v.objects.values() if o.type == DOCUMENT) + 1
                title = body.get("title") or f"{CLASSES[cls][0]} {oid}"
                stamp = now()
                obj = Obj(DOCUMENT, oid, cls, title, stamp, 20, {"custom": [(1, True, "Allow")]})
                f = File(v.next_file_id, title, body.get("extension", "txt"),
                         body.get("text") or v._text(oid, 1, v.next_file_id, cls, title, 0))
                v.next_file_id += 1
                obj.versions.append(Version(1, stamp, 20, [f], v._values(cls, [1])))
                v.objects[(DOCUMENT, oid)] = obj
                v.events.append((stamp, DOCUMENT, oid, 1, "UPDATED"))
                return self._send(201, v.object_version(obj))
            m = re.fullmatch(r"/mock/objects/(\d+)/(\d+)/(modify|delete)", path)
            if m:
                obj = self._object(m)
                stamp = max(now(), obj.last_modified() + timedelta(seconds=1))
                if m.group(3) == "delete":
                    obj.deleted, obj.deleted_at = True, stamp
                    v.events.append((stamp, obj.type, obj.id, obj.latest.number, "DELETED"))
                    return self._send(200, v.object_version(obj))
                prev = obj.latest
                files = [File(f.id, f.name, f.ext, f.text) for f in prev.files
                         if f.id not in set(body.get("removeFileIds", []))]
                number = prev.number + 1
                for f in files:
                    f.text = re.sub(r"MFX-(\d+)-v\d+-", rf"MFX-\1-v{number}-", f.text)
                    if body.get("appendText"):
                        f.text += "\n" + body["appendText"]
                if body.get("title"):
                    obj.title = body["title"]
                obj.versions.append(Version(number, stamp, 20, files, dict(prev.values)))
                v.events.append((stamp, obj.type, obj.id, number, "UPDATED"))
                return self._send(200, v.object_version(obj))
        raise MfwsError(404, f"Unknown mock endpoint '{path}'.")


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    with VAULT.lock:
        documents = sum(1 for o in VAULT.objects.values() if o.type == DOCUMENT)
    print(f"MFWS mock on :{PORT}/REST, vault {VAULT_NAME} {BRACED}: {documents} documents, "
          f"{CUSTOMER_COUNT} customers, users {sorted(USERS)}, VAF at /REST{VAF_PATH}, permissions={PERMISSIONS}",
          flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
