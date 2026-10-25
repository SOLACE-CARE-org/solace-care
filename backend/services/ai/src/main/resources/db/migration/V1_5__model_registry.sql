-- AI models and routes as rows (D61), like payment providers and message senders: each model is a
-- row with its key in Vault, mounted as a file and named by secret_ref. A feature's route is the
-- ordered list of chat models it tries. A changed route waits as pending until the feature's
-- evaluation set passes on it with the live prompt version; only then does it go live. Core's
-- aiTransferAuthorised flag still keeps health data on local models.
CREATE TABLE ai_model (
    name            TEXT PRIMARY KEY CHECK (name ~ '^[a-z][a-z0-9-]{1,40}$'),
    -- CHAT for the features, SPEECH for the scribe's transcripts, EMBEDDING for the corpus.
    kind            TEXT        NOT NULL CHECK (kind IN ('CHAT', 'SPEECH', 'EMBEDDING')),
    url             TEXT        NOT NULL CHECK (url ~ '^https?://'),
    model           TEXT        NOT NULL CHECK (length(model) BETWEEN 1 AND 120),
    -- Hosted by Solace: may receive health data without a transfer authorisation.
    local           BOOLEAN     NOT NULL,
    secret_ref      TEXT        NOT NULL CHECK (secret_ref ~ '^[a-z0-9][a-z0-9._-]{0,62}$'),
    status          TEXT        NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
    -- Speech and embedding models are tried in this order; chat models follow their routes.
    priority        INTEGER     NOT NULL DEFAULT 1 CHECK (priority BETWEEN 1 AND 10),
    version         INTEGER     NOT NULL CHECK (version >= 1),
    updated_by      TEXT        NOT NULL,
    updated_by_name TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE ai_model_version (
    name            TEXT        NOT NULL REFERENCES ai_model (name),
    version         INTEGER     NOT NULL,
    snapshot        JSONB       NOT NULL,
    reason          TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by      TEXT        NOT NULL,
    changed_by_name TEXT        NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (name, version)
);

-- Each feature's route: live, and the change waiting for its evaluation.
CREATE TABLE ai_route (
    feature    TEXT    NOT NULL REFERENCES ai_feature (code),
    state      TEXT    NOT NULL CHECK (state IN ('LIVE', 'PENDING')),
    priority   INTEGER NOT NULL CHECK (priority BETWEEN 1 AND 10),
    model_name TEXT    NOT NULL REFERENCES ai_model (name),
    PRIMARY KEY (feature, state, priority),
    UNIQUE (feature, state, model_name)
);

-- Who proposed a pending route and why; a feature has at most one pending change.
CREATE TABLE ai_route_proposal (
    feature         TEXT        PRIMARY KEY REFERENCES ai_feature (code),
    reason          TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    proposed_by     TEXT        NOT NULL,
    proposed_by_name TEXT       NOT NULL,
    proposed_at     TIMESTAMPTZ NOT NULL
);

-- Every route that went live, with the evaluation that let it.
CREATE TABLE ai_route_version (
    feature         TEXT        NOT NULL REFERENCES ai_feature (code),
    version         INTEGER     NOT NULL CHECK (version >= 1),
    routes          TEXT        NOT NULL,
    eval_run_id     UUID,
    reason          TEXT        NOT NULL,
    changed_by      TEXT        NOT NULL,
    changed_by_name TEXT        NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (feature, version)
);

-- An evaluation of a pending route names the models it ran on, in order.
ALTER TABLE eval_run ADD COLUMN routes TEXT;

-- Tokens a feature may use per calendar month; null keeps solace.ai.budgets ([1 000 000]).
ALTER TABLE ai_feature ADD COLUMN monthly_budget BIGINT CHECK (monthly_budget IS NULL OR monthly_budget >= 0);
