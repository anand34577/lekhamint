package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
)

// User is a server account (a person, not a money account).
type User struct {
	ID           string `json:"id"`
	Email        string `json:"email"`
	Name         string `json:"name"`
	PasswordHash string `json:"-"`
	// SSOSub is the identity provider's subject claim this account is bound to
	// ("" for password accounts). Later single sign-ins must present the same
	// subject, so a different identity bearing the same email cannot sign in.
	SSOSub string `json:"-"`
	// Two-step sign-in: the authenticator secret, whether it is on, the last
	// code step used (so a code works once) and hashed recovery codes.
	TOTPSecret    string `json:"-"`
	TOTPEnabled   bool   `json:"totp_enabled"`
	TOTPLastStep  int64  `json:"-"`
	RecoveryCodes string `json:"-"`
	IsAdmin       bool   `json:"is_admin"`
	Disabled      bool   `json:"disabled"`
	CreatedAt     int64  `json:"created_at"`
	UpdatedAt     int64  `json:"updated_at"`
}

// ErrDuplicate is returned when a unique key already exists.
var ErrDuplicate = errors.New("already exists")

// NormalizeEmail lowercases and trims an email address.
func NormalizeEmail(s string) string { return strings.ToLower(strings.TrimSpace(s)) }

const userCols = "id, email, name, password_hash, sso_sub, totp_secret, totp_enabled, totp_last_step, recovery_codes, is_admin, disabled, created_at, updated_at"

func scanUser(sc interface{ Scan(...any) error }) (User, error) {
	var u User
	var admin, disabled, totp int64
	err := sc.Scan(&u.ID, &u.Email, &u.Name, &u.PasswordHash, &u.SSOSub, &u.TOTPSecret, &totp, &u.TOTPLastStep, &u.RecoveryCodes,
		&admin, &disabled, &u.CreatedAt, &u.UpdatedAt)
	u.IsAdmin, u.Disabled, u.TOTPEnabled = admin != 0, disabled != 0, totp != 0
	return u, err
}

// CreateUser inserts a user. Returns ErrDuplicate if the email is taken.
func CreateUser(ctx context.Context, q db.Querier, u User) error {
	_, err := q.ExecContext(ctx, "INSERT INTO users ("+userCols+") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
		u.ID, u.Email, u.Name, u.PasswordHash, u.SSOSub, u.TOTPSecret, boolInt(u.TOTPEnabled), u.TOTPLastStep, u.RecoveryCodes,
		boolInt(u.IsAdmin), boolInt(u.Disabled), u.CreatedAt, u.UpdatedAt)
	if db.IsUniqueViolation(err) {
		return ErrDuplicate
	}
	return err
}

// BindUserSSO records the identity provider subject an account is bound to.
// The WHERE clause keeps a racing second binding from replacing the first.
func BindUserSSO(ctx context.Context, q db.Querier, userID, sub string) error {
	_, err := q.ExecContext(ctx, "UPDATE users SET sso_sub = ?, updated_at = ? WHERE id = ? AND sso_sub = ''",
		sub, time.Now().UnixMilli(), userID)
	return err
}

// SetTOTP stores the authenticator secret, whether two-step sign-in is on and
// the hashed recovery codes. An empty secret turns it off.
func SetTOTP(ctx context.Context, q db.Querier, userID, secret string, enabled bool, recovery string) error {
	res, err := q.ExecContext(ctx, "UPDATE users SET totp_secret = ?, totp_enabled = ?, totp_last_step = 0, recovery_codes = ?, updated_at = ? WHERE id = ?",
		secret, boolInt(enabled), recovery, time.Now().UnixMilli(), userID)
	return expectOne(res, err)
}

// UseTOTPStep records the code step just used. The condition makes a second
// sign-in with the same code lose, even when both arrive at once.
func UseTOTPStep(ctx context.Context, q db.Querier, userID string, step int64) (bool, error) {
	res, err := q.ExecContext(ctx, "UPDATE users SET totp_last_step = ? WHERE id = ? AND totp_last_step < ?", step, userID, step)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n == 1, err
}

