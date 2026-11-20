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

"""Platform API v1 Schedules resource clients (``/api/v1/schedules`` and ``/api/v1/jobs/{id}/schedule``)."""

from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime
from typing import Any

from opencrawling._transport import AsyncTransport, SyncTransport, api_action, api_path
from opencrawling.config import RequestOptions
from opencrawling.pagination import AsyncPageIterator, Page, PageRequest, SyncPageIterator
from opencrawling.v1.models import (
    CronValidationResult,
    JobSchedule,
    JobScheduleConfig,
    ScheduleDispatch,
    ScheduleFilter,
    SchedulerOverview,
)
from opencrawling.versioned import Versioned


def _schedule_list_params(filter: ScheduleFilter | None, page: PageRequest | None) -> dict[str, Any]:
    f = filter or ScheduleFilter()
    p = page or PageRequest.first()
    return {
        "enabled": f.enabled,
        "outcome": str(f.outcome) if f.outcome is not None else None,
        "q": f.q,
        "sort": f.sort,
        "limit": p.limit,
        "cursor": p.cursor,
    }


def _dump_schedule(config: JobScheduleConfig | Mapping[str, Any]) -> dict[str, Any]:
    if isinstance(config, JobScheduleConfig):
        return config.model_dump(mode="json", by_alias=True, exclude_none=True)
    return dict(config)


