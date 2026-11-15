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
CSGQL mock: an emulator of the IBM FileNet Content Services GraphQL API (the read side) for testing the OpenCrawling
FileNet connector without a FileNet P8 installation.

It is a real GraphQL server (graphql-core) over schema.graphql, a subset of IBM's published schema that conformance.py
checks field by field, so a query the real API would reject is rejected here too. Behind it is a deterministic object
store: documents of three classes with real PDF / DOCX / TXT content, multiple content elements, external content
references, version series, folders (with multi-filing), access permissions of every source and type, and an audit
log of DeletionEvents. Searches take the CE SQL subset the connector needs. Mock-only admin endpoints under /mock change
the store and inject faults.

Protocol, as IBM's sample client and its Content Services MCP server use it:
  POST /content-services-graphql/graphql    JSON {query, variables, operationName}, or the raw query as application/graphql
  GET  /content-services-graphql/ping       liveness, returns an ECM-CS-XSRF-Token
  GET  <downloadUrl>                         content of one element; downloadUrl is relative to /content-services-graphql
  POST /oauth/token                          client-credentials tokens for the bearer mode (mock-only)
Every request but /ping needs Basic or Bearer authentication and the ECM-CS-XSRF-Token header with a cookie of the
same value (double submit). GraphQL errors come back with HTTP 200 and an "errors" array. Where IBM's material is
silent (the download path, the DeletionEvent properties, the HTTP status of a failed XSRF check), the mock makes an
assumption that README.md lists; the connector treats each of those as configurable or opaque.
"""

import base64
import hashlib
import io
import json
import os
import random
import re
import secrets
import sys
import threading
import time
import uuid
import zipfile
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote, urlparse

from graphql import GraphQLError, build_schema, graphql_sync

PORT = int(os.environ.get("CSGQL_PORT", "8080"))
OS_NAME = os.environ.get("CSGQL_OBJECT_STORE", "OS1")
OS_ID = "{" + str(uuid.uuid5(uuid.NAMESPACE_URL, "filenet-mock-os:" + OS_NAME)).upper() + "}"
USERS = dict(u.split(":", 1) for u in os.environ.get("CSGQL_USERS", "p8crawler:secret").split(",") if ":" in u)
OAUTH_CLIENTS = dict(c.split(":", 1) for c in os.environ.get("CSGQL_OAUTH_CLIENTS", "opencrawling:client-secret").split(",")
                     if ":" in c)
DOCUMENT_COUNT = int(os.environ.get("CSGQL_DOCUMENT_COUNT", "60"))
SEED = int(os.environ.get("CSGQL_SEED", "125"))
DEFAULT_PAGE_SIZE = int(os.environ.get("CSGQL_DEFAULT_PAGE_SIZE", "50"))
ENUM_PAGE_SIZE = int(os.environ.get("CSGQL_ENUM_PAGE_SIZE", "50"))  # IBM: -Decm.content.graphql.enumPropertyPageSize
MAX_PAGE_SIZE = int(os.environ.get("CSGQL_MAX_PAGE_SIZE", "1000"))
AUDIT = os.environ.get("CSGQL_AUDIT", "true").lower() == "true"
QUIET = os.environ.get("CSGQL_QUIET", "false").lower() == "true"
BASE = "/content-services-graphql"
XSRF = "ECM-CS-XSRF-Token"

VIEWER = 131201         # IBM's sample: a viewer (READ | VIEW_CONTENT | READ_ACL)
FULL_CONTROL = 998903   # IBM's sample: full control
VIEW_CONTENT = 128
READ = 1

CLASSES = {  # symbolic name -> (parent, custom property definitions: name -> (TypeID, cardinality, label))
    "Document": (None, {}),
    "Contract": ("Document", {"ContractNumber": ("STRING", "SINGLE", "Contract number"),
                              "Counterparty": ("STRING", "SINGLE", "Counterparty"),
                              "ContractValue": ("DOUBLE", "SINGLE", "Contract value"),
                              "Regions": ("STRING", "LIST", "Regions"),
                              "SignedDate": ("DATE", "SINGLE", "Signed date")}),
    "Invoice": ("Document", {"InvoiceNumber": ("STRING", "SINGLE", "Invoice number"),
                             "Amount": ("DOUBLE", "SINGLE", "Amount"),
                             "Paid": ("BOOLEAN", "SINGLE", "Paid"),
                             "LineCount": ("LONG", "SINGLE", "Line count")}),
}
FOLDERS = ["/Contracts", "/Contracts/2026", "/Contracts/Archive", "/Invoices", "/Policies"]
USERS_DIR = ["jdoe", "asmith", "mallory", "ceadmin", "p8crawler"]
COUNTERPARTIES = ["Acme Logistics", "Globex Holdings", "Initech", "Umbrella Freight", "Soylent Trading"]
REGIONS = ["EMEA", "APAC", "AMER"]
FINANCE_DN = "cn=Finance,ou=groups,dc=example,dc=com"
VERSION_STATUS = {"RELEASED": 1, "IN_PROCESS": 2, "RESERVATION": 3, "SUPERSEDED": 4}
SYSTEM_PROPS = {  # property -> (TypeID, label)
    "Id": ("GUID", "ID"), "DocumentTitle": ("STRING", "Document Title"), "Creator": ("STRING", "Created By"),
    "DateCreated": ("DATE", "Date Created"), "LastModifier": ("STRING", "Modified By"),
    "DateLastModified": ("DATE", "Date Last Modified"), "MajorVersionNumber": ("LONG", "Major Version Number"),
    "MinorVersionNumber": ("LONG", "Minor Version Number"), "IsCurrentVersion": ("BOOLEAN", "Is Current Version"),
    "VersionStatus": ("LONG", "Version Status"), "ContentSize": ("DOUBLE", "Content Size"),
    "MimeType": ("STRING", "Content Type"), "VersionSeries": ("GUID", "Version Series"),
}


def guid(*parts):
    return "{" + str(uuid.uuid5(uuid.NAMESPACE_URL, "filenet-mock:" + ":".join(map(str, parts)))).upper() + "}"


def iso(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def now():
    return datetime.now(timezone.utc)


# ----------------------------------------------------------------------------------------------- the object store

class Element:
    def __init__(self, seq, ext=None, text=None, location=None):
        self.seq, self.ext, self.text, self.location = seq, ext, text, location

    @property
    def mime(self):
        if self.location:
            return "text/html"
        return MIME[self.ext]

    def data(self):
        return file_bytes(self.ext, self.text)


class Version:
    def __init__(self, series, major, minor, status, created, creator, elements, props):
        self.id = guid("doc", series.id, major, minor)
        self.series, self.major, self.minor, self.status = series, major, minor, status
        self.created = self.modified = created
        self.creator = self.modifier = creator
        self.elements, self.props, self.usn = elements, props, 1

    @property
    def current(self):
        return self.series.versions[-1] is self


class Series:
    def __init__(self, n, cls, title, pattern):
        self.n, self.cls, self.title, self.pattern = n, cls, title, pattern
        self.id = guid("series", n)
        self.versions, self.folders, self.direct = [], [], []
        self.markings = []  # names of the markings applied (ActiveMarkings)

    @property
    def code(self):
        v = self.versions[-1]
        return f"FNX-{self.n:04d}-v{v.major}.{v.minor}"


class Folder:
    def __init__(self, path, perms):
        self.path, self.perms = path, perms
        self.id = guid("folder", path)
        self.name = path.rsplit("/", 1)[-1] or "Root Folder"
        self.created = self.modified = datetime(2025, 1, 6, 9, 0, tzinfo=timezone.utc)

    @property
    def parent_path(self):
        return self.path.rsplit("/", 1)[0] or "/" if self.path != "/" else None


def ace(grantee, gtype, atype, mask, source="SOURCE_DIRECT", depth="OBJECT_ONLY"):
    return {"granteeName": grantee, "granteeType": gtype, "accessType": atype, "accessMask": mask,
            "permissionSource": source, "inheritableDepth": depth}


class Store:
    """The object store; every change goes through the lock."""

    def __init__(self):
        self.lock = threading.RLock()
        self.reset()

    def reset(self):
        with self.lock:
            self.rng = random.Random(SEED)
            self.audit = AUDIT
            self.series, self.events, self.tokens, self.faults = {}, [], {}, []
            self.stats = {"requests": {}, "downloads": 0, "authFailures": 0, "xsrfFailures": 0}
            self.access_tokens = {}
            # inheritable folder permissions: /Contracts lets Finance read everything below it
            self.folders = {"/": Folder("/", [])}
            for p in FOLDERS:
                perms = [ace("ceadmin", "USER", "ALLOW", FULL_CONTROL)]
                if p == "/Contracts":
                    perms.append(ace(FINANCE_DN, "GROUP", "ALLOW", VIEWER, depth="ALL_CHILDREN_NOT_OBJECT"))
                self.folders[p] = Folder(p, perms)
            base = datetime(2026, 1, 5, 8, 0, tzinfo=timezone.utc)
            for n in range(1, DOCUMENT_COUNT + 1):
                self._seed(n, base + timedelta(hours=7 * n))

    def _seed(self, n, created):
        cls = ["Contract", "Invoice", "Document"][n % 3]
        title = {"Contract": f"Supply agreement {n:04d}", "Invoice": f"Invoice {n:04d}",
                 "Document": f"Policy note {n:04d}"}[cls]
        s = Series(n, cls, title, n % 7)
        s.direct = self._pattern_perms(s.pattern)
        if s.pattern == 6:
            s.markings = ["Confidential"]
        s.folders = {"Contract": ["/Contracts/2026" if n % 2 else "/Contracts/Archive"], "Invoice": ["/Invoices"],
                     "Document": ["/Policies"]}[cls]
        if n % 10 == 3:
            s.folders = s.folders + ["/Policies"]  # filed in two folders
        if n % 17 == 0:
            s.folders = []                          # unfiled
        creator = USERS_DIR[n % 3]
        versions = 2 if n % 5 == 0 else 1
        for i in range(versions):
            self._add_version(s, i + 1, 0, created + timedelta(days=i * 3), creator)
        if n % 11 == 0:  # an in-process minor version on top of the released one
            self._add_version(s, versions, 1, created + timedelta(days=versions * 3 + 1), creator, status="IN_PROCESS")
        self.series[s.id] = s

    def _pattern_perms(self, p):
        """The object's own (direct) entries; each pattern exercises one mapping rule."""
        return [
            [ace("Contract Readers", "GROUP", "ALLOW", VIEWER), ace("jdoe", "USER", "ALLOW", FULL_CONTROL)],
            [ace("#AUTHENTICATED-USERS", "GROUP", "ALLOW", VIEWER)],
            [ace("Legal", "GROUP", "ALLOW", VIEWER), ace("asmith", "USER", "ALLOW", VIEWER),
             ace("mallory", "USER", "DENY", VIEW_CONTENT)],                     # a partial deny: no content for mallory
            [ace("asmith", "USER", "ALLOW", VIEWER, source="SOURCE_DEFAULT")],
            [ace("Auditors", "GROUP", "ALLOW", READ), ace("ceadmin", "USER", "ALLOW", FULL_CONTROL)],  # properties only
            [],                                                                  # no direct entries
            [ace("S-1-5-21-1004336348-1177238915-682003330-512", "UNKNOWN", "ALLOW", VIEWER),
             ace("Legal", "GROUP", "ALLOW", VIEWER), ace("Legal", "GROUP", "DENY", VIEWER, source="MARKING")],
        ][p]

    def _add_version(self, s, major, minor, when, creator, status="RELEASED", text_extra=""):
        n = s.n
        ext = ["pdf", "docx", "txt"][n % 3] if s.cls != "Document" else ["pdf", "txt"][n % 2]
        for v in s.versions:
            if v.status == "RELEASED" and status == "RELEASED":
                v.status = "SUPERSEDED"
        code = f"FNX-{n:04d}-v{major}.{minor}"
        text = (f"{s.title}\nReference {code}\n" + TOPICS[s.cls].format(n=n, cp=COUNTERPARTIES[n % 5]) +
                (("\n" + text_extra) if text_extra else ""))
        elements = []
        if n % 13 == 0:
            elements = []  # a document without content
        elif n % 19 == 0:
            elements = [Element(0, location=f"https://records.example.com/archive/{n}")]
        else:
            elements = [Element(0, ext, text)]
            if n % 4 == 0:
                elements.append(Element(1, "txt", f"Annex to {s.title}\nAnnex reference {code}-A\nSchedule of rates."))
        props = {}
        if s.cls == "Contract":
            props = {"ContractNumber": f"CT-{n:05d}", "Counterparty": COUNTERPARTIES[n % 5],
                     "ContractValue": round(1000 + n * 1234.5, 2), "Regions": REGIONS[: 1 + n % 3],
                     "SignedDate": iso(datetime(2026, 1 + n % 12, 1 + n % 27, tzinfo=timezone.utc))}
        elif s.cls == "Invoice":
            props = {"InvoiceNumber": f"INV-{n:05d}", "Amount": round(n * 87.25, 2), "Paid": n % 2 == 0,
                     "LineCount": 1 + n % 9}
        v = Version(s, major, minor, status, when, creator, elements, props)
        s.versions.append(v)
        return v

    # ----- views used by the resolvers

    def version(self, ident):
        for s in self.series.values():
            for v in s.versions:
                if v.id == ident.upper():
                    return v
        return None

    def all_versions(self):
        return [v for s in self.series.values() for v in s.versions]

    def effective_perms(self, s):
        """FileNet's Permissions on an object: its own entries plus those inherited from its security parent (the
        first folder it is filed in), as SOURCE_PARENT."""
        perms = list(s.direct)
        if s.folders:
            chain, path = [], s.folders[0]
            while path and path != "/":
                chain.append(self.folders[path])
                path = path.rsplit("/", 1)[0] or "/"
            for f in chain:
                for p in f.perms:
                    if p["inheritableDepth"] in ("ALL_CHILDREN_NOT_OBJECT", "OBJECT_ALL_CHILDREN"):
                        perms.append({**p, "permissionSource": "SOURCE_PARENT", "inheritableDepth": "OBJECT_ONLY"})
        return perms

    def in_folder(self, s, path, sub):
        path = path.rstrip("/") or "/"
        for f in s.folders:
            if f == path or (sub and (path == "/" or f.startswith(path + "/"))):
                return True
        return False

    def touch(self, v):
        v.modified = now() if now() > v.modified else v.modified + timedelta(seconds=1)
        v.modifier = "ceadmin"
        v.usn += 1

    def delete_series(self, s):
        del self.series[s.id]
        if self.audit:
            for v in s.versions:
                self.events.append({"Id": guid("event", v.id, len(self.events)), "SourceObjectId": v.id,
                                    "SourceClassId": guid("class", s.cls), "DateCreated": now(), "Creator": "ceadmin"})


