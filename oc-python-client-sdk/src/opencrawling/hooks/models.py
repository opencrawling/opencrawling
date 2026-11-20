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

"""Typed request/decision models and protected-key guards for OpenCrawling governance & enrichment hooks."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from opencrawling.errors import OpenCrawlingError

GovernanceDecisionType = Literal["APPROVE", "SANITIZE", "REJECT"]

# Exact mirror of GovernanceGate.isProtectedKey(...) and OisMetadata.isOisBlockKey(...) in oc-core/oc-runtime.
PROTECTED_METADATA_KEYS: frozenset[str] = frozenset(
    {
        "acl",
        "security",
        "sys_acl",
        "documentId",
        "uri",
        "lastModified",
        "ois:source",
        "ois:provenance",
        "ois:governance",
    }
)

PROTECTED_METADATA_PREFIXES: tuple[str, ...] = (
    "security_",
    "acl_",
    "ois:source.",
    "ois:provenance.",
    "ois:governance.",
)


class ProtectedMetadataKeyError(OpenCrawlingError, ValueError):
    """Raised when a hook attempts to mutate a protected ACL, identity, or OIS provenance key."""

    def __init__(self, key: str) -> None:
        super().__init__(
            f"Metadata key {key!r} is protected and cannot be modified by governance or enrichment hooks"
        )
        self.key = key


def is_protected_metadata_key(key: str) -> bool:
    """Return ``True`` if ``key`` is protected by ``GovernanceGate.isProtectedKey``."""
    if not key:
        return False
    if key in PROTECTED_METADATA_KEYS:
        return True
    return key.startswith(PROTECTED_METADATA_PREFIXES)


def _normalize_hook_metadata(
    metadata: Mapping[str, str | Sequence[str]] | None,
) -> dict[str, list[str]] | None:
    if metadata is None:
        return None
    normalized: dict[str, list[str]] = {}
    for key, value in metadata.items():
        if is_protected_metadata_key(key):
            raise ProtectedMetadataKeyError(key)
        if isinstance(value, str):
            normalized[key] = [value]
        else:
            normalized[key] = [str(item) for item in value]
    return normalized


class GovernanceRequest(BaseModel):
    """Inbound payload sent by OpenCrawling ``HttpGovernanceHook``."""

    model_config = ConfigDict(populate_by_name=True, extra="allow")

    document_id: str = Field(alias="documentId")
    uri: str | None = None
    text: str | None = None
    metadata: dict[str, list[str]] = Field(default_factory=dict)
    security: dict[str, Any] | None = None


class Decision(BaseModel):
    """Outbound governance decision returned to OpenCrawling ``HttpGovernanceHook``."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    decision: GovernanceDecisionType
    text: str | None = None
    metadata: dict[str, list[str]] | None = None
    reason: str | None = None
    audit: dict[str, str] = Field(default_factory=dict)

    @field_validator("metadata")
    @classmethod
    def _reject_protected_keys(
        cls,
        value: dict[str, list[str]] | None,
    ) -> dict[str, list[str]] | None:
        if value is not None:
            for key in value:
                if is_protected_metadata_key(key):
                    raise ProtectedMetadataKeyError(key)
        return value

    @classmethod
    def approve(cls, *, audit: Mapping[str, str] | None = None) -> Decision:
        """Approve the document unmodified."""
        return cls(decision="APPROVE", audit=dict(audit) if audit else {})

    @classmethod
    def sanitize(
        cls,
        *,
        text: str | None = None,
        metadata: Mapping[str, str | Sequence[str]] | None = None,
        reason: str | None = None,
        audit: Mapping[str, str] | None = None,
    ) -> Decision:
        """Sanitize text and/or non-protected metadata fields before indexing."""
        normalized_meta = _normalize_hook_metadata(metadata)
        return cls(
            decision="SANITIZE",
            text=text,
            metadata=normalized_meta,
            reason=reason,
            audit=dict(audit) if audit else {},
        )

    @classmethod
    def reject(
        cls,
        reason: str,
        *,
        audit: Mapping[str, str] | None = None,
    ) -> Decision:
        """Reject the document so it is dropped from the pipeline."""
        if not reason or not reason.strip():
            raise ValueError("Rejection reason must not be blank")
        return cls(decision="REJECT", reason=reason.strip(), audit=dict(audit) if audit else {})

    def to_payload(self) -> dict[str, Any]:
        """Serialize into the exact JSON wire format expected by ``HttpGovernanceHook``."""
        payload: dict[str, Any] = {"decision": self.decision}
        if self.text is not None:
            payload["text"] = self.text
        if self.metadata is not None:
            payload["metadata"] = self.metadata
        if self.reason is not None:
            payload["reason"] = self.reason
        if self.audit:
            payload["audit"] = self.audit
        return payload


class EnrichmentRequest(BaseModel):
    """Inbound payload for HTTP enrichment hooks (aligned with OpenCrawling enrichment contract)."""

    model_config = ConfigDict(populate_by_name=True, extra="allow")

    document_id: str = Field(alias="documentId")
    uri: str | None = None
    mime_type: str | None = Field(default=None, alias="mimeType")
    text: str | None = None
    metadata: dict[str, list[str]] = Field(default_factory=dict)


class EnrichmentResponse(BaseModel):
    """Outbound enrichment payload adding non-protected metadata or transformed text."""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    text: str | None = None
    metadata: dict[str, list[str]] = Field(default_factory=dict)
    audit: dict[str, str] = Field(default_factory=dict)

    @field_validator("metadata")
    @classmethod
    def _reject_protected_keys(cls, value: dict[str, list[str]]) -> dict[str, list[str]]:
        for key in value:
            if is_protected_metadata_key(key):
                raise ProtectedMetadataKeyError(key)
        return value

    @classmethod
    def of(
        cls,
        *,
        text: str | None = None,
        metadata: Mapping[str, str | Sequence[str]] | None = None,
        audit: Mapping[str, str] | None = None,
    ) -> EnrichmentResponse:
        normalized_meta = _normalize_hook_metadata(metadata) or {}
        return cls(text=text, metadata=normalized_meta, audit=dict(audit) if audit else {})

    def to_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        if self.text is not None:
            payload["text"] = self.text
        if self.metadata:
            payload["metadata"] = self.metadata
        if self.audit:
            payload["audit"] = self.audit
        return payload