// SetRecoveryCodes replaces the stored recovery code hashes. It succeeds only
// if the list is still old, so a code cannot be used twice in a race.
func SetRecoveryCodes(ctx context.Context, q db.Querier, userID, old, next string) (bool, error) {
	res, err := q.ExecContext(ctx, "UPDATE users SET recovery_codes = ? WHERE id = ? AND recovery_codes = ?", next, userID, old)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n == 1, err
}

// CreatePasswordReset stores a reset link's token hash. Older links for the
// person stop working, so only the newest email counts.
func CreatePasswordReset(ctx context.Context, q db.Querier, userID, tokenHash string, now, expires int64) error {
	if _, err := q.ExecContext(ctx, "DELETE FROM password_resets WHERE user_id = ?", userID); err != nil {
		return err
	}
	_, err := q.ExecContext(ctx, "INSERT INTO password_resets (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
		tokenHash, userID, now, expires)
	return err
}

// TakePasswordReset consumes a reset token and returns its user. Expired and
// unknown tokens return ErrNotFound; a token works once.
func TakePasswordReset(ctx context.Context, q db.Querier, tokenHash string, now int64) (string, error) {
	var userID string
	var expires int64
	err := q.QueryRowContext(ctx, "SELECT user_id, expires_at FROM password_resets WHERE token_hash = ?", tokenHash).Scan(&userID, &expires)
	if db.IsNoRows(err) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", err
	}
	res, err := q.ExecContext(ctx, "DELETE FROM password_resets WHERE token_hash = ?", tokenHash)
	if err := expectOne(res, err); err != nil {
		return "", err
	}
	if expires < now {
		return "", ErrNotFound
	}
	return userID, nil
}

// DeleteExpiredResets removes reset links that can no longer be used.
func DeleteExpiredResets(ctx context.Context, q db.Querier, now int64) error {
	_, err := q.ExecContext(ctx, "DELETE FROM password_resets WHERE expires_at < ?", now)
	return err
}

// UserByEmail finds a user by normalised email.
func UserByEmail(ctx context.Context, q db.Querier, email string) (User, error) {
	u, err := scanUser(q.QueryRowContext(ctx, "SELECT "+userCols+" FROM users WHERE email = ?", NormalizeEmail(email)))
	if db.IsNoRows(err) {
		return User{}, ErrNotFound
	}
	return u, err
}

// UserByID finds a user by ID.
func UserByID(ctx context.Context, q db.Querier, id string) (User, error) {
	u, err := scanUser(q.QueryRowContext(ctx, "SELECT "+userCols+" FROM users WHERE id = ?", id))
	if db.IsNoRows(err) {
		return User{}, ErrNotFound
	}
	return u, err
}

// CountUsers returns the number of users.
func CountUsers(ctx context.Context, q db.Querier) (int, error) {
	var n int
	err := q.QueryRowContext(ctx, "SELECT COUNT(*) FROM users").Scan(&n)
	return n, err
}

// SetPassword replaces a user's password hash.
func SetPassword(ctx context.Context, q db.Querier, userID, hash string) error {
	res, err := q.ExecContext(ctx, "UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
		hash, time.Now().UnixMilli(), userID)
	return expectOne(res, err)
}

// EachUser streams all users (for dumps).
func EachUser(ctx context.Context, q db.Querier, fn func(User) error) error {
	rows, err := q.QueryContext(ctx, "SELECT "+userCols+" FROM users ORDER BY created_at, id")
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		u, err := scanUser(rows)
		if err == nil {
			err = fn(u)
		}
		if err != nil {
			return err
		}
	}
	return rows.Err()
}

// Session is a logged-in device.
type Session struct {
	ID         string `json:"id"`
	UserID     string `json:"-"`
	TokenHash  string `json:"-"`
	DeviceName string `json:"device_name"`
	CreatedAt  int64  `json:"created_at"`
	LastUsedAt int64  `json:"last_used_at"`
	ExpiresAt  int64  `json:"expires_at"`
}

const sessionCols = "id, user_id, token_hash, device_name, created_at, last_used_at, expires_at"

func scanSession(sc interface{ Scan(...any) error }) (Session, error) {
	var s Session
	err := sc.Scan(&s.ID, &s.UserID, &s.TokenHash, &s.DeviceName, &s.CreatedAt, &s.LastUsedAt, &s.ExpiresAt)
	return s, err
}

