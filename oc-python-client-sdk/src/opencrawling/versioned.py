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
"""Versioned resource wrapper carrying HTTP ``ETag`` and ``Location`` metadata."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Generic, TypeVar

T = TypeVar("T")


@dataclass(frozen=True, slots=True)
class Versioned(Generic[T]):
    """A Platform API v1 resource paired with its HTTP version metadata.

    Attributes:
        data: The deserialized resource payload.
        etag: The ``ETag`` header returned by the runtime, for optimistic concurrency via
            ``RequestOptions(if_match=resource.etag)`` or conditional reads via ``If-None-Match``.
        location: The ``Location`` header returned after resource creation or job-run start.
    """

    data: T
    etag: str | None = None
    location: str | None = None

    @property
    def value(self) -> T:
        """Alias for ``data`` matching ``oc-java-client-sdk``'s ``Versioned.value()``."""
        return self.data
