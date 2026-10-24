-- Kosh schema v2: categorisation rules.
--
-- A rule matches a transaction's payee or note and suggests a category, a
-- cleaner payee name and tags. Rules sync like every other entity, so the
-- same rules apply on every device and in imports.

CREATE TABLE rules (
    ledger_id         TEXT NOT NULL REFERENCES ledgers (id) ON DELETE CASCADE,
    id                TEXT NOT NULL,
    hlc               TEXT NOT NULL,
    seq               INTEGER NOT NULL,
    deleted           INTEGER NOT NULL DEFAULT 0,
    server_updated_at INTEGER NOT NULL,
    name            TEXT,
    match_field     TEXT,
    match_type      TEXT,
    pattern         TEXT,
    tx_type         TEXT,
    account_id      TEXT,
    min_amount      INTEGER,
    max_amount      INTEGER,
    set_category_id TEXT,
    set_payee       TEXT,
    add_tag_ids     TEXT,
    sort_order      INTEGER,
    enabled         INTEGER,
    created_at      INTEGER,
    PRIMARY KEY (ledger_id, id)
);
CREATE INDEX rules_seq_idx ON rules (ledger_id, seq);