TOPICS = {
    "Contract": "Master supply agreement {n} between the company and {cp}. Payment terms net 30. Governing law: England.",
    "Invoice": "Invoice {n} issued to {cp} for freight services. Total due within 30 days.",
    "Document": "Internal policy note {n} on records retention and the handling of customer correspondence.",
}

STORE = Store()


# ----------------------------------------------------------------------------------------------- CE SQL subset

class SqlError(Exception):
    pass


TOKEN_RE = re.compile(r"""\s*(?:(?P<guid>\{[0-9A-Fa-f-]{36}\})|(?P<dt>\d{8}T\d{6}(?:\.\d+)?Z?)|(?P<str>'(?:[^']|'')*')|
    (?P<num>-?\d+(?:\.\d+)?)|(?P<prop>(?:\w+\.)?\[[^\]]+\])|(?P<op><>|<=|>=|=|<|>|\(|\)|,|\*)|(?P<word>[\w.#:-]+))""",
                      re.X)


def tokenize(text):
    pos, out = 0, []
    text = text.strip()
    while pos < len(text):
        m = TOKEN_RE.match(text, pos)
        if not m or m.end() == pos:
            raise SqlError(f"Syntax error near '{text[pos:pos + 20]}'")
        kind = m.lastgroup
        out.append((kind, m.group(kind)))
        pos = m.end()
        while pos < len(text) and text[pos].isspace():
            pos += 1
    return out


