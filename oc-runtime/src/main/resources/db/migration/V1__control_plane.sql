-- Control-plane store (#315): definitions the runtime kept in data/*.json.
-- Each row holds one definition as JSON; version increases on every change of its body.
-- Portable between PostgreSQL and H2.

CREATE TABLE oc_job (
    id          VARCHAR(255)             NOT NULL PRIMARY KEY,
    position    INTEGER                  NOT NULL,
    body        TEXT                     NOT NULL,
    version     BIGINT                   NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE oc_connector (
    name        VARCHAR(255)             NOT NULL PRIMARY KEY,
    position    INTEGER                  NOT NULL,
    body        TEXT                     NOT NULL,
    version     BIGINT                   NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Single objects (settings.json, transport-settings.json, ...) by name, and the markers that a list was initialized.
CREATE TABLE oc_setting (
    name        VARCHAR(255)             NOT NULL PRIMARY KEY,
    body        TEXT                     NOT NULL,
    version     BIGINT                   NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
