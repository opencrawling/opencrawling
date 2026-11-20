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

"""Platform API v1 Auto-Narrativization Copilot clients."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path
from opencrawling.v1.models import CopilotRequest, CopilotResponse


def _dump_copilot_request(request: CopilotRequest | Mapping[str, Any]) -> dict[str, Any]:
    if isinstance(request, CopilotRequest):
        return request.model_dump(mode="json", by_alias=True, exclude_none=True)
    return dict(request)


class NarrativizationClient:
    """Synchronous client for ``/api/v1/narrativization:generate-template``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def generate_template(self, request: CopilotRequest | Mapping[str, Any]) -> CopilotResponse:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("narrativization"), "generate-template"),
                json_body=_dump_copilot_request(request),
            )
        )
        return CopilotResponse.model_validate(self._transport.parse_json(response))


class AsyncNarrativizationClient:
    """Asynchronous client for ``/api/v1/narrativization:generate-template``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def generate_template(self, request: CopilotRequest | Mapping[str, Any]) -> CopilotResponse:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("narrativization"), "generate-template"),
                json_body=_dump_copilot_request(request),
            )
        )
        return CopilotResponse.model_validate(self._transport.parse_json(response))
