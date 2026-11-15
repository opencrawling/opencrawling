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
DCTM REST mock: an emulator of OpenText Documentum REST Services (the read side) for testing the OpenCrawling Documentum
connector without a Documentum installation. Python standard library only.

Behind the HTTP layer is a small repository: typed object tables (dm_document and two custom subtypes with single and
repeating attributes, dm_folder / dm_cabinet, dm_acl, dm_user, dm_group, dm_audittrail), version trees with implicit
and symbolic labels, primary content and renditions (real PDF / DOCX / TXT), and permission sets of every permit type.
A DQL engine runs SELECT statements over those tables with Documentum's rules where the connector depends on them:

  - FROM <type> searches the type and its subtypes, CURRENT versions only unless the type is followed by (ALL);
  - a repeating attribute in WHERE needs ANY; selected repeating attributes come back as arrays (r_object_id must be
    selected, as the server only then folds them into one row);
  - DATE('...', 'fmt') literals are read in the server's time zone (DCTM_TZ_OFFSET) while values are kept in UTC, and
    times have one-second precision;
  - an unknown type or attribute, an unsupported construct or a non-SELECT statement is an error (HTTP 400);
  - without ORDER BY the row order is unspecified: rows come back in a scrambled order;
  - a user who is not a superuser only sees the objects they may BROWSE, evaluated with Documentum's permit rules
    (most permissive grant, access restrictions, required groups, required group sets, dm_owner/dm_group/dm_world).

That last evaluator is also the ground truth the tests compare the connector's security mapping with (GET /mock/access).

Protocol, as OpenText's REST clients use it (shapes copied from a REST 16.7 server's published responses):
  GET /dctm-rest/services                            home document (application/home+json)
  GET /dctm-rest/repositories                        repositories feed
  GET /dctm-rest/repositories/{repo}                 repository; with ?dql= a DQL query result feed (page, items-per-page)
  GET /dctm-rest/repositories/{repo}/objects/{id}    an object with all its attributes
  GET .../objects/{id}/contents                      the renditions feed
  GET .../objects/{id}/contents/content              content metadata (format=, page=, media-url-policy=) with enclosure links
  GET .../objects/{id}/content-media                 the content bytes (the LOCAL enclosure)
  GET /dctm-rest/repositories/{repo}/acls/{id}       a permission set
  GET /dctm-rest/repositories/{repo}/currentuser     the authenticated user
  GET .../documents|folders|cabinets/{id}, .../objects/{id}/versions[/current], /permission-set, /permissions,
      /parent-links, .../folders/{id}/folders|documents|objects, and the cabinets, users, groups, acls, types and
      formats collections: every link the mock advertises is served (README.md has the list)
  POST /oauth/token                                  client-credentials tokens for bearer authentication (mock-only)
Everything under /repositories/{repo} needs Basic or Bearer authentication. Errors are {status, code, message, details,
id}. conformance/check_spec.py checks all of it against OpenText's published OpenAPI document. Mock-only admin endpoints under /mock change the repository and inject faults; README.md lists them and every
assumption made where OpenText's material is silent.
"""

import base64
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
from urllib.parse import parse_qs, quote, unquote, urlencode, urlparse

PORT = int(os.environ.get("DCTM_PORT", "8080"))
REPO = os.environ.get("DCTM_REPOSITORY", "corp")
DOCBASE_ID = os.environ.get("DCTM_DOCBASE_ID", "0277b6")
USERS = dict(u.split(":", 1) for u in os.environ.get("DCTM_USERS", "dctm_crawler:secret,jdoe:secret").split(",")
             if ":" in u)
OAUTH_CLIENTS = dict(c.split(":", 1) for c in os.environ.get("DCTM_OAUTH_CLIENTS", "opencrawling:client-secret").split(",")
                     if ":" in c)
OAUTH_USER = os.environ.get("DCTM_OAUTH_USER", "dctm_crawler")
DOCUMENT_COUNT = int(os.environ.get("DCTM_DOCUMENT_COUNT", "60"))
SEED = int(os.environ.get("DCTM_SEED", "124"))
DEFAULT_PAGE_SIZE = int(os.environ.get("DCTM_DEFAULT_PAGE_SIZE", "100"))
MAX_PAGE_SIZE = int(os.environ.get("DCTM_MAX_PAGE_SIZE", "1000"))  # rest.paging.max.size
# what a larger items-per-page gets: an error, or silently the maximum (OpenText does not document which)
PAGE_OVERSIZE = os.environ.get("DCTM_PAGE_OVERSIZE", "error").lower()
AUDIT = os.environ.get("DCTM_AUDIT", "true").lower() == "true"
QUIET = os.environ.get("DCTM_QUIET", "false").lower() == "true"
ACS_HOST = os.environ.get("DCTM_ACS_HOST", "acs.dctm-mock.invalid:9080")
# the largest request line plus headers, as Tomcat's maxHttpHeaderSize (8 KB by default) under the REST WAR
MAX_HEADER_SIZE = int(os.environ.get("DCTM_MAX_HEADER_SIZE", "8192"))
# the REST root the server writes in its links (rest.context / a proxy), when it differs from what clients call
PUBLIC_URL = os.environ.get("DCTM_PUBLIC_URL", "").rstrip("/")
# content-media answers with a same-server redirect first, as a server configured to redirect content requests may
CONTENT_REDIRECT = os.environ.get("DCTM_CONTENT_REDIRECT", "false").lower() == "true"
CONTEXT = "/dctm-rest"
DM_JSON = "application/vnd.emc.documentum+json"
LINKREL = "http://identifiers.emc.com/linkrel/"
NULL_ID = "0000000000000000"


def tz_from(text):
    m = re.fullmatch(r"([+-])(\d{2}):?(\d{2})", text.strip())
    if not m:
        try:  # a region such as Europe/Berlin: the offset of each date follows its daylight saving rules
            from zoneinfo import ZoneInfo  # noqa: PLC0415
            return ZoneInfo(text.strip())
        except Exception:
            raise SystemExit("DCTM_TZ_OFFSET must look like +02:00 or name a time zone such as Europe/Berlin")
    delta = timedelta(hours=int(m.group(2)), minutes=int(m.group(3)))
    return timezone(-delta if m.group(1) == "-" else delta)


SERVER_TZ = tz_from(os.environ.get("DCTM_TZ_OFFSET", "+00:00"))

# ----------------------------------------------------------------------------------------------- types

S, I, B, D, T, ID = "string", "integer", "boolean", "double", "time", "id"  # Documentum datatypes

SYSOBJECT = [  # (name, datatype, repeating), in the order a REST 16.7 server returns dm_sysobject attributes
    ("object_name", S, False), ("r_object_type", S, False), ("title", S, False), ("subject", S, False),
    ("authors", S, True), ("keywords", S, True), ("a_application_type", S, False), ("a_status", S, False),
    ("r_creation_date", T, False), ("r_modify_date", T, False), ("r_modifier", S, False), ("r_access_date", T, False),
    ("a_is_hidden", B, False), ("i_is_deleted", B, False), ("a_retention_date", T, False), ("a_archive", B, False),
    ("a_compound_architecture", S, False), ("a_link_resolved", B, False), ("i_reference_cnt", I, False),
    ("i_has_folder", B, False), ("i_folder_id", ID, True), ("r_composite_id", ID, True), ("r_composite_label", S, True),
    ("r_component_label", ID, True), ("r_order_no", I, True), ("r_link_cnt", I, False), ("r_link_high_cnt", I, False),
    ("r_assembled_from_id", ID, False), ("r_frzn_assembly_cnt", I, False), ("r_has_frzn_assembly", B, False),
    ("resolution_label", S, False), ("r_is_virtual_doc", I, False), ("i_contents_id", ID, False),
    ("a_content_type", S, False), ("r_page_cnt", I, False), ("r_content_size", I, False), ("a_full_text", B, False),
    ("a_storage_type", S, False), ("i_cabinet_id", ID, False), ("owner_name", S, False), ("owner_permit", I, False),
    ("group_name", S, False), ("group_permit", I, False), ("world_permit", I, False), ("i_antecedent_id", ID, False),
    ("i_chronicle_id", ID, False), ("i_latest_flag", B, False), ("r_lock_owner", S, False), ("r_lock_date", T, False),
    ("r_lock_machine", S, False), ("log_entry", S, False), ("r_version_label", S, True), ("i_branch_cnt", I, False),
    ("i_direct_dsc", B, False), ("r_immutable_flag", B, False), ("r_frozen_flag", B, False), ("r_has_events", B, False),
    ("acl_domain", S, False), ("acl_name", S, False), ("a_special_app", S, False), ("i_is_reference", B, False),
    ("r_creator_name", S, False), ("r_is_public", B, False), ("r_policy_id", ID, False), ("r_resume_state", I, False),
    ("r_current_state", I, False), ("r_alias_set_id", ID, False), ("a_effective_date", T, True),
    ("a_expiration_date", T, True), ("a_publish_formats", S, True), ("a_effective_label", S, True),
    ("a_effective_flag", S, True), ("a_category", S, False), ("language_code", S, False), ("a_is_template", B, False),
    ("a_controlling_app", S, False), ("r_full_content_size", D, False), ("a_extended_properties", S, True),
    ("a_is_signed", B, False), ("a_last_review_date", T, False), ("i_retain_until", T, False),
    ("r_aspect_name", S, True), ("i_retainer_id", ID, True), ("i_partition", I, False), ("i_is_replica", B, False),
    ("i_vstamp", I, False),
]
TYPES = {  # name -> (supertype, own attributes)
    "dm_sysobject": (None, SYSOBJECT),
    "dm_document": ("dm_sysobject", []),
    "dm_folder": ("dm_sysobject", [("r_folder_path", S, True), ("i_ancestor_id", ID, True)]),
    "dm_cabinet": ("dm_folder", [("is_private", B, False)]),
    "oc_contract": ("dm_document", [("contract_number", S, False), ("counterparty", S, False),
                                    ("contract_value", D, False), ("regions", S, True), ("signed_date", T, False)]),
    "oc_submission": ("dm_document", [("submission_id", S, False), ("agency", S, False), ("product_codes", S, True),
                                      ("approved", B, False), ("page_estimate", I, False), ("review_dates", T, True)]),
    "dm_acl": (None, [("object_name", S, False), ("description", S, False), ("owner_name", S, False),
                      ("r_is_internal", B, False), ("r_accessor_name", S, True), ("r_accessor_permit", I, True),
                      ("r_accessor_xpermit", I, True), ("r_is_group", B, True), ("globally_managed", B, False),
                      ("acl_class", I, False), ("r_has_events", B, False), ("r_permit_type", I, True),
                      ("r_application_permit", S, True), ("i_has_required_groups", B, False),
                      ("i_has_required_group_set", B, False), ("i_has_access_restrictions", B, False),
                      ("r_template_id", ID, False), ("r_alias_set_id", ID, False), ("i_partition", I, False),
                      ("i_is_replica", B, False), ("i_vstamp", I, False)]),
    "dm_user": (None, [("user_name", S, False), ("user_os_name", S, False), ("user_address", S, False),
                       ("user_group_name", S, False), ("user_privileges", I, False), ("user_xprivileges", I, False),
                       ("user_login_name", S, False),
                       ("user_login_domain", S, False), ("user_source", S, False), ("user_state", I, False),
                       ("r_is_group", B, False), ("i_vstamp", I, False)]),
    "dm_group": (None, [("group_name", S, False), ("users_names", S, True), ("groups_names", S, True),
                        ("owner_name", S, False), ("group_class", S, False), ("is_private", B, False),
                        ("description", S, False), ("i_vstamp", I, False)]),
    "dm_format": (None, [("name", S, False), ("description", S, False), ("mime_type", S, False),
                         ("dos_extension", S, False), ("can_index", B, False), ("i_vstamp", I, False)]),
    "dm_audittrail": (None, [("event_name", S, False), ("event_description", S, False), ("user_name", S, False),
                             ("time_stamp", T, False), ("time_stamp_utc", T, False), ("audited_obj_id", ID, False),
                             ("chronicle_id", ID, False), ("object_name", S, False), ("version_label", S, False),
                             ("object_type", S, False), ("owner_name", S, False), ("i_vstamp", I, False)]),
}
TAGS = {"dm_document": "09", "oc_contract": "09", "oc_submission": "09", "dm_folder": "0b", "dm_cabinet": "0c",
        "dm_acl": "45", "dm_user": "11", "dm_group": "12", "dm_audittrail": "5f", "dmr_content": "06", "dm_format": "27"}


def supertypes(t):
    while t:
        yield t
        t = TYPES[t][0]


def attributes(t):
    """Every attribute of a type: (name, datatype, repeating), inherited ones first, then r_object_id."""
    chain = list(supertypes(t))[::-1]
    out = [a for c in chain for a in TYPES[c][1]]
    return out + [("r_object_id", ID, False)]


def attribute(t, name):
    for a in attributes(t):
        if a[0] == name:
            return a
    return None


def is_subtype(t, of):
    return of in supertypes(t)


def is_sysobject(t):
    return is_subtype(t, "dm_sysobject")


FORMATS = {  # dm_format name -> (mime type, dos extension, format object id suffix)
    "pdf": ("application/pdf", "pdf", "19f"),
    "msw12": ("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx", "1a2"),
    "crtext": ("text/plain", "txt", "1a5"),
}
FORMAT_DESCRIPTIONS = {"pdf": "Acrobat PDF", "msw12": "Microsoft Word Office Open XML Document", "crtext": "Text Document (Windows)"}
SUPERUSER, SYSADMIN = 16, 8
VIEW_AUDIT = 32  # user_xprivileges bit: DF_XPRIVILEGE_VIEW_AUDIT
PERMITS = {1: "NONE", 2: "BROWSE", 3: "READ", 4: "RELATE", 5: "VERSION", 6: "WRITE", 7: "DELETE"}
# extended permit names (DFC IDfACL.DF_XPERMIT_*_STR); DFC does not publish their bits in r_accessor_xpermit, so the
# bit of each is an assumption of this mock (the connector ignores extended permits)
XPERMITS = [(1 << (16 + i), n) for i, n in enumerate(["EXECUTE_PROC", "CHANGE_LOCATION", "CHANGE_STATE", "CHANGE_PERMIT",
                                                       "CHANGE_OWNER", "DELETE_OBJECT", "CHANGE_FOLDER_LINKS"])]
DATATYPE_NAMES = {S: "STRING", I: "INTEGER", B: "BOOLEAN", D: "DOUBLE", T: "TIME", ID: "ID"}  # DFC IDfAttr.DM_* names
ACCESS_PERMIT, EXTENDED_PERMIT, APPLICATION_PERMIT, ACCESS_RESTRICTION, EXTENDED_RESTRICTION, \
    APPLICATION_RESTRICTION, REQUIRED_GROUP, REQUIRED_GROUP_SET = range(8)


def feed_time():
    """A feed's "updated": the server clock with real milliseconds (2020-07-20T04:57:02.815+00:00)."""
    now = datetime.now(timezone.utc).astimezone(SERVER_TZ)
    offset = now.strftime("%z")
    return now.strftime("%Y-%m-%dT%H:%M:%S.") + f"{now.microsecond // 1000:03d}" + offset[:3] + ":" + offset[3:]


