-- Payments (Phase 1, epic 6; D14, D28, D35): payment requests, a double-entry ledger, refunds,
-- earnings released to specialists, payout runs, webhook deduplication and reconciliation.
-- Amounts are whole FCFA.

-- A request to a wallet for a booking's price. The phone is the payer's own number, kept to send
-- any refund back to the same wallet.
CREATE TABLE payment (
    id             UUID        PRIMARY KEY,
    booking_id     UUID        NOT NULL,
    provider       TEXT        NOT NULL,
    method         TEXT        NOT NULL CHECK (method IN ('MTN_MOMO', 'ORANGE_MONEY')),
    phone          TEXT        NOT NULL,
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    -- Solace's fee in force when the payment was taken (D35).
    fee_percent    NUMERIC(5, 2) NOT NULL CHECK (fee_percent BETWEEN 0 AND 100),
    status         TEXT        NOT NULL CHECK (status IN ('PENDING', 'COMPLETE', 'FAILED')),
    provider_ref   TEXT,
    failure_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    completed_at   TIMESTAMPTZ
);
CREATE INDEX payment_booking ON payment (booking_id, created_at DESC);
CREATE INDEX payment_pending ON payment (created_at) WHERE status = 'PENDING';
CREATE UNIQUE INDEX payment_provider_ref ON payment (provider, provider_ref);

-- The ledger. Every journal balances (checked at commit); nothing is ever changed or removed:
-- a correction is a new journal.
CREATE TABLE ledger_journal (
    id             UUID        PRIMARY KEY,
    kind           TEXT        NOT NULL,
    booking_id     UUID,
    payment_id     UUID,
    refund_id      UUID,
    payout_item_id UUID,
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX ledger_journal_booking ON ledger_journal (booking_id);

CREATE TABLE ledger_line (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journal_id    UUID        NOT NULL REFERENCES ledger_journal (id),
    account       TEXT        NOT NULL CHECK (account IN (
                      'PROVIDER_CLEARING',   -- asset: money at the aggregator
                      'HELD',                -- liability: paid for sessions not yet completed
                      'SPECIALIST_PAYABLE',  -- liability: released to a specialist, not paid out
                      'PLATFORM_REVENUE',    -- income: Solace's fee
                      'REFUNDS_PAYABLE',     -- liability: approved refunds not yet received
                      'PAYOUTS_IN_TRANSIT')),-- liability: payouts sent, not yet confirmed
    specialist_id UUID,
    booking_id    UUID,
    debit         BIGINT      NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit        BIGINT      NOT NULL DEFAULT 0 CHECK (credit >= 0),
    CHECK ((debit = 0) <> (credit = 0))
);
CREATE INDEX ledger_line_account ON ledger_line (account, specialist_id);
CREATE INDEX ledger_line_booking ON ledger_line (booking_id) WHERE booking_id IS NOT NULL;

CREATE FUNCTION ledger_journal_balances() RETURNS trigger AS $$
DECLARE
    unbalanced BIGINT;
BEGIN
    SELECT sum(debit) - sum(credit) INTO unbalanced FROM ledger_line WHERE journal_id = NEW.journal_id;
    IF unbalanced <> 0 THEN
        RAISE EXCEPTION 'ledger journal % does not balance (%)', NEW.journal_id, unbalanced;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_line_balanced
    AFTER INSERT ON ledger_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_journal_balances();

CREATE FUNCTION ledger_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'the ledger is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_journal_append_only BEFORE UPDATE OR DELETE ON ledger_journal
    FOR EACH ROW EXECUTE FUNCTION ledger_is_append_only();
CREATE TRIGGER ledger_line_append_only BEFORE UPDATE OR DELETE ON ledger_line
    FOR EACH ROW EXECUTE FUNCTION ledger_is_append_only();
CREATE TRIGGER ledger_journal_no_truncate BEFORE TRUNCATE ON ledger_journal
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_is_append_only();
CREATE TRIGGER ledger_line_no_truncate BEFORE TRUNCATE ON ledger_line
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_is_append_only();

-- Refunds go back to the wallet that paid, as a transfer (D35). Two people decide each one (D14);
-- a policy refund is proposed by the system.
CREATE TABLE refund (
    id               UUID        PRIMARY KEY,
    booking_id       UUID        NOT NULL,
    payment_id       UUID        NOT NULL REFERENCES payment (id),
    amount           BIGINT      NOT NULL CHECK (amount > 0),
    reason           TEXT        NOT NULL,
    policy_version   INT,
    proposed_by      TEXT,
    proposed_by_name TEXT,
    status           TEXT        NOT NULL CHECK (status IN ('PROPOSED', 'DECLINED', 'APPROVED', 'SENT', 'PAID', 'FAILED')),
    decided_by       TEXT,
    decided_by_name  TEXT,
    decided_at       TIMESTAMPTZ,
    provider_ref     TEXT,
    failure_reason   TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    CHECK (proposed_by IS NULL OR decided_by IS NULL OR proposed_by <> decided_by)
);
CREATE INDEX refund_status ON refund (status, created_at);
CREATE UNIQUE INDEX refund_one_open_per_payment ON refund (payment_id)
    WHERE status IN ('PROPOSED', 'APPROVED', 'SENT');

-- What each booking released to its specialist: the sub-ledger payout runs are made from.
CREATE TABLE earning (
    id             UUID        PRIMARY KEY,
    booking_id     UUID        NOT NULL,
    specialist_id  UUID        NOT NULL,
    kind           TEXT        NOT NULL CHECK (kind IN ('SESSION', 'RETAINED', 'NO_SHOW', 'ADJUSTMENT')),
    gross          BIGINT      NOT NULL,
    fee            BIGINT      NOT NULL,
    net            BIGINT      NOT NULL,
    released_at    TIMESTAMPTZ NOT NULL,
    payout_item_id UUID
);
CREATE INDEX earning_unpaid ON earning (specialist_id, released_at) WHERE payout_item_id IS NULL;

CREATE TABLE payout_run (
    id               UUID        PRIMARY KEY,
    period_start     DATE        NOT NULL UNIQUE,
    period_end       DATE        NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'SENT', 'DONE')),
    fee_percent      NUMERIC(5, 2) NOT NULL,
    prepared_by      TEXT,
    prepared_by_name TEXT,
    prepared_at      TIMESTAMPTZ,
    approved_by      TEXT,
    approved_by_name TEXT,
    approved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL,
    CHECK (prepared_by IS NULL OR approved_by IS NULL OR prepared_by <> approved_by)
);

