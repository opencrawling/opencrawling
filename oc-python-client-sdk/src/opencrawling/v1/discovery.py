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

"""Platform API v1 Repository Discovery clients (``/api/v1/discovery/connectors``)."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path
from opencrawling.v1.models import (
    DiscoveryConnectorInfo,
    DiscoveryDryRunRequest,
    DiscoveryDryRunView,
    DiscoveryTreeView,
)


def _dump_dry_run_request(request: DiscoveryDryRunRequest | Mapping[str, Any] | None) -> dict[str, Any]:
    if request is None:
        return {}
    if isinstance(request, DiscoveryDryRunRequest):
        return request.model_dump(mode="json", by_alias=True, exclude_none=True)
    return dict(request)


class DiscoveryClient:
    """Synchronous client for ``/api/v1/discovery/connectors``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def connectors(self) -> list[DiscoveryConnectorInfo]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("discovery", "connectors"))
        )
        parsed = self._transport.parse_json(response)
        if not isinstance(parsed, list):
            return []
        return [DiscoveryConnectorInfo.model_validate(item) for item in parsed]

    def tree(
        self,
        connector: str,
        *,
        start: str | None = None,
        depth: int | None = None,
        max_nodes: int | None = None,
        include_documents: bool | None = None,
    ) -> DiscoveryTreeView:
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("discovery", "connectors", connector, "tree"),
                params={
                    "start": start,
                    "depth": depth,
                    "maxNodes": max_nodes,
                    "includeDocuments": include_documents,
                },
            )
        )
        return DiscoveryTreeView.model_validate(self._transport.parse_json(response))

    def dry_run(
        self,
        connector: str,
        request: DiscoveryDryRunRequest | Mapping[str, Any] | None = None,
    ) -> DiscoveryDryRunView:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("discovery", "connectors", connector), "dry-run"),
                json_body=_dump_dry_run_request(request),
            )
        )
        return DiscoveryDryRunView.model_validate(self._transport.parse_json(response))


class AsyncDiscoveryClient:
    """Asynchronous client for ``/api/v1/discovery/connectors``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def connectors(self) -> list[DiscoveryConnectorInfo]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("discovery", "connectors"))
        )
        parsed = self._transport.parse_json(response)
        if not isinstance(parsed, list):
            return []
        return [DiscoveryConnectorInfo.model_validate(item) for item in parsed]

    async def tree(
        self,
        connector: str,
        *,
        start: str | None = None,
        depth: int | None = None,
        max_nodes: int | None = None,
        include_documents: bool | None = None,
    ) -> DiscoveryTreeView:
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("discovery", "connectors", connector, "tree"),
                params={
                    "start": start,
                    "depth": depth,
                    "maxNodes": max_nodes,
                    "includeDocuments": include_documents,
                },
            )
        )
        return DiscoveryTreeView.model_validate(self._transport.parse_json(response))

    async def dry_run(
        self,
        connector: str,
        request: DiscoveryDryRunRequest | Mapping[str, Any] | None = None,
    ) -> DiscoveryDryRunView:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("discovery", "connectors", connector), "dry-run"),
                json_body=_dump_dry_run_request(request),
            )
        )
        return DiscoveryDryRunView.model_validate(self._transport.parse_json(response))
