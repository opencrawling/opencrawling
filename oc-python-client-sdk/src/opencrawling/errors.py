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

"""Exception hierarchy and RFC 9457 Problem Details error representation."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


class OpenCrawlingError(Exception):
    """Base exception for all OpenCrawling Python SDK errors."""


class NetworkError(OpenCrawlingError):
    """Raised when the OpenCrawling runtime cannot be reached or a response cannot be parsed."""

    def __init__(self, message: str, *, cause: BaseException | None = None) -> None:
        super().__init__(message)
        self.cause = cause


class OAuth2Error(OpenCrawlingError):
    """Raised when an OAuth 2.0 / OIDC identity provider refuses a request or cannot be reached.

    Never echoes raw HTTP response bodies or bearer tokens.
    """

    def __init__(
        self,
        message: str,
        *,
        error: str | None = None,
        status: int | None = None,
        cause: BaseException | None = None,
    ) -> None:
        super().__init__(message)
        self.error = error
        self.status = status
        self.cause = cause


@dataclass(frozen=True, slots=True)
class ProblemFieldError:
    """One field-level validation error inside an RFC 9457 problem response."""

    field: str
    message: str


class ApiProblemError(OpenCrawlingError):
    """Structured exception representing an RFC 9457 ``application/problem+json`` API error.

    Exposes standard RFC 9457 members (``type``, ``title``, ``status``, ``detail``, ``instance``),
    OpenCrawling correlation metadata (``request_id``, ``errors``, ``retry_after``), and any
    problem-specific extension members (such as ``current_etag`` on ``412 Precondition Failed`` or
    ``execution_id`` on ``409 job-already-running``).
    """

    def __init__(
        self,
        *,
        status: int,
        type: str | None = None,
        title: str | None = None,
        detail: str | None = None,
        instance: str | None = None,
        request_id: str | None = None,
        errors: tuple[ProblemFieldError, ...] | list[ProblemFieldError] = (),
        extensions: dict[str, Any] | None = None,
        retry_after: float | None = None,
        raw_body: str | None = None,
    ) -> None:
        self.status = status
        self.type = type
        self.title = title
        self.detail = detail
        self.instance = instance
        self.request_id = request_id
        self.errors: tuple[ProblemFieldError, ...] = tuple(errors)
        self.extensions: dict[str, Any] = dict(extensions) if extensions else {}
        self.retry_after = retry_after
        self.raw_body = raw_body
        super().__init__(self._format_message())

    @property
    def code(self) -> str | None:
        """Short slug derived from ``type`` (e.g. ``not-found``, ``precondition-failed``)."""
        if not self.type:
            return None
        anchor = self.type.find("#problem-")
        if anchor >= 0:
            return self.type[anchor + len("#problem-") :]
        slash = self.type.rfind("/")
        return self.type[slash + 1 :] if slash >= 0 else self.type

    @property
    def current_etag(self) -> str | None:
        """Current resource ETag returned in ``412 Precondition Failed`` responses, if present."""
        val = self.extensions.get("currentEtag")
        return str(val) if isinstance(val, str) else None

    @property
    def execution_id(self) -> str | None:
        """Running execution identifier returned in ``409 job-already-running`` responses, if present."""
        val = self.extensions.get("executionId")
        return str(val) if isinstance(val, str) else None

    def _format_message(self) -> str:
        parts = [f"{self.status} {self.title or 'Error'}"]
        if self.code:
            parts[0] = f"{parts[0]} [{self.code}]"
        if self.detail:
            parts.append(f": {self.detail}")
        if self.errors:
            field_details = "; ".join(f"{err.field} {err.message}" for err in self.errors)
            parts.append(f" ({field_details})")
        if self.request_id:
            parts.append(f" [request_id={self.request_id}]")
        return "".join(parts)

    def __repr__(self) -> str:
        return (
            f"ApiProblemError(status={self.status!r}, code={self.code!r}, "
            f"title={self.title!r}, request_id={self.request_id!r})"
        )
