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

"""Platform API v1 Job Runs resource clients (``/api/v1/job-runs``)."""

from __future__ import annotations

import asyncio
import time

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path
from opencrawling.errors import OpenCrawlingError
from opencrawling.pagination import AsyncPageIterator, Page, PageRequest, SyncPageIterator
from opencrawling.v1.models import JobRun, JobRunHistory


class JobRunsClient:
    """Synchronous client for ``/api/v1/job-runs``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def list(
        self,
        job_id: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[JobRun]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("job-runs"),
                params={"jobId": job_id, "limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[JobRun].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        job_id: str | None = None,
        *,
        page_size: int | None = None,
    ) -> SyncPageIterator[JobRun]:
        return SyncPageIterator(lambda req: self.list(job_id=job_id, page=req), page_size=page_size)

    def get(self, execution_id: str) -> JobRun:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("job-runs", execution_id))
        )
        return JobRun.model_validate(self._transport.parse_json(response))

    def stop(self, execution_id: str) -> JobRun:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("job-runs", execution_id), "stop"))
        )
        return JobRun.model_validate(self._transport.parse_json(response))

    def pause(self, execution_id: str) -> JobRun:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("job-runs", execution_id), "pause"))
        )
        return JobRun.model_validate(self._transport.parse_json(response))

    def resume(self, execution_id: str) -> JobRun:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("job-runs", execution_id), "resume"))
        )
        return JobRun.model_validate(self._transport.parse_json(response))

    def history(self, execution_id: str) -> JobRunHistory:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("job-runs", execution_id, "history"))
        )
        return JobRunHistory.model_validate(self._transport.parse_json(response))

    def wait_for_completion(
        self,
        execution_id: str,
        *,
        poll_interval: float = 2.0,
        timeout: float = 600.0,
    ) -> JobRunHistory:
        """Poll ``history(execution_id)`` until the run reaches a terminal state or ``timeout`` elapses."""
        interval = poll_interval if poll_interval > 0 else 2.0
        max_wait = timeout if timeout >= 0 else 600.0
        deadline = time.monotonic() + max_wait
        while True:
            current = self.history(execution_id)
            if current.is_terminal:
                return current
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise OpenCrawlingError(
                    f"Timed out after {max_wait:.1f}s waiting for job run {execution_id} "
                    f"to finish (last state: {current.state or 'unknown'})"
                )
            time.sleep(min(interval, remaining))


class AsyncJobRunsClient:
    """Asynchronous client for ``/api/v1/job-runs``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def list(
        self,
        job_id: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[JobRun]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("job-runs"),
                params={"jobId": job_id, "limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[JobRun].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        job_id: str | None = None,
        *,
        page_size: int | None = None,
    ) -> AsyncPageIterator[JobRun]:
        return AsyncPageIterator(lambda req: self.list(job_id=job_id, page=req), page_size=page_size)

    aiter = iter

    async def get(self, execution_id: str) -> JobRun:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("job-runs", execution_id))
        )
        return JobRun.model_validate(self._transport.parse_json(response))

    async def stop(self, execution_id: str) -> JobRun:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("job-runs", execution_id), "stop"))
        )
        return JobRun.model_validate(self._transport.parse_json(response))

    async def pause(self, execution_id: str) -> JobRun:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("job-runs", execution_id), "pause"))
        )
        return JobRun.model_validate(self._transport.parse_json(response))

    async def resume(self, execution_id: str) -> JobRun:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("job-runs", execution_id), "resume"))
        )
        return JobRun.model_validate(self._transport.parse_json(response))

    async def history(self, execution_id: str) -> JobRunHistory:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("job-runs", execution_id, "history"))
        )
        return JobRunHistory.model_validate(self._transport.parse_json(response))

    async def wait_for_completion(
        self,
        execution_id: str,
        *,
        poll_interval: float = 2.0,
        timeout: float = 600.0,
    ) -> JobRunHistory:
        """Poll ``history(execution_id)`` asynchronously until terminal state or ``timeout``."""
        interval = poll_interval if poll_interval > 0 else 2.0
        max_wait = timeout if timeout >= 0 else 600.0
        deadline = time.monotonic() + max_wait
        while True:
            current = await self.history(execution_id)
            if current.is_terminal:
                return current
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise OpenCrawlingError(
                    f"Timed out after {max_wait:.1f}s waiting for job run {execution_id} "
                    f"to finish (last state: {current.state or 'unknown'})"
                )
            await asyncio.sleep(min(interval, remaining))