// CreateSession stores a new session.
func CreateSession(ctx context.Context, q db.Querier, s Session) error {
	_, err := q.ExecContext(ctx, "INSERT INTO sessions ("+sessionCols+") VALUES (?, ?, ?, ?, ?, ?, ?)",
		s.ID, s.UserID, s.TokenHash, s.DeviceName, s.CreatedAt, s.LastUsedAt, s.ExpiresAt)
	return err
}

// SessionByTokenHash finds a session by the SHA-256 of its token.
func SessionByTokenHash(ctx context.Context, q db.Querier, hash string) (Session, error) {
	s, err := scanSession(q.QueryRowContext(ctx, "SELECT "+sessionCols+" FROM sessions WHERE token_hash = ?", hash))
	if db.IsNoRows(err) {
		return Session{}, ErrNotFound
	}
	return s, err
}

// TouchSession extends a session's sliding expiry.
func TouchSession(ctx context.Context, q db.Querier, id string, now, expires int64) error {
	_, err := q.ExecContext(ctx, "UPDATE sessions SET last_used_at = ?, expires_at = ? WHERE id = ?", now, expires, id)
	return err
}

// ListSessions returns a user's sessions, newest first.
func ListSessions(ctx context.Context, q db.Querier, userID string) ([]Session, error) {
	rows, err := q.QueryContext(ctx, "SELECT "+sessionCols+" FROM sessions WHERE user_id = ? ORDER BY last_used_at DESC", userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Session{}
	for rows.Next() {
		s, err := scanSession(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// DeleteSession removes one of a user's sessions.
func DeleteSession(ctx context.Context, q db.Querier, userID, id string) error {
	res, err := q.ExecContext(ctx, "DELETE FROM sessions WHERE id = ? AND user_id = ?", id, userID)
	return expectOne(res, err)
}

// DeleteUserSessions removes all of a user's sessions except keepID.
func DeleteUserSessions(ctx context.Context, q db.Querier, userID, keepID string) error {
	_, err := q.ExecContext(ctx, "DELETE FROM sessions WHERE user_id = ? AND id <> ?", userID, keepID)
	return err
}

// DeleteExpiredSessions removes sessions that expired before now.
func DeleteExpiredSessions(ctx context.Context, q db.Querier, now int64) (int64, error) {
	res, err := q.ExecContext(ctx, "DELETE FROM sessions WHERE expires_at < ?", now)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

func expectOne(res interface{ RowsAffected() (int64, error) }, err error) error {
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}

// SoleOwnedLedgers returns the IDs of ledgers where userID is the only owner.
// Deleting that user deletes these ledgers too.
func SoleOwnedLedgers(ctx context.Context, q db.Querier, userID string) ([]string, error) {
	rows, err := q.QueryContext(ctx, "SELECT m.ledger_id FROM ledger_members m WHERE m.user_id = ? AND m.role = ? "+
		"AND (SELECT COUNT(*) FROM ledger_members o WHERE o.ledger_id = m.ledger_id AND o.role = ?) = 1 ORDER BY m.ledger_id",
		userID, RoleOwner, RoleOwner)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// HandOverLedger makes the longest-standing remaining member (editors before
// viewers) the owner of a ledger that leavingUserID owns alone. It reports
// false when nobody else is a member, so the caller can delete the ledger.
func HandOverLedger(ctx context.Context, q db.Querier, ledgerID, leavingUserID string) (bool, error) {
	var next string
	err := q.QueryRowContext(ctx, "SELECT user_id FROM ledger_members WHERE ledger_id = ? AND user_id <> ? "+
		"ORDER BY CASE role WHEN ? THEN 0 WHEN ? THEN 1 ELSE 2 END, created_at, user_id LIMIT 1",
		ledgerID, leavingUserID, RoleOwner, RoleEditor).Scan(&next)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	_, err = q.ExecContext(ctx, "UPDATE ledger_members SET role = ? WHERE ledger_id = ? AND user_id = ?", RoleOwner, ledgerID, next)
	return err == nil, err
}

// DeleteUser removes a user. Sessions and memberships go with it (ON DELETE CASCADE).
func DeleteUser(ctx context.Context, q db.Querier, id string) error {
	res, err := q.ExecContext(ctx, "DELETE FROM users WHERE id = ?", id)
	return expectOne(res, err)
}
