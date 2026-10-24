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
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    name                 TEXT,
    lender               TEXT,
    kind                 TEXT,
    account_id           TEXT,
    currency             TEXT,
    principal            INTEGER,
    annual_rate          TEXT,
    rate_type            TEXT,
    start_date           TEXT,
    first_due_date       TEXT,
    tenure_months        INTEGER,
    emi_amount           INTEGER,
    paid_before          INTEGER,
    pay_from_account_id  TEXT,
    interest_category_id TEXT,
    processing_fee       INTEGER,
    remind_days          INTEGER,
    closed               INTEGER,
    closed_date          TEXT,
    note                 TEXT,
    created_at           INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX loans_seq_idx ON loans (ledger_id, seq);

CREATE TABLE deposits (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    name              TEXT,
    institution       TEXT,
    kind              TEXT,
    account_id        TEXT,
    currency          TEXT,
    amount            INTEGER,
    annual_rate       TEXT,
    start_date        TEXT,
    maturity_date     TEXT,
    compounding       TEXT,
    payout            TEXT,
    payout_account_id TEXT,
    maturity_amount   INTEGER,
    tax_percent       TEXT,
    status            TEXT,
    closed_date       TEXT,
    auto_renew        INTEGER,
    remind_days       INTEGER,
    note              TEXT,
    created_at        INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX deposits_seq_idx ON deposits (ledger_id, seq);

ALTER TABLE transactions ADD COLUMN loan_id TEXT;
ALTER TABLE transactions ADD COLUMN deposit_id TEXT;
ALTER TABLE transactions ADD COLUMN part TEXT;
CREATE INDEX transactions_loan_idx ON transactions (ledger_id, loan_id);
CREATE INDEX transactions_deposit_idx ON transactions (ledger_id, deposit_id);
