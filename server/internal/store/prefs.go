package store

import (
	"context"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
)

// Prefs are one person's notification choices.
type Prefs struct {
	UserID       string `json:"-"`
	NotifyEmail  bool   `json:"notify_email"`
	NotifyGotify bool   `json:"notify_gotify"`
	GotifyToken  string `json:"-"` // secret: only "set or not" is ever returned
	EvSecurity   bool   `json:"ev_security"`
	EvDue        bool   `json:"ev_due"`
	EvBudget     bool   `json:"ev_budget"`
	EvDigest     bool   `json:"ev_digest"` // weekly summary email on Mondays
	// AiAPIKey is the person's own key for a model they connect to from the
	// browser. Stored server-side so it never sits in the browser's storage;
	// returned only to its owner.
	AiAPIKey string `json:"-"`
}

// DefaultPrefs are used until a person saves their own.
func DefaultPrefs(userID string) Prefs {
	return Prefs{UserID: userID, EvSecurity: true, EvDue: true, EvBudget: true, EvDigest: true}
}

const prefCols = "notify_email, notify_gotify, gotify_token, ev_security, ev_due, ev_budget, ai_api_key, ev_digest"

func scanPrefs(userID string, sc interface{ Scan(...any) error }) (Prefs, error) {
	p := Prefs{UserID: userID}
	var e, g, s, d, b, w int64
	if err := sc.Scan(&e, &g, &p.GotifyToken, &s, &d, &b, &p.AiAPIKey, &w); err != nil {
		return p, err
	}
	p.NotifyEmail, p.NotifyGotify, p.EvSecurity, p.EvDue, p.EvBudget, p.EvDigest = e != 0, g != 0, s != 0, d != 0, b != 0, w != 0
	return p, nil
}

// GetPrefs returns the saved preferences, or the defaults.
func GetPrefs(ctx context.Context, q db.Querier, userID string) (Prefs, error) {
	p, err := scanPrefs(userID, q.QueryRowContext(ctx, "SELECT "+prefCols+" FROM user_prefs WHERE user_id = ?", userID))
	if db.IsNoRows(err) {
		return DefaultPrefs(userID), nil
	}
	return p, err
}

// PutPrefs saves preferences (insert or replace).
func PutPrefs(ctx context.Context, q db.Querier, p Prefs) error {
	_, err := q.ExecContext(ctx, `INSERT INTO user_prefs (user_id, `+prefCols+`, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (user_id) DO UPDATE SET notify_email = excluded.notify_email, notify_gotify = excluded.notify_gotify,
		gotify_token = excluded.gotify_token, ev_security = excluded.ev_security, ev_due = excluded.ev_due,
		ev_budget = excluded.ev_budget, ai_api_key = excluded.ai_api_key, ev_digest = excluded.ev_digest, updated_at = excluded.updated_at`,
		p.UserID, boolInt(p.NotifyEmail), boolInt(p.NotifyGotify), p.GotifyToken, boolInt(p.EvSecurity), boolInt(p.EvDue), boolInt(p.EvBudget), p.AiAPIKey,
		boolInt(p.EvDigest), time.Now().UnixMilli())
	return err
}

// EachPrefs streams the preferences of every user who has any channel on.
func EachPrefs(ctx context.Context, q db.Querier) ([]Prefs, error) {
	rows, err := q.QueryContext(ctx, "SELECT user_id, "+prefCols+" FROM user_prefs WHERE notify_email = 1 OR notify_gotify = 1")
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

// SetAiAPIKey saves (or, with "", clears) the person's assistant API key
// without touching their notification choices.
func SetAiAPIKey(ctx context.Context, q db.Querier, userID, key string) error {
	p, err := GetPrefs(ctx, q, userID)
	if err != nil {
		return err
	}
	p.AiAPIKey = key
	return PutPrefs(ctx, q, p)
}

// ClaimNotice records that a reminder was sent and reports whether this call
// was the first (so a reminder goes out once, even across restarts).
func ClaimNotice(ctx context.Context, q db.Querier, userID, key string) (bool, error) {
	res, err := q.ExecContext(ctx, "INSERT INTO notify_log (user_id, key, sent_at) VALUES (?, ?, ?) ON CONFLICT (user_id, key) DO NOTHING",
		userID, key, time.Now().UnixMilli())
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n > 0, err
}

// ReleaseNotice forgets a claim, e.g. when sending failed and should be retried.
func ReleaseNotice(ctx context.Context, q db.Querier, userID, key string) error {
	_, err := q.ExecContext(ctx, "DELETE FROM notify_log WHERE user_id = ? AND key = ?", userID, key)
	return err
}

// PruneNotices removes log rows older than the cutoff.
func PruneNotices(ctx context.Context, q db.Querier, before int64) error {
	_, err := q.ExecContext(ctx, "DELETE FROM notify_log WHERE sent_at < ?", before)
	return err
}
