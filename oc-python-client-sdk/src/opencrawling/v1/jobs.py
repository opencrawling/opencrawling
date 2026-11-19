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

"""Platform API v1 Jobs resource clients (``/api/v1/jobs``)."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from opencrawling._transport import (
    MERGE_PATCH_MEDIA_TYPE,
    AsyncTransport,
    SyncTransport,
    api_action,
    api_path,
)
from opencrawling.config import RequestOptions
from opencrawling.pagination import AsyncPageIterator, Page, PageRequest, SyncPageIterator
from opencrawling.v1.models import (
    DiagnosticReport,
    ErrorLogsResponse,
    Job,
    JobFilter,
    JobInput,
    JobRun,
    JobTraceResponse,
)
from opencrawling.versioned import Versioned


def _job_list_params(filter: JobFilter | None, page: PageRequest | None) -> dict[str, Any]:
    f = filter or JobFilter()
    p = page or PageRequest.first()
    return {
        "status": f.status,
        "pipelineMode": f.pipeline_mode,
        "repositoryConnector": f.repository_connector,
        "outputConnector": f.output_connector,
        "q": f.q,
        "sort": f.sort,
        "limit": p.limit,
        "cursor": p.cursor,
    }


def _dump_job_input(job: JobInput | Mapping[str, Any]) -> dict[str, Any]:
    if isinstance(job, JobInput):
        return job.model_dump(mode="json", by_alias=True, exclude_none=True)
    return dict(job)


class JobsClient:
    """Synchronous client for ``/api/v1/jobs``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def list(
        self,
        filter: JobFilter | None = None,
        page: PageRequest | None = None,
    ) -> Page[Job]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("jobs"), params=_job_list_params(filter, page))
        )
        return Page[Job].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        filter: JobFilter | None = None,
        *,
        page_size: int | None = None,
    ) -> SyncPageIterator[Job]:
        return SyncPageIterator(lambda req: self.list(filter=filter, page=req), page_size=page_size)

    def create(
        self,
        job: JobInput | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Job]:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_path("jobs"),
                json_body=_dump_job_input(job),
                options=options,
            )
        )
        return Versioned(
            data=Job.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def get(self, job_id: str) -> Versioned[Job]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("jobs", job_id))
        )
        return Versioned(
            data=Job.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def get_if_changed(self, job_id: str, etag: str | None) -> Versioned[Job] | None:
        opts = RequestOptions(if_none_match=etag) if etag else None
        response = self._transport.request("GET", api_path("jobs", job_id), options=opts)
        if response.status_code == 304:
            return None
        self._transport.check_ok(response)
        return Versioned(
            data=Job.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def replace(
        self,
        job_id: str,
        job: JobInput | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Job]:
        response = self._transport.check_ok(
            self._transport.request(
                "PUT",
                api_path("jobs", job_id),
                json_body=_dump_job_input(job),
                options=options,
            )
        )
        return Versioned(
            data=Job.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def patch(
        self,
        job_id: str,
        changes: Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Job]:
        """Apply an RFC 7396 JSON Merge Patch; ``None`` values clear optional fields."""
        response = self._transport.check_ok(
            self._transport.request(
                "PATCH",
                api_path("jobs", job_id),
                json_body=dict(changes),
                content_type=MERGE_PATCH_MEDIA_TYPE,
                options=options,
            )
        )
        return Versioned(
            data=Job.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def delete(self, job_id: str, options: RequestOptions | None = None) -> None:
        self._transport.check_ok(
            self._transport.request("DELETE", api_path("jobs", job_id), options=options)
        )

    def start(self, job_id: str, options: RequestOptions | None = None) -> Versioned[JobRun]:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("jobs", job_id), "start"), options=options)
        )
        return Versioned(
            data=JobRun.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def stop(self, job_id: str) -> Job:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("jobs", job_id), "stop"))
        )
        return Job.model_validate(self._transport.parse_json(response))

    def pause(self, job_id: str) -> Job:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("jobs", job_id), "pause"))
        )
        return Job.model_validate(self._transport.parse_json(response))

    def resume(self, job_id: str) -> Job:
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("jobs", job_id), "resume"))
        )
        return Job.model_validate(self._transport.parse_json(response))

    def diagnose(self, job_id: str) -> DiagnosticReport:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("jobs", job_id, "diagnosis"))
        )
        return DiagnosticReport.model_validate(self._transport.parse_json(response))

    def traces(self, job_id: str) -> JobTraceResponse:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("jobs", job_id, "traces"))
        )
        return JobTraceResponse.model_validate(self._transport.parse_json(response))

    def errors(self, job_id: str, timeframe: str | None = None) -> ErrorLogsResponse:
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("jobs", job_id, "errors"),
                params={"timeframe": timeframe},
            )
        )
        return ErrorLogsResponse.model_validate(self._transport.parse_json(response))


