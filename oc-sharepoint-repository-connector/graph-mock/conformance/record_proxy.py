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
A recording reverse proxy for another Graph-like server: every exchange is appended to a JSON-lines file in the format of
graph_mock.py's GRAPH_RECORD, so check_conformance.py can check a server that wasn't written by us. The Host header is
passed through, so the server builds its links (@odata.nextLink, download URLs) on the proxy's address and the client
stays behind the proxy.

--token VALUE also answers the Entra client-credentials token endpoint (POST /<tenant>/oauth2/v2.0/token) with that access
token, for servers that accept a fixed bearer and have no token endpoint. --download-pattern marks download requests.
Standard library only.
"""

import argparse
import http.client
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers", "transfer-encoding", "upgrade"}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--upstream", required=True, help="e.g. http://127.0.0.1:18280")
    parser.add_argument("--record", required=True)
    parser.add_argument("--token", help="answer the token endpoint with this access token")
    parser.add_argument("--download-pattern", default=r"^/_download/|/_layouts/15/download\.aspx")
    args = parser.parse_args()
    upstream = urlsplit(args.upstream)
    lock = threading.Lock()
    download = re.compile(args.download_pattern)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *a):
            pass

        def _record(self, kind, status, headers, body, content_type):
            parsed = None
            if body and "json" in (content_type or ""):
                try:
                    parsed = json.loads(body)
                except ValueError:
                    parsed = None
            entry = {"method": self.command, "url": self.path, "host": self.headers.get("Host"), "status": status,
                     "kind": kind, "authorization": bool(self.headers.get("Authorization")), "contentType": content_type,
                     "clientRequestId": self.headers.get("client-request-id"),
                     "headers": {k: v for k, v in headers.items() if k in ("Location", "Retry-After")}, "body": parsed}
            with lock, open(args.record, "a", encoding="utf-8") as out:
                out.write(json.dumps(entry) + "\n")

        def _reply(self, status, headers, body):
            self.send_response(status)
            for k, v in headers.items():
                if k.lower() not in HOP and k.lower() != "content-length":
                    self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _handle(self):
            length = int(self.headers.get("Content-Length") or 0)
            request_body = self.rfile.read(length) if length else None
            path = urlsplit(self.path).path
            if args.token and self.command == "POST" and re.fullmatch(r"/[^/]+/oauth2/v2\.0/token", path):
                body = json.dumps({"token_type": "Bearer", "expires_in": 3599, "ext_expires_in": 3599,
                                   "access_token": args.token}).encode()
                self._record("token", 200, {}, body, "application/json")
                return self._reply(200, {"Content-Type": "application/json"}, body)
            connection = http.client.HTTPConnection(upstream.hostname, upstream.port, timeout=120)
            headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP}
            connection.request(self.command, self.path, body=request_body, headers=headers)
            response = connection.getresponse()
            body = response.read()
            response_headers = {k: v for k, v in response.getheaders()}
            connection.close()
            kind = "download" if download.search(path) else ("graph" if path.startswith("/v1.0/") else "other")
            self._record(kind, response.status, response_headers, body, response.getheader("Content-Type"))
            self._reply(response.status, response_headers, body)

        do_GET = do_POST = do_DELETE = do_PUT = do_PATCH = _handle

    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
