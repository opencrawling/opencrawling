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

"""Platform API v1 Dynamic Jobs resource clients (``/api/v1/dynamic-jobs``)."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path
from opencrawling.config import RequestOptions
from opencrawling.pagination import AsyncPageIterator, Page, PageRequest, SyncPageIterator
from opencrawling.v1.models import (
    DocumentIngestRequest,
    DynamicJob,
    DynamicJobAccepted,
    DynamicJobRequest,
)


def _dump_dynamic_request(
    request: DynamicJobRequest | DocumentIngestRequest | Mapping[str, Any],
) -> dict[str, Any]:
    if isinstance(request, (DynamicJobRequest, DocumentIngestRequest)):
        return request.model_dump(mode="json", by_alias=True, exclude_none=True)
    return dict(request)


class DynamicJobsClient:
    """Synchronous client for ``/api/v1/dynamic-jobs``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def start(
        self,
        request: DynamicJobRequest | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> DynamicJobAccepted:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_path("dynamic-jobs"),
                json_body=_dump_dynamic_request(request),
                options=options,
            )
        )
        return DynamicJobAccepted.model_validate(self._transport.parse_json(response))

    def ingest_document(
        self,
        request: DocumentIngestRequest | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> DynamicJobAccepted:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("dynamic-jobs"), "ingest-document"),
                json_body=_dump_dynamic_request(request),
                options=options,
            )
        )
        return DynamicJobAccepted.model_validate(self._transport.parse_json(response))

    def list(self, page: PageRequest | None = None) -> Page[DynamicJob]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("dynamic-jobs"),
                params={"limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[DynamicJob].model_validate(self._transport.parse_json(response))

    def iter(self, *, page_size: int | None = None) -> SyncPageIterator[DynamicJob]:
        return SyncPageIterator(lambda req: self.list(page=req), page_size=page_size)

    def get(self, job_id: str) -> DynamicJob:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("dynamic-jobs", job_id))
        )
        return DynamicJob.model_validate(self._transport.parse_json(response))

    def stop(self, job_id: str) -> DynamicJob:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("dynamic-jobs", job_id), "stop"))
        )
        return DynamicJob.model_validate(self._transport.parse_json(response))

    def approve(self, job_id: str) -> DynamicJob:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("dynamic-jobs", job_id), "approve"))
        )
        return DynamicJob.model_validate(self._transport.parse_json(response))

    def reject(self, job_id: str, reason: str | None = None) -> DynamicJob:
        body = {"reason": reason} if reason is not None else None
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("dynamic-jobs", job_id), "reject"),
                json_body=body,
            )
        )
        return DynamicJob.model_validate(self._transport.parse_json(response))


class AsyncDynamicJobsClient:
    """Asynchronous client for ``/api/v1/dynamic-jobs``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def start(
        self,
        request: DynamicJobRequest | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> DynamicJobAccepted:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_path("dynamic-jobs"),
                json_body=_dump_dynamic_request(request),
                options=options,
            )
        )
        return DynamicJobAccepted.model_validate(self._transport.parse_json(response))

    async def ingest_document(
        self,
        request: DocumentIngestRequest | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> DynamicJobAccepted:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("dynamic-jobs"), "ingest-document"),
                json_body=_dump_dynamic_request(request),
                options=options,
            )
        )
        return DynamicJobAccepted.model_validate(self._transport.parse_json(response))

    async def list(self, page: PageRequest | None = None) -> Page[DynamicJob]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("dynamic-jobs"),
                params={"limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[DynamicJob].model_validate(self._transport.parse_json(response))

    def iter(self, *, page_size: int | None = None) -> AsyncPageIterator[DynamicJob]:
        return AsyncPageIterator(lambda req: self.list(page=req), page_size=page_size)

    aiter = iter

    async def get(self, job_id: str) -> DynamicJob:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("dynamic-jobs", job_id))
        )
        return DynamicJob.model_validate(self._transport.parse_json(response))

    async def stop(self, job_id: str) -> DynamicJob:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("dynamic-jobs", job_id), "stop"))
        )
        return DynamicJob.model_validate(self._transport.parse_json(response))

    async def approve(self, job_id: str) -> DynamicJob:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("dynamic-jobs", job_id), "approve"))
        )
        return DynamicJob.model_validate(self._transport.parse_json(response))

    async def reject(self, job_id: str, reason: str | None = None) -> DynamicJob:
        body = {"reason": reason} if reason is not None else None
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("dynamic-jobs", job_id), "reject"),
                json_body=body,
            )
        )
        return DynamicJob.model_validate(self._transport.parse_json(response))