def utcnow():
    return datetime.now(timezone.utc).replace(microsecond=0)  # Documentum keeps whole seconds


def rest_time(dt):
    """A REST date value: RFC 3339 in the server's time zone, milliseconds always zero (2020-07-17T17:51:38.000+00:00)."""
    if dt is None:
        return None
    local = dt.astimezone(SERVER_TZ)
    offset = local.strftime("%z")
    return local.strftime("%Y-%m-%dT%H:%M:%S") + ".000" + offset[:3] + ":" + offset[3:]


def json_value(datatype, value):
    if datatype == T:
        return rest_time(value)
    if datatype == ID:
        return value or NULL_ID
    if datatype == D:
        return float(value or 0)
    if datatype == I:
        return int(value or 0)
    if datatype == B:
        return bool(value)
    return "" if value is None else value


def json_properties(obj, names=None):
    props = {}
    for name, datatype, repeating in attributes(obj.type):
        if names is not None and name not in names:
            continue
        value = obj.attrs.get(name)
        if repeating:
            props[name] = [json_value(datatype, v) for v in value] if value else None  # an empty list is null
        else:
            props[name] = json_value(datatype, value)
    if names is not None:
        props = {n: props[n] for n in names if n in props}  # select-list order
    return props


class Obj:
    def __init__(self, type_, attrs):
        self.type = type_
        self.attrs = attrs
        self.contents = []  # sysobjects: [{format, page, rendition, bytes, set_time, id}]

    @property
    def id(self):
        return self.attrs["r_object_id"]


# ----------------------------------------------------------------------------------------------- the repository

class ObjectTable(dict):
    """The objects by id; counts additions and removals, which is what the per-type indexes depend on."""

    def __init__(self):
        super().__init__()
        self.generation = 0

    def __setitem__(self, key, value):
        if key not in self:
            self.generation += 1
        super().__setitem__(key, value)

    def __delitem__(self, key):
        self.generation += 1
        super().__delitem__(key)


