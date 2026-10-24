-- Kosh schema v1.
--
-- Synced entity tables share the same sync columns and a composite primary key
-- (ledger_id, id): a record can never be addressed outside its ledger.
-- Entity fields are nullable at the database level on purpose. Tombstones for
-- records this server never saw must still be storable. Field rules are enforced
-- by internal/entity before any write.

CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    name          TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    is_admin      INTEGER NOT NULL DEFAULT 0,
    disabled      INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);

CREATE TABLE sessions (
    id           TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   TEXT NOT NULL UNIQUE,
    device_name  TEXT NOT NULL,
    created_at   INTEGER NOT NULL,
    last_used_at INTEGER NOT NULL,
    expires_at   INTEGER NOT NULL
);
CREATE INDEX sessions_user_idx ON sessions (user_id);
CREATE INDEX sessions_expiry_idx ON sessions (expires_at);

CREATE TABLE ledgers (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    base_currency TEXT NOT NULL,
    seq           INTEGER NOT NULL DEFAULT 0,
    created_by    TEXT NOT NULL,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);

CREATE TABLE ledger_members (
    ledger_id  TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    user_id    TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       TEXT NOT NULL CHECK (role IN ('owner', 'editor', 'viewer')),
    created_at INTEGER NOT NULL,
    PRIMARY KEY (ledger_id, user_id)
);
CREATE INDEX ledger_members_user_idx ON ledger_members (user_id);

CREATE TABLE accounts (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    name                 TEXT,
    type                 TEXT,
    currency             TEXT,
    opening_balance      INTEGER,
    credit_limit         INTEGER,
    color                TEXT,
    icon                 TEXT,
    include_in_net_worth INTEGER,
    archived             INTEGER,
    sort_order           INTEGER,
    note                 TEXT,
    created_at           INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX accounts_seq_idx ON accounts (ledger_id, seq);

CREATE TABLE categories (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    name       TEXT,
    kind       TEXT,
    parent_id  TEXT,
    nature     TEXT,
    color      TEXT,
    icon       TEXT,
    archived   INTEGER,
    sort_order INTEGER,
    created_at INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX categories_seq_idx ON categories (ledger_id, seq);

CREATE TABLE tags (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    name       TEXT,
    color      TEXT,
    archived   INTEGER,
    created_at INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX tags_seq_idx ON tags (ledger_id, seq);

CREATE TABLE transactions (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    type                  TEXT,
    status                TEXT,
    account_id            TEXT,
    amount                INTEGER,
    to_account_id         TEXT,
    to_amount             INTEGER,
    category_id           TEXT,
    payee                 TEXT,
    note                  TEXT,
    occurred_at           INTEGER,
    local_date            TEXT,
    tz_offset_min         INTEGER,
    tag_ids               TEXT,
    original_amount       INTEGER,
    original_currency     TEXT,
    recurring_rule_id     TEXT,
    debt_id               TEXT,
    is_adjustment         INTEGER,
    excluded_from_reports INTEGER,
    created_at            INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX transactions_seq_idx ON transactions (ledger_id, seq);
CREATE INDEX transactions_date_idx ON transactions (ledger_id, local_date, occurred_at);
CREATE INDEX transactions_account_idx ON transactions (ledger_id, account_id);
CREATE INDEX transactions_to_account_idx ON transactions (ledger_id, to_account_id);
CREATE INDEX transactions_category_idx ON transactions (ledger_id, category_id);

CREATE TABLE transaction_splits (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    transaction_id TEXT,
    category_id    TEXT,
    amount         INTEGER,
    note           TEXT,
    sort_order     INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX transaction_splits_seq_idx ON transaction_splits (ledger_id, seq);
CREATE INDEX transaction_splits_tx_idx ON transaction_splits (ledger_id, transaction_id);

CREATE TABLE budgets (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    name          TEXT,
    amount        INTEGER,
    currency      TEXT,
    period        TEXT,
    start_date    TEXT,
    end_date      TEXT,
    category_ids  TEXT,
    account_ids   TEXT,
    rollover      INTEGER,
    alert_percent INTEGER,
    archived      INTEGER,
    created_at    INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX budgets_seq_idx ON budgets (ledger_id, seq);

CREATE TABLE recurring_rules (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    name            TEXT,
    type            TEXT,
    account_id      TEXT,
    to_account_id   TEXT,
    amount          INTEGER,
    to_amount       INTEGER,
    category_id     TEXT,
    payee           TEXT,
    note            TEXT,
    tag_ids         TEXT,
    frequency       TEXT,
    every           INTEGER,
    start_date      TEXT,
    end_date        TEXT,
    max_occurrences INTEGER,
    mode            TEXT,
    next_date       TEXT,
    paused          INTEGER,
    created_at      INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX recurring_rules_seq_idx ON recurring_rules (ledger_id, seq);

CREATE TABLE debts (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    person     TEXT,
    direction  TEXT,
    amount     INTEGER,
    currency   TEXT,
    due_date   TEXT,
    note       TEXT,
    closed     INTEGER,
    created_at INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX debts_seq_idx ON debts (ledger_id, seq);

CREATE TABLE exchange_rates (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    base           TEXT,
    quote          TEXT,
    rate           TEXT,
    effective_date TEXT,
    source         TEXT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX exchange_rates_seq_idx ON exchange_rates (ledger_id, seq);
CREATE INDEX exchange_rates_pair_idx ON exchange_rates (ledger_id, base, quote, effective_date);