class SchedulesClient:
    """Synchronous client for Platform API v1 native scheduling endpoints."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def list(
        self,
        filter: ScheduleFilter | None = None,
        page: PageRequest | None = None,
    ) -> Page[JobSchedule]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("schedules"), params=_schedule_list_params(filter, page))
        )
        return Page[JobSchedule].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        filter: ScheduleFilter | None = None,
        *,
        page_size: int | None = None,
    ) -> SyncPageIterator[JobSchedule]:
        return SyncPageIterator(lambda req: self.list(filter=filter, page=req), page_size=page_size)

    def overview(self, *, horizon: str = "24h", limit: int = 50) -> SchedulerOverview:
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("schedules", "overview"),
                params={"horizon": horizon, "limit": limit},
            )
        )
        return SchedulerOverview.model_validate(self._transport.parse_json(response))

    def validate_cron(
        self,
        cron: str,
        *,
        timezone: str | None = None,
        count: int = 5,
        start_at: datetime | None = None,
        end_at: datetime | None = None,
    ) -> CronValidationResult:
        payload: dict[str, Any] = {"cron": cron, "count": count}
        if timezone is not None:
            payload["timezone"] = timezone
        if start_at is not None:
            payload["startAt"] = start_at.isoformat()
        if end_at is not None:
            payload["endAt"] = end_at.isoformat()
        response = self._transport.check_ok(
            self._transport.request("POST", api_action(api_path("schedules"), "validate-cron"), json_body=payload)
        )
        return CronValidationResult.model_validate(self._transport.parse_json(response))

    def get(self, job_id: str) -> Versioned[JobSchedule]:
        response = self._transport.check_ok(
            self._transport.request("GET", api_path("jobs", job_id, "schedule"))
        )
        return Versioned(
            data=JobSchedule.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def get_if_changed(self, job_id: str, etag: str | None) -> Versioned[JobSchedule] | None:
        opts = RequestOptions(if_none_match=etag) if etag else None
        response = self._transport.request("GET", api_path("jobs", job_id, "schedule"), options=opts)
        if response.status_code == 304:
            return None
        self._transport.check_ok(response)
        return Versioned(
            data=JobSchedule.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def set(
        self,
        job_id: str,
        config: JobScheduleConfig | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[JobSchedule]:
        response = self._transport.check_ok(
            self._transport.request(
                "PUT",
                api_path("jobs", job_id, "schedule"),
                json_body=_dump_schedule(config),
                options=options,
            )
        )
        return Versioned(
            data=JobSchedule.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def delete(self, job_id: str, options: RequestOptions | None = None) -> None:
        self._transport.check_ok(
            self._transport.request("DELETE", api_path("jobs", job_id, "schedule"), options=options)
        )

    def pause(self, job_id: str, options: RequestOptions | None = None) -> Versioned[JobSchedule]:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("jobs", job_id, "schedule"), "pause"),
                options=options,
            )
        )
        return Versioned(
            data=JobSchedule.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def resume(self, job_id: str, options: RequestOptions | None = None) -> Versioned[JobSchedule]:
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_action(api_path("jobs", job_id, "schedule"), "resume"),
                options=options,
            )
        )
        return Versioned(
            data=JobSchedule.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    def dispatches(
        self,
        job_id: str,
        *,
        outcome: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[ScheduleDispatch]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            self._transport.request(
                "GET",
                api_path("jobs", job_id, "schedule", "dispatches"),
                params={"outcome": outcome, "limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[ScheduleDispatch].model_validate(self._transport.parse_json(response))

    def iter_dispatches(
        self,
        job_id: str,
        *,
        outcome: str | None = None,
        page_size: int | None = None,
    ) -> SyncPageIterator[ScheduleDispatch]:
        return SyncPageIterator(
            lambda req: self.dispatches(job_id, outcome=outcome, page=req),
            page_size=page_size,
        )


class AsyncSchedulesClient:
    """Asynchronous client for Platform API v1 native scheduling endpoints."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    async def list(
        self,
        filter: ScheduleFilter | None = None,
        page: PageRequest | None = None,
    ) -> Page[JobSchedule]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("schedules"), params=_schedule_list_params(filter, page))
        )
        return Page[JobSchedule].model_validate(self._transport.parse_json(response))

    def iter(
        self,
        filter: ScheduleFilter | None = None,
        *,
        page_size: int | None = None,
    ) -> AsyncPageIterator[JobSchedule]:
        return AsyncPageIterator(lambda req: self.list(filter=filter, page=req), page_size=page_size)

    async def overview(self, *, horizon: str = "24h", limit: int = 50) -> SchedulerOverview:
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("schedules", "overview"),
                params={"horizon": horizon, "limit": limit},
            )
        )
        return SchedulerOverview.model_validate(self._transport.parse_json(response))

    async def validate_cron(
        self,
        cron: str,
        *,
        timezone: str | None = None,
        count: int = 5,
        start_at: datetime | None = None,
        end_at: datetime | None = None,
    ) -> CronValidationResult:
        payload: dict[str, Any] = {"cron": cron, "count": count}
        if timezone is not None:
            payload["timezone"] = timezone
        if start_at is not None:
            payload["startAt"] = start_at.isoformat()
        if end_at is not None:
            payload["endAt"] = end_at.isoformat()
        response = self._transport.check_ok(
            await self._transport.request("POST", api_action(api_path("schedules"), "validate-cron"), json_body=payload)
        )
        return CronValidationResult.model_validate(self._transport.parse_json(response))

    async def get(self, job_id: str) -> Versioned[JobSchedule]:
        response = self._transport.check_ok(
            await self._transport.request("GET", api_path("jobs", job_id, "schedule"))
        )
        return Versioned(
            data=JobSchedule.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def get_if_changed(self, job_id: str, etag: str | None) -> Versioned[JobSchedule] | None:
        opts = RequestOptions(if_none_match=etag) if etag else None
        response = await self._transport.request("GET", api_path("jobs", job_id, "schedule"), options=opts)
        if response.status_code == 304:
            return None
        self._transport.check_ok(response)
        return Versioned(
            data=JobSchedule.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def set(
        self,
        job_id: str,
        config: JobScheduleConfig | Mapping[str, Any],
        options: RequestOptions | None = None,
    ) -> Versioned[JobSchedule]:
        response = self._transport.check_ok(
            await self._transport.request(
                "PUT",
                api_path("jobs", job_id, "schedule"),
                json_body=_dump_schedule(config),
                options=options,
            )
        )
        return Versioned(
            data=JobSchedule.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def delete(self, job_id: str, options: RequestOptions | None = None) -> None:
        self._transport.check_ok(
            await self._transport.request("DELETE", api_path("jobs", job_id, "schedule"), options=options)
        )

    async def pause(self, job_id: str, options: RequestOptions | None = None) -> Versioned[JobSchedule]:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("jobs", job_id, "schedule"), "pause"),
                options=options,
            )
        )
        return Versioned(
            data=JobSchedule.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def resume(self, job_id: str, options: RequestOptions | None = None) -> Versioned[JobSchedule]:
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_action(api_path("jobs", job_id, "schedule"), "resume"),
                options=options,
            )
        )
        return Versioned(
            data=JobSchedule.model_validate(self._transport.parse_json(response)),
            etag=response.headers.get("etag"),
            location=response.headers.get("location"),
        )

    async def dispatches(
        self,
        job_id: str,
        *,
        outcome: str | None = None,
        page: PageRequest | None = None,
    ) -> Page[ScheduleDispatch]:
        p = page or PageRequest.first()
        response = self._transport.check_ok(
            await self._transport.request(
                "GET",
                api_path("jobs", job_id, "schedule", "dispatches"),
                params={"outcome": outcome, "limit": p.limit, "cursor": p.cursor},
            )
        )
        return Page[ScheduleDispatch].model_validate(self._transport.parse_json(response))

    def iter_dispatches(
        self,
        job_id: str,
        *,
        outcome: str | None = None,
        page_size: int | None = None,
    ) -> AsyncPageIterator[ScheduleDispatch]:
        return AsyncPageIterator(
            lambda req: self.dispatches(job_id, outcome=outcome, page=req),
            page_size=page_size,
        )
