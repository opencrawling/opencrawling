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
"""Authentication provider protocols and static credential helpers."""

from __future__ import annotations

import inspect
from collections.abc import Awaitable
from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class TokenProvider(Protocol):
    """Synchronous bearer token supplier.

    Implementations must be thread-safe as a single client may be shared across threads.
    """

    def access_token(self) -> str | None:
        """Return the bearer token to attach to the current request, or ``None``."""
        ...

    def invalidate(self) -> bool:
        """Invalidate any cached token after an HTTP ``401``.

        Return ``True`` if a fresh token can be acquired on retry, or ``False`` for fixed tokens.
        """
        ...


@runtime_checkable
class AsyncTokenProvider(Protocol):
    """Asynchronous bearer token supplier for ``AsyncOpenCrawlingClient``.

    Custom providers may implement either ``aaccess_token()`` or an ``async def access_token()``.
    """

    async def aaccess_token(self) -> str | None:
        """Return the bearer token to attach to the current request, or ``None``."""
        ...

    def invalidate(self) -> bool:
        """Invalidate any cached token after an HTTP ``401``."""
        ...


@runtime_checkable
class _AwaitableTokenProvider(Protocol):
    async def access_token(self) -> str | None: ...

    def invalidate(self) -> bool | Awaitable[bool]: ...


AnyTokenProvider = TokenProvider | AsyncTokenProvider | _AwaitableTokenProvider


class StaticTokenProvider:
    """Bearer token provider backed by a fixed token string.

    Works with both synchronous and asynchronous clients. Never exposes the token in ``repr`` or ``str``.
    """

    def __init__(self, token: str | None) -> None:
        cleaned = token.strip() if token is not None else ""
        self._token: str | None = cleaned if cleaned else None

    def access_token(self) -> str | None:
        return self._token

    async def aaccess_token(self) -> str | None:
        return self._token

    def invalidate(self) -> bool:
        return False

    def __repr__(self) -> str:
        state = "configured" if self._token is not None else "empty"
        return f"StaticTokenProvider(token=<{state}>)"

    __str__ = __repr__


async def resolve_async_token(provider: AnyTokenProvider | None) -> str | None:
    """Resolve a bearer token from any sync or async token provider without blocking built-ins."""
    if provider is None:
        return None
    if isinstance(provider, AsyncTokenProvider):
        return await provider.aaccess_token()
    result = provider.access_token()
    if inspect.isawaitable(result):
        awaited: str | None = await result
        return awaited
    return result


async def invalidate_async_token(provider: AnyTokenProvider | None) -> bool:
    """Invalidate a cached token on any sync or async token provider."""
    if provider is None:
        return False
    result: Any = provider.invalidate()
    if inspect.isawaitable(result):
        return bool(await result)
    return bool(result)
