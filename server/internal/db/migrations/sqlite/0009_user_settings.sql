-- Kosh schema v9: per-person app settings (theme, chosen book, assistant options, dismissed
-- tips...), kept on the server so they follow the person to every browser and device.
-- One row per setting, so two tabs changing different settings never overwrite each other.
-- value is JSON text written by the apps; the server only checks its size.

CREATE TABLE user_settings (
    user_id    TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key        TEXT NOT NULL,
    value      TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, key)
);