CREATE TABLE payout_item (
    id             UUID        PRIMARY KEY,
    run_id         UUID        NOT NULL REFERENCES payout_run (id),
    specialist_id  UUID        NOT NULL,
    sessions       INT         NOT NULL,
    gross          BIGINT      NOT NULL,
    commission     BIGINT      NOT NULL,
    net            BIGINT      NOT NULL,
    method         TEXT,
    phone          TEXT,
    status         TEXT        NOT NULL CHECK (status IN ('READY', 'HELD', 'SENT', 'PAID', 'FAILED')),
    hold_reason    TEXT,
    provider_ref   TEXT,
    failure_reason TEXT,
    updated_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, specialist_id)
);
CREATE INDEX payout_item_specialist ON payout_item (specialist_id, updated_at DESC);

-- Webhooks are idempotent on the provider's event id.
CREATE TABLE webhook_event (
    provider    TEXT        NOT NULL,
    event_id    TEXT        NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (provider, event_id)
);

CREATE TABLE reconciliation_run (
    id         UUID        PRIMARY KEY,
    day        DATE        NOT NULL UNIQUE,
    checked    INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE reconciliation_mismatch (
    run_id    UUID NOT NULL REFERENCES reconciliation_run (id),
    kind      TEXT NOT NULL CHECK (kind IN ('PAYMENT', 'REFUND', 'PAYOUT')),
    reference UUID NOT NULL,
    ours      TEXT NOT NULL,
    theirs    TEXT NOT NULL
);