class Repository:
    def __init__(self):
        self.lock = threading.RLock()
        self.reset()

    def reset(self):
        with self.lock:
            self.objects = ObjectTable()
            self.indexes = {}  # (generation, kind) -> index, so a listing does not scan every object for every row
            self.counters = {}
            self.stats = {"requests": {}, "authFailures": 0, "downloads": 0, "dql": []}
            self.faults = []
            self.tokens = {}
            self.audit = AUDIT
            self._seed()

    # ----- ids and basics

    def new_id(self, type_):
        tag = TAGS.get(type_, "08")
        n = self.counters.get(tag, 0x80000100) + 1
        self.counters[tag] = n
        return f"{tag}{DOCBASE_ID}{n:08x}"

    def add(self, type_, **attrs):
        values = {}
        for name, datatype, repeating in attributes(type_):
            if name in attrs:
                values[name] = attrs[name]
            elif repeating:
                values[name] = []
            else:
                values[name] = {S: "", I: 0, B: False, D: 0.0, T: None, ID: NULL_ID}[datatype]
        values["r_object_id"] = attrs.get("r_object_id") or self.new_id(type_)
        if "r_object_type" in values:
            values["r_object_type"] = type_
        obj = Obj(type_, values)
        self.objects[obj.id] = obj
        return obj

    def indexed(self, kind, build):
        key = (self.objects.generation, kind)
        index = self.indexes.get(key)
        if index is None:
            if len(self.indexes) > 64 or any(g != self.objects.generation for g, _ in self.indexes):
                self.indexes = {k: v for k, v in self.indexes.items() if k[0] == self.objects.generation}
            index = self.indexes[key] = build()
        return index

    def of_type(self, type_):
        """The objects of a type and its subtypes (a fresh list: callers may delete while iterating)."""
        return list(self.indexed(("type", type_), lambda: [o for o in self.objects.values() if is_subtype(o.type, type_)]))

    def user(self, name):
        """A user or group by user_name, else by login name (the first in user_name order when several domains share it)."""
        users = self.of_type("dm_user")
        for u in users:
            if u.attrs["user_name"] == name:
                return u
        logins = sorted((u for u in users if u.attrs["user_login_name"] == name), key=lambda u: u.attrs["user_name"])
        return logins[0] if logins else None

    def refresh_permits(self):
        """owner_permit, group_permit and world_permit: the basic permits of dm_owner, dm_group and dm_world in each
        sysobject's permission set (1, None, without such an entry)."""
        cache = {}
        for o in self.of_type("dm_sysobject"):
            key = (o.attrs["acl_domain"], o.attrs["acl_name"])
            if key not in cache:
                acl = self.acl(*key)
                found = {}
                if acl is not None:
                    a = acl.attrs
                    for i, accessor in enumerate(a["r_accessor_name"]):
                        if a["r_permit_type"][i] == ACCESS_PERMIT and accessor in ("dm_owner", "dm_group", "dm_world"):
                            found[accessor] = max(found.get(accessor, 1), a["r_accessor_permit"][i])
                cache[key] = found
            o.attrs["owner_permit"] = cache[key].get("dm_owner", 1)
            o.attrs["group_permit"] = cache[key].get("dm_group", 1)
            o.attrs["world_permit"] = cache[key].get("dm_world", 1)

    def move_folder(self, folder, parent):
        """Relink a folder under another one: its path and its subfolders' paths and ancestors change, and only the folder
        itself is saved (r_modify_date); the documents below it are not touched."""
        old, new = folder.attrs["r_folder_path"][0], parent.attrs["r_folder_path"][0] + "/" + folder.attrs["object_name"]
        folder.attrs["i_folder_id"] = [parent.id]
        moved = [f for f in self.of_type("dm_folder") if folder.id in f.attrs["i_ancestor_id"]]
        for f in moved:
            f.attrs["r_folder_path"] = [new + p[len(old):] if p == old or p.startswith(old + "/") else p
                                        for p in f.attrs["r_folder_path"]]
        for f in sorted(moved, key=lambda f: len(f.attrs["r_folder_path"][0])):
            ancestors, p = [f.id], self.objects.get(f.attrs["i_folder_id"][0]) if f.attrs["i_folder_id"] else None
            while p:
                ancestors.append(p.id)
                p = self.objects.get(p.attrs["i_folder_id"][0]) if p.attrs["i_folder_id"] else None
            f.attrs["i_ancestor_id"] = ancestors
            f.attrs["i_cabinet_id"] = ancestors[-1]
        self.touch(folder)

    def acl(self, domain, name):
        by_name = self.indexed(("acl",), lambda: {(a.attrs["owner_name"], a.attrs["object_name"]): a
                                                 for a in self.objects.values() if a.type == "dm_acl"})
        return by_name.get((domain, name))

    def folder_by_path(self, path):
        for f in self.of_type("dm_folder"):
            if path in f.attrs["r_folder_path"]:
                return f
        return None

    # ----- seed data

    def _seed(self):
        rnd = random.Random(SEED)
        t0 = datetime(2026, 1, 5, 9, 0, 0, tzinfo=timezone.utc)
        people = [("dmadmin", "dmadmin", SUPERUSER, 0), ("dctm_crawler", "dctm_crawler", SUPERUSER, 0),
                  ("John Doe", "jdoe", 0, 0), ("Anna Smith", "asmith", 0, VIEW_AUDIT), ("Mallory Grey", "mallory", 0, 0),
                  ("Rui Costa", "rcosta", 0, 0), ("Kim Lee", "klee", 0, 0), ("Kevin Lee", "klee@EXT", 0, 0)]
        for name, login, privileges, xprivileges in people:
            login, _, domain = login.partition("@")  # user_login_name is unique per user_login_domain only
            self.add("dm_user", user_name=name, user_os_name=login, user_login_name=login, user_login_domain=domain or "CORP",
                     user_address=login + "@example.com", user_privileges=privileges, user_xprivileges=xprivileges,
                     user_source="LDAP", user_group_name="docu")
        groups = {"finance": (["John Doe", "Anna Smith"], []), "legal": (["Anna Smith"], []),
                  "regulatory": (["Rui Costa"], ["legal"]), "emea": (["John Doe", "Mallory Grey", "Kim Lee"], []),
                  "apac": (["Rui Costa", "Kim Lee"], []), "docu": (["dmadmin"], [])}
        for g, (users, subgroups) in groups.items():
            self.add("dm_group", group_name=g, users_names=users, groups_names=subgroups, owner_name="dmadmin",
                     group_class="group")
            self.add("dm_user", user_name=g, user_os_name=g, user_login_name=g, r_is_group=True, user_source="LDAP")
        for fmt, (mime, ext, suffix) in FORMATS.items():
            self.add("dm_format", r_object_id=f"27{DOCBASE_ID}80000{suffix}", name=fmt, mime_type=mime, dos_extension=ext,
                     description=FORMAT_DESCRIPTIONS[fmt], can_index=True)
        # permission sets: (name, entries) with entries (accessor, permit, permit type, is group, xpermit)
        w, o, g = "dm_world", "dm_owner", "dm_group"
        self.patterns = [
            ("oc_finance_read", [(w, 1, 0, False), (o, 7, 0, False), ("finance", 3, 0, True), ("John Doe", 6, 0, False),
                                 ("Mallory Grey", 7, APPLICATION_PERMIT, False, 0, "oc_reports")]),
            ("oc_public_read", [(w, 3, 0, False), (o, 7, 0, False)]),
            ("oc_finance_restricted", [(w, 1, 0, False), (o, 7, 0, False), ("finance", 3, 0, True), ("emea", 3, 0, True),
                                       ("Mallory Grey", 3, ACCESS_RESTRICTION, False)]),
            ("oc_legal_required", [(w, 1, 0, False), (o, 7, 0, False), ("finance", 3, 0, True),
                                   ("legal", 0, REQUIRED_GROUP, True)]),
            ("oc_region_set", [(w, 3, 0, False), (o, 7, 0, False), ("emea", 0, REQUIRED_GROUP_SET, True),
                               ("apac", 0, REQUIRED_GROUP_SET, True)]),
            ("oc_group_read", [(w, 1, 0, False), (o, 7, 0, False), (g, 3, 0, False)]),
            ("oc_owner_only", [(w, 2, 0, False), (o, 7, 0, False), ("Kim Lee", 0, EXTENDED_PERMIT, False, 1 << 20)]),
            ("oc_browse_only", [(w, 2, 0, False), (o, 2, 0, False), ("finance", 2, 0, True),
                                ("Anna Smith", 0, EXTENDED_RESTRICTION, False, 1 << 17)]),
            (None, [(w, 1, 0, False), (o, 7, 0, False), ("Rui Costa", 3, 0, False), ("legal", 5, 0, True),
                    ("Kevin Lee", 3, 0, False)]),  # custom
        ]
        for name, entries in self.patterns:
            if name:
                self.make_acl("dmadmin", name, entries)
        self.add("dm_acl", object_name="dm_45" + DOCBASE_ID + "80000101", owner_name="dm_dbo", r_is_internal=True,
                 r_accessor_name=["dm_world", "dm_owner"], r_accessor_permit=[3, 7], r_accessor_xpermit=[0, 0],
                 r_is_group=[False, False], r_permit_type=[0, 0], r_application_permit=["", ""])  # a system default

        def folder(path, cabinet=False):
            parent = path.rsplit("/", 1)[0]
            parent_obj = self.folder_by_path(parent) if parent else None
            obj = self.add("dm_cabinet" if cabinet else "dm_folder", object_name=path.rsplit("/", 1)[1],
                           r_folder_path=[path], r_creation_date=t0 - timedelta(days=30), r_version_label=["1.0", "CURRENT"],
                           r_modify_date=t0 - timedelta(days=30), owner_name="dmadmin", acl_domain="dmadmin",
                           acl_name="oc_public_read", i_has_folder=True, i_latest_flag=True, r_creator_name="dmadmin",
                           i_folder_id=[parent_obj.id] if parent_obj else [], group_name="docu", r_modifier="dmadmin")
            obj.attrs["i_chronicle_id"] = obj.id
            ancestors = [obj.id]
            p = parent_obj
            while p:
                ancestors.append(p.id)
                p = self.objects.get(p.attrs["i_folder_id"][0]) if p.attrs["i_folder_id"] else None
            obj.attrs["i_ancestor_id"] = ancestors
            obj.attrs["i_cabinet_id"] = ancestors[-1]
            return obj

        for path in ["/Corporate", "/Regulatory"]:
            folder(path, cabinet=True)
        for path in ["/Corporate/Contracts", "/Corporate/Contracts/2026", "/Corporate/Invoices",
                     "/Regulatory/Submissions", "/Regulatory/Submissions/Archive"]:
            folder(path)
        owners = ["John Doe", "Anna Smith", "Rui Costa", "Kim Lee", "dmadmin"]
        placements = [["/Corporate/Contracts"], ["/Corporate/Contracts/2026"], ["/Corporate/Invoices"],
                      ["/Regulatory/Submissions"], ["/Regulatory/Submissions/Archive"],
                      ["/Corporate/Contracts", "/Regulatory/Submissions"], ["/Corporate"]]
        when = t0
        for n in range(DOCUMENT_COUNT):
            type_ = ["dm_document", "oc_contract", "oc_submission"][n % 3]
            if n % 10 not in (4, 7):  # some documents share their predecessor's modification second
                when = when + timedelta(minutes=37, seconds=rnd.randint(1, 50))
            self.seed_document(n, type_, owners[n % len(owners)], placements[n % len(placements)], when, rnd)
        self.refresh_permits()

    def make_acl(self, domain, name, entries):
        def col(i, default):
            return [e[i] if len(e) > i else default for e in entries]
        types = col(2, 0)
        return self.add("dm_acl", object_name=name, owner_name=domain, description=name,
                        r_accessor_name=col(0, ""), r_accessor_permit=col(1, 1), r_permit_type=types,
                        r_is_group=col(3, False), r_accessor_xpermit=col(4, 0), r_application_permit=col(5, ""),
                        i_has_required_groups=REQUIRED_GROUP in types, i_has_required_group_set=REQUIRED_GROUP_SET in types,
                        i_has_access_restrictions=any(t in (ACCESS_RESTRICTION, EXTENDED_RESTRICTION,
                                                            APPLICATION_RESTRICTION) for t in types),
                        acl_class=0, r_is_internal=name.startswith("dm_45"))

    def create_document(self, type_, owner, folders, pattern_index, fmt, text):
        """A new document (admin endpoint): one version 1.0 in a chosen format, with the given text in its content."""
        n = 9000 + len({o.attrs["i_chronicle_id"] for o in self.of_type("dm_document")})
        pattern, entries = self.patterns[pattern_index % len(self.patterns)]
        if pattern is None:
            acl = self.make_acl(owner, "dm_45" + DOCBASE_ID + f"8{n:07x}", entries)
            domain, pattern = owner, acl.attrs["object_name"]
        else:
            domain = "dmadmin"
        title = {"oc_contract": "Contract", "oc_submission": "Submission"}.get(type_, "Policy")
        attrs = dict(object_name=f"{title} {n:03d}", title=f"{title} {n:03d} title", owner_name=owner,
                     group_name="finance", acl_domain=domain, acl_name=pattern, r_creator_name=owner, r_modifier=owner)
        if type_ == "oc_contract":
            attrs.update(contract_number=f"C-{n:05d}", counterparty="Acme", regions=["EMEA"])
        folder_ids = [self.folder_by_path(p).id for p in folders]
        return self.new_version(None, type_, attrs, "1.0", utcnow(), folder_ids, fmt, n, text_extra=text)

    def seed_document(self, n, type_, owner, folders, when, rnd):
        pattern, entries = self.patterns[n % len(self.patterns)]
        if pattern is None:  # a custom ACL of this document only, as Documentum creates when permissions are edited
            acl = self.make_acl(owner, "dm_45" + DOCBASE_ID + f"8{n:07x}", entries)
            domain, pattern = owner, acl.attrs["object_name"]
        else:
            domain = "dmadmin"
        fmt = ["pdf", "msw12", "crtext", "msw12", None][n % 5]
        title = {"dm_document": "Policy", "oc_contract": "Contract", "oc_submission": "Submission"}[type_]
        attrs = dict(object_name=f"{title} {n:03d}", title=f"{title} {n:03d} title", subject=f"Subject of {n}",
                     authors=[owner] + (["Kim Lee"] if n % 4 == 0 else []), keywords=["oc", f"k{n % 3}"],
                     owner_name=owner, group_name="regulatory" if n % 2 else "finance", acl_domain=domain,
                     acl_name=pattern, r_creator_name=owner, r_modifier=owner, a_content_type=fmt or "",
                     language_code="en", a_category="general")
        if type_ == "oc_contract":
            attrs.update(contract_number=f"C-{n:05d}", counterparty=["Acme", "Globex", "Initech"][n % 3],
                         contract_value=1000.5 * n, regions=["EMEA", "APAC", "AMER"][:1 + n % 3],
                         signed_date=datetime(2025, 1 + n % 12, 1 + n % 27, 12, 0, tzinfo=timezone.utc))
        elif type_ == "oc_submission":
            attrs.update(submission_id=f"SUB-{n:04d}", agency=["EMA", "FDA"][n % 2], product_codes=[f"P{n}", f"P{n + 1}"],
                         approved=n % 2 == 0, page_estimate=10 + n,
                         review_dates=[datetime(2026, 2, 1 + n % 20, tzinfo=timezone.utc)] if n % 3 else [])
        labels = [["1.0"], ["1.0", "1.1"], ["1.0", "2.0"], ["1.0", "1.1", "2.0", "2.1"]][n % 4]
        folder_ids = [self.folder_by_path(p).id for p in folders]
        previous = None
        for i, label in enumerate(labels):
            stamp = when - timedelta(hours=(len(labels) - 1 - i) * 5)
            previous = self.new_version(previous, type_, dict(attrs), label, stamp, folder_ids, fmt, n,
                                        rendition=n % 5 == 1, current=i == len(labels) - 1)

    def new_version(self, previous, type_, attrs, label, when, folder_ids, fmt, n, rendition=False, current=True,
                    text_extra=""):
        attrs.update(r_creation_date=when, r_modify_date=when, r_version_label=[label] + (["CURRENT"] if current else []),
                     i_folder_id=list(folder_ids), i_has_folder=current, i_latest_flag=True,
                     i_cabinet_id=self.cabinet_of(folder_ids), i_vstamp=0 if current else 1)
        if previous is not None:
            attrs["i_chronicle_id"] = previous.attrs["i_chronicle_id"]
            attrs["i_antecedent_id"] = previous.id
        obj = self.add(type_, **attrs)
        if previous is None:
            obj.attrs["i_chronicle_id"] = obj.id
        else:
            previous.attrs["i_latest_flag"] = False
            previous.attrs["i_has_folder"] = False
            if "CURRENT" in previous.attrs["r_version_label"]:
                previous.attrs["r_version_label"] = [v for v in previous.attrs["r_version_label"] if v != "CURRENT"]
        if fmt:
            text = (f"{attrs['object_name']} version {label}. Reference DCTM-{n}-v{label}. "
                    f"This document belongs to the OpenCrawling Documentum test repository. {text_extra}").strip()
            self.set_content(obj, fmt, text, when)
            if rendition:
                self.add_content(obj, "pdf", text + " (PDF rendition)", when, rendition=True)
        return obj

    def cabinet_of(self, folder_ids):
        for fid in folder_ids:
            f = self.objects.get(fid)
            if f:
                return f.attrs["i_cabinet_id"]
        return NULL_ID

    def set_content(self, obj, fmt, text, when):
        obj.contents = []
        self.add_content(obj, fmt, text, when)

    def add_content(self, obj, fmt, text, when, rendition=False):
        data = file_bytes(FORMATS[fmt][1], text)
        content = {"id": self.new_id("dmr_content"), "format": fmt, "page": 0, "rendition": rendition, "bytes": data,
                   "set_time": when, "text": text}
        obj.contents.append(content)
        if not rendition:
            obj.attrs.update(a_content_type=fmt, r_page_cnt=1, r_content_size=len(data),
                             r_full_content_size=float(len(data)), i_contents_id=content["id"])
        return content

    # ----- mutations (admin endpoints)

    def touch(self, obj, when=None):
        obj.attrs["r_modify_date"] = when or utcnow()
        obj.attrs["i_vstamp"] += 1

    def chain(self, chronicle):
        return sorted([o for o in self.of_type("dm_sysobject") if o.attrs["i_chronicle_id"] == chronicle],
                      key=lambda o: o.attrs["r_creation_date"])

    def current(self, chronicle):
        for o in self.chain(chronicle):
            if "CURRENT" in o.attrs["r_version_label"]:
                return o
        return None

    def checkin(self, chronicle, major=True, text=""):
        cur = self.current(chronicle)
        label = cur.attrs["r_version_label"][0]
        a, b = label.split(".")[:2]
        new_label = f"{int(a) + 1}.0" if major else f"{a}.{int(b) + 1}"
        attrs = {k: v for k, v in cur.attrs.items() if k not in ("r_object_id", "i_vstamp", "i_antecedent_id")}
        fmt = cur.attrs["a_content_type"] or None
        n = int(re.search(r"(\d+)$", cur.attrs["object_name"]).group(1)) if re.search(r"(\d+)$", cur.attrs["object_name"]) \
            else 0
        obj = self.new_version(cur, cur.type, attrs, new_label, utcnow(), cur.attrs["i_folder_id"], fmt, n,
                               text_extra=text)
        cur.attrs["i_vstamp"] += 1
        return obj

    def destroy(self, obj, event="dm_destroy"):
        del self.objects[obj.id]
        if self.audit:
            now = utcnow()
            self.add("dm_audittrail", event_name=event, event_description=event.replace("dm_", "").capitalize(),
                     user_name="dmadmin", time_stamp=now, time_stamp_utc=now, audited_obj_id=obj.id,
                     chronicle_id=obj.attrs["i_chronicle_id"], object_name=obj.attrs["object_name"],
                     version_label=obj.attrs["r_version_label"][0] if obj.attrs["r_version_label"] else "",
                     object_type=obj.type, owner_name=obj.attrs["owner_name"])

    def destroy_chronicle(self, chronicle, current_only=False):
        versions = self.chain(chronicle)
        if current_only:
            cur = self.current(chronicle)
            self.destroy(cur)
            prev = self.objects.get(cur.attrs["i_antecedent_id"])
            if prev is not None:
                # its antecedent becomes CURRENT again; nothing says the server saves it, so r_modify_date and i_vstamp
                # stay as they were and only deletion tracking can notice the change
                prev.attrs["r_version_label"] = prev.attrs["r_version_label"] + ["CURRENT"]
                prev.attrs["i_has_folder"] = True
                prev.attrs["i_latest_flag"] = True
            return
        for v in versions:
            self.destroy(v)

    # ----- Documentum permission evaluation (the ground truth for the security tests)

    def groups_of(self, user_name):
        """Every group a user is in, directly or through nested groups."""
        groups = {g.attrs["group_name"] for g in self.of_type("dm_group") if user_name in g.attrs["users_names"]}
        while True:
            more = {g.attrs["group_name"] for g in self.of_type("dm_group")
                    if set(g.attrs["groups_names"]) & groups and g.attrs["group_name"] not in groups}
            if not more:
                return groups
            groups |= more

    def supergroups(self, group_name):
        """A group and every group it is in, directly or through nested groups."""
        groups = {group_name}
        while True:
            more = {g.attrs["group_name"] for g in self.of_type("dm_group")
                    if set(g.attrs["groups_names"]) & groups and g.attrs["group_name"] not in groups}
            if not more:
                return groups
            groups |= more

    def permit(self, user_name, obj):
        """The basic permit (1-7) a user has on a sysobject, by Documentum's ACL evaluation."""
        user = self.user(user_name)
        if user is None:
            return 1
        if user.attrs["user_privileges"] & SUPERUSER:
            return 7
        if user.attrs["r_is_group"]:
            return self.evaluate(user.attrs["user_name"], self.supergroups(user.attrs["user_name"]), obj)
        return self.evaluate(user.attrs["user_name"], self.groups_of(user.attrs["user_name"]), obj)

    def evaluate(self, name, groups, obj):
        """The basic permit of an accessor (a user, or a group as for IDfSysObject.getPermitEx) with these groups."""
        acl = self.acl(obj.attrs["acl_domain"], obj.attrs["acl_name"])
        if acl is None:
            return 1

        def matches(accessor):
            if accessor == "dm_world":
                return True
            if accessor == "dm_owner":
                return obj.attrs["owner_name"] == name or obj.attrs["owner_name"] in groups
            if accessor == "dm_group":
                return obj.attrs["group_name"] in groups
            return accessor == name or accessor in groups

        a = acl.attrs
        best = 1
        required, required_set = [], []
        restrictions = []
        for i, accessor in enumerate(a["r_accessor_name"]):
            ptype, permit = a["r_permit_type"][i], a["r_accessor_permit"][i]
            if ptype == ACCESS_PERMIT and matches(accessor):
                best = max(best, permit)
            elif ptype == ACCESS_RESTRICTION and matches(accessor):
                restrictions.append(permit)
            elif ptype == REQUIRED_GROUP:
                required.append(accessor)
            elif ptype == REQUIRED_GROUP_SET:
                required_set.append(accessor)
        for r in restrictions:  # a restriction denies its level and everything above it
            best = min(best, r - 1)
        if any(g not in groups for g in required) or (required_set and not set(required_set) & groups):
            best = 1
        return max(best, 1)

    def visible(self, user_name, obj):
        if obj.type == "dm_audittrail":  # superusers and the View Audit extended privilege only
            user = self.user(user_name)
            return user is not None and bool(user.attrs["user_privileges"] & SUPERUSER
                                              or user.attrs["user_xprivileges"] & VIEW_AUDIT)
        if not is_sysobject(obj.type):
            return True
        return self.permit(user_name, obj) >= 2


