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

"""Platform API v1 Operations & Observability clients."""

from __future__ import annotations

from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_path
from opencrawling.v1.models import SystemStatus, ThroughputMetricsResponse


class OperationsClient:
    """Synchronous client for ``/api/v1/system/*`` and ``/api/v1/observability/metrics``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def status(self) -> SystemStatus:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("system", "status"))
        )
        return SystemStatus.model_validate(self._transport.parse_json(response))

    def throughput(self) -> list[dict[str, Any]]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("system", "throughput"))
        )
        parsed = self._transport.parse_json(response)
        return [dict(item) for item in parsed] if isinstance(parsed, list) else []

    def logs(self) -> list[str]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("system", "logs"))
        )
        parsed = self._transport.parse_json(response)
        return [str(line) for line in parsed] if isinstance(parsed, list) else []

    def metrics(self, connector_id: str | None = None) -> ThroughputMetricsResponse:
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("observability", "metrics"),
                params={"connectorId": connector_id},
            )
        )
        return ThroughputMetricsResponse.model_validate(self._transport.parse_json(response))


class AsyncOperationsClient:
    """Asynchronous client for ``/api/v1/system/*`` and ``/api/v1/observability/metrics``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def status(self) -> SystemStatus:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("system", "status"))
        )
        return SystemStatus.model_validate(self._transport.parse_json(response))

    async def throughput(self) -> list[dict[str, Any]]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("system", "throughput"))
        )
        parsed = self._transport.parse_json(response)
        return [dict(item) for item in parsed] if isinstance(parsed, list) else []

    async def logs(self) -> list[str]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("system", "logs"))
        )
        parsed = self._transport.parse_json(response)
        return [str(line) for line in parsed] if isinstance(parsed, list) else []

    async def metrics(self, connector_id: str | None = None) -> ThroughputMetricsResponse:
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("observability", "metrics"),
                params={"connectorId": connector_id},
            )
        )
        return ThroughputMetricsResponse.model_validate(self._transport.parse_json(response))
