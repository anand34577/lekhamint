-- Kosh schema v6: indexes for large ledgers.
--
-- Account history, the Trash and payee search all sort by date; these let the
-- database walk the index in order instead of sorting every matching row.

CREATE INDEX IF NOT EXISTS transactions_account_date_idx ON transactions (ledger_id, account_id, local_date DESC, occurred_at DESC);
CREATE INDEX IF NOT EXISTS transactions_to_account_date_idx ON transactions (ledger_id, to_account_id, local_date DESC);
CREATE INDEX IF NOT EXISTS transactions_deleted_idx ON transactions (ledger_id, deleted, local_date DESC);
CREATE INDEX IF NOT EXISTS transactions_debt_idx ON transactions (ledger_id, debt_id);