# ----------------------------------------------------------------------------------------------- DQL

class DqlError(Exception):
    def __init__(self, message, code="E_DQL_QUERY_ERROR"):
        super().__init__(message)
        self.code = code


TOKEN = re.compile(r"\s*(?:(?P<str>'(?:[^']|'')*')|(?P<num>-?\d+(?:\.\d+)?)|(?P<op><>|!=|<=|>=|[=<>(),*])"
                   r"|(?P<word>[A-Za-z_][A-Za-z0-9_.]*))")
KEYWORDS = {"SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "ORDER", "BY", "ASC", "DESC", "ANY", "LIKE", "IN", "IS",
            "NULL", "NULLDATE", "NULLSTRING", "NULLINT", "TRUE", "FALSE", "DATE", "FOLDER", "CABINET", "DESCEND", "ALL",
            "ENABLE", "UPPER", "LOWER", "DISTINCT", "ESCAPE", "NOW", "TODAY"}


def tokenize(text):
    pos, out = 0, []
    text = text.strip().rstrip(";")
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m or m.end() == pos:
            if text[pos:].strip() == "":
                break
            raise DqlError(f"[DM_QUERY_E_SYNTAX]error: \"A Parser Error (syntax error) has occurred in the vicinity "
                           f"of: {text[pos:pos + 20]}\"")
        pos = m.end()
        kind = m.lastgroup
        value = m.group(kind)
        if kind == "word" and value.upper() in KEYWORDS:
            out.append(("kw", value.upper()))
        elif kind == "str":
            out.append(("str", value[1:-1].replace("''", "'")))
        elif kind == "num":
            out.append(("num", float(value) if "." in value else int(value)))
        else:
            out.append((kind, value))
    return out


DATE_FORMATS = {"yyyy/mm/dd hh:mi:ss": "%Y/%m/%d %H:%M:%S", "yyyy-mm-dd hh:mi:ss": "%Y-%m-%d %H:%M:%S",
                "mm/dd/yyyy hh:mi:ss": "%m/%d/%Y %H:%M:%S", "yyyy/mm/dd": "%Y/%m/%d", "mm/dd/yyyy": "%m/%d/%Y",
                "dd/mm/yyyy hh:mi:ss": "%d/%m/%Y %H:%M:%S", "yyyy-mm-dd": "%Y-%m-%d"}


def parse_date(text, fmt=None):
    """A DQL DATE literal, read in the server's time zone (as Documentum does), as a UTC datetime."""
    candidates = [DATE_FORMATS[fmt.lower()]] if fmt and fmt.lower() in DATE_FORMATS else list(DATE_FORMATS.values())
    if fmt and fmt.lower() not in DATE_FORMATS:
        raise DqlError(f"[DM_QUERY_E_BAD_DATE_FORMAT]error: unsupported date pattern '{fmt}'")
    for f in candidates:
        try:
            return datetime.strptime(text, f).replace(tzinfo=SERVER_TZ).astimezone(timezone.utc)
        except ValueError:
            continue
    raise DqlError(f"[DM_QUERY_E_BAD_DATE]error: \"The date '{text}' does not match the pattern\"")


class Query:
    def __init__(self, text):
        self.text = text
        self.toks = tokenize(text)
        self.i = 0
        self.parse()

    def peek(self, kind=None, value=None, ahead=0):
        if self.i + ahead >= len(self.toks):
            return None
        t = self.toks[self.i + ahead]
        if (kind and t[0] != kind) or (value and t[1] != value):
            return None
        return t

    def take(self, kind=None, value=None):
        t = self.peek(kind, value)
        if t is None:
            near = " ".join(str(x[1]) for x in self.toks[self.i:self.i + 4]) or "end of statement"
            raise DqlError(f"[DM_QUERY_E_SYNTAX]error: \"A Parser Error (syntax error) has occurred in the vicinity of: "
                           f"{near}\" (expected {value or kind})")
        self.i += 1
        return t

    def parse(self):
        if not self.peek("kw", "SELECT"):
            raise DqlError("[DM_QUERY_E_NOT_SELECT]error: only SELECT statements may run through the dql resource",
                           "E_INPUT_ILLEGAL_ARGUMENTS")
        self.take()
        if self.peek("kw", "DISTINCT"):
            raise DqlError("[DM_QUERY_E_UNSUPPORTED]error: the mock does not support DISTINCT")
        self.columns = []
        if self.peek("op", "*"):
            self.take()
            self.columns = None
        else:
            while True:
                self.columns.append(self.take("word")[1].lower())
                if not self.peek("op", ","):
                    break
                self.take()
        self.take("kw", "FROM")
        self.type = self.take("word")[1].lower()
        if self.type not in TYPES:
            raise DqlError(f"[DM_QUERY_E_REG_TABLE_QUAL]error: \"Unable to find the type or registered table "
                           f"'{self.type}'\"")
        self.all_versions = False
        if self.peek("op", "(") and self.peek("kw", "ALL", 1):
            self.take(), self.take(), self.take("op", ")")
            self.all_versions = True
        if self.columns is not None:
            for c in self.columns:
                self.check_attr(c)
            if "r_object_id" not in self.columns and any(self.check_attr(c)[2] for c in self.columns):
                raise DqlError("[DM_QUERY_E_UNSUPPORTED]error: the mock only returns repeating attributes when "
                               "r_object_id is selected (otherwise Documentum returns one row per value)")
        self.where = None
        if self.peek("kw", "WHERE"):
            self.take()
            self.where = self.expr()
        self.order = []
        if self.peek("kw", "ORDER"):
            self.take(), self.take("kw", "BY")
            while True:
                name = self.take("word")[1].lower()
                a = self.check_attr(name)
                if a[2]:
                    raise DqlError(f"[DM_QUERY_E_REPEATING_ORDER]error: cannot order by the repeating attribute {name}")
                desc = False
                if self.peek("kw", "ASC") or self.peek("kw", "DESC"):
                    desc = self.take()[1] == "DESC"
                self.order.append((name, desc))
                if not self.peek("op", ","):
                    break
                self.take()
        self.top = None
        if self.peek("kw", "ENABLE"):
            self.take(), self.take("op", "(")
            while not self.peek("op", ")"):
                hint = self.take("word")[1].upper()
                if hint == "RETURN_TOP":
                    self.top = self.take("num")[1]
                elif hint == "ROW_BASED":
                    raise DqlError("[DM_QUERY_E_UNSUPPORTED]error: the mock does not support ROW_BASED")
                if self.peek("op", ","):
                    self.take()
            self.take("op", ")")
        if self.i != len(self.toks):
            raise DqlError(f"[DM_QUERY_E_SYNTAX]error: \"A Parser Error (syntax error) has occurred in the vicinity of: "
                           f"{' '.join(str(x[1]) for x in self.toks[self.i:self.i + 4])}\"")

    def check_attr(self, name):
        a = attribute(self.type, name)
        if a is None:
            raise DqlError(f"[DM_QUERY_E_NOT_ATTRIBUTE]error: \"You have specified an invalid attribute name ({name}).\"")
        return a

    # ----- expressions: AST nodes are callables obj -> bool

    def expr(self):
        left = self.term()
        while self.peek("kw", "OR"):
            self.take()
            right = self.term()
            left = (lambda l, r: lambda o, ctx: l(o, ctx) or r(o, ctx))(left, right)
        return left

    def term(self):
        left = self.factor()
        while self.peek("kw", "AND"):
            self.take()
            right = self.factor()
            left = (lambda l, r: lambda o, ctx: l(o, ctx) and r(o, ctx))(left, right)
        return left

    def factor(self):
        if self.peek("kw", "NOT"):
            self.take()
            inner = self.factor()
            return lambda o, ctx: not inner(o, ctx)
        if self.peek("op", "("):
            self.take()
            inner = self.expr()
            self.take("op", ")")
            return inner
        if self.peek("kw", "FOLDER") or self.peek("kw", "CABINET"):
            func = self.take()[1]
            self.take("op", "(")
            path = self.take("str")[1]
            descend = False
            if self.peek("op", ","):
                self.take(), self.take("kw", "DESCEND")
                descend = True
            self.take("op", ")")
            return self.folder_predicate(func, path, descend)
        any_ = False
        if self.peek("kw", "ANY"):
            self.take()
            any_ = True
        func = None
        if self.peek("kw", "UPPER") or self.peek("kw", "LOWER"):
            func = self.take()[1]
            self.take("op", "(")
        name = self.take("word")[1].lower()
        if func:
            self.take("op", ")")
        a = self.check_attr(name)
        if a[2] and not any_:
            raise DqlError(f"[DM_QUERY_E_REPEATING_USED]error: \"The repeating attribute ({name}) is used in a "
                           f"predicate without ANY.\"")
        if any_ and not a[2]:
            raise DqlError(f"[DM_QUERY_E_ANY_ON_SINGLE]error: ANY used with the single-valued attribute {name}")
        test = self.predicate(a)
        conv = (lambda v: v.upper() if isinstance(v, str) else v) if func == "UPPER" else \
            (lambda v: v.lower() if isinstance(v, str) else v) if func == "LOWER" else (lambda v: v)

        def evaluate(o, ctx, name=name, repeating=a[2]):
            value = o.attrs.get(name)
            if repeating:
                return any(test(conv(v)) for v in (value or []))
            return test(conv(value))
        return evaluate

    def literal(self, attr):
        t = self.take()
        datatype = attr[1]
        if t[0] == "kw" and t[1] == "DATE":
            self.take("op", "(")
            if self.peek("kw", "NOW") or self.peek("kw", "TODAY"):
                today = self.take()[1] == "TODAY"
                self.take("op", ")")
                now = utcnow()
                if today:  # midnight of the server's day
                    local = now.astimezone(SERVER_TZ).replace(hour=0, minute=0, second=0)
                    return local.astimezone(timezone.utc)
                return now
            text = self.take("str")[1]
            fmt = None
            if self.peek("op", ","):
                self.take()
                fmt = self.take("str")[1]
            self.take("op", ")")
            return parse_date(text, fmt)
        if datatype == T:
            if t[0] == "kw" and t[1] == "NULLDATE":
                return None
            if t[0] == "str":
                return parse_date(t[1])
            raise DqlError(f"[DM_QUERY_E_TYPE_MISMATCH]error: a time attribute ({attr[0]}) needs a DATE literal")
        if t[0] == "kw" and t[1] in ("TRUE", "FALSE"):
            if datatype != B:
                raise DqlError(f"[DM_QUERY_E_TYPE_MISMATCH]error: {attr[0]} is not boolean")
            return t[1] == "TRUE"
        if t[0] == "num":
            if datatype in (S, ID):
                raise DqlError(f"[DM_QUERY_E_TYPE_MISMATCH]error: {attr[0]} needs a quoted literal")
            return bool(t[1]) if datatype == B else t[1]
        if t[0] == "str":
            if datatype in (I, D, B):
                raise DqlError(f"[DM_QUERY_E_TYPE_MISMATCH]error: {attr[0]} needs a {datatype} literal")
            return t[1]
        if t[0] == "kw" and t[1] in ("NULLSTRING", "NULLINT"):
            return "" if t[1] == "NULLSTRING" else 0
        raise DqlError(f"[DM_QUERY_E_SYNTAX]error: unexpected {t[1]}")

    def predicate(self, attr):
        if self.peek("kw", "IS"):
            self.take()
            negate = False
            if self.peek("kw", "NOT"):
                self.take()
                negate = True
            self.take("kw")  # NULL, NULLDATE, NULLSTRING
            is_null = lambda v: v is None or v == "" or v == NULL_ID
            return (lambda v: not is_null(v)) if negate else is_null
        negate = False
        if self.peek("kw", "NOT"):
            self.take()
            negate = True
        if self.peek("kw", "LIKE"):
            self.take()
            pattern = self.take("str")[1]
            if self.peek("kw", "ESCAPE"):
                raise DqlError("[DM_QUERY_E_UNSUPPORTED]error: the mock does not support LIKE ... ESCAPE")
            rx = re.compile("^" + "".join(".*" if c == "%" else "." if c == "_" else re.escape(c) for c in pattern) + "$",
                            re.S)
            test = lambda v: isinstance(v, str) and rx.match(v) is not None
            return (lambda v: not test(v)) if negate else test
        if self.peek("kw", "IN"):
            self.take()
            self.take("op", "(")
            values = [self.literal(attr)]
            while self.peek("op", ","):
                self.take()
                values.append(self.literal(attr))
            self.take("op", ")")
            if len(values) > 1000:
                raise DqlError("[DM_QUERY_E_TOO_MANY_VALUES]error: too many values in an IN list")
            test = lambda v: v in values
            return (lambda v: not test(v)) if negate else test
        if negate:
            raise DqlError("[DM_QUERY_E_SYNTAX]error: NOT must be followed by LIKE or IN here")
        op = self.take("op")[1]
        value = self.literal(attr)
        if op not in ("=", "<>", "!=", "<", ">", "<=", ">="):
            raise DqlError(f"[DM_QUERY_E_SYNTAX]error: unexpected operator {op}")

        def compare(v):
            if op == "=":
                return v == value
            if op in ("<>", "!="):
                return v != value
            if v is None or value is None:
                return False
            try:
                return {"<": v < value, ">": v > value, "<=": v <= value, ">=": v >= value}[op]
            except TypeError:
                return False
        return compare

    def folder_predicate(self, func, path, descend):
        with STORE.lock:
            folder = STORE.folder_by_path(path)
        if folder is None or (func == "CABINET" and folder.type != "dm_cabinet"):
            raise DqlError(f"[DM_QUERY_E_INVALID_PATH]error: \"The folder path '{path}' does not exist.\"")
        fid = folder.id

        def evaluate(o, ctx):
            ids = o.attrs.get("i_folder_id") or []
            if fid in ids:
                return True
            if not descend:
                return False
            for parent in ids:
                p = STORE.objects.get(parent)
                if p is not None and fid in (p.attrs.get("i_ancestor_id") or []):
                    return True
            return False
        return evaluate

    # ----- execution

    def run(self, user):
        with STORE.lock:
            rows = []
            for o in STORE.of_type(self.type):
                if not STORE.visible(user, o):
                    continue
                if is_sysobject(o.type):
                    if not self.all_versions and "CURRENT" not in o.attrs["r_version_label"]:
                        continue
                    if o.attrs.get("i_is_deleted"):
                        continue
                if self.where is None or self.where(o, None):
                    rows.append(o)
            if self.order:
                for name, desc in reversed(self.order):
                    rows.sort(key=lambda o: sort_key(o.attrs.get(name)), reverse=desc)
            else:  # no ORDER BY: the order is unspecified and each page re-runs the query, so shuffle on every run
                random.shuffle(rows)
            if self.top is not None:
                rows = rows[:int(self.top)]
            names = self.columns if self.columns is not None else [a[0] for a in attributes(self.type)]
            return [(o, json_properties(o, names)) for o in rows]