def literal(kind, value):
    if kind == "str":
        return value[1:-1].replace("''", "'")
    if kind == "num":
        return float(value) if "." in value else int(value)
    if kind == "guid":
        return value.upper()
    if kind == "dt":
        return datetime.strptime(value[:15], "%Y%m%dT%H%M%S").replace(tzinfo=timezone.utc)
    if kind == "word" and value.lower() in ("true", "false"):
        return value.lower() == "true"
    raise SqlError(f"Unexpected value {value}")


class Where:
    """A recursive-descent parser of the CE SQL WHERE subset: AND / OR / NOT / parentheses, comparisons and LIKE on
    [Property] (optionally alias.[Property]), IS [NOT] NULL, This INFOLDER / INSUBFOLDER 'path', ISCLASS(alias, Class).
    It compiles to a predicate over a row accessor."""

    def __init__(self, text, known):
        self.toks, self.i, self.known = tokenize(text) if text and text.strip() else [], 0, known
        self.pred = self.expr() if self.toks else (lambda row: True)
        if self.i != len(self.toks):
            raise SqlError(f"Syntax error near '{self.toks[self.i][1]}'")

    def peek(self, word=None):
        if self.i >= len(self.toks):
            return None
        k, v = self.toks[self.i]
        return v if word is None or v.upper() == word else None

    def take(self, word=None):
        if word and (self.peek() or "").upper() != word:
            raise SqlError(f"Expected {word} near '{self.peek()}'")
        self.i += 1
        return self.toks[self.i - 1]

    def expr(self):
        left = self.term()
        while self.peek("OR"):
            self.take()
            right = self.term()
            left = (lambda a, b: lambda r: a(r) or b(r))(left, right)
        return left

    def term(self):
        left = self.factor()
        while self.peek("AND"):
            self.take()
            right = self.factor()
            left = (lambda a, b: lambda r: a(r) and b(r))(left, right)
        return left

    def factor(self):
        if self.peek("NOT"):
            self.take()
            inner = self.factor()
            return lambda r: not inner(r)
        if self.peek("("):
            self.take()
            inner = self.expr()
            self.take(")")
            return inner
        if self.peek("ISCLASS"):
            self.take()
            self.take("(")
            self.take()
            self.take(",")
            cls = self.take()[1]
            self.take(")")
            return lambda r: r("__class") == cls
        kind, value = self.take()
        if kind == "word" and value.upper() == "THIS" or value.upper().endswith(".THIS"):
            op = self.take()[1].upper()
            if op not in ("INFOLDER", "INSUBFOLDER"):
                raise SqlError(f"Unsupported operator {op} on This")
            k, path = self.take()
            target = literal(k, path)
            return lambda r: r("__infolder")(target, op == "INSUBFOLDER")
        if kind != "prop":
            raise SqlError(f"Expected a property near '{value}'")
        name = value[value.index("[") + 1:-1]
        if name not in self.known:
            raise SqlError(f"E_BAD_VALUE: The property '{name}' is not a property of the queried class")
        if self.peek("IS"):
            self.take()
            neg = bool(self.peek("NOT")) and self.take()
            self.take("NULL")
            return lambda r: (r(name) is None) != bool(neg)
        op = self.take()[1].upper()
        k, raw = self.take()
        val = literal(k, raw)
        if op == "LIKE":
            rx = re.compile("^" + re.escape(str(val)).replace("%", ".*").replace("_", ".") + "$", re.I)
            return lambda r: r(name) is not None and bool(rx.match(str(r(name))))
        ops = {"=": lambda a, b: a == b, "<>": lambda a, b: a != b, "<": lambda a, b: a < b, ">": lambda a, b: a > b,
               "<=": lambda a, b: a <= b, ">=": lambda a, b: a >= b}
        if op not in ops:
            raise SqlError(f"Unsupported operator {op}")

        def compare(r):
            a = r(name)
            if a is None:
                return False
            if isinstance(a, list):
                return any(ops[op](x, val) for x in a)
            if isinstance(a, str) and isinstance(val, str) and a.startswith("{"):
                return ops[op](a.upper(), val.upper())
            try:
                return ops[op](a, val)
            except TypeError:
                raise SqlError(f"E_BAD_VALUE: cannot compare '{name}' with {raw}")
        return compare