class AsyncJobsClient:
    """Asynchronous client for ``/api/v1/jobs``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def list(
        self,
        filter: JobFilter | None = None,
        page: PageRequest | None = None,
    ) -> Page[Job]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("jobs"), params=_job_list_params(filter, page))
        )
        return Page[Job].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        filter: JobFilter | None = None,
        *,
        page_size: int | None = None,
    ) -> AsyncPageIterator[Job]:
        return AsyncPageIterator(lambda req: self.list(filter=filter, page=req), page_size=page_size)

    aiter = iter

    async def create(
        self,
        job: JobInput | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Job]:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_path("jobs"),
                json_body=_dump_job_input(job),
                options=options,
            )
        )
        return Versioned(
            data=Job.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def get(self, job_id: str) -> Versioned[Job]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("jobs", job_id))
        )
        return Versioned(
            data=Job.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def get_if_changed(self, job_id: str, etag: str | None) -> Versioned[Job] | None:
        opts = RequestOptions(if_none_match=etag) if etag else None
        response = await self._transport.request("GET", api_path("jobs", job_id), options=opts)
        if response.status_code == 304:
            return None
        self._transport.check_ok(response)
        return Versioned(
            data=Job.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def replace(
        self,
        job_id: str,
        job: JobInput | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Job]:
        response = self._transport.check_ok(
            await self._transport.request(
                "PUT",
                api_path("jobs", job_id),
                json_body=_dump_job_input(job),
                options=options,
            )
        )
        return Versioned(
            data=Job.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def patch(
        self,
        job_id: str,
        changes: Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[Job]:
        """Apply an RFC 7396 JSON Merge Patch; ``None`` values clear optional fields."""
        response = self._transport.check_ok(
            await self._transport.request(
                "PATCH",
                api_path("jobs", job_id),
                json_body=dict(changes),
                content_type=MERGE_PATCH_MEDIA_TYPE,
                options=options,
            )
        )
        return Versioned(
            data=Job.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def delete(self, job_id: str, options: RequestOptions | None = None) -> None:
        self._transport.check_ok(
            await self._transport.request("DELETE", api_path("jobs", job_id), options=options)
        )

    async def start(self, job_id: str, options: RequestOptions | None = None) -> Versioned[JobRun]:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("jobs", job_id), "start"), options=options)
        )
        return Versioned(
            data=JobRun.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def stop(self, job_id: str) -> Job:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("jobs", job_id), "stop"))
        )
        return Job.model_validate(self._transport.parse_json(response))

    async def pause(self, job_id: str) -> Job:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("jobs", job_id), "pause"))
        )
        return Job.model_validate(self._transport.parse_json(response))

    async def resume(self, job_id: str) -> Job:
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("jobs", job_id), "resume"))
        )
        return Job.model_validate(self._transport.parse_json(response))

    async def diagnose(self, job_id: str) -> DiagnosticReport:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("jobs", job_id, "diagnosis"))
        )
        return DiagnosticReport.model_validate(self._transport.parse_json(response))

    async def traces(self, job_id: str) -> JobTraceResponse:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("jobs", job_id, "traces"))
        )
        return JobTraceResponse.model_validate(self._transport.parse_json(response))

    async def errors(self, job_id: str, timeframe: str | None = None) -> ErrorLogsResponse:
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("jobs", job_id, "errors"),
                params={"timeframe": timeframe},
            )
        )
        return ErrorLogsResponse.model_validate(self._transport.parse_json(response))
