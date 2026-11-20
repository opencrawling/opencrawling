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

"""Tests verifying lossless round-trip of unknown fields in SystemSettings and TransportSettings."""

from __future__ import annotations

import json
from typing import Any

import httpx

from opencrawling import (
    AsyncOpenCrawlingClient,
    OpenCrawlingClient,
    RequestOptions,
    SystemSettings,
    TransportSettings,
)


def test_system_settings_preserves_unknown_server_fields_losslessly() -> None:
    server_state: dict[str, Any] = {
        "embeddingProvider": "ollama",
        "ollamaModel": "mxbai-embed-large",
        "chunkSize": 512,
        "tikaEngine": "tika4",
        "futureNestedConfig": {"threshold": 0.92, "flags": ["alpha", "beta"]},
        "futureExperimentalToggle": True,
        "futureExplicitNull": None,
    }
    sent_bodies: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET" and request.url.path == "/api/v1/settings/system":
            return httpx.Response(200, headers={"ETag": '"sys-v1"'}, json=server_state)
        if request.method == "PUT" and request.url.path == "/api/v1/settings/system":
            assert request.headers.get("if-match") == '"sys-v1"'
            body = json.loads(request.content.decode("utf-8"))
            sent_bodies.append(body)
            return httpx.Response(200, headers={"ETag": '"sys-v2"'}, json=body)
        return httpx.Response(404)

    client = OpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )
    current = client.settings.system()
    assert current.etag == '"sys-v1"'
    assert current.data.chunk_size == 512
    assert current.data.model_extra is not None
    assert current.data.model_extra["futureExperimentalToggle"] is True

    # Modify a single known field via with_updates and replace
    modified = current.data.with_updates(chunk_size=1024)
    updated = client.settings.replace_system(modified, RequestOptions.matching(current.etag or ""))
    assert updated.etag == '"sys-v2"'
    assert updated.data.chunk_size == 1024
    assert len(sent_bodies) == 1
    assert sent_bodies[0] == {
        "embeddingProvider": "ollama",
        "ollamaModel": "mxbai-embed-large",
        "chunkSize": 1024,
        "tikaEngine": "tika4",
        "futureNestedConfig": {"threshold": 0.92, "flags": ["alpha", "beta"]},
        "futureExperimentalToggle": True,
        "futureExplicitNull": None,
    }


def test_system_settings_in_place_mutation_preserves_unknown_fields() -> None:
    raw = {
        "chunkSize": 256,
        "brandNewServerKey": "preserve-me",
    }
    settings = SystemSettings.model_validate(raw)
    settings.chunk_size = 768
    payload = settings.to_payload()
    assert payload == {"chunkSize": 768, "brandNewServerKey": "preserve-me"}


async def test_async_transport_settings_preserves_unknown_fields_losslessly() -> None:
    sent_bodies: list[dict[str, Any]] = []
    server_transport: dict[str, Any] = {
        "grpcEnabled": True,
        "grpcHost": "oc-runtime",
        "grpcPort": 9095,
        "tlsEnabled": False,
        "keepAliveSeconds": 45,
        "maxInboundMessageBytes": 16_777_216,
    }

    async def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET":
            return httpx.Response(200, headers={"ETag": '"tr-v1"'}, json=server_transport)
        body = json.loads(request.content.decode("utf-8"))
        sent_bodies.append(body)
        return httpx.Response(200, headers={"ETag": '"tr-v2"'}, json=body)

    async with AsyncOpenCrawlingClient(
        "http://localhost:8080",
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    ) as client:
        current = await client.settings.transport()
        assert isinstance(current.data, TransportSettings)
        updated = await client.settings.replace_transport(
            current.data.with_updates(tls_enabled=True),
            RequestOptions.matching(current.etag or ""),
        )
        assert updated.data.tls_enabled is True
        assert sent_bodies[0]["keepAliveSeconds"] == 45
        assert sent_bodies[0]["maxInboundMessageBytes"] == 16_777_216
        assert sent_bodies[0]["tlsEnabled"] is True
