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

"""Open Ingestion Standard (OIS) Direct Ingestion models and HTTP clients.

Targets ``POST /api/v1/ingest/ois/{jobId}`` with strongly typed Zero-Trust
``SecurityConfig`` (fail-closed by default) and ``UPSERT`` / ``DELETE`` tombstones.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, ClassVar

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

from opencrawling._transport import AsyncTransport, SyncTransport, api_path
from opencrawling.auth.base import StaticTokenProvider


class _OisModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )


class OisContent(_OisModel):
    """Textual content payload of an ``OisDocument``."""

    mime_type: str = "text/plain"
    text: str

    @field_validator("mime_type", mode="before")
    @classmethod
    def _default_mime_type(cls, value: Any) -> str:
        if value is None or not str(value).strip():
            return "text/plain"
        return str(value).strip()

    @classmethod
    def of(cls, mime_type: str, text: str) -> OisContent:
        return cls(mime_type=mime_type, text=text)

    @classmethod
    def plain(cls, text: str) -> OisContent:
        return cls(mime_type="text/plain", text=text)

    @classmethod
    def markdown(cls, text: str) -> OisContent:
        return cls(mime_type="text/markdown", text=text)

    @classmethod
    def html(cls, text: str) -> OisContent:
        return cls(mime_type="text/html", text=text)


class OisSource(_OisModel):
    """Source repository provenance metadata attached to an ``OisDocument``."""

    type: str
    instance: str | None = None

    @classmethod
    def of(cls, type: str, instance: str | None = None) -> OisSource:
        return cls(type=type, instance=instance)


class PermissionRule(_OisModel):
    """Single Access Control List (ACL) entry inside ``SecurityConfig``."""

    identity: str
    identity_type: str = "user"
    access: str = "read"

    @field_validator("identity", "access", mode="before")
    @classmethod
    def _require_non_blank(cls, value: Any) -> str:
        if value is None or not str(value).strip():
            raise ValueError("must not be blank")
        return str(value).strip()

    @field_validator("identity_type", mode="before")
    @classmethod
    def _default_identity_type(cls, value: Any) -> str:
        if value is None or not str(value).strip():
            return "user"
        return str(value).strip()

    @classmethod
    def allow_user(cls, identity: str, *, access: str = "read") -> PermissionRule:
        return cls(identity=identity, identity_type="user", access=access)

    @classmethod
    def allow_group(cls, identity: str, *, access: str = "read") -> PermissionRule:
        return cls(identity=identity, identity_type="group", access=access)

    @classmethod
    def allow_role(cls, identity: str, *, access: str = "read") -> PermissionRule:
        return cls(identity=identity, identity_type="role", access=access)

    @classmethod
    def deny_user(cls, identity: str) -> PermissionRule:
        return cls(identity=identity, identity_type="user", access="deny")

    @classmethod
    def deny_group(cls, identity: str) -> PermissionRule:
        return cls(identity=identity, identity_type="group", access="deny")

    @classmethod
    def deny_role(cls, identity: str) -> PermissionRule:
        return cls(identity=identity, identity_type="role", access="deny")


class SecurityConfig(_OisModel):
    """Zero-Trust ACL security block attached to an ``OisDocument``.

    Defaults to fail-closed (``inheritance_enabled=False``, ``evaluation_policy="FAIL_CLOSED"``,
    empty ``permissions`` list denying all non-admin readers unless explicitly allowed).
    """

    POLICY_FAIL_CLOSED: ClassVar[str] = "FAIL_CLOSED"

    inheritance_enabled: bool = False
    evaluation_policy: str = "FAIL_CLOSED"
    permissions: list[PermissionRule] = Field(default_factory=list)

    @field_validator("evaluation_policy", mode="before")
    @classmethod
    def _default_policy(cls, value: Any) -> str:
        if value is None or not str(value).strip():
            return cls.POLICY_FAIL_CLOSED
        return str(value).strip()

    @classmethod
    def fail_closed(
        cls,
        permissions: Sequence[PermissionRule],
        *,
        inheritance_enabled: bool = False,
    ) -> SecurityConfig:
        return cls(
            inheritance_enabled=inheritance_enabled,
            evaluation_policy=cls.POLICY_FAIL_CLOSED,
            permissions=list(permissions),
        )

    @classmethod
    def none(cls) -> SecurityConfig:
        """Fail-closed configuration with no reader grants."""
        return cls(
            inheritance_enabled=False,
            evaluation_policy=cls.POLICY_FAIL_CLOSED,
            permissions=[],
        )


class OisDocument(_OisModel):
    """Open Ingestion Standard (OIS) document payload for ``POST /api/v1/ingest/ois/{jobId}``."""

    ACTION_UPSERT: ClassVar[str] = "UPSERT"
    ACTION_DELETE: ClassVar[str] = "DELETE"

    id: str
    action: str = "UPSERT"
    content: OisContent | None = None
    metadata: dict[str, Any] | None = None
    security: SecurityConfig | None = None
    source: OisSource | None = None

    @field_validator("id", mode="before")
    @classmethod
    def _require_id(cls, value: Any) -> str:
        if value is None or not str(value).strip():
            raise ValueError("id is required")
        return str(value).strip()

    @field_validator("action", mode="before")
    @classmethod
    def _validate_action(cls, value: Any) -> str:
        text = str(value).strip().upper() if value is not None else ""
        if text not in {cls.ACTION_UPSERT, cls.ACTION_DELETE}:
            raise ValueError("action must be UPSERT or DELETE")
        return text

    @model_validator(mode="after")
    def _validate_upsert_requirements(self) -> OisDocument:
        if self.action == self.ACTION_UPSERT:
            if self.content is None or self.content.text is None:
                raise ValueError("content.text is required for UPSERT")
            if self.security is None:
                raise ValueError("security is required for UPSERT (fail-closed Zero-Trust policy)")
        return self

    @classmethod
    def upsert(
        cls,
        *,
        id: str,
        content: OisContent,
        security: SecurityConfig,
        metadata: Mapping[str, Any] | None = None,
        source: OisSource | None = None,
    ) -> OisDocument:
        """Construct an ``UPSERT`` OIS document with mandatory Zero-Trust ``security``."""
        return cls(
            id=id,
            action=cls.ACTION_UPSERT,
            content=content,
            metadata=dict(metadata) if metadata is not None else None,
            security=security,
            source=source,
        )

    @classmethod
    def delete(
        cls,
        *,
        id: str,
        source: OisSource | None = None,
    ) -> OisDocument:
        """Construct a ``DELETE`` tombstone OIS document."""
        return cls(
            id=id,
            action=cls.ACTION_DELETE,
            content=None,
            metadata=None,
            security=None,
            source=source,
        )

    def to_payload(self) -> dict[str, Any]:
        return self.model_dump(mode="json", by_alias=True, exclude_none=True)


@dataclass(frozen=True, slots=True)
class OisIngestResponse:
    """Outcome of pushing an ``OisDocument`` to ``POST /api/v1/ingest/ois/{jobId}``."""

    job_id: str
    document_id: str
    action: str
    status: int

    @property
    def is_accepted(self) -> bool:
        return self.status == 202


class OisIngestClient:
    """Synchronous client for ``POST /api/v1/ingest/ois/{jobId}``."""

    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport

    def with_token(self, ois_token: str) -> OisIngestClient:
        """Return a scoped client authenticated with the dedicated ``opencrawling.ingest.ois.token``."""
        if not ois_token or not ois_token.strip():
            raise ValueError("ois_token must not be blank")
        return OisIngestClient(
            self._transport.with_token_provider(StaticTokenProvider(ois_token.strip()))
        )

    def push_ois(self, job_id: str, document: OisDocument) -> OisIngestResponse:
        """Push an ``OisDocument`` (``UPSERT`` or ``DELETE``) into ``job_id``'s RAG pipeline."""
        if not job_id or not job_id.strip():
            raise ValueError("job_id is required")
        cleaned_job_id = job_id.strip()
        response = self._transport.check_ok(
            self._transport.request(
                "POST",
                api_path("ingest", "ois", cleaned_job_id),
                json_body=document.to_payload(),
                auto_idempotency=False,
                force_repeatable=True,
            )
        )
        return OisIngestResponse(
            job_id=cleaned_job_id,
            document_id=document.id,
            action=document.action,
            status=response.status_code,
        )

    push = push_ois

    def upsert(
        self,
        job_id: str,
        document_id: str,
        content: OisContent,
        *,
        security: SecurityConfig,
        metadata: Mapping[str, Any] | None = None,
        source: OisSource | None = None,
    ) -> OisIngestResponse:
        doc = OisDocument.upsert(
            id=document_id,
            content=content,
            security=security,
            metadata=metadata,
            source=source,
        )
        return self.push_ois(job_id, doc)

    def delete(
        self,
        job_id: str,
        document_id: str,
        *,
        source: OisSource | None = None,
    ) -> OisIngestResponse:
        return self.push_ois(job_id, OisDocument.delete(id=document_id, source=source))