def sort_key(v):
    if v is None or v == "":
        return (0, "")
    if isinstance(v, datetime):
        return (1, v.timestamp())
    if isinstance(v, bool):
        return (1, int(v))
    if isinstance(v, (int, float)):
        return (1, v)
    return (1, v)


# ----------------------------------------------------------------------------------------------- content files

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


def file_bytes(ext, text):
    return pdf_bytes(text) if ext == "pdf" else docx_bytes(text) if ext == "docx" else text.encode()


STORE = Repository()

# server settings a test can change at run time (POST /mock/config); /mock/reset restores these environment values
RUNTIME = ("MAX_PAGE_SIZE", "PAGE_OVERSIZE", "MAX_HEADER_SIZE", "PUBLIC_URL", "CONTENT_REDIRECT", "SERVER_TZ")
CONFIG_DEFAULTS = {k: globals()[k] for k in RUNTIME}


def configure(values):
    """Apply {maxPageSize, pageOversize, maxHeaderSize, publicUrl, contentRedirect, timeZone}; None restores defaults."""
    g = globals()
    if values is None:
        g.update(CONFIG_DEFAULTS)
        return
    keys = {"maxPageSize": ("MAX_PAGE_SIZE", int), "pageOversize": ("PAGE_OVERSIZE", str),
            "maxHeaderSize": ("MAX_HEADER_SIZE", int), "publicUrl": ("PUBLIC_URL", lambda v: str(v).rstrip("/")),
            "contentRedirect": ("CONTENT_REDIRECT", bool), "timeZone": ("SERVER_TZ", tz_from)}
    for k, v in values.items():
        if k not in keys:
            raise ValueError(f"unknown setting {k}; known: {sorted(keys)}")
        name, convert = keys[k]
        g[name] = convert(v)

# ----------------------------------------------------------------------------------------------- HTTP


class RequestError(Exception):
    """An HTTP error answer raised from inside a resource method."""

    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code = status, code


def strip_links(value):
    """links=false: the representation without its links (and its entries' links)."""
    if isinstance(value, dict):
        return {k: strip_links(v) for k, v in value.items() if k != "links"}
    if isinstance(value, list):
        return [strip_links(v) for v in value]
    return value


def kind_of(obj):
    """The URL collection an object is canonically addressed in."""
    if is_subtype(obj.type, "dm_cabinet"):
        return "cabinets"
    if is_subtype(obj.type, "dm_folder"):
        return "folders"
    if is_subtype(obj.type, "dm_document"):
        return "documents"
    return {"dm_acl": "acls", "dm_user": "users", "dm_group": "groups", "dm_format": "formats"}.get(obj.type, "objects")


RESOURCE_NAMES = {"objects": "object", "documents": "document", "folders": "folder", "cabinets": "cabinet", "acls": "acl",
                  "users": "user", "groups": "group", "formats": "format"}


