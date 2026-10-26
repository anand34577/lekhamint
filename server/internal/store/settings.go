package store

import (
	"context"
	"encoding/json"
	"errors"
	"regexp"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
)

// Settings limits. Settings are small UI choices; anything larger belongs in a ledger record.
const (
	MaxSettingBytes = 16 << 10
	MaxSettingsPer  = 200
)

var settingKeyRe = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,63}$`)

// ErrTooManySettings is returned when a person already has MaxSettingsPer settings.
var ErrTooManySettings = errors.New("too many settings")

// ValidSettingKey reports whether k is an acceptable setting name: lower case letters,
// digits, dot, dash and underscore, up to 64 characters (e.g. "theme" or "fy_start.<ledger id>").
func ValidSettingKey(k string) bool { return settingKeyRe.MatchString(k) }

// Setting is one stored setting, for backups.
type Setting struct {
	UserID    string          `json:"user_id"`
	Key       string          `json:"key"`
	Value     json.RawMessage `json:"value"`
	UpdatedAt int64           `json:"updated_at"`
}

// GetSettings returns all of a person's settings as key → JSON value.
func GetSettings(ctx context.Context, q db.Querier, userID string) (map[string]json.RawMessage, error) {
	rows, err := q.QueryContext(ctx, "SELECT key, value FROM user_settings WHERE user_id = ?", userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]json.RawMessage{}
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			return nil, err
		}
		out[k] = json.RawMessage(v)
	}
	return out, rows.Err()
}

// PutSetting stores one setting. value must already be valid JSON; callers check the key and size.
func PutSetting(ctx context.Context, q db.Querier, userID, key string, value json.RawMessage) error {
	var exists int
	err := q.QueryRowContext(ctx, "SELECT 1 FROM user_settings WHERE user_id = ? AND key = ?", userID, key).Scan(&exists)
	if db.IsNoRows(err) {
		var n int
		if err := q.QueryRowContext(ctx, "SELECT COUNT(*) FROM user_settings WHERE user_id = ?", userID).Scan(&n); err != nil {
			return err
		}
		if n >= MaxSettingsPer {
			return ErrTooManySettings
		}
	} else if err != nil {
		return err
	}
	return putSetting(ctx, q, Setting{UserID: userID, Key: key, Value: value, UpdatedAt: time.Now().UnixMilli()})
}

func putSetting(ctx context.Context, q db.Querier, s Setting) error {
	_, err := q.ExecContext(ctx, `INSERT INTO user_settings (user_id, key, value, updated_at) VALUES (?, ?, ?, ?)
		ON CONFLICT (user_id, key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
		s.UserID, s.Key, string(s.Value), s.UpdatedAt)
	return err
}

// RestoreSetting writes a setting from a backup as it was, keeping its timestamp.
func RestoreSetting(ctx context.Context, q db.Querier, s Setting) error { return putSetting(ctx, q, s) }

// DeleteSetting removes one setting; removing one that does not exist is not an error.
func DeleteSetting(ctx context.Context, q db.Querier, userID, key string) error {
	_, err := q.ExecContext(ctx, "DELETE FROM user_settings WHERE user_id = ? AND key = ?", userID, key)
	return err
}

// EachSetting calls fn for every stored setting of every person (for backups).
func EachSetting(ctx context.Context, q db.Querier, fn func(Setting) error) error {
	rows, err := q.QueryContext(ctx, "SELECT user_id, key, value, updated_at FROM user_settings ORDER BY user_id, key")
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var s Setting
		var v string
		if err := rows.Scan(&s.UserID, &s.Key, &v, &s.UpdatedAt); err != nil {
			return err
		}
		s.Value = json.RawMessage(v)
		if err := fn(s); err != nil {
			return err
		}
	}
	return rows.Err()
}

// AllPrefs returns every saved notification preference row, secrets included (for backups).
func AllPrefs(ctx context.Context, q db.Querier) ([]Prefs, error) {
	rows, err := q.QueryContext(ctx, "SELECT user_id, "+prefCols+" FROM user_prefs ORDER BY user_id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Prefs
	for rows.Next() {
		var id string
		var p Prefs
		var e, g, s, d, b, w int64
		if err := rows.Scan(&id, &e, &g, &p.GotifyToken, &s, &d, &b, &p.AiAPIKey, &w); err != nil {
			return nil, err
		}
		p.UserID, p.NotifyEmail, p.NotifyGotify, p.EvSecurity, p.EvDue, p.EvBudget, p.EvDigest = id, e != 0, g != 0, s != 0, d != 0, b != 0, w != 0
		out = append(out, p)
	}
	return out, rows.Err()
}
