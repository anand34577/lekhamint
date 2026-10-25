-- Kosh schema v8 (PostgreSQL): savings goals, investment valuations, receipt attachments,
-- tax sections on categories, a per-record change history, two-step sign-in,
-- password reset links, the weekly digest and Trash clean-up.
--
-- goals, valuations and attachments are synced entities (docs/04). blobs holds
-- attachment files, content-addressed by SHA-256 per ledger; it is not part of
-- the change feed (files are fetched on demand). record_history keeps who wrote
-- each version of a record. ledger_purges remembers how far old tombstones were
-- removed, so a device that last synced before that does a full resync.

CREATE TABLE goals (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    name          TEXT,
    target_amount BIGINT,
    currency      TEXT,
    target_date   TEXT,
    start_date    TEXT,
    account_ids   TEXT,
    saved_amount  BIGINT,
    color         TEXT,
    icon          TEXT,
    archived      BIGINT,
    achieved_date TEXT,
    note          TEXT,
    created_at    BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX goals_seq_idx ON goals (ledger_id, seq);

CREATE TABLE valuations (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    account_id TEXT,
    date       TEXT,
    value      BIGINT,
    note       TEXT,
    created_at BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX valuations_seq_idx ON valuations (ledger_id, seq);
CREATE INDEX valuations_account_idx ON valuations (ledger_id, account_id, date);

CREATE TABLE attachments (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               BIGINT NOT NULL,
    deleted           BIGINT NOT NULL DEFAULT 0,
    server_updated_at BIGINT NOT NULL,
    transaction_id TEXT,
    name           TEXT,
    mime           TEXT,
    size           BIGINT,
    sha256         TEXT,
    created_at     BIGINT,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX attachments_seq_idx ON attachments (ledger_id, seq);
CREATE INDEX attachments_tx_idx ON attachments (ledger_id, transaction_id);

ALTER TABLE categories ADD COLUMN tax_section TEXT;

CREATE TABLE blobs (
    ledger_id  TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    sha256     TEXT NOT NULL,
    mime       TEXT NOT NULL,
    size       BIGINT NOT NULL,
    data       BYTEA NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (ledger_id, sha256)
);

CREATE TABLE record_history (
    ledger_id TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    seq       BIGINT NOT NULL,
    entity    TEXT NOT NULL,
    record_id TEXT NOT NULL,
    hlc       TEXT NOT NULL,
    user_id   TEXT NOT NULL,
    device    TEXT NOT NULL,
    deleted   BIGINT NOT NULL,
    data      TEXT NOT NULL,
    at        BIGINT NOT NULL,
    PRIMARY KEY (ledger_id, seq)
);
CREATE INDEX record_history_rec_idx ON record_history (ledger_id, record_id);
CREATE INDEX record_history_at_idx ON record_history (at);

CREATE TABLE ledger_purges (
    ledger_id  TEXT PRIMARY KEY REFERENCES ledgers (id) ON DELETE CASCADE,
    purged_seq BIGINT NOT NULL,
    purged_at  BIGINT NOT NULL
);

CREATE TABLE password_resets (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX password_resets_user_idx ON password_resets (user_id);

ALTER TABLE users ADD COLUMN totp_secret TEXT NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN totp_enabled BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN totp_last_step BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN recovery_codes TEXT NOT NULL DEFAULT '';

ALTER TABLE user_prefs ADD COLUMN ev_digest BIGINT NOT NULL DEFAULT 1;