class AsyncOisIngestClient:
    """Asynchronous client for ``POST /api/v1/ingest/ois/{jobId}``."""

    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport

    def with_token(self, ois_token: str) -> AsyncOisIngestClient:
        """Return a scoped async client authenticated with ``opencrawling.ingest.ois.token``."""
        if not ois_token or not ois_token.strip():
            raise ValueError("ois_token must not be blank")
        return AsyncOisIngestClient(
            self._transport.with_token_provider(StaticTokenProvider(ois_token.strip()))
        )

    async def push_ois(self, job_id: str, document: OisDocument) -> OisIngestResponse:
        """Push an ``OisDocument`` (``UPSERT`` or ``DELETE``) asynchronously."""
        if not job_id or not job_id.strip():
            raise ValueError("job_id is required")
        cleaned_job_id = job_id.strip()
        response = self._transport.check_ok(
            await self._transport.request(
                "POST",
                api_path("ingest", "ois", cleaned_job_id),
                json_body=document.to_payload(),
                auto_idempotency=False,
                force_repeatable=True,
            )
        )
        return OisIngestResponse(
            job_id=cleaned_job_id,
            document_id=document.id,
            action=document.action,
            status=response.status_code,
        )

    push = push_ois

    async def upsert(
        self,
        job_id: str,
        document_id: str,
        content: OisContent,
        *,
        security: SecurityConfig,
        metadata: Mapping[str, Any] | None = None,
        source: OisSource | None = None,
    ) -> OisIngestResponse:
        doc = OisDocument.upsert(
            id=document_id,
            content=content,
            security=security,
            metadata=metadata,
            source=source,
        )
        return await self.push_ois(job_id, doc)

    async def delete(
        self,
        job_id: str,
        document_id: str,
        *,
        source: OisSource | None = None,
    ) -> OisIngestResponse:
        return await self.push_ois(job_id, OisDocument.delete(id=document_id, source=source))