def parse_from(text, default="Document"):
    """'Document', 'Document d', 'Document d WITH INCLUDESUBCLASSES' ... -> (class, include subclasses)."""
    words = (text or default).split()
    cls, include = words[0], True
    rest = " ".join(words[1:]).upper()
    if "EXCLUDESUBCLASSES" in rest:
        include = False
    return cls, include


def order_key(order_by):
    """'DateLastModified DESC, Id' -> a list of (property, descending)."""
    keys = []
    for part in (order_by or "").split(","):
        bits = part.strip().split()
        if bits:
            keys.append((bits[0].strip("[]").split(".")[-1].strip("[]"), len(bits) > 1 and bits[1].upper() == "DESC"))
    return keys


def sort_rows(rows, keys, accessor):
    for name, desc in reversed(keys):
        rows.sort(key=lambda r: (accessor(r, name) is None, accessor(r, name) if accessor(r, name) is not None else 0),
                  reverse=desc)
    return rows


def class_matches(cls, wanted, include):
    if cls == wanted:
        return True
    if not include:
        return False
    parent = CLASSES.get(cls, (None,))[0]
    while parent:
        if parent == wanted:
            return True
        parent = CLASSES[parent][0]
    return False


def doc_value(v, name):
    s = v.series
    sysvals = {"Id": v.id, "DocumentTitle": s.title, "Creator": v.creator, "DateCreated": v.created,
               "LastModifier": v.modifier, "DateLastModified": v.modified, "MajorVersionNumber": v.major,
               "MinorVersionNumber": v.minor, "IsCurrentVersion": v.current, "VersionStatus": VERSION_STATUS[v.status],
               "ContentSize": float(sum(len(e.data()) for e in v.elements if not e.location)) if v.elements else None,
               "MimeType": v.elements[0].mime if v.elements else None, "VersionSeries": s.id,
               "Name": s.title}
    if name in sysvals:
        return sysvals[name]
    if name in v.props:
        val = v.props[name]
        if CLASSES[s.cls][1].get(name, ("",))[0] == "DATE" and isinstance(val, str):
            return datetime.fromisoformat(val.replace("Z", "+00:00"))
        return val
    return None


def doc_known(cls):
    known = set(SYSTEM_PROPS) | {"Name"}
    c = cls
    while c:
        known |= set(CLASSES[c][1])
        c = CLASSES[c][0]
    if cls == "Document":  # a subclass property is not queryable from the base class in CE
        pass
    return known


def search_documents(cls, include, where, order_by):
    if cls not in CLASSES:
        raise SqlError(f"E_BAD_CLASSID: The class '{cls}' was not found")
    pred = Where(where, doc_known(cls)).pred
    rows = []
    with STORE.lock:
        for v in STORE.all_versions():
            if not class_matches(v.series.cls, cls, include):
                continue
            row = lambda name, v=v: (v.series.cls if name == "__class" else
                                     (lambda path, sub, v=v: STORE.in_folder(v.series, path, sub))
                                     if name == "__infolder" else doc_value(v, name))
            if pred(row):
                rows.append(v)
    keys = order_key(order_by) or [("Id", False)]
    return sort_rows(rows, keys, doc_value)


FOLDER_PROPS = {"Id": "GUID", "FolderName": "STRING", "PathName": "STRING", "DateCreated": "DATE",
                "DateLastModified": "DATE"}


def folder_value(f, name):
    return {"Id": f.id, "FolderName": f.name, "PathName": f.path, "DateCreated": f.created,
            "DateLastModified": f.modified}.get(name)


def search_folders(where, order_by):
    pred = Where(where, set(FOLDER_PROPS)).pred
    with STORE.lock:
        rows = [f for f in STORE.folders.values() if f.path != "/" and pred(
            lambda name, f=f: "Folder" if name == "__class" else
            (lambda path, sub, f=f: (f.parent_path == (path.rstrip("/") or "/")) or
             (sub and f.path.startswith(path.rstrip("/") + "/"))) if name == "__infolder" else folder_value(f, name))]
    return sort_rows(rows, order_key(order_by) or [("PathName", False)], folder_value)


EVENT_PROPS = {"Id": "GUID", "SourceObjectId": "GUID", "SourceClassId": "GUID", "DateCreated": "DATE",
               "Creator": "STRING"}

