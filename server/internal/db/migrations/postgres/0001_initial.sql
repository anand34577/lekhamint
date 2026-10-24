-- Kosh schema v1 (PostgreSQL). Keep in step with ../sqlite/0001_initial.sql.
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
    is_admin      BIGINT NOT NULL DEFAULT 0,
    disabled      BIGINT NOT NULL DEFAULT 0,
    created_at    BIGINT NOT NULL,
    updated_at    BIGINT NOT NULL
);

CREATE TABLE sessions (
    id           TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   TEXT NOT NULL UNIQUE,
    device_name  TEXT NOT NULL,
    created_at   BIGINT NOT NULL,
    last_used_at BIGINT NOT NULL,
    expires_at   BIGINT NOT NULL
);
CREATE INDEX sessions_user_idx ON sessions (user_id);
CREATE INDEX sessions_expiry_idx ON sessions (expires_at);

CREATE TABLE ledgers (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    base_currency TEXT NOT NULL,
    seq           BIGINT NOT NULL DEFAULT 0,
    created_by    TEXT NOT NULL,
    created_at    BIGINT NOT NULL,
    updated_at    BIGINT NOT NULL
);

CREATE TABLE ledger_members (
    ledger_id  TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    user_id    TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       TEXT NOT NULL CHECK (role IN ('owner', 'editor', 'viewer')),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (ledger_id, user_id)
);
CREATE INDEX ledger_members_user_idx ON ledger_members (user_id);

CREATE TABLE accounts (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    name                 TEXT,
    type                 TEXT,
    currency             TEXT,
    opening_balance      BIGINT,
    credit_limit         BIGINT,
    color                TEXT,
    icon                 TEXT,
    include_in_net_worth BIGINT,
    archived             BIGINT,
    sort_order           BIGINT,
    note                 TEXT,
    created_at           BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX accounts_seq_idx ON accounts (ledger_id, seq);

CREATE TABLE categories (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    name       TEXT,
    kind       TEXT,
    parent_id  TEXT,
    nature     TEXT,
    color      TEXT,
    icon       TEXT,
    archived   BIGINT,
    sort_order BIGINT,
    created_at BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX categories_seq_idx ON categories (ledger_id, seq);

CREATE TABLE tags (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    name       TEXT,
    color      TEXT,
    archived   BIGINT,
    created_at BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX tags_seq_idx ON tags (ledger_id, seq);

CREATE TABLE transactions (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    type                  TEXT,
    status                TEXT,
    account_id            TEXT,
    amount                BIGINT,
    to_account_id         TEXT,
    to_amount             BIGINT,
    category_id           TEXT,
    payee                 TEXT,
    note                  TEXT,
    occurred_at           BIGINT,
    local_date            TEXT,
    tz_offset_min         BIGINT,
    tag_ids               TEXT,
    original_amount       BIGINT,
    original_currency     TEXT,
    recurring_rule_id     TEXT,
    debt_id               TEXT,
    is_adjustment         BIGINT,
    excluded_from_reports BIGINT,
    created_at            BIGINT,
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
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    transaction_id TEXT,
    category_id    TEXT,
    amount         BIGINT,
    note           TEXT,
    sort_order     BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX transaction_splits_seq_idx ON transaction_splits (ledger_id, seq);
CREATE INDEX transaction_splits_tx_idx ON transaction_splits (ledger_id, transaction_id);

CREATE TABLE budgets (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    name          TEXT,
    amount        BIGINT,
    currency      TEXT,
    period        TEXT,
    start_date    TEXT,
    end_date      TEXT,
    category_ids  TEXT,
    account_ids   TEXT,
    rollover      BIGINT,
    alert_percent BIGINT,
    archived      BIGINT,
    created_at    BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX budgets_seq_idx ON budgets (ledger_id, seq);

CREATE TABLE recurring_rules (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    name            TEXT,
    type            TEXT,
    account_id      TEXT,
    to_account_id   TEXT,
    amount          BIGINT,
    to_amount       BIGINT,
    category_id     TEXT,
    payee           TEXT,
    note            TEXT,
    tag_ids         TEXT,
    frequency       TEXT,
    every           BIGINT,
    start_date      TEXT,
    end_date        TEXT,
    max_occurrences BIGINT,
    mode            TEXT,
    next_date       TEXT,
    paused          BIGINT,
    created_at      BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX recurring_rules_seq_idx ON recurring_rules (ledger_id, seq);

CREATE TABLE debts (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    person     TEXT,
    direction  TEXT,
    amount     BIGINT,
    currency   TEXT,
    due_date   TEXT,
    note       TEXT,
    closed     BIGINT,
    created_at BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX debts_seq_idx ON debts (ledger_id, seq);

CREATE TABLE exchange_rates (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    base           TEXT,
    quote          TEXT,
    rate           TEXT,
    effective_date TEXT,
    source         TEXT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX exchange_rates_seq_idx ON exchange_rates (ledger_id, seq);
CREATE INDEX exchange_rates_pair_idx ON exchange_rates (ledger_id, base, quote, effective_date);
