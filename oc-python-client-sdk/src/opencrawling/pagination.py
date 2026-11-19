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

"""Cursor-based pagination models and lazy synchronous / asynchronous iterators."""

from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable, Iterator
from dataclasses import dataclass
from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

T = TypeVar("T")


class PageInfo(BaseModel):
    """Pagination metadata returned alongside a collection page."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )

    limit: int = 50
    next_cursor: str | None = None
    total_items: int = 0


class Page(BaseModel, Generic[T]):
    """One cursor-paginated slice of a Platform API v1 collection."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="ignore",
    )

    items: list[T] = Field(default_factory=list)
    page: PageInfo = Field(default_factory=PageInfo)

    @property
    def has_next(self) -> bool:
        """Return ``True`` when the server provided a non-empty ``next_cursor``."""
        return bool(self.page.next_cursor)

    def __len__(self) -> int:
        return len(self.items)

    def __iter__(self) -> Iterator[T]:  # type: ignore[override]
        return iter(self.items)


@dataclass(frozen=True, slots=True)
class PageRequest:
    """Cursor pagination request parameters.

    Attributes:
        limit: Page size between 1 and 500 (``None`` uses the runtime default of 50).
            Keep ``limit`` constant while following cursors.
        cursor: ``page.next_cursor`` from the preceding page, or ``None`` for the first page.
    """

    limit: int | None = None
    cursor: str | None = None

    @classmethod
    def first(cls) -> PageRequest:
        return cls(limit=None, cursor=None)

    @classmethod
    def of_size(cls, limit: int) -> PageRequest:
        return cls(limit=limit, cursor=None)

    def next_for(self, page: Page[object]) -> PageRequest:
        return PageRequest(limit=self.limit, cursor=page.page.next_cursor)


class SyncPageIterator(Iterator[T]):
    """Lazy synchronous iterator that follows ``next_cursor`` across pages on demand."""

    def __init__(
        self,
        fetch_page: Callable[[PageRequest], Page[T]],
        *,
        page_size: int | None = None,
    ) -> None:
        self._fetch_page = fetch_page
        self._page_size = page_size
        self._current_page: Page[T] | None = None
        self._current_items: Iterator[T] = iter(())
        self._exhausted = False

    def __iter__(self) -> SyncPageIterator[T]:
        return self

    def __next__(self) -> T:
        while True:
            try:
                return next(self._current_items)
            except StopIteration:
                pass
            if self._exhausted:
                raise StopIteration
            if self._current_page is None:
                req = PageRequest(limit=self._page_size, cursor=None)
            elif self._current_page.has_next:
                req = PageRequest(limit=self._page_size, cursor=self._current_page.page.next_cursor)
            else:
                self._exhausted = True
                raise StopIteration
            self._current_page = self._fetch_page(req)
            self._current_items = iter(self._current_page.items)
            if not self._current_page.has_next and not self._current_page.items:
                self._exhausted = True
                raise StopIteration


class AsyncPageIterator(AsyncIterator[T]):
    """Lazy asynchronous iterator that follows ``next_cursor`` across pages on demand."""

    def __init__(
        self,
        fetch_page: Callable[[PageRequest], Awaitable[Page[T]]],
        *,
        page_size: int | None = None,
    ) -> None:
        self._fetch_page = fetch_page
        self._page_size = page_size
        self._current_page: Page[T] | None = None
        self._current_items: Iterator[T] = iter(())
        self._exhausted = False

    def __aiter__(self) -> AsyncPageIterator[T]:
        return self

    async def __anext__(self) -> T:
        while True:
            try:
                return next(self._current_items)
            except StopIteration:
                pass
            if self._exhausted:
                raise StopAsyncIteration
            if self._current_page is None:
                req = PageRequest(limit=self._page_size, cursor=None)
            elif self._current_page.has_next:
                req = PageRequest(limit=self._page_size, cursor=self._current_page.page.next_cursor)
            else:
                self._exhausted = True
                raise StopAsyncIteration
            self._current_page = await self._fetch_page(req)
            self._current_items = iter(self._current_page.items)
            if not self._current_page.has_next and not self._current_page.items:
                self._exhausted = True
                raise StopAsyncIteration
