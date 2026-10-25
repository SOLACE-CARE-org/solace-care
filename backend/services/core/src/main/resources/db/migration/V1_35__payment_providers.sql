-- Payment providers as data (D61): each account with an aggregator is a row, routes say which one
-- collects, refunds and pays out for each method, and every payment, refund and payout item keeps
-- the provider that handled it, so later checks and webhooks go back to that provider.

CREATE TABLE payment_provider (
    code            TEXT PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9-]{1,30}$'),
    adapter         TEXT        NOT NULL CHECK (adapter IN ('NOTCHPAY', 'SANDBOX')),
    display_name    TEXT        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 80),
    mode            TEXT        NOT NULL CHECK (mode IN ('TEST', 'LIVE')),
    status          TEXT        NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'DRAINING', 'DISABLED')),
    -- Null: the adapter's default address.
    base_url        TEXT,
    -- Where the keys are: 'config' for solace.payments.<adapter>.*, otherwise the prefix of the
    -- mounted key files (<secrets-dir>/<secret_ref>.public-key and so on). Never a key itself.
    secret_ref      TEXT        NOT NULL CHECK (secret_ref ~ '^[a-z0-9][a-z0-9._-]{0,62}$'),
    version         INTEGER     NOT NULL CHECK (version >= 1),
    updated_by      TEXT        NOT NULL,
    updated_by_name TEXT        NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    -- A sandbox account never takes real money.
    CHECK (adapter <> 'SANDBOX' OR mode = 'TEST')
);

-- Every version of a provider, kept with its reason (like platform_setting_version, D42). The
-- activation checks a Super admin confirmed for a LIVE provider are kept in the snapshot.
CREATE TABLE payment_provider_version (
    code            TEXT        NOT NULL REFERENCES payment_provider (code),
    version         INTEGER     NOT NULL,
    snapshot        JSONB       NOT NULL,
    reason          TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by      TEXT        NOT NULL,
    changed_by_name TEXT        NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (code, version)
);

-- Which provider handles what, in order: priority 1 first.
CREATE TABLE payment_route (
    purpose       TEXT    NOT NULL CHECK (purpose IN ('COLLECT', 'REFUND', 'PAYOUT')),
    method        TEXT    NOT NULL CHECK (method IN ('MTN_MOMO', 'ORANGE_MONEY')),
    priority      INTEGER NOT NULL CHECK (priority BETWEEN 1 AND 10),
    provider_code TEXT    NOT NULL REFERENCES payment_provider (code),
    -- Whole FCFA; null means no limit.
    min_amount    BIGINT  CHECK (min_amount IS NULL OR min_amount > 0),
    max_amount    BIGINT  CHECK (max_amount IS NULL OR max_amount > 0),
    PRIMARY KEY (purpose, method, priority),
    UNIQUE (purpose, method, provider_code),
    CHECK (min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount)
);

-- The whole route table as it was after each change, with its reason.
CREATE TABLE payment_route_version (
    version         INTEGER     PRIMARY KEY CHECK (version >= 1),
    routes          JSONB       NOT NULL,
    reason          TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by      TEXT        NOT NULL,
    changed_by_name TEXT        NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL
);

-- The providers payments were already taken with. Core adds the configured provider and its
-- routes at start-up when no route exists yet (PaymentProviders.bootstrap).
INSERT INTO payment_provider (code, adapter, display_name, mode, status, secret_ref, version,
                              updated_by, updated_by_name, updated_at)
SELECT DISTINCT provider,
       CASE provider WHEN 'sandbox' THEN 'SANDBOX' ELSE 'NOTCHPAY' END,
       CASE provider WHEN 'sandbox' THEN 'Sandbox' ELSE 'Notch Pay' END,
       CASE provider WHEN 'sandbox' THEN 'TEST' ELSE 'LIVE' END,
       'ACTIVE', 'config', 1, 'system', 'System', now()
  FROM payment;

ALTER TABLE payment ADD CONSTRAINT payment_provider_known
    FOREIGN KEY (provider) REFERENCES payment_provider (code);

-- A refund is paid by the provider that took the payment while it is ACTIVE, otherwise by the next
-- provider on the REFUND route for the method the seeker is repaid in. Method and phone start as
-- the payment's.
ALTER TABLE refund ADD COLUMN provider TEXT REFERENCES payment_provider (code);
ALTER TABLE refund ADD COLUMN method TEXT CHECK (method IN ('MTN_MOMO', 'ORANGE_MONEY'));
ALTER TABLE refund ADD COLUMN phone TEXT;
UPDATE refund r
   SET method = p.method,
       phone = p.phone,
       provider = CASE WHEN r.status IN ('SENT', 'PAID') OR r.provider_ref IS NOT NULL
                       THEN p.provider END
  FROM payment p
 WHERE p.id = r.payment_id;
ALTER TABLE refund ALTER COLUMN method SET NOT NULL;
ALTER TABLE refund ALTER COLUMN phone SET NOT NULL;
DROP INDEX IF EXISTS refund_provider_ref;
CREATE UNIQUE INDEX refund_provider_ref ON refund (provider, provider_ref)
    WHERE provider_ref IS NOT NULL;

-- A payout item is sent by the first available provider on the PAYOUT route for its method.
ALTER TABLE payout_item ADD COLUMN provider TEXT REFERENCES payment_provider (code);
UPDATE payout_item
   SET provider = (SELECT provider FROM payment ORDER BY created_at DESC LIMIT 1)
 WHERE provider_ref IS NOT NULL OR status IN ('SENT', 'PAID');
CREATE UNIQUE INDEX payout_item_provider_ref ON payout_item (provider, provider_ref)
    WHERE provider_ref IS NOT NULL;

-- Each provider holds its own float, so money at a provider is counted per provider. NOT VALID:
-- the rule holds for every new line without rewriting the append-only ledger; older clearing
-- lines all belong to the one provider in use before this migration.
ALTER TABLE ledger_line ADD COLUMN provider TEXT;
ALTER TABLE ledger_line ADD CONSTRAINT ledger_line_clearing_provider
    CHECK (account <> 'PROVIDER_CLEARING' OR provider IS NOT NULL) NOT VALID;
CREATE INDEX ledger_line_provider ON ledger_line (provider) WHERE provider IS NOT NULL;

-- Each item is reconciled with the provider that handled it; a mismatch names that provider.
ALTER TABLE reconciliation_mismatch ADD COLUMN provider TEXT;
