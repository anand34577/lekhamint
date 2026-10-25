-- Kosh schema v3: bank loans with EMI schedules, fixed and recurring deposits,
-- and links from transactions to them (docs/04).
--
-- A transaction's "part" says what it does for the linked loan, deposit or
-- debt: principal, interest, fee or prepayment. Interest and fees count in
-- reports; principal never does.

CREATE TABLE loans (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    name                 TEXT,
    lender               TEXT,
    kind                 TEXT,
    account_id           TEXT,
    currency             TEXT,
    principal            BIGINT,
    annual_rate          TEXT,
    rate_type            TEXT,
    start_date           TEXT,
    first_due_date       TEXT,
    tenure_months        BIGINT,
    emi_amount           BIGINT,
    paid_before          BIGINT,
    pay_from_account_id  TEXT,
    interest_category_id TEXT,
    processing_fee       BIGINT,
    remind_days          BIGINT,
    closed               BIGINT,
    closed_date          TEXT,
    note                 TEXT,
    created_at           BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX loans_seq_idx ON loans (ledger_id, seq);

CREATE TABLE deposits (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    name              TEXT,
    institution       TEXT,
    kind              TEXT,
    account_id        TEXT,
    currency          TEXT,
    amount            BIGINT,
    annual_rate       TEXT,
    start_date        TEXT,
    maturity_date     TEXT,
    compounding       TEXT,
    payout            TEXT,
    payout_account_id TEXT,
    maturity_amount   BIGINT,
    tax_percent       TEXT,
    status            TEXT,
    closed_date       TEXT,
    auto_renew        BIGINT,
    remind_days       BIGINT,
    note              TEXT,
    created_at        BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX deposits_seq_idx ON deposits (ledger_id, seq);

ALTER TABLE transactions ADD COLUMN loan_id TEXT;
ALTER TABLE transactions ADD COLUMN deposit_id TEXT;
ALTER TABLE transactions ADD COLUMN part TEXT;
CREATE INDEX transactions_loan_idx ON transactions (ledger_id, loan_id);
CREATE INDEX transactions_deposit_idx ON transactions (ledger_id, deposit_id);
