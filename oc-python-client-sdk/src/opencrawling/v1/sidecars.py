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
"""Platform API v1 OIS Sidecars resource clients (``/api/v1/sidecar-stores``)."""

from __future__ import annotations

from collections.abc import AsyncIterator, Iterator
from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_path
from opencrawling.v1.models import SidecarPage, SidecarStoreInfo


class SidecarsClient:
    """Synchronous client for ``/api/v1/sidecar-stores``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def stores(self) -> list[SidecarStoreInfo]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("sidecar-stores"))
        )
        parsed = self._transport.parse_json(response)
        if not isinstance(parsed, list):
            return []
        return [SidecarStoreInfo.model_validate(item) for item in parsed]

    def read(
        self,
        connector: str,
        *,
        prefix: str | None = None,
        limit: int | None = None,
        cursor: str | None = None,
    ) -> SidecarPage:
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("sidecar-stores", connector, "sidecars"),
                params={"prefix": prefix, "limit": limit, "cursor": cursor},
            )
        )
        return SidecarPage.model_validate(self._transport.parse_json(response))

    def iter(
        self,
        connector: str,
        *,
        prefix: str | None = None,
        limit: int | None = None,
    ) -> Iterator[dict[str, Any]]:
        """Lazily yield authorized OIS sidecar metadata documents across cursor pages."""
        cursor: str | None = None
        while True:
            page = self.read(connector, prefix=prefix, limit=limit, cursor=cursor)
            yield from page.items
            if not page.has_next:
                break
            cursor = page.next_cursor


class AsyncSidecarsClient:
    """Asynchronous client for ``/api/v1/sidecar-stores``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def stores(self) -> list[SidecarStoreInfo]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("sidecar-stores"))
        )
        parsed = self._transport.parse_json(response)
        if not isinstance(parsed, list):
            return []
        return [SidecarStoreInfo.model_validate(item) for item in parsed]

    async def read(
        self,
        connector: str,
        *,
        prefix: str | None = None,
        limit: int | None = None,
        cursor: str | None = None,
    ) -> SidecarPage:
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("sidecar-stores", connector, "sidecars"),
                params={"prefix": prefix, "limit": limit, "cursor": cursor},
            )
        )
        return SidecarPage.model_validate(self._transport.parse_json(response))

    async def iter(
        self,
        connector: str,
        *,
        prefix: str | None = None,
        limit: int | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        """Lazily yield authorized OIS sidecar metadata documents asynchronously."""
        cursor: str | None = None
        while True:
            page = await self.read(connector, prefix=prefix, limit=limit, cursor=cursor)
            for item in page.items:
                yield item
            if not page.has_next:
                break
            cursor = page.next_cursor

    aiter = iter
