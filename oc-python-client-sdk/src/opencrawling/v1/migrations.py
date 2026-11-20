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

"""Platform API v1 ManifoldCF Migration clients (``/api/v1/migrations/manifoldcf:*``)."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path


class MigrationsClient:
    """Synchronous client for ``/api/v1/migrations``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def plan_manifoldcf(self, request: Mapping[str, Any]) -> dict[str, Any]:
        """Plan an Apache ManifoldCF migration without modifying OpenCrawling state."""
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("migrations", "manifoldcf"), "plan"),
                json_body=dict(request),
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}

    def apply_manifoldcf(self, request: Mapping[str, Any]) -> dict[str, Any]:
        """Apply an Apache ManifoldCF migration plan in-process."""
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("migrations", "manifoldcf"), "apply"),
                json_body=dict(request),
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}


class AsyncMigrationsClient:
    """Asynchronous client for ``/api/v1/migrations``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def plan_manifoldcf(self, request: Mapping[str, Any]) -> dict[str, Any]:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("migrations", "manifoldcf"), "plan"),
                json_body=dict(request),
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}

    async def apply_manifoldcf(self, request: Mapping[str, Any]) -> dict[str, Any]:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("migrations", "manifoldcf"), "apply"),
                json_body=dict(request),
            )
        )
        parsed = self._transport.parse_json(response)
        return parsed if isinstance(parsed, dict) else {"result": parsed}
