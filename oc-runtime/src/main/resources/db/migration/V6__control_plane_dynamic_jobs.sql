-- Dynamic jobs (#316, part 1): the record of each job an agent or a BPM event asked for (who, what, its state, and
-- while it waits for approval the request to start), kept in the control-plane database so that every runtime on it
-- sees the same jobs, after a restart too. The job's run is a row of oc_run like every job run.
-- position is the order the jobs were created in; body is the job as JSON; the other columns are copies for the queries. Portable between PostgreSQL and H2.

CREATE TABLE oc_dynamic_job (
    job_id        VARCHAR(64)              NOT NULL PRIMARY KEY,
    position      BIGINT                   NOT NULL,
    owner         VARCHAR(512)             NOT NULL,
    state         VARCHAR(32)              NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at   TIMESTAMP WITH TIME ZONE,
    body          TEXT                     NOT NULL
);

CREATE INDEX oc_dynamic_job_owner ON oc_dynamic_job (owner, state);
CREATE INDEX oc_dynamic_job_position ON oc_dynamic_job (position);
CREATE INDEX oc_dynamic_job_finished ON oc_dynamic_job (finished_at);

-- The idempotency keys that started dynamic jobs. id is the SHA-256 of the owner and key, so neither is stored;
-- request is the checked request the key started, to tell a retry from another request.
CREATE TABLE oc_dynamic_job_key (
    id        VARCHAR(64)  NOT NULL PRIMARY KEY,
    job_id    VARCHAR(64)  NOT NULL,
    request   TEXT         NOT NULL
);

CREATE INDEX oc_dynamic_job_key_job ON oc_dynamic_job_key (job_id);
