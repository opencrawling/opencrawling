# Versioning, Stability, and Deprecation Policy

`opencrawling-sdk` follows [Semantic Versioning 2.0.0](https://semver.org/) and ships as a
**Production/Stable (1.x GA)** package on PyPI with PEP 561 strict typing (`py.typed`).

## Public API Surface

The following modules and symbols form the stable public API:

- Top-level exports in `opencrawling.__all__` (`OpenCrawlingClient`, `AsyncOpenCrawlingClient`, `RequestOptions`, `RetryPolicy`, `Versioned`, errors, pagination, models, ingestion, and hooks).
- `opencrawling.auth` — token providers and OAuth 2.0 / RFC 8693 / RFC 8628 helpers.
- `opencrawling.ingest` — OIS Direct Ingestion and optional gRPC Push streaming (`[grpc]`).
- `opencrawling.hooks` — HMAC-SHA256 governance & enrichment hook helpers (`[hooks]`).
- `opencrawling.v1` — Platform API v1 resource clients and Pydantic models.

Modules prefixed with a single underscore (e.g. `opencrawling._transport`, `opencrawling._tracing`,
`opencrawling._version`) are internal implementation details and may change in minor or patch
releases without notice.

## Semantic Versioning Guarantees (`MAJOR.MINOR.PATCH`)

- **PATCH (`1.0.x`)**: Backwards-compatible bug fixes, wire-format interoperability fixes,
  documentation updates, and dependency floor/ceiling adjustments that do not break existing callers.
- **MINOR (`1.x.0`)**: Backwards-compatible additions — new Platform API v1 endpoints or optional
  fields, new optional extras, new helper classes/functions, and new optional keyword arguments with
  safe defaults.
- **MAJOR (`x.0.0`)**: Breaking changes to method signatures, removal of previously deprecated APIs,
  or dropping support for a Platform API major version.

## Deprecation Policy

1. Any public symbol, parameter, or behavior scheduled for removal will emit a Python
   `DeprecationWarning` (via `warnings.warn(..., DeprecationWarning, stacklevel=2)`) and be
   documented in [CHANGELOG.md](CHANGELOG.md) under `### Deprecated`.
2. Deprecated APIs remain functional for **at least two minor releases** (or **6 months**,
   whichever is longer) before removal in the next major release.
3. Security-critical fixes (e.g. fail-closed ACL validation or signature verification hardening) may
   be enforced immediately in a patch release with clear release notes.

## Runtime & Python Compatibility Matrix

| `opencrawling-sdk` | OpenCrawling Runtime | Python Versions | Status |
|--------------------|----------------------|-----------------|--------|
| `1.0.5+` (GA)      | `1.0.5+` (API v1)    | 3.11, 3.12, 3.13, 3.14 | Supported (GA) |
| `1.0.4` (Beta)     | `1.0.4` (API v1)     | 3.11, 3.12, 3.13 | Superseded |

- **Python support**: The SDK supports all active CPython versions from `3.11` upward. Dropping an
  end-of-life Python version occurs only in a minor or major release after upstream Python EOL.
- **Forward compatibility**: Settings and response models that evolve across runtime versions use
  `extra="allow"` so newer runtime fields are preserved losslessly on older SDK versions.

## GA Release Checklist

Before tagging a GA release (`vX.Y.Z`):

1. Update [CHANGELOG.md](CHANGELOG.md) moving items from `[Unreleased]` to `[X.Y.Z] - YYYY-MM-DD`.
2. Verify all local quality gates pass:
   - `ruff check src tests`
   - `mypy --strict src/opencrawling`
   - `pytest -v` (with `[dev,http2,hooks,otel]`)
3. Verify built wheel contains `opencrawling/py.typed` and passes `twine check dist/*`.
4. Tag `vX.Y.Z` to trigger `.github/workflows/python-sdk-publish.yml` (OIDC Trusted Publisher to
   PyPI with Sigstore attestations + GitHub Release assets and SHA-256 checksums).
