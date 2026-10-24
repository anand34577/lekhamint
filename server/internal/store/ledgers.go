package store

import (
	"context"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
)

// Roles a ledger member can have.
const (
	RoleOwner  = "owner"
	RoleEditor = "editor"
	RoleViewer = "viewer"
)

// ValidRole reports whether r is a known role.
func ValidRole(r string) bool { return r == RoleOwner || r == RoleEditor || r == RoleViewer }

// Ledger is a book of accounts: the unit of sync and sharing.
type Ledger struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	BaseCurrency string `json:"base_currency"`
	Seq          int64  `json:"seq"`
	CreatedBy    string `json:"created_by"`
	CreatedAt    int64  `json:"created_at"`
	UpdatedAt    int64  `json:"updated_at"`
	Role         string `json:"role,omitempty"` // the caller's role, when listed for a user
}

// Member is a user's membership in a ledger.
type Member struct {
	LedgerID  string `json:"ledger_id"`
	UserID    string `json:"user_id"`
	Email     string `json:"email,omitempty"`
	Name      string `json:"name,omitempty"`
	Role      string `json:"role"`
	CreatedAt int64  `json:"created_at"`
}

const ledgerCols = "l.id, l.name, l.base_currency, l.seq, l.created_by, l.created_at, l.updated_at"

func scanLedger(sc interface{ Scan(...any) error }, withRole bool) (Ledger, error) {
	var l Ledger
	dest := []any{&l.ID, &l.Name, &l.BaseCurrency, &l.Seq, &l.CreatedBy, &l.CreatedAt, &l.UpdatedAt}
	if withRole {
		dest = append(dest, &l.Role)
	}
	err := sc.Scan(dest...)
	return l, err
}

// CreateLedger inserts a ledger. Returns ErrDuplicate if the ID exists.
func CreateLedger(ctx context.Context, q db.Querier, l Ledger) error {
	_, err := q.ExecContext(ctx,
		"INSERT INTO ledgers (id, name, base_currency, seq, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
		l.ID, l.Name, l.BaseCurrency, l.Seq, l.CreatedBy, l.CreatedAt, l.UpdatedAt)
	if db.IsUniqueViolation(err) {
		return ErrDuplicate
	}
	return err
}

// GetLedger loads a ledger.
func GetLedger(ctx context.Context, q db.Querier, id string) (Ledger, error) {
	l, err := scanLedger(q.QueryRowContext(ctx, "SELECT "+ledgerCols+" FROM ledgers l WHERE l.id = ?", id), false)
	if db.IsNoRows(err) {
		return Ledger{}, ErrNotFound
	}
	return l, err
}