SELECT_RE = re.compile(r"^\s*SELECT\s+(?P<sel>.+?)\s+FROM\s+(?P<from>.+?)(?:\s+WHERE\s+(?P<where>.+?))?"
                       r"(?:\s+ORDER\s+BY\s+(?P<order>.+?))?\s*$", re.I | re.S)


def run_sql(sql):
    """repositoryRows: SELECT [A], d.[B] FROM Class [alias] [WITH ...] [WHERE ...] [ORDER BY ...] over Document,
    Folder or DeletionEvent. Returns a list of property lists."""
    m = SELECT_RE.match(sql)
    if not m:
        raise SqlError("Syntax error: expected SELECT ... FROM ...")
    names = [p.strip().split(".")[-1].strip("[]") for p in m.group("sel").split(",")]
    cls, include = parse_from(m.group("from"))
    if cls == "DeletionEvent":
        for n in names:
            if n != "*" and n not in EVENT_PROPS:
                raise SqlError(f"E_BAD_VALUE: The property '{n}' is not a property of the queried class")
        pred = Where(m.group("where"), set(EVENT_PROPS)).pred
        with STORE.lock:
            rows = [e for e in STORE.events if pred(lambda name, e=e: e.get(name))]
        rows = sort_rows(rows, order_key(m.group("order")) or [("DateCreated", False)], lambda e, n: e.get(n))
        names = list(EVENT_PROPS) if names == ["*"] else names
        return [[prop_dict(n, EVENT_PROPS[n], "SINGLE", n, e.get(n)) for n in names] for e in rows]
    if cls == "Folder":
        rows = search_folders(m.group("where"), m.group("order"))
        names = list(FOLDER_PROPS) if names == ["*"] else names
        for n in names:
            if n not in FOLDER_PROPS:
                raise SqlError(f"E_BAD_VALUE: The property '{n}' is not a property of the queried class")
        return [[prop_dict(n, FOLDER_PROPS[n], "SINGLE", n, folder_value(f, n)) for n in names] for f in rows]
    rows = search_documents(cls, include, m.group("where"), m.group("order"))
    known = doc_known(cls)
    names = sorted(SYSTEM_PROPS) if names == ["*"] else names
    for n in names:
        if n not in known:
            raise SqlError(f"E_BAD_VALUE: The property '{n}' is not a property of the queried class")
    return [[doc_prop(v, n) for n in names] for v in rows]


# ----------------------------------------------------------------------------------------------- GraphQL objects

def prop_dict(name, type_id, cardinality, label, value):
    typename = {("STRING", "SINGLE"): "StringProperty", ("STRING", "LIST"): "StringListProperty",
                ("LONG", "SINGLE"): "IntegerProperty", ("DOUBLE", "SINGLE"): "FloatProperty",
                ("BOOLEAN", "SINGLE"): "BooleanProperty", ("DATE", "SINGLE"): "DateTimeProperty",
                ("GUID", "SINGLE"): "IdProperty"}[(type_id, cardinality)]
    out_value = iso(value) if isinstance(value, datetime) else value
    d = {"__typename": typename, "id": name, "alias": name, "label": label, "type": type_id,
         "cardinality": cardinality, "value": out_value}
    d.update({"StringProperty": {"stringValue": out_value}, "StringListProperty": {"stringListValue": out_value},
              "IntegerProperty": {"intValue": out_value}, "FloatProperty": {"floatValue": out_value},
              "BooleanProperty": {"booleanValue": out_value},
              "DateTimeProperty": {"dateTimeValue": out_value, "isDateOnly": False},
              "IdProperty": {"idValue": out_value}}[typename])
    return d


def doc_prop(v, name):
    if name in SYSTEM_PROPS:
        t, label = SYSTEM_PROPS[name]
        return prop_dict(name, t, "SINGLE", label, doc_value(v, name))
    t, card, label = CLASSES[v.series.cls][1][name]
    return prop_dict(name, t, card, label, doc_value(v, name))


def markings_prop(s):
    """ActiveMarkings, a dependent-object list: one ActiveMarking per marking applied to the document."""
    markings = [{"__typename": "DependentObjectType", "className": "ActiveMarking",
                 "properties": lambda info, includes=None, m=m: [prop_dict("PropertyDisplayName", "STRING", "SINGLE",
                                                                            "Property Display Name", m)]}
                for m in s.markings]
    return {"__typename": "ObjectListProperty", "id": "ActiveMarkings", "alias": "ActiveMarkings",
            "label": "Active Markings", "type": "OBJECT", "cardinality": "LIST", "value": None,
            "objectListValue": markings}


def includes_filter(names, includes):
    return [n for n in names if includes is None or n in includes]


def page(items, size, kind, factory):
    """A page of a result set, with a continuation token held server-side for the rest."""
    size = max(1, min(size or DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE))
    first, rest = items[:size], items[size:]
    token = None
    if rest:
        token = base64.urlsafe_b64encode(secrets.token_bytes(24)).decode().rstrip("=")
        with STORE.lock:
            STORE.tokens[token] = {"kind": kind, "rest": rest, "size": size, "factory": factory,
                                   "expires": time.time() + 900}
    return {"pageInfo": {"token": token, "totalCount": None}, kind: [factory(x) for x in first]}


def more(token, kind):
    with STORE.lock:
        state = STORE.tokens.pop(token, None)
    if not state or state["expires"] < time.time() or state["kind"] != kind:
        raise GraphQLError("FNRCE0051E: E_BAD_VALUE: The continuation token is not valid or has expired.")
    return page(state["rest"], state["size"], kind, state["factory"])


def perm_dict(p):
    return {"__typename": "AccessPermissionType", "className": "AccessPermission", **p,
            "properties": lambda info, includes=None: []}


def element_dict(v, e):
    common = {"className": "ContentReference" if e.location else "ContentTransfer", "contentType": e.mime,
              "elementSequenceNumber": e.seq, "properties": lambda info, includes=None: []}
    if e.location:
        return {"__typename": "ContentReferenceType", **common, "contentLocation": e.location}
    data = e.data()
    url = (f"/content?repositoryIdentifier={quote(OS_NAME)}&documentId={quote(v.id)}"
           f"&elementSequenceNumber={e.seq}")
    return {"__typename": "ContentTransferType", **common, "contentSize": float(len(data)),
            "retrievalName": f"{v.series.title}.{e.ext}", "downloadUrl": url}


