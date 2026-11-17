-- Durable job runs (#315, part 3): every run of a job, wherever it runs, and the history of its states.
-- A run is active while ended_at is NULL; the runtime that runs it renews heartbeat_at, and a run whose heartbeat
-- is too old is marked INTERRUPTED by any runtime. Portable between PostgreSQL and H2.

CREATE TABLE oc_run (
    execution_id    VARCHAR(64)              NOT NULL PRIMARY KEY,
    job_id          VARCHAR(255)             NOT NULL,
    caller          VARCHAR(512),
    runtime_id      VARCHAR(255)             NOT NULL,
    state           VARCHAR(32)              NOT NULL,
    stop_requested  BOOLEAN                  NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at        TIMESTAMP WITH TIME ZONE
);

CREATE INDEX oc_run_job ON oc_run (job_id);
CREATE INDEX oc_run_ended ON oc_run (ended_at);

CREATE TABLE oc_run_step (
    execution_id  VARCHAR(64)              NOT NULL,
    seq           INTEGER                  NOT NULL,
    event         VARCHAR(32)              NOT NULL,
    occurred_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    runtime_id    VARCHAR(255)             NOT NULL,
    detail        VARCHAR(1024),
    PRIMARY KEY (execution_id, seq)
);
