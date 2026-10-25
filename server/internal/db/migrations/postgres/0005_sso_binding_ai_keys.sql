-- Kosh schema v5: bind single sign-on identities to accounts, and keep the
-- browser-mode assistant API key on the server instead of the browser.
-- See the sqlite migration for the rationale.

ALTER TABLE users ADD COLUMN sso_sub TEXT NOT NULL DEFAULT '';

ALTER TABLE user_prefs ADD COLUMN ai_api_key TEXT NOT NULL DEFAULT '';