def folder_dict(f):
    def contained(info):
        with STORE.lock:
            docs = sorted((v for s in STORE.series.values() if f.path in s.folders for v in s.versions if v.current),
                          key=lambda v: v.id)
        return page(docs, ENUM_PAGE_SIZE, "documents", doc_dict)

    def subs(info):
        with STORE.lock:
            kids = sorted((x for x in STORE.folders.values() if x.parent_path == f.path and x.path != "/"),
                          key=lambda x: x.path)
        return page(kids, ENUM_PAGE_SIZE, "folders", folder_dict)

    return {"__typename": "FolderType", "className": "Folder", "id": f.id, "name": f.name, "pathName": f.path,
            "dateCreated": iso(f.created), "dateLastModified": iso(f.modified), "inheritParentPermissions": True,
            "permissions": [perm_dict(p) for p in f.perms], "containedDocuments": contained, "subFolders": subs,
            "properties": lambda info, includes=None: [prop_dict(n, t, "SINGLE", n, folder_value(f, n))
                                                       for n, t in FOLDER_PROPS.items()
                                                       if includes is None or n in includes]}


def doc_dict(v):
    s = v.series

    def props(info, includes=None):
        names = list(SYSTEM_PROPS)
        c = s.cls
        while c:
            names += list(CLASSES[c][1])
            c = CLASSES[c][0]
        out = [doc_prop(v, n) for n in includes_filter(names, includes)]
        if includes is None or "ActiveMarkings" in includes:
            out.append(markings_prop(s))
        return out

    def filed(info):
        with STORE.lock:
            fs = [STORE.folders[p] for p in s.folders]
        return page(fs, ENUM_PAGE_SIZE, "folders", folder_dict)

    with STORE.lock:
        perms = STORE.effective_perms(s)
    size = doc_value(v, "ContentSize")
    return {"__typename": "DocumentType", "className": s.cls, "id": v.id, "name": s.title,
            "mimeType": v.elements[0].mime if v.elements else None, "contentSize": size,
            "creator": v.creator, "dateCreated": iso(v.created), "lastModifier": v.modifier,
            "dateLastModified": iso(v.modified), "majorVersionNumber": v.major, "minorVersionNumber": v.minor,
            "isCurrentVersion": v.current, "versionStatus": v.status, "updateSequenceNumber": v.usn,
            "accessAllowed": FULL_CONTROL, "versionSeries": {"__typename": "VersionSeriesType", "className": "VersionSeries",
                                                            "id": s.id, "properties": lambda info, includes=None: []},
            "contentElements": [element_dict(v, e) for e in v.elements],
            "permissions": [perm_dict(p) for p in perms], "foldersFiledIn": filed, "properties": props}


def check_os(ident):
    if ident not in (OS_NAME, OS_ID):
        raise GraphQLError(f"FNRCE0051E: E_OBJECT_NOT_FOUND: The object store '{ident}' was not found.")


def sql_guard(fn):
    def wrapper(*a, **kw):
        try:
            return fn(*a, **kw)
        except SqlError as e:
            msg = str(e)
            raise GraphQLError("FNRCE0013E: " + (msg if msg.startswith("E_") else "E_BAD_VALUE: " + msg))
    return wrapper


def r_api_info(_, info, repositoryIdentifier=None):
    if repositoryIdentifier:
        check_os(repositoryIdentifier)
    return {"buildDate": "2026-09-29", "buildNumber": "mock", "implementationTitle": "Content Services GraphQL (mock)",
            "implementationVersion": "5.6.0-mock", "productVersion": "5.6.0"}


def r_object_store(_, info, identifier):
    check_os(identifier)
    return {"className": "ObjectStore", "id": OS_ID, "displayName": OS_NAME, "symbolicName": OS_NAME,
            "properties": lambda info, includes=None: []}


def r_document(_, info, identifier, repositoryIdentifier):
    check_os(repositoryIdentifier)
    with STORE.lock:
        v = STORE.version(identifier) if identifier.startswith("{") else None
        if v is None and identifier.startswith("/"):
            path, name = identifier.rsplit("/", 1)
            v = next((s.versions[-1] for s in STORE.series.values() if s.title == name and (path or "/") in s.folders),
                     None)
    if v is None:
        raise GraphQLError(f"FNRCE0051E: E_OBJECT_NOT_FOUND: The requested item was not found. Object identity: "
                           f"{identifier}")
    return doc_dict(v)


@sql_guard
def r_documents(_, info, repositoryIdentifier, **kw):
    check_os(repositoryIdentifier)
    cls, include = parse_from(kw.get("from"))
    return page(search_documents(cls, include, kw.get("where"), kw.get("orderBy")), kw.get("pageSize"), "documents",
                doc_dict)


def r_folder(_, info, identifier, repositoryIdentifier):
    check_os(repositoryIdentifier)
    with STORE.lock:
        f = STORE.folders.get(identifier.rstrip("/") or "/") or next(
            (x for x in STORE.folders.values() if x.id == identifier.upper()), None)
    if f is None:
        raise GraphQLError(f"FNRCE0051E: E_OBJECT_NOT_FOUND: The requested item was not found. Object identity: "
                           f"{identifier}")
    return folder_dict(f)


@sql_guard
def r_folders(_, info, repositoryIdentifier, **kw):
    check_os(repositoryIdentifier)
    return page(search_folders(kw.get("where"), kw.get("orderBy")), kw.get("pageSize"), "folders", folder_dict)


@sql_guard
def r_rows(_, info, repositoryIdentifier, sql, pageSize=None):
    check_os(repositoryIdentifier)
    return page(run_sql(sql), pageSize, "repositoryRows", lambda props: {"properties": props})


SCHEMA = build_schema(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "schema.graphql"),
                           encoding="utf-8").read())
for name, resolver in {"_apiInfo": r_api_info, "objectStore": r_object_store, "document": r_document,
                       "documents": r_documents, "moreDocuments": lambda _, i, token: more(token, "documents"),
                       "folder": r_folder, "folders": r_folders,
                       "moreFolders": lambda _, i, token: more(token, "folders"),
                       "repositoryRows": r_rows,
                       "moreRepositoryRows": lambda _, i, token: more(token, "repositoryRows")}.items():
    SCHEMA.query_type.fields[name].resolve = resolver
