-- Kosh schema v5: bind single sign-on identities to accounts, and keep the
-- browser-mode assistant API key on the server instead of the browser.
--
-- sso_sub stores the identity provider's subject ("sub") claim the first time
-- that account signs in through the provider. Later sign-ins must present the
-- same subject, so a different identity bearing the same email cannot take
-- over the account. Password accounts keep ''.

ALTER TABLE users ADD COLUMN sso_sub TEXT NOT NULL DEFAULT '';

ALTER TABLE user_prefs ADD COLUMN ai_api_key TEXT NOT NULL DEFAULT '';
