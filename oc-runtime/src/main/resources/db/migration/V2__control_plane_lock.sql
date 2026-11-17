-- Control-plane store (#315, part 2): runtimes that share the database take this row's lock for every change, and
-- every change that alters a definition increases revision, so each runtime knows when its cached copy is stale.
-- Portable between PostgreSQL and H2.

CREATE TABLE oc_lock (
    name      VARCHAR(64) NOT NULL PRIMARY KEY,
    revision  BIGINT      NOT NULL
);

INSERT INTO oc_lock (name, revision) VALUES ('control-plane', 0);
