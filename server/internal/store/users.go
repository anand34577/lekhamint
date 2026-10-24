package store

import (
	"context"
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
	IsAdmin      bool   `json:"is_admin"`
	Disabled     bool   `json:"disabled"`
	CreatedAt    int64  `json:"created_at"`
	UpdatedAt    int64  `json:"updated_at"`
}

// ErrDuplicate is returned when a unique key already exists.
var ErrDuplicate = errors.New("already exists")

// NormalizeEmail lowercases and trims an email address.
func NormalizeEmail(s string) string { return strings.ToLower(strings.TrimSpace(s)) }

const userCols = "id, email, name, password_hash, is_admin, disabled, created_at, updated_at"

func scanUser(sc interface{ Scan(...any) error }) (User, error) {
	var u User
	var admin, disabled int64
	err := sc.Scan(&u.ID, &u.Email, &u.Name, &u.PasswordHash, &admin, &disabled, &u.CreatedAt, &u.UpdatedAt)
	u.IsAdmin, u.Disabled = admin != 0, disabled != 0
	return u, err
}

// CreateUser inserts a user. Returns ErrDuplicate if the email is taken.
func CreateUser(ctx context.Context, q db.Querier, u User) error {
	_, err := q.ExecContext(ctx, "INSERT INTO users ("+userCols+") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
		u.ID, u.Email, u.Name, u.PasswordHash, boolInt(u.IsAdmin), boolInt(u.Disabled), u.CreatedAt, u.UpdatedAt)
	if db.IsUniqueViolation(err) {
		return ErrDuplicate
	}
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
