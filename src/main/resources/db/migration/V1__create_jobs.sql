CREATE TABLE jobs (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(200) NOT NULL UNIQUE,
    description           TEXT,
    job_type              VARCHAR(50)  NOT NULL,          -- HTTP, ...
    payload               JSONB        NOT NULL,          -- executor-specific config
    schedule_type         VARCHAR(20)  NOT NULL,          -- CRON | ONE_TIME | FIXED_RATE
    cron_expression       VARCHAR(100),
    fixed_rate_seconds    INTEGER,
    run_at                TIMESTAMPTZ,                    -- for ONE_TIME
    timezone              VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    status                VARCHAR(20)  NOT NULL,          -- ACTIVE | PAUSED | COMPLETED | DEAD_LETTER
    next_run_at           TIMESTAMPTZ,
    last_run_at           TIMESTAMPTZ,
    max_retries           INTEGER      NOT NULL DEFAULT 3,
    retry_count           INTEGER      NOT NULL DEFAULT 0,
    backoff_initial_secs  INTEGER      NOT NULL DEFAULT 10,
    backoff_multiplier    NUMERIC(4,2) NOT NULL DEFAULT 2.0,
    backoff_max_secs      INTEGER      NOT NULL DEFAULT 3600,
    timeout_seconds       INTEGER      NOT NULL DEFAULT 60,
    misfire_policy        VARCHAR(20)  NOT NULL DEFAULT 'FIRE_ONCE_NOW',
    locked_by             VARCHAR(100),
    locked_until          TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Partial index: the claim query only scans active, due jobs
CREATE INDEX idx_jobs_due ON jobs (next_run_at) WHERE status = 'ACTIVE';
