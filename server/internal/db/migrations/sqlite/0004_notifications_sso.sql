-- Kosh schema v4: notification preferences and the reminder log.
--
-- user_prefs holds each person's notification choices. gotify_token is the
-- person's own Gotify application token; the API never returns it.
-- notify_log remembers which reminders were already sent so each goes out once.

CREATE TABLE user_prefs (
    user_id        TEXT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    notify_email   INTEGER NOT NULL DEFAULT 0,
    notify_gotify  INTEGER NOT NULL DEFAULT 0,
    gotify_token   TEXT NOT NULL DEFAULT '',
    ev_security    INTEGER NOT NULL DEFAULT 1,
    ev_due         INTEGER NOT NULL DEFAULT 1,
    ev_budget      INTEGER NOT NULL DEFAULT 1,
    updated_at     INTEGER NOT NULL
);

CREATE TABLE notify_log (
    user_id TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key     TEXT NOT NULL,
    sent_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, key)
);
