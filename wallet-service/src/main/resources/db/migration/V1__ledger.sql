-- Money is BIGINT minor units (cents). Never floating point.
CREATE TABLE accounts (
    id            BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT UNIQUE,                       -- NULL for system accounts
    type          VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'SYSTEM')),
    currency      CHAR(3)     NOT NULL DEFAULT 'USD',
    balance_minor BIGINT      NOT NULL DEFAULT 0,      -- cached; always equals the sum of the account's ledger entries
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Backstop for the application-level funds check: a user account can never go negative.
    CONSTRAINT ck_user_balance_non_negative CHECK (type = 'SYSTEM' OR balance_minor >= 0)
);

-- Account 1 is the funding source for top-ups (money enters the system as a debit here, so the sum of ALL
-- balances is always exactly zero). Being the lowest id it is always locked first.
INSERT INTO accounts (id, owner_user_id, type) VALUES (1, NULL, 'SYSTEM');
SELECT setval(pg_get_serial_sequence('accounts', 'id'), 1);

CREATE TABLE transactions (
    id             UUID PRIMARY KEY,
    type           VARCHAR(10) NOT NULL CHECK (type IN ('TRANSFER', 'TOPUP')),
    status         VARCHAR(10) NOT NULL CHECK (status IN ('COMPLETED', 'HELD', 'DECLINED', 'REJECTED')),
    from_account   BIGINT      NOT NULL REFERENCES accounts (id),
    to_account     BIGINT      NOT NULL REFERENCES accounts (id),
    initiator_user BIGINT      NOT NULL,
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency       CHAR(3)     NOT NULL,
    category       VARCHAR(40),
    note           VARCHAR(140),
    fraud_score    INT         NOT NULL DEFAULT 0,
    fraud_reasons  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_distinct_accounts CHECK (from_account <> to_account)
);
CREATE INDEX ix_txn_from ON transactions (from_account, created_at DESC);
CREATE INDEX ix_txn_to ON transactions (to_account, created_at DESC);
CREATE INDEX ix_txn_status ON transactions (status) WHERE status IN ('HELD', 'DECLINED');

CREATE TABLE ledger_entries (
    id           BIGSERIAL PRIMARY KEY,
    txn_id       UUID        NOT NULL REFERENCES transactions (id),
    account_id   BIGINT      NOT NULL REFERENCES accounts (id),
    direction    VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency     CHAR(3)     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_entries_txn ON ledger_entries (txn_id);
CREATE INDEX ix_entries_account ON ledger_entries (account_id);

-- The double-entry invariant, enforced by the database and not only by application code: when a
-- transaction commits, the entries of every touched txn must sum to zero (credits minus debits).
CREATE FUNCTION check_txn_balanced() RETURNS trigger AS $$
DECLARE
    imbalance BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
      INTO imbalance FROM ledger_entries WHERE txn_id = NEW.txn_id;
    IF imbalance <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % is unbalanced by %', NEW.txn_id, imbalance;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_txn_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_txn_balanced();

-- Entries are immutable: corrections are new, offsetting transactions.
CREATE FUNCTION forbid_entry_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_entry_change();

CREATE TABLE idempotency_keys (
    key            VARCHAR(100) NOT NULL,
    user_id        BIGINT       NOT NULL,
    request_hash   CHAR(64)     NOT NULL,
    response_status INT         NOT NULL,
    response_body  TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (key, user_id)
);

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    msg_key      VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX ix_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
