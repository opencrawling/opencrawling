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

"""Test harness helpers for sending HMAC-SHA256 signed requests to OpenCrawling hook services."""

from __future__ import annotations

import asyncio
import json
from collections.abc import Mapping
from typing import Any

import httpx

from opencrawling.hooks.models import (
    Decision,
    EnrichmentRequest,
    EnrichmentResponse,
    GovernanceRequest,
)
from opencrawling.hooks.signing import build_signed_headers


def _serialize_hook_payload(
    payload: GovernanceRequest | EnrichmentRequest | Mapping[str, Any] | bytes | str,
) -> bytes:
    if isinstance(payload, bytes):
        return payload
    if isinstance(payload, str):
        return payload.encode("utf-8")
    if isinstance(payload, (GovernanceRequest, EnrichmentRequest)):
        return payload.model_dump_json(by_alias=True, exclude_none=True).encode("utf-8")
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


class AsyncSignedHookTestClient:
    """Async test helper that signs and dispatches OpenCrawling hook requests against an ASGI app or URL."""

    def __init__(
        self,
        *,
        secret: str,
        app: Any | None = None,
        base_url: str = "http://testserver",
        http_client: httpx.AsyncClient | None = None,
        enforce_min_length: bool = True,
    ) -> None:
        self._secret = secret
        self._enforce_min_length = enforce_min_length
        if http_client is not None:
            self._client = http_client
            self._owns_client = False
        elif app is not None:
            self._client = httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app),
                base_url=base_url,
            )
            self._owns_client = True
        else:
            self._client = httpx.AsyncClient(base_url=base_url)
            self._owns_client = True

    async def post_signed(
        self,
        path: str,
        payload: GovernanceRequest | EnrichmentRequest | Mapping[str, Any] | bytes | str,
        *,
        timestamp: int | None = None,
        secret: str | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> httpx.Response:
        """Send a signed ``POST`` request with ``X-OpenCrawling-Timestamp`` and ``X-OpenCrawling-Signature``."""
        raw = _serialize_hook_payload(payload)
        req_headers = {
            "Content-Type": "application/json",
            **build_signed_headers(
                raw,
                secret if secret is not None else self._secret,
                timestamp=timestamp,
                enforce_min_length=self._enforce_min_length,
            ),
        }
        if headers:
            req_headers.update(headers)
        return await self._client.post(path, content=raw, headers=req_headers)

    async def post_governance(
        self,
        payload: GovernanceRequest | Mapping[str, Any],
        *,
        path: str = "/governance",
        timestamp: int | None = None,
    ) -> Decision:
        """Post a signed governance request and parse the ``200 OK`` ``Decision`` response."""
        response = await self.post_signed(path, payload, timestamp=timestamp)
        response.raise_for_status()
        return Decision.model_validate(response.json())

    async def post_enrichment(
        self,
        payload: EnrichmentRequest | Mapping[str, Any],
        *,
        path: str = "/enrich",
        timestamp: int | None = None,
    ) -> EnrichmentResponse:
        """Post a signed enrichment request and parse the ``200 OK`` ``EnrichmentResponse``."""
        response = await self.post_signed(path, payload, timestamp=timestamp)
        response.raise_for_status()
        return EnrichmentResponse.model_validate(response.json())

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def __aenter__(self) -> AsyncSignedHookTestClient:
        return self

    async def __aexit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        await self.aclose()


class SignedHookTestClient:
    """Synchronous test helper for sending signed OpenCrawling hook requests to an ASGI app or URL."""

    def __init__(
        self,
        *,
        secret: str,
        app: Any | None = None,
        base_url: str = "http://testserver",
        http_client: httpx.Client | None = None,
        enforce_min_length: bool = True,
    ) -> None:
        self._secret = secret
        self._app = app
        self._base_url = base_url
        self._enforce_min_length = enforce_min_length
        self._http_client = http_client

    def post_signed(
        self,
        path: str,
        payload: GovernanceRequest | EnrichmentRequest | Mapping[str, Any] | bytes | str,
        *,
        timestamp: int | None = None,
        secret: str | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> httpx.Response:
        """Send a signed ``POST`` request synchronously."""
        if self._app is not None:

            async def _dispatch() -> httpx.Response:
                async with AsyncSignedHookTestClient(
                    secret=self._secret,
                    app=self._app,
                    base_url=self._base_url,
                    enforce_min_length=self._enforce_min_length,
                ) as async_client:
                    return await async_client.post_signed(
                        path,
                        payload,
                        timestamp=timestamp,
                        secret=secret,
                        headers=headers,
                    )

            return asyncio.run(_dispatch())

        raw = _serialize_hook_payload(payload)
        req_headers = {
            "Content-Type": "application/json",
            **build_signed_headers(
                raw,
                secret if secret is not None else self._secret,
                timestamp=timestamp,
                enforce_min_length=self._enforce_min_length,
            ),
        }
        if headers:
            req_headers.update(headers)
        if self._http_client is not None:
            return self._http_client.post(path, content=raw, headers=req_headers)
        with httpx.Client(base_url=self._base_url) as client:
            return client.post(path, content=raw, headers=req_headers)

    def post_governance(
        self,
        payload: GovernanceRequest | Mapping[str, Any],
        *,
        path: str = "/governance",
        timestamp: int | None = None,
    ) -> Decision:
        """Post a signed governance request and parse the ``200 OK`` ``Decision`` response."""
        response = self.post_signed(path, payload, timestamp=timestamp)
        response.raise_for_status()
        return Decision.model_validate(response.json())

    def post_enrichment(
        self,
        payload: EnrichmentRequest | Mapping[str, Any],
        *,
        path: str = "/enrich",
        timestamp: int | None = None,
    ) -> EnrichmentResponse:
        """Post a signed enrichment request and parse the ``200 OK`` ``EnrichmentResponse``."""
        response = self.post_signed(path, payload, timestamp=timestamp)
        response.raise_for_status()
        return EnrichmentResponse.model_validate(response.json())