for t in SCHEMA.type_map.values():
    if hasattr(t, "resolve_type") and t.__class__.__name__ == "GraphQLInterfaceType":
        t.resolve_type = lambda value, info, abstract: value["__typename"]


# ----------------------------------------------------------------------------------------------- file formats

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


_FILE_CACHE = {}


def file_bytes(ext, text):
    key = (ext, text)
    if key not in _FILE_CACHE:
        _FILE_CACHE[key] = pdf_bytes(text) if ext == "pdf" else docx_bytes(text) if ext == "docx" else text.encode()
    return _FILE_CACHE[key]


# ----------------------------------------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "CSGQLMock/1.0"

    def log_message(self, fmt, *args):
        if not QUIET:
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # ----- plumbing

    def send(self, status, body=b"", content_type="application/json", headers=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def count(self, op):
        with STORE.lock:
            STORE.stats["requests"][op] = STORE.stats["requests"].get(op, 0) + 1

    def authenticated(self):
        auth = self.headers.get("Authorization") or ""
        ok = False
        if auth.startswith("Basic "):
            try:
                user, _, pwd = base64.b64decode(auth[6:]).decode().partition(":")
                ok = USERS.get(user) == pwd and pwd != ""
            except Exception:
                ok = False
        elif auth.startswith("Bearer "):
            with STORE.lock:
                exp = STORE.access_tokens.get(auth[7:])
            ok = exp is not None and exp > time.time()
        if not ok:
            with STORE.lock:
                STORE.stats["authFailures"] += 1
            self.send(401, {"error": "Unauthorized"}, headers={"WWW-Authenticate": 'Basic realm="FileNet P8"'})
        return ok

    def xsrf_ok(self):
        header = self.headers.get(XSRF)
        cookies = {}
        for part in (self.headers.get("Cookie") or "").split(";"):
            k, _, v = part.strip().partition("=")
            if k:
                cookies[k] = v
        if header and cookies.get(XSRF) == header:
            return True
        with STORE.lock:
            STORE.stats["xsrfFailures"] += 1
        self.send(403, {"error": "Forbidden", "message": "The ECM-CS-XSRF-Token header is missing or does not match "
                                                         "its cookie."})
        return False

    def fault(self, op):
        """An injected fault for this operation, if any: (status, retry-after) or ('graphql', message)."""
        with STORE.lock:
            for f in STORE.faults:
                if f["op"] in (op, "*") and f["remaining"] > 0:
                    f["remaining"] -= 1
                    return f
        return None

    # ----- routes

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == BASE + "/ping":
            self.count("ping")
            return self.send(200, {"message": "Content Services GraphQL API (mock) is running"},
                             headers={XSRF: str(uuid.uuid4())})
        if url.path == BASE + "/content":
            return self.download(parse_qs(url.query))
        if url.path.startswith("/mock/"):
            return self.admin_get(unquote(url.path))
        self.send(404, {"error": "Not Found"})

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == BASE + "/graphql":
            return self.graphql()
        if url.path == "/oauth/token":
            return self.oauth()
        if url.path.startswith("/mock/"):
            return self.admin_post(unquote(url.path))
        self.send(404, {"error": "Not Found"})

    def oauth(self):
        form = parse_qs(self.body().decode())
        cid, secret = (form.get("client_id") or [""])[0], (form.get("client_secret") or [""])[0]
        auth = self.headers.get("Authorization") or ""
        if auth.startswith("Basic "):
            cid, _, secret = base64.b64decode(auth[6:]).decode().partition(":")
        if (form.get("grant_type") or [""])[0] != "client_credentials" or OAUTH_CLIENTS.get(cid) != secret or not secret:
            return self.send(401, {"error": "invalid_client"})
        token = secrets.token_urlsafe(32)
        with STORE.lock:
            STORE.access_tokens[token] = time.time() + 3600
        self.count("oauth")
        self.send(200, {"access_token": token, "token_type": "Bearer", "expires_in": 3600})

    def graphql(self):
        raw = self.body()
        if not self.authenticated() or not self.xsrf_ok():
            return
        ctype = (self.headers.get("Content-Type") or "").split(";")[0].strip()
        try:
            if ctype == "application/graphql":
                req = {"query": raw.decode("utf-8")}
            else:
                req = json.loads(raw or b"{}")
        except Exception:
            return self.send(400, {"errors": [{"message": "The request body is not valid JSON."}]})
        query = req.get("query") or ""
        op = (re.search(r"\{\s*(?:\w+\s*:\s*)?(_?\w+)", query) or [None, "unknown"])[1]
        self.count(op)
        f = self.fault(op)
        if f:
            if f.get("graphqlError"):
                return self.send(200, {"data": None, "errors": [{"message": f["graphqlError"]}]})
            return self.send(f["status"], {"error": "injected fault"},
                             headers={"Retry-After": str(f["retryAfter"])} if f.get("retryAfter") is not None else {})
        result = graphql_sync(SCHEMA, query, variable_values=req.get("variables"),
                              operation_name=req.get("operationName"))
        payload = {"data": result.data}
        if result.errors:
            payload["errors"] = [e.formatted for e in result.errors]
        self.send(200, payload)

    def download(self, q):
        if not self.authenticated() or not self.xsrf_ok():
            return
        self.count("download")
        f = self.fault("download")
        if f and not f.get("graphqlError"):
            return self.send(f["status"], {"error": "injected fault"},
                             headers={"Retry-After": str(f["retryAfter"])} if f.get("retryAfter") is not None else {})
        if (q.get("repositoryIdentifier") or [""])[0] not in (OS_NAME, OS_ID):
            return self.send(404, {"error": "object store not found"})
        with STORE.lock:
            v = STORE.version((q.get("documentId") or [""])[0])
        seq = int((q.get("elementSequenceNumber") or ["0"])[0])
        e = next((x for x in (v.elements if v else []) if x.seq == seq and not x.location), None)
        if e is None:
            return self.send(404, {"error": "content element not found"})
        data = e.data()
        with STORE.lock:
            STORE.stats["downloads"] += 1
        self.send(200, data, content_type=e.mime,
                  headers={"Content-Disposition": f'attachment; filename="{v.series.title}.{e.ext}"'})

    # ----- admin (mock-only)

    def admin_get(self, path):
        with STORE.lock:
            if path == "/mock/stats":
                current = [s.versions[-1] for s in STORE.series.values()]
                return self.send(200, {
                    "objectStore": OS_NAME, "objectStoreId": OS_ID, "series": len(STORE.series),
                    "versions": len(STORE.all_versions()), "events": len(STORE.events), "audit": STORE.audit,
                    "withContent": sum(1 for v in current if any(not e.location for e in v.elements)),
                    "elements": sum(len([e for e in v.elements if not e.location]) for v in current),
                    "markedElements": sum(len([e for e in v.elements if not e.location]) for v in current
                                          if v.series.markings),
                    "requests": STORE.stats["requests"], "downloads": STORE.stats["downloads"],
                    "authFailures": STORE.stats["authFailures"], "xsrfFailures": STORE.stats["xsrfFailures"],
                    "openTokens": len(STORE.tokens)})
            if path == "/mock/documents":
                return self.send(200, [self.describe(s) for s in sorted(STORE.series.values(), key=lambda s: s.n)])
            m = re.match(r"^/mock/documents/(\{[^}]+\})$", path)
            if m and m.group(1).upper() in STORE.series:
                return self.send(200, self.describe(STORE.series[m.group(1).upper()], text=True))
        self.send(404, {"error": "Not Found"})

    @staticmethod
    def describe(s, text=False):
        cur = s.versions[-1]
        d = {"seriesId": s.id, "n": s.n, "class": s.cls, "title": s.title, "pattern": s.pattern, "code": s.code,
             "folders": s.folders, "currentVersionId": cur.id,
             "releasedVersionId": next((v.id for v in reversed(s.versions) if v.status == "RELEASED"), None),
             "versions": [{"id": v.id, "major": v.major, "minor": v.minor, "status": v.status} for v in s.versions],
             "elements": [{"seq": e.seq, "type": "reference" if e.location else e.ext} for e in cur.elements],
             "dateLastModified": iso(cur.modified), "permissions": STORE.effective_perms(s),
             "markings": s.markings}
        if text:
            d["text"] = "\n".join(e.text for e in cur.elements if e.text)
        return d

    def admin_post(self, path):
        try:
            body = json.loads(self.body() or b"{}")
        except Exception:
            return self.send(400, {"error": "invalid JSON"})
        with STORE.lock:
            if path == "/mock/reset":
                STORE.reset()
                return self.send(200, {"reset": True})
            if path == "/mock/faults":
                STORE.faults = [] if body.get("clear") else STORE.faults + [{
                    "op": body.get("op", "*"), "status": int(body.get("status", 503)), "remaining": int(body.get("count", 1)),
                    "retryAfter": body.get("retryAfter"), "graphqlError": body.get("graphqlError")}]
                return self.send(200, {"faults": len(STORE.faults)})
            if path == "/mock/audit":
                STORE.audit = bool(body.get("enabled", True))
                return self.send(200, {"audit": STORE.audit})
            if path == "/mock/expire-tokens":
                n = len(STORE.tokens)
                STORE.tokens.clear()
                STORE.access_tokens.clear()
                return self.send(200, {"expired": n})
            if path == "/mock/documents":  # create a document
                n = max([s.n for s in STORE.series.values()] + [DOCUMENT_COUNT]) + 1
                cls = body.get("class", "Document")
                s = Series(n, cls, body.get("title", f"New document {n:04d}"), int(body.get("pattern", 1)))
                s.direct = STORE._pattern_perms(s.pattern)
                s.markings = list(body.get("markings", ["Confidential"] if s.pattern == 6 else []))
                s.folders = body.get("folders", ["/Policies"])
                v = STORE._add_version(s, 1, 0, now(), "ceadmin", text_extra=body.get("text", ""))
                STORE.series[s.id] = s
                return self.send(200, self.describe(s))
            m = re.match(r"^/mock/documents/(\{[^}]+\})/(checkin|delete|permissions|markings|file|unfile)$", path)
            if not m or m.group(1).upper() not in STORE.series:
                return self.send(404, {"error": "Not Found"})
            s, action = STORE.series[m.group(1).upper()], m.group(2)
            cur = s.versions[-1]
            if action == "checkin":  # a new version (major by default)
                major = body.get("major", True)
                nv = STORE._add_version(s, cur.major + 1 if major else cur.major, 0 if major else cur.minor + 1,
                                        max(now(), cur.modified + timedelta(seconds=1)), "ceadmin",
                                        status="RELEASED" if major else "IN_PROCESS",
                                        text_extra=body.get("text", ""))
                nv.modifier = "ceadmin"
            elif action == "delete":
                if body.get("currentVersionOnly") and len(s.versions) > 1:
                    v = s.versions.pop()
                    if STORE.audit:
                        STORE.events.append({"Id": guid("event", v.id, len(STORE.events)), "SourceObjectId": v.id,
                                             "SourceClassId": guid("class", s.cls), "DateCreated": now(),
                                             "Creator": "ceadmin"})
                    STORE.touch(s.versions[-1])
                else:
                    STORE.delete_series(s)
                    return self.send(200, {"deleted": s.id})
            elif action == "permissions":  # replace the direct entries; a save updates DateLastModified
                s.direct = [ace(p["granteeName"], p.get("granteeType", "USER"), p.get("accessType", "ALLOW"),
                                int(p.get("accessMask", VIEWER)), p.get("permissionSource", "SOURCE_DIRECT"))
                            for p in body.get("permissions", [])]
                STORE.touch(cur)
            elif action == "markings":  # replace the applied markings; a save updates DateLastModified
                s.markings = [str(m) for m in body.get("markings", [])]
                STORE.touch(cur)
            elif action == "file":
                s.folders = s.folders + [body["folder"]] if body["folder"] not in s.folders else s.folders
                STORE.touch(cur)
            elif action == "unfile":
                s.folders = [f for f in s.folders if f != body["folder"]]
                STORE.touch(cur)
            return self.send(200, self.describe(s))


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    print(f"CSGQL mock: object store {OS_NAME} {OS_ID}, {len(STORE.series)} version series, "
          f"{len(STORE.all_versions())} versions, audit={'on' if STORE.audit else 'off'}, on :{PORT}{BASE}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
