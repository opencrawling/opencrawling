-- Native job scheduling state (#197), shared by every runtime on the control-plane database (#315).
-- Portable between PostgreSQL and H2.

CREATE TABLE oc_schedule_state (
    job_id                VARCHAR(255)             NOT NULL PRIMARY KEY,
    enabled               BOOLEAN                  NOT NULL,
    next_fire_at          TIMESTAMP WITH TIME ZONE,
    last_fired_at         TIMESTAMP WITH TIME ZONE,
    last_execution_id     VARCHAR(64),
    last_outcome          VARCHAR(32),
    last_detail           VARCHAR(1024),
    coalesce_pending      BOOLEAN                  NOT NULL DEFAULT FALSE,
    consecutive_failures  INTEGER                  NOT NULL DEFAULT 0,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX oc_schedule_due ON oc_schedule_state (enabled, next_fire_at);

-- Bounded audit log of scheduler dispatches (cleaned up by RunRetention alongside oc_run).
CREATE TABLE oc_schedule_dispatch (
    id              VARCHAR(64)              NOT NULL PRIMARY KEY,
    job_id          VARCHAR(255)             NOT NULL,
    scheduled_for   TIMESTAMP WITH TIME ZONE NOT NULL,
    dispatched_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    runtime_id      VARCHAR(255)             NOT NULL,
    execution_id    VARCHAR(64),
    outcome         VARCHAR(32)              NOT NULL,
    detail          VARCHAR(1024)
);

CREATE INDEX oc_schedule_dispatch_job ON oc_schedule_dispatch (job_id, dispatched_at);
CREATE INDEX oc_schedule_dispatch_at  ON oc_schedule_dispatch (dispatched_at);
