-- Idempotency keys of the Platform API (#315, part 4), shared by every runtime on the control-plane database.
-- id is the SHA-256 of the caller's scope and key, so callers, paths and keys are never stored. A row without a
-- status is a request still running; it expires after the in-progress timeout unless its runtime renews it. A row
-- with a status is a finished request: answer holds the answer to replay, or is NULL when it was not kept.
-- Portable between PostgreSQL and H2.

CREATE TABLE oc_idempotency_key (
    id           VARCHAR(64)              NOT NULL PRIMARY KEY,
    fingerprint  VARCHAR(128)             NOT NULL,
    status       INTEGER,
    answer       TEXT,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX oc_idempotency_key_expires ON oc_idempotency_key (expires_at);