def error_body(status, code, message, details=None):
    return {"status": status, "code": code, "message": message, "details": details or message,
            "id": str(uuid.uuid4())}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "DctmRestMock/1.0"

    def log_message(self, fmt, *args):
        if not QUIET:
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # ----- plumbing

    def base(self):
        return PUBLIC_URL or f"http://{self.headers.get('Host') or f'localhost:{PORT}'}{CONTEXT}"

    def repo_base(self):
        return f"{self.base()}/repositories/{REPO}"

    def send(self, status, body=b"", content_type=DM_JSON + ";charset=UTF-8", headers=None):
        if isinstance(body, (dict, list)):
            if getattr(self, "q", {}).get("links") == "false":
                body = strip_links(body)
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

    def fail(self, status, code, message, headers=None):
        self.send(status, error_body(status, code, message), headers=headers)

    def count(self, op):
        with STORE.lock:
            STORE.stats["requests"][op] = STORE.stats["requests"].get(op, 0) + 1

    def fault(self, op):
        with STORE.lock:
            for f in STORE.faults:
                if f["op"] in (op, "*") and f["remaining"] > 0:
                    f["remaining"] -= 1
                    return f
        return None

    def apply_fault(self, op):
        f = self.fault(op)
        if f is None:
            return False
        headers = {"Retry-After": str(f["retryAfter"])} if f.get("retryAfter") is not None else None
        self.fail(f.get("status", 503), f.get("code", "E_SERVICE_UNAVAILABLE"), "Injected fault", headers)
        return True

    def authenticated_user(self):
        """The user name of a valid Basic or Bearer authorization, or None after sending a 401."""
        auth = self.headers.get("Authorization") or ""
        user = None
        if auth.startswith("Basic "):
            try:
                name, _, pwd = base64.b64decode(auth[6:]).decode().partition(":")
                if USERS.get(name) == pwd and pwd != "":
                    user = name
            except Exception:
                user = None
        elif auth.startswith("Bearer "):
            with STORE.lock:
                token = STORE.tokens.get(auth[7:])
            if token is not None and token[1] > time.time():
                user = token[0]
        if user is None:
            with STORE.lock:
                STORE.stats["authFailures"] += 1
            self.fail(401, "E_BAD_CREDENTIALS", "Authentication failed: the credentials are missing or invalid.",
                      {"WWW-Authenticate": 'Basic realm="Documentum"'})
        return user

    def accepts_json(self):
        accept = (self.headers.get("Accept") or "*/*").lower()
        if "json" in accept or "*/*" in accept:
            return True
        self.fail(406, "E_MEDIA_TYPE_NOT_ACCEPTABLE", "Only JSON representations are served by this mock.")
        return False

    # ----- routes

    def too_large(self):
        """A request line plus headers over Tomcat's maxHttpHeaderSize: Tomcat's bare HTML 400, before any REST code."""
        if self.path.startswith("/mock/"):
            return False
        size = len(self.requestline) + 2 + sum(len(k) + len(v) + 4 for k, v in self.headers.items())
        if size <= MAX_HEADER_SIZE:
            return False
        self.send(400, "<!doctype html><html><head><title>HTTP Status 400 – Bad Request</title></head><body>"
                       "<h1>HTTP Status 400 – Bad Request</h1><p>Request header is too large</p></body></html>",
                  content_type="text/html;charset=utf-8", headers={"Connection": "close"})
        self.close_connection = True
        return True

    def do_GET(self):
        if self.too_large():
            return
        url = urlparse(self.path)
        path = unquote(url.path)
        q = {k: v[-1] for k, v in parse_qs(url.query, keep_blank_values=True).items()}
        self.q = q
        if path.startswith("/mock/"):
            return self.admin_get(path, q)
        if path in (CONTEXT, CONTEXT + "/", CONTEXT + "/services"):
            self.count("home")
            return self.send(200, self.home(), content_type="application/home+json;charset=UTF-8")
        if path == CONTEXT + "/product-info":
            self.count("product-info")
            return self.send(200, self.product_info())
        if path == CONTEXT + "/repositories" or path == CONTEXT + "/repositories/":
            self.count("repositories")
            return self.send(200, self.repositories())
        m = re.match(rf"^{CONTEXT}/repositories/([^/]+)(/.*)?$", path)
        if not m:
            return self.fail(404, "E_RESOURCE_NOT_FOUND", f"The resource {path} does not exist.")
        if m.group(1) != REPO:
            return self.fail(404, "E_REPOSITORY_NOT_FOUND", f"The repository {m.group(1)} does not exist.")
        if not (m.group(2) or "").endswith("/content-media") and not self.accepts_json():
            return
        user = self.authenticated_user()
        if user is None:
            return
        try:
            return self.route(m.group(2) or "", q, user, path)
        except DqlError as e:
            return self.fail(400, e.code, str(e))
        except RequestError as e:
            return self.fail(e.status, e.code, str(e))

    def route(self, rest, q, user, path):
        b = self.repo_base()
        if rest in ("", "/"):
            if "dql" in q:
                return self.dql(q, user)
            self.count("repository")
            return self.send(200, self.repository())
        if rest == "/currentuser":
            self.count("currentuser")
            with STORE.lock:
                return self.send(200, self.object_json(STORE.user(user), "users", q))
        if rest == "/batches":
            return self.fail(405, "E_METHOD_NOT_ALLOWED", "Batches are created with POST.", {"Allow": "POST"})
        m = re.match(r"^/(users|groups|formats|types)(?:/([^/]+))?$", rest)
        if m:
            return self.named(m.group(1), unquote(m.group(2)) if m.group(2) else None, q, user)
        if rest in ("/cabinets", "/acls"):
            self.count(rest[1:])
            with STORE.lock:
                items = [o for o in STORE.of_type("dm_cabinet" if rest == "/cabinets" else "dm_acl")
                         if STORE.visible(user, o)]
                return self.send(200, self.feed(q, b + rest, rest[1:].capitalize(), items))
        if rest in ("/checked-out-objects", "/network-locations", "/relations", "/relation-types", "/aspect-types"):
            # nothing is checked out, and the mock has no BOCS servers, relations or aspects
            self.count(rest[1:])
            return self.send(200, self.feed(q, b + rest, rest[1:].replace("-", " ").capitalize(), []))
        m = re.match(r"^/(objects|documents|folders|cabinets|acls)/([0-9a-f]{16})(/.*)?$", rest)
        if not m:
            return self.fail(404, "E_RESOURCE_NOT_FOUND", f"The resource {path} does not exist.")
        kind, oid, sub = m.group(1), m.group(2), m.group(3) or ""
        with STORE.lock:
            obj = STORE.objects.get(oid)
            if obj is None or (is_sysobject(obj.type) and not STORE.visible(user, obj)):
                return self.fail(404, "E_RESOURCE_NOT_FOUND",
                                 f"The object {oid} does not exist or you do not have access to it.")
            if kind != "objects" and not (kind == kind_of(obj) or (kind == "folders" and kind_of(obj) == "cabinets")):
                return self.fail(404, "E_RESOURCE_NOT_FOUND", f"{oid} is not a {RESOURCE_NAMES[kind]}.")
            if sub == "":
                self.count("object")
                if self.apply_fault("object"):
                    return
                return self.send(200, self.object_json(obj, kind, q))
            if not is_sysobject(obj.type):
                return self.fail(404, "E_RESOURCE_NOT_FOUND", f"The resource {path} does not exist.")
            if sub in ("/folders", "/documents", "/objects") and kind in ("folders", "cabinets"):
                self.count("folder-children")
                return self.send(200, self.children(obj, sub[1:], q, user))
            if sub == "/versions":
                self.count("versions")
                versions = [v for v in reversed(STORE.chain(obj.attrs["i_chronicle_id"])) if STORE.visible(user, v)]
                return self.send(200, self.feed(q, f"{b}/objects/{oid}/versions", f"Versions of {oid}", versions))
            if sub == "/versions/current":
                self.count("current-version")
                cur = STORE.current(obj.attrs["i_chronicle_id"])
                if cur is None or not STORE.visible(user, cur):
                    return self.fail(404, "E_RESOURCE_NOT_FOUND", f"{oid} has no CURRENT version you can see.")
                return self.send(200, self.object_json(cur, "objects", q, self_href=f"{b}/objects/{oid}/versions/current"))
            if sub == "/permission-set":
                self.count("permission-set")
                return self.send(200, self.permission_set(obj))
            if sub == "/permissions":
                self.count("permissions")
                return self.send(200, self.permissions(obj, q.get("accessor") or user))
            m2 = re.match(r"^/parent-links(?:/([0-9a-f]{16}))?$", sub)
            if m2:
                self.count("parent-links")
                return self.send(200, self.parent_links(obj, m2.group(1), q))
            if STORE.permit(user, obj) < 3:
                return self.fail(403, "E_ACCESS_DENIED", f"READ permission on {oid} is required for its content.")
            if sub == "/contents":
                self.count("contents")
                return self.send(200, self.contents_feed(obj, q))
            if sub == "/contents/content":
                self.count("content")
                if self.apply_fault("content"):
                    return
                return self.content_meta(obj, q)
            if sub == "/content-media":
                return self.content_media(obj, q)
        return self.fail(404, "E_RESOURCE_NOT_FOUND", f"The resource {path} does not exist.")

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == "/oauth/token":
            return self.oauth()
        if url.path.startswith("/mock/"):
            return self.admin_post(unquote(url.path))
        if url.path.startswith(CONTEXT + "/repositories/"):
            # the crawler never writes; anything else posted here is an error the connector must not cause
            return self.fail(405, "E_METHOD_NOT_ALLOWED", "The mock is read-only.")
        self.fail(404, "E_RESOURCE_NOT_FOUND", f"The resource {url.path} does not exist.")

    do_PUT = do_POST
    do_DELETE = do_POST

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    # ----- resources

    def home(self):
        rels = ["repositories", "about", "product-info"]
        resources = {}
        for rel in rels:
            href = f"{self.base()}/repositories" if rel == "repositories" else f"{self.base()}/product-info"
            key = rel if rel == "about" else LINKREL + rel
            resources[key] = {"href": href, "hints": {"allow": ["GET"], "representations": [
                "application/xml", "application/json", "application/atom+xml", DM_JSON]}}
        return {"resources": resources}

    def repositories(self):
        href = f"{self.repo_base()}"
        return {"id": f"{self.base()}/repositories", "title": "Repositories",
                "author": [{"name": "Open Text Documentum"}], "updated": feed_time(), "page": 1,
                "items-per-page": 100, "links": [{"rel": "self", "href": f"{self.base()}/repositories"}],
                "entries": [{"id": href, "title": REPO, "summary": f"Repository {REPO}", "updated": feed_time(),
                             "links": [{"rel": "edit", "href": href}],
                             "content": {"type": DM_JSON, "src": href}}]}

    def repository(self):
        b = self.repo_base()
        links = [{"rel": "self", "href": b}]
        for rel in ["users", "current-user", "groups", "cabinets", "formats", "types", "acls", "checked-out-objects",
                    "network-locations", "relations", "relation-types", "aspect-types", "batches"]:
            links.append({"rel": LINKREL + rel, "href": f"{b}/{rel.replace('current-user', 'currentuser')}"})
        links.append({"rel": LINKREL + "dql", "hreftemplate": b + "{?dql}"})
        return {"id": int(DOCBASE_ID, 16), "name": REPO, "description": "OpenCrawling Documentum test repository",
                "servers": [{"name": REPO, "host": "dctm-mock", "version": "23.4.0000.0124 Linux64.Postgres",
                             "docbroker": "dctm-mock"}], "links": links}

    def product_info(self):
        return {"name": "product-info", "properties": {"product": "Documentum REST Services",
                                                       "product_version": "23.4.0000.0124", "major": "23.4",
                                                       "minor": "0000", "build_number": "0124", "revision_number": "0"},
                "links": [{"rel": "self", "href": f"{self.base()}/product-info"}]}

    def href(self, obj, kind=None):
        """An object's URL in a collection (its canonical one by default); users, groups and formats go by name."""
        b, kind = self.repo_base(), kind or kind_of(obj)
        key = {"users": "user_name", "groups": "group_name", "formats": "name"}.get(kind)
        return f"{b}/{kind}/{quote(obj.attrs[key]) if key else obj.id}"

    def view(self, obj, q):
        """The view parameter: :default and :all are every attribute, otherwise a comma-separated attribute list."""
        v = (q or {}).get("view")
        if v in (None, "", ":default", ":all"):
            return None
        names = [n.strip() for n in v.split(",") if n.strip()]
        for n in names:
            if attribute(obj.type, n) is None:
                raise RequestError(400, "E_INPUT_ILLEGAL_ARGUMENTS", f"{n} is not an attribute of {obj.type}.")
        return names

    def object_json(self, obj, kind=None, q=None, self_href=None):
        """An object as fetched through the collection kind: its name, self, edit and canonical links depend on it."""
        b = self.repo_base()
        kind = kind or kind_of(obj)
        canonical = self.href(obj)
        here = self.href(obj, kind) if kind not in ("users", "groups", "formats") else canonical
        links = [{"rel": "self", "href": self_href or here}]
        if is_sysobject(obj.type):
            o = f"{b}/objects/{obj.id}"
            links += [{"rel": "edit", "href": here}, {"rel": LINKREL + "delete", "href": here}]
            if here != canonical:
                links.append({"rel": "canonical", "href": canonical})
            links.append({"rel": LINKREL + "parent-links", "href": o + "/parent-links"})
            for parent in obj.attrs.get("i_folder_id") or []:
                links.append({"rel": "parent", "title": parent, "href": f"{b}/folders/{parent}"})
            if obj.attrs.get("i_cabinet_id") not in (None, "", NULL_ID) and kind_of(obj) != "cabinets":
                links.append({"rel": LINKREL + "cabinet", "href": f"{b}/cabinets/{obj.attrs['i_cabinet_id']}"})
            if is_subtype(obj.type, "dm_folder"):
                f = f"{b}/folders/{obj.id}"
                links += [{"rel": LINKREL + "folders", "href": f + "/folders"},
                          {"rel": LINKREL + "documents", "href": f + "/documents"},
                          {"rel": LINKREL + "objects", "href": f + "/objects"}]
            else:
                links += [{"rel": "contents", "href": o + "/contents"},
                          {"rel": LINKREL + "primary-content", "href": o + "/contents/content"},
                          {"rel": "version-history", "href": o + "/versions"},
                          {"rel": LINKREL + "current-version", "href": o + "/versions/current"}]
            links += [{"rel": LINKREL + "relations",
                       "href": f"{b}/relations?" + urlencode({"related-object-id": obj.id, "related-object-role": "any"})},
                      {"rel": LINKREL + "permission-set", "href": o + "/permission-set"},
                      {"rel": LINKREL + "permissions", "href": o + "/permissions"}]
        return {"name": RESOURCE_NAMES.get(kind, "object"), "type": obj.type, "definition": f"{b}/types/{obj.type}",
                "properties": json_properties(obj, self.view(obj, q)), "links": links}

    # ----- collections

    def paging(self, q):
        try:
            page = int(q.get("page", "1"))
            size = int(q.get("items-per-page", str(DEFAULT_PAGE_SIZE)))
        except ValueError:
            raise RequestError(400, "E_INPUT_ILLEGAL_ARGUMENTS", "page and items-per-page must be integers.")
        if page < 1 or size < 1:
            raise RequestError(400, "E_INPUT_ILLEGAL_ARGUMENTS", "page and items-per-page must be positive.")
        if size > MAX_PAGE_SIZE:
            if PAGE_OVERSIZE != "cap":
                raise RequestError(400, "E_INPUT_ILLEGAL_ARGUMENTS",
                                   f"items-per-page {size} is larger than the maximum page size {MAX_PAGE_SIZE}.")
            size = MAX_PAGE_SIZE
        return page, size

    @staticmethod
    def filtered(items, q):
        """The filter parameter, in the subset the mock understands: starts-with(attr,'v') and attr='v', joined by
        "and". Anything else is refused rather than ignored, so a client never gets an unfiltered feed by mistake."""
        text = (q.get("filter") or "").strip()
        if not text:
            return items
        tests = []
        for part in re.split(r"\s+and\s+", text, flags=re.I):
            m = re.fullmatch(r"starts-with\(\s*(\w+)\s*,\s*(['\"])(.*)\2\s*\)", part.strip()) or \
                re.fullmatch(r"(\w+)\s*=\s*(['\"])(.*)\2", part.strip())
            if not m:
                raise RequestError(400, "E_INPUT_ILLEGAL_ARGUMENTS",
                                   f"The filter {part!r} is not supported by this mock (starts-with() and = only).")
            tests.append((m.group(1), m.group(3), part.strip().lower().startswith("starts-with")))

        def keep(o):
            for name, value, prefix in tests:
                if name not in o.attrs:
                    raise RequestError(400, "E_INPUT_ILLEGAL_ARGUMENTS", f"{name} is not an attribute of {o.type}.")
                actual = o.attrs[name]
                actual = "" if actual is None else str(actual)
                if not (actual.startswith(value) if prefix else actual == value):
                    return False
            return True
        return [o for o in items if keep(o)]

    @staticmethod
    def sorted_items(items, q):
        """The sort parameter: attr [asc|desc], comma-separated."""
        text = (q.get("sort") or "").strip()
        if not text:
            return items
        for part in reversed([p.strip() for p in text.split(",") if p.strip()]):
            m = re.fullmatch(r"(\w+)(?:\s+(asc|desc))?", part, flags=re.I)
            if not m:
                raise RequestError(400, "E_INPUT_ILLEGAL_ARGUMENTS", f"The sort {part!r} is not valid.")
            name, desc = m.group(1), (m.group(2) or "asc").lower() == "desc"
            for o in items:
                if name not in o.attrs:
                    raise RequestError(400, "E_INPUT_ILLEGAL_ARGUMENTS", f"{name} is not an attribute of {o.type}.")
            items = sorted(items, key=lambda o: sort_key(o.attrs[name]), reverse=desc)
        return items

    def entry(self, obj, q, inline):
        href = self.href(obj)
        title = obj.attrs.get("object_name") or obj.attrs.get("user_name") or obj.attrs.get("group_name") \
            or obj.attrs.get("name") or obj.id
        e = {"id": href, "title": title}
        if is_sysobject(obj.type):
            owner = obj.attrs["owner_name"]
            e["author"] = [{"name": owner, "uri": f"{self.repo_base()}/users/{quote(owner)}"}]
            e["summary"] = f"{obj.type} {obj.id}"
            e["updated"] = rest_time(obj.attrs["r_modify_date"])
            e["published"] = rest_time(obj.attrs["r_creation_date"])
        else:
            e["author"] = [{"name": "Open Text Documentum"}]
            e["summary"] = f"{obj.type} {obj.id}"
            e["updated"] = feed_time()
        e["links"] = [{"rel": "edit", "href": href}]
        e["content"] = self.object_json(obj, None, q) if inline else {"type": DM_JSON, "src": href}
        return e

    def feed(self, q, href, title, items, entry=None):
        """A collection feed: filter, sort, page, items-per-page, include-total, inline."""
        page, size = self.paging(q)
        items = self.sorted_items(self.filtered(items, q), q)
        chunk = items[(page - 1) * size: page * size]
        inline = q.get("inline", "false") == "true"
        kept = {k: v for k, v in q.items() if k not in ("page", "items-per-page")}

        def page_href(p):
            return href + "?" + urlencode({**kept, "page": p, "items-per-page": size})
        links = [{"rel": "self", "href": href + ("?" + urlencode(q) if q else "")}]
        more = len(items) > page * size  # the server reads one row past the page (PagedQueryTemplate's RETURN_RANGE)
        if page > 1 or more:
            links.append({"rel": "first", "href": page_href(1)})
        if more:
            links.append({"rel": "next", "href": page_href(page + 1)})
        if page > 1:
            links.append({"rel": "previous", "href": page_href(page - 1)})
        out = {"id": href, "title": title, "author": [{"name": "Open Text Documentum"}], "updated": feed_time(),
               "page": page, "items-per-page": size}
        if q.get("include-total") == "true":
            out["total"] = len(items)
        out["links"] = links
        if chunk:
            out["entries"] = [(entry or self.entry)(o, q, inline) for o in chunk]
        return out

    def named(self, kind, name, q, user):
        """users, groups, formats and types: collections whose members are addressed by name."""
        self.count(kind)
        b = self.repo_base()
        with STORE.lock:
            if kind == "types":
                if name is None:
                    names = sorted(TYPES)
                    return self.send(200, self.type_feed(q, names))
                if name not in TYPES:
                    raise RequestError(404, "E_TYPE_NOT_FOUND", f"The type {name} does not exist.")
                return self.send(200, self.type_json(name))
            table, key = {"users": ("dm_user", "user_name"), "groups": ("dm_group", "group_name"),
                          "formats": ("dm_format", "name")}[kind]
            items = sorted(STORE.of_type(table), key=lambda o: o.attrs[key])
            if kind == "users":
                items = [o for o in items if not o.attrs["r_is_group"]]
            if name is None:
                return self.send(200, self.feed(q, f"{b}/{kind}", kind.capitalize(), items))
            for o in items:
                if o.attrs[key] == name:
                    return self.send(200, self.object_json(o, kind, q))
            raise RequestError(404, "E_RESOURCE_NOT_FOUND", f"The {RESOURCE_NAMES[kind]} {name} does not exist.")

    def type_json(self, name):
        b = self.repo_base()
        out = {"name": name, "label": name, "category": "standard"}
        parent = TYPES[name][0]
        if parent:
            out["parent"] = f"{b}/types/{parent}"
        out["properties"] = [{"name": n, "type": DATATYPE_NAMES[dt], "repeating": rep, "length": 255 if dt == S else 0,
                              "label": n, "hidden": n.startswith(("i_", "r_")), "readonly": n.startswith(("i_", "r_"))}
                             for n, dt, rep in attributes(name)]
        out["links"] = [{"rel": "self", "href": f"{b}/types/{name}"}]
        return out

    def type_feed(self, q, names):
        b = self.repo_base()
        page, size = self.paging(q)
        if q.get("filter") or q.get("sort"):
            raise RequestError(400, "E_INPUT_ILLEGAL_ARGUMENTS", "The mock does not filter or sort types.")
        chunk = names[(page - 1) * size: page * size]
        inline = q.get("inline", "false") == "true"
        out = {"id": f"{b}/types", "title": "Types", "author": [{"name": "Open Text Documentum"}],
               "updated": feed_time(), "page": page, "items-per-page": size}
        if q.get("include-total") == "true":
            out["total"] = len(names)
        out["links"] = [{"rel": "self", "href": f"{b}/types"}]
        if len(names) > page * size:
            out["links"].append({"rel": "next", "href": f"{b}/types?" + urlencode({"page": page + 1,
                                                                                   "items-per-page": size})})
        if chunk:
            out["entries"] = [{"id": f"{b}/types/{n}", "title": n, "author": [{"name": "Open Text Documentum"}],
                               "updated": feed_time(), "links": [{"rel": "edit", "href": f"{b}/types/{n}"}],
                               "content": self.type_json(n) if inline else {"type": DM_JSON, "src": f"{b}/types/{n}"}}
                              for n in chunk]
        return out

    def children(self, folder, what, q, user):
        b = self.repo_base()
        kids = [o for o in STORE.of_type("dm_sysobject")
                if folder.id in (o.attrs.get("i_folder_id") or []) and STORE.visible(user, o)
                and (q.get("include-all-versions") == "true" or "CURRENT" in o.attrs["r_version_label"])]
        if what == "folders":
            kids = [o for o in kids if is_subtype(o.type, "dm_folder")]
        elif what == "documents":
            kids = [o for o in kids if is_subtype(o.type, "dm_document")]
        kids.sort(key=lambda o: o.attrs["object_name"])
        title = {"folders": "Folders", "documents": "Documents", "objects": "Objects"}[what]
        return self.feed(q, f"{b}/folders/{folder.id}/{what}", f"{title} under folder {folder.id}", kids)

    def parent_links(self, obj, parent, q):
        b = self.repo_base()
        o = f"{b}/objects/{obj.id}/parent-links"
        parents = obj.attrs.get("i_folder_id") or []

        def link(fid):
            return {"href": f"{o}/{fid}", "parent-id": fid, "child-id": obj.id,
                    "links": [{"rel": "self", "href": f"{o}/{fid}"}, {"rel": "parent", "href": f"{b}/folders/{fid}"},
                              {"rel": "child", "href": f"{b}/objects/{obj.id}"}]}
        if parent is not None:
            if parent not in parents:
                raise RequestError(404, "E_RESOURCE_NOT_FOUND", f"{obj.id} is not linked into {parent}.")
            return link(parent)
        page, size = self.paging(q)
        chunk = parents[(page - 1) * size: page * size]
        inline = q.get("inline", "false") == "true"
        out = {"id": o, "title": f"Parent folder links of {obj.id}", "author": [{"name": "Open Text Documentum"}],
               "updated": feed_time(), "page": page, "items-per-page": size, "links": [{"rel": "self", "href": o}]}
        if chunk:
            out["entries"] = [{"id": f"{o}/{fid}", "title": fid, "updated": feed_time(),
                               "links": [{"rel": "edit", "href": f"{o}/{fid}"}],
                               "content": link(fid) if inline else {"type": DM_JSON, "src": f"{o}/{fid}"}}
                              for fid in chunk]
        return out

    # ----- permissions

    @staticmethod
    def xpermit_names(bits):
        return ",".join(n for bit, n in XPERMITS if bits & bit)

    def permission_set(self, obj):
        """The object's permission set as the REST server renders it: per accessor, the basic permission, extended
        permissions and application permission granted (permitted) or denied (restricted), and the required groups."""
        b = self.repo_base()
        acl = STORE.acl(obj.attrs["acl_domain"], obj.attrs["acl_name"])
        out = {"permitted": [], "restricted": [], "required-group": [], "required-group-set": []}
        if acl is not None:
            a = acl.attrs
            merged = {}
            for i, accessor in enumerate(a["r_accessor_name"]):
                ptype = a["r_permit_type"][i]
                if ptype == REQUIRED_GROUP:
                    out["required-group"].append(accessor)
                    continue
                if ptype == REQUIRED_GROUP_SET:
                    out["required-group-set"].append(accessor)
                    continue
                side = "restricted" if ptype in (ACCESS_RESTRICTION, EXTENDED_RESTRICTION, APPLICATION_RESTRICTION) \
                    else "permitted"
                p = merged.setdefault((side, accessor), {"accessor": accessor, "basic-permission": None,
                                                        "extend-permissions": "", "application-permission": ""})
                if ptype in (ACCESS_PERMIT, ACCESS_RESTRICTION):
                    p["basic-permission"] = PERMITS[a["r_accessor_permit"][i]].capitalize()
                xnames = self.xpermit_names(a["r_accessor_xpermit"][i])
                if xnames:
                    p["extend-permissions"] = ",".join(filter(None, [p["extend-permissions"], xnames]))
                if ptype in (APPLICATION_PERMIT, APPLICATION_RESTRICTION):
                    p["application-permission"] = a["r_application_permit"][i]
            for (side, _), p in merged.items():
                out[side].append(p)
        out["links"] = [{"rel": "self", "href": f"{b}/objects/{obj.id}/permission-set"}]
        if acl is not None:
            out["links"].append({"rel": LINKREL + "acl", "href": f"{b}/acls/{acl.id}"})
        return out

    def permissions(self, obj, accessor):
        """The basic permission an accessor (a user or a group) has on the object, as Documentum evaluates it."""
        principal = STORE.user(accessor)
        if principal is None:
            raise RequestError(404, "E_USER_NOT_FOUND", f"The user or group {accessor} does not exist.")
        permit = STORE.permit(principal.attrs["user_name"], obj)
        return {"accessor": principal.attrs["user_name"], "basic-permission": PERMITS[permit].capitalize(),
                "extend-permissions": "", "application-permission": "",
                "links": [{"rel": "self", "href": f"{self.repo_base()}/objects/{obj.id}/permissions?"
                                                  + urlencode({"accessor": principal.attrs["user_name"]})}]}

    def dql(self, q, user):
        self.count("dql")
        if self.apply_fault("dql"):
            return
        text = q["dql"]
        page, size = self.paging(q)
        with STORE.lock:
            STORE.stats["dql"].append(text)
            del STORE.stats["dql"][:-500]
        query = Query(text)
        rows = query.run(user)
        chunk = rows[(page - 1) * size: page * size]
        b = self.repo_base()

        def page_href(p):
            return b + "?" + urlencode({"dql": text, "page": p, "items-per-page": size})
        links = [{"rel": "self", "href": page_href(page)}, {"rel": "first", "href": page_href(1)}]
        # REST Services pages with RETURN_RANGE up to page * items-per-page + 1 (PagedQueryTemplate), so it knows whether
        # another page follows: a full last page has no "next"
        if len(rows) > page * size:
            links.append({"rel": "next", "href": page_href(page + 1)})
        if page > 1:
            links.append({"rel": "previous", "href": page_href(page - 1)})
        feed = {"id": page_href(page), "title": "DQL query results", "author": [{"name": "Open Text Documentum"}],
                "updated": feed_time(), "page": page, "items-per-page": size, "links": links}
        if chunk:  # an empty page has no "entries" at all
            entries = []
            for i, (obj, props) in enumerate(chunk):
                entry_links = [{"rel": "edit", "href": f"{b}/objects/{obj.id}"}] if "r_object_id" in props else []
                entries.append({"id": f"{page_href(page)}&index={i}", "title": props.get("r_object_id", "Result"),
                                "author": [{"name": "Open Text Documentum"}], "updated": feed_time(),
                                "published": rest_time(utcnow()), "links": entry_links,
                                "content": {"json-root": "query-result", "definition": f"{b}/types/{obj.type}",
                                            "properties": props, "links": entry_links}})
            feed["entries"] = entries
        self.send(200, feed)

    def content_props(self, obj, c):
        mime, ext, fmt_suffix = FORMATS[c["format"]]
        return {"object_name": obj.attrs["object_name"], "r_object_id": c["id"], "rendition": 1 if c["rendition"] else 0,
                "full_format": c["format"], "format": f"27{DOCBASE_ID}80000{fmt_suffix}",
                "full_content_size": float(len(c["bytes"])), "set_time": rest_time(c["set_time"]), "r_content_hash": "",
                "i_vstamp": 0, "mime_type": mime, "dos_extension": ext, "format_name": c["format"],
                "parent_id": [obj.id], "page": [c["page"]], "page_modifier": [None]}

    def find_content(self, obj, fmt, page):
        for c in obj.contents:
            if c["page"] != page:
                continue
            if fmt is None and not c["rendition"]:
                return c
            if fmt is not None and c["format"] == fmt:
                return c
        return None

    def content_links(self, obj, c, policy):
        b = self.repo_base()
        self_href = f"{b}/objects/{obj.id}/contents/content?format={c['format']}&modifier=&page={c['page']}"
        local = f"{b}/objects/{obj.id}/content-media?format={c['format']}&modifier=&page={c['page']}"
        acs = (f"http://{ACS_HOST}/ACS/servlet/ACS?command=read&version=2.3&docbaseid={DOCBASE_ID}&objectid={obj.id}"
               f"&format={c['format']}&pagenum={c['page']}&signature={secrets.token_urlsafe(24)}&expire_delta=360")
        links = [{"rel": "self", "href": self_href}]
        if policy == "local":
            links.append({"rel": "enclosure", "title": "LOCAL", "href": local})
        elif policy == "all":
            links += [{"rel": "enclosure", "title": "ACS", "href": acs}, {"rel": "enclosure", "title": "LOCAL", "href": local}]
        elif policy == "dc-only":
            links.append({"rel": "enclosure", "title": "ACS", "href": acs})
        else:  # dc-pref, the default: the distributed content (ACS) URL when ACS is available
            links.append({"rel": "enclosure", "title": "ACS", "href": acs})
        return links

    def content_meta(self, obj, q):
        fmt = q.get("format") or None
        try:
            page = int(q.get("page") or "0")
        except ValueError:
            return self.fail(400, "E_INPUT_ILLEGAL_ARGUMENTS", "page must be an integer.")
        c = self.find_content(obj, fmt, page)
        if c is None:
            return self.fail(404, "E_CONTENT_NOT_FOUND", f"The object {obj.id} has no content"
                             + (f" in format {fmt}" if fmt else "") + f" at page {page}.")
        policy = (q.get("media-url-policy") or "dc-pref").lower()
        self.send(200, {"name": "content", "properties": self.content_props(obj, c),
                        "links": self.content_links(obj, c, policy)})

    def contents_feed(self, obj, q):
        b = self.repo_base()
        policy = (q.get("media-url-policy") or "dc-pref").lower()
        inline = q.get("inline", "false") == "true"
        entries = []
        for c in obj.contents:
            href = f"{b}/objects/{obj.id}/contents/content?format={c['format']}&modifier=&page={c['page']}"
            entry = {"id": href, "title": c["format"], "updated": rest_time(c["set_time"]),
                     "links": [{"rel": "edit", "href": href}]}
            if inline:
                entry["content"] = {"name": "content", "properties": self.content_props(obj, c),
                                    "links": self.content_links(obj, c, policy)}
            else:
                entry["content"] = {"type": DM_JSON, "src": href}
            entries.append(entry)
        return {"id": f"{b}/objects/{obj.id}/contents", "title": "Contents", "updated": feed_time(),
                "page": 1, "items-per-page": 100, "links": [{"rel": "self", "href": f"{b}/objects/{obj.id}/contents"}],
                "entries": entries}

    def content_media(self, obj, q):
        self.count("content-media")
        if self.apply_fault("download"):
            return
        c = self.find_content(obj, q.get("format") or None, int(q.get("page") or "0"))
        if c is None:
            return self.fail(404, "E_CONTENT_NOT_FOUND", f"The object {obj.id} has no such content.")
        if CONTENT_REDIRECT and q.get("redirected") != "true":
            target = f"{self.repo_base()}/objects/{obj.id}/content-media?" + urlencode({**q, "redirected": "true"})
            return self.send(302, b"", content_type="text/plain", headers={"Location": target})
        with STORE.lock:
            STORE.stats["downloads"] += 1
        mime = FORMATS[c["format"]][0]
        name = f"{obj.attrs['object_name']}.{FORMATS[c['format']][1]}"
        self.send(200, c["bytes"], content_type=mime,
                  headers={"Content-Disposition": f"attachment; filename=\"{quote(name)}\""})

    def oauth(self):
        form = {k: v[-1] for k, v in parse_qs(self.body().decode()).items()}
        cid, secret = form.get("client_id"), form.get("client_secret")
        auth = self.headers.get("Authorization") or ""
        if auth.startswith("Basic "):
            cid, _, secret = base64.b64decode(auth[6:]).decode().partition(":")
        if form.get("grant_type") != "client_credentials" or OAUTH_CLIENTS.get(cid) != secret or not secret:
            return self.send(401, {"error": "invalid_client"}, content_type="application/json")
        token = secrets.token_urlsafe(32)
        with STORE.lock:
            STORE.tokens[token] = (OAUTH_USER, time.time() + 3600)
        self.count("oauth")
        self.send(200, {"access_token": token, "token_type": "Bearer", "expires_in": 3600},
                  content_type="application/json")

    # ----- admin (mock-only)

    def admin_get(self, path, q):
        with STORE.lock:
            if path == "/mock/stats":
                return self.send(200, {"requests": STORE.stats["requests"], "authFailures": STORE.stats["authFailures"],
                                       "downloads": STORE.stats["downloads"], "dql": STORE.stats["dql"][-50:],
                                       "objects": len(STORE.objects),
                                       "documents": len(STORE.of_type("dm_document"))}, content_type="application/json")
            if path == "/mock/documents":
                return self.send(200, [self.describe(c) for c in self.chronicles()], content_type="application/json")
            m = re.match(r"^/mock/documents/([0-9a-f]{16})$", path)
            if m:
                if not STORE.chain(m.group(1)):
                    return self.send(404, {"error": "unknown chronicle"}, content_type="application/json")
                return self.send(200, self.describe(m.group(1), text=True), content_type="application/json")
            if path == "/mock/access":
                u = STORE.user(q.get("user", ""))  # a user name or a login name
                if u is None:
                    return self.send(404, {"error": "unknown user"}, content_type="application/json")
                user = u.attrs["user_name"]
                readable = {}
                for o in STORE.of_type("dm_document"):
                    readable[o.id] = STORE.permit(user, o) >= 3
                return self.send(200, {"user": user, "login": u.attrs["user_login_name"],
                                       "groups": sorted(STORE.groups_of(user)), "readable": readable},
                                 content_type="application/json")
            if path == "/mock/principals":
                users = [{"userName": u.attrs["user_name"], "login": u.attrs["user_login_name"],
                          "domain": u.attrs["user_login_domain"],
                          "groups": sorted(STORE.groups_of(u.attrs["user_name"])),
                          "superuser": bool(u.attrs["user_privileges"] & SUPERUSER)}
                         for u in STORE.of_type("dm_user") if not u.attrs["r_is_group"]]
                return self.send(200, users, content_type="application/json")
        self.send(404, {"error": "unknown admin endpoint"}, content_type="application/json")

    @staticmethod
    def chronicles():
        seen = []
        for o in STORE.of_type("dm_document"):
            if o.attrs["i_chronicle_id"] not in seen:
                seen.append(o.attrs["i_chronicle_id"])
        return seen

    def describe(self, chronicle, text=False):
        versions = STORE.chain(chronicle)
        cur = STORE.current(chronicle)
        folders = []
        for fid in (cur or versions[-1]).attrs["i_folder_id"]:
            f = STORE.objects.get(fid)
            if f:
                folders += f.attrs["r_folder_path"]
        out = {"chronicleId": chronicle, "type": versions[-1].type,
               "currentId": cur.id if cur else None, "objectName": (cur or versions[-1]).attrs["object_name"],
               "acl": [(cur or versions[-1]).attrs["acl_domain"], (cur or versions[-1]).attrs["acl_name"]],
               "owner": (cur or versions[-1]).attrs["owner_name"], "group": (cur or versions[-1]).attrs["group_name"],
               "folders": folders,
               "versions": [{"id": v.id, "labels": v.attrs["r_version_label"], "modified": rest_time(v.attrs["r_modify_date"]),
                             "vstamp": v.attrs["i_vstamp"], "formats": [c["format"] for c in v.contents],
                             "contentSize": v.attrs["r_content_size"]}
                            for v in versions]}
        if text:
            out["text"] = {v.id: [c["text"] for c in v.contents] for v in versions}
        return out

    def admin_post(self, path):
        try:
            return self.admin_change(path)
        finally:
            with STORE.lock:
                STORE.refresh_permits()

    def admin_change(self, path):
        try:
            data = json.loads(self.body() or b"{}")
        except ValueError:
            return self.send(400, {"error": "invalid JSON"}, content_type="application/json")
        with STORE.lock:
            if path == "/mock/reset":
                configure(None)
                STORE.reset()
                return self.ok({"reset": True})
            if path == "/mock/config":
                try:
                    configure(data)
                except (ValueError, SystemExit) as e:
                    return self.send(400, {"error": str(e)}, content_type="application/json")
                return self.ok({k: str(globals()[k]) for k in RUNTIME})
            if path == "/mock/faults":
                if data.get("clear"):
                    STORE.faults = []
                else:
                    STORE.faults.append({"op": data.get("op", "*"), "status": int(data.get("status", 503)),
                                         "code": data.get("code", "E_SERVICE_UNAVAILABLE"),
                                         "remaining": int(data.get("count", 1)), "retryAfter": data.get("retryAfter")})
                return self.ok({"faults": STORE.faults})
            if path == "/mock/audit":
                STORE.audit = bool(data.get("enabled", True))
                return self.ok({"audit": STORE.audit})
            if path == "/mock/expire-tokens":
                STORE.tokens = {}
                return self.ok({"expired": True})
            if path == "/mock/purge-audit":
                for a in STORE.of_type("dm_audittrail"):
                    del STORE.objects[a.id]
                return self.ok({"purged": True})
            if path == "/mock/documents":
                fmt = data.get("format", "crtext")
                if fmt not in FORMATS:
                    return self.send(400, {"error": f"format must be one of {sorted(FORMATS)}"},
                                     content_type="application/json")
                type_ = data.get("type", "dm_document")
                if type_ not in TYPES or not is_subtype(type_, "dm_document"):
                    return self.send(400, {"error": "unknown document type"}, content_type="application/json")
                obj = STORE.create_document(type_, data.get("owner", "John Doe"),
                                            data.get("folders", ["/Corporate/Contracts"]), int(data.get("pattern", 1)),
                                            fmt, data.get("text", ""))
                return self.ok(self.describe(obj.attrs["i_chronicle_id"]))
            m = re.match(r"^/mock/documents/([0-9a-f]{16})/(checkin|destroy|update|acl|link|unlink)$", path)
            if m:
                chronicle, action = m.group(1), m.group(2)
                cur = STORE.current(chronicle)
                if cur is None:
                    return self.send(404, {"error": "unknown chronicle"}, content_type="application/json")
                if action == "checkin":
                    STORE.checkin(chronicle, major=bool(data.get("major", True)), text=data.get("text", ""))
                elif action == "destroy":
                    STORE.destroy_chronicle(chronicle, current_only=bool(data.get("currentVersionOnly", False)))
                    return self.ok({"destroyed": chronicle})
                elif action == "update":
                    for k, v in data.get("attributes", {}).items():
                        if attribute(cur.type, k) is None:
                            return self.send(400, {"error": f"unknown attribute {k}"}, content_type="application/json")
                        cur.attrs[k] = v
                    STORE.touch(cur)
                elif action == "acl":
                    if STORE.acl(data["domain"], data["name"]) is None:
                        return self.send(400, {"error": "unknown ACL"}, content_type="application/json")
                    cur.attrs.update(acl_domain=data["domain"], acl_name=data["name"])
                    STORE.touch(cur)
                elif action in ("link", "unlink"):
                    folder = STORE.folder_by_path(data["folder"])
                    if folder is None:
                        return self.send(400, {"error": "unknown folder"}, content_type="application/json")
                    ids = cur.attrs["i_folder_id"]
                    cur.attrs["i_folder_id"] = ids + [folder.id] if action == "link" else [i for i in ids if i != folder.id]
                    STORE.touch(cur)
                return self.ok(self.describe(chronicle))
            m = re.match(r"^/mock/acls/([^/]+)/([^/]+)$", path)
            if m:  # edit a permission set in place: only the ACL changes (i_vstamp), not the documents using it
                acl = STORE.acl(m.group(1), m.group(2))
                if acl is None:
                    return self.send(404, {"error": "unknown ACL"}, content_type="application/json")
                entries = [(e["name"], int(e.get("permit", 3)), int(e.get("permitType", 0)), bool(e.get("isGroup", False)),
                            int(e.get("xpermit", 0)), e.get("applicationPermit", "")) for e in data["entries"]]
                fresh = STORE.make_acl(acl.attrs["owner_name"], acl.attrs["object_name"], entries)
                del STORE.objects[fresh.id]
                for k in ("r_accessor_name", "r_accessor_permit", "r_permit_type", "r_is_group", "r_accessor_xpermit",
                          "r_application_permit", "i_has_required_groups", "i_has_required_group_set",
                          "i_has_access_restrictions"):
                    acl.attrs[k] = fresh.attrs[k]
                acl.attrs["i_vstamp"] += 1
                return self.ok({"acl": json_properties(acl)})
            if path == "/mock/folders/move":
                folder, parent = STORE.folder_by_path(data.get("folder", "")), STORE.folder_by_path(data.get("to", ""))
                if folder is None or parent is None or folder.type == "dm_cabinet" \
                        or folder.id in parent.attrs["i_ancestor_id"]:
                    return self.send(400, {"error": "folder and to must be existing folders, to not below folder"},
                                     content_type="application/json")
                STORE.move_folder(folder, parent)
                return self.ok({"folder": json_properties(folder, ["r_object_id", "r_folder_path", "r_modify_date"])})
            m = re.match(r"^/mock/groups/([^/]+)$", path)
            if m:
                g = next((x for x in STORE.of_type("dm_group") if x.attrs["group_name"] == m.group(1)), None)
                if g is None:
                    return self.send(404, {"error": "unknown group"}, content_type="application/json")
                g.attrs["users_names"] = data.get("users", g.attrs["users_names"])
                g.attrs["i_vstamp"] += 1
                return self.ok({"group": json_properties(g)})
        self.send(404, {"error": "unknown admin endpoint"}, content_type="application/json")

    def ok(self, body):
        self.send(200, body, content_type="application/json")


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    sys.stderr.write(f"DCTM REST mock: repository '{REPO}' with {DOCUMENT_COUNT} documents on port {PORT}, "
                     f"server time zone {SERVER_TZ}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