// LedgersForUser lists the ledgers a user belongs to, with their role.
func LedgersForUser(ctx context.Context, q db.Querier, userID string) ([]Ledger, error) {
	rows, err := q.QueryContext(ctx, "SELECT "+ledgerCols+", m.role FROM ledgers l "+
		"JOIN ledger_members m ON m.ledger_id = l.id WHERE m.user_id = ? ORDER BY l.created_at, l.id", userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Ledger{}
	for rows.Next() {
		l, err := scanLedger(rows, true)
		if err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

// UpdateLedger changes a ledger's name and base currency.
func UpdateLedger(ctx context.Context, q db.Querier, id, name, baseCurrency string) error {
	res, err := q.ExecContext(ctx, "UPDATE ledgers SET name = ?, base_currency = ?, updated_at = ? WHERE id = ?",
		name, baseCurrency, time.Now().UnixMilli(), id)
	return expectOne(res, err)
}

// DeleteLedger permanently removes a ledger and, via ON DELETE CASCADE,
// every record and membership in it.
func DeleteLedger(ctx context.Context, q db.Querier, id string) error {
	res, err := q.ExecContext(ctx, "DELETE FROM ledgers WHERE id = ?", id)
	return expectOne(res, err)
}

// LockLedgerSeq locks the ledger row for the rest of the transaction and
// returns its current change-feed position. All writers to a ledger
// serialise here, so seq order equals commit order.
func LockLedgerSeq(ctx context.Context, q db.Querier, ledgerID string) (int64, error) {
	var seq int64
	err := q.QueryRowContext(ctx, "UPDATE ledgers SET seq = seq WHERE id = ? RETURNING seq", ledgerID).Scan(&seq)
	if db.IsNoRows(err) {
		return 0, ErrNotFound
	}
	return seq, err
}

// SetLedgerSeq stores the change-feed position after a write batch.
func SetLedgerSeq(ctx context.Context, q db.Querier, ledgerID string, seq int64) error {
	_, err := q.ExecContext(ctx, "UPDATE ledgers SET seq = ?, updated_at = ? WHERE id = ?", seq, time.Now().UnixMilli(), ledgerID)
	return err
}

// EachLedger streams all ledgers (for dumps).
func EachLedger(ctx context.Context, q db.Querier, fn func(Ledger) error) error {
	rows, err := q.QueryContext(ctx, "SELECT "+ledgerCols+" FROM ledgers l ORDER BY l.created_at, l.id")
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		l, err := scanLedger(rows, false)
		if err == nil {
			err = fn(l)
		}
		if err != nil {
			return err
		}
	}
	return rows.Err()
}

// AddMember adds or updates a membership.
func AddMember(ctx context.Context, q db.Querier, m Member) error {
	_, err := q.ExecContext(ctx,
		"INSERT INTO ledger_members (ledger_id, user_id, role, created_at) VALUES (?, ?, ?, ?) "+
			"ON CONFLICT (ledger_id, user_id) DO UPDATE SET role = excluded.role",
		m.LedgerID, m.UserID, m.Role, m.CreatedAt)
	return err
}

// MemberRole returns a user's role in a ledger, or ErrNotFound.
func MemberRole(ctx context.Context, q db.Querier, ledgerID, userID string) (string, error) {
	var role string
	err := q.QueryRowContext(ctx, "SELECT role FROM ledger_members WHERE ledger_id = ? AND user_id = ?", ledgerID, userID).Scan(&role)
	if db.IsNoRows(err) {
		return "", ErrNotFound
	}
	return role, err
}

// ListMembers lists a ledger's members with their names.
func ListMembers(ctx context.Context, q db.Querier, ledgerID string) ([]Member, error) {
	rows, err := q.QueryContext(ctx, "SELECT m.ledger_id, m.user_id, u.email, u.name, m.role, m.created_at "+
		"FROM ledger_members m JOIN users u ON u.id = m.user_id WHERE m.ledger_id = ? ORDER BY m.created_at", ledgerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Member{}
	for rows.Next() {
		var m Member
		if err := rows.Scan(&m.LedgerID, &m.UserID, &m.Email, &m.Name, &m.Role, &m.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// CountOwners returns how many owners a ledger has.
func CountOwners(ctx context.Context, q db.Querier, ledgerID string) (int, error) {
	var n int
	err := q.QueryRowContext(ctx, "SELECT COUNT(*) FROM ledger_members WHERE ledger_id = ? AND role = ?", ledgerID, RoleOwner).Scan(&n)
	return n, err
}

// RemoveMember deletes a membership.
func RemoveMember(ctx context.Context, q db.Querier, ledgerID, userID string) error {
	res, err := q.ExecContext(ctx, "DELETE FROM ledger_members WHERE ledger_id = ? AND user_id = ?", ledgerID, userID)
	return expectOne(res, err)
}

// EachMember streams all memberships (for dumps).
func EachMember(ctx context.Context, q db.Querier, fn func(Member) error) error {
	rows, err := q.QueryContext(ctx, "SELECT ledger_id, user_id, role, created_at FROM ledger_members ORDER BY ledger_id, user_id")
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var m Member
		if err := rows.Scan(&m.LedgerID, &m.UserID, &m.Role, &m.CreatedAt); err != nil {
			return err
		}
		if err := fn(m); err != nil {
			return err
		}
	}
	return rows.Err()
}
