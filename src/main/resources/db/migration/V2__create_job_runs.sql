CREATE TABLE job_runs (
    id             UUID PRIMARY KEY,
    job_id         UUID        NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt        INTEGER     NOT NULL,
    status         VARCHAR(20) NOT NULL,   -- RUNNING | SUCCEEDED | FAILED | TIMED_OUT | LOST
    scheduled_for  TIMESTAMPTZ NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    node_id        VARCHAR(100) NOT NULL,
    result_summary TEXT,                   -- truncated to 4 KB
    error_message  TEXT                    -- truncated to 4 KB
);
CREATE INDEX idx_job_runs_job_started ON job_runs (job_id, started_at DESC);
