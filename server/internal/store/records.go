// Package store is the persistence layer. Functions take a db.Querier, so
// they work both on the pool and inside a transaction.
package store

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/entity"
)

// ErrNotFound is returned when a row does not exist.
var ErrNotFound = errors.New("not found")

// Record is a synced entity row in API form.
type Record struct {
	Entity  string      `json:"entity"`
	ID      string      `json:"id"`
	HLC     string      `json:"hlc"`
	Seq     int64       `json:"seq"`
	Deleted bool        `json:"deleted"`
	Data    entity.Data `json:"data"`
}

const syncCols = "id, hlc, seq, deleted"

func selectList(s *entity.Spec) string {
	return syncCols + ", " + strings.Join(s.Columns(), ", ")
}

func scanRecord(s *entity.Spec, sc interface{ Scan(...any) error }) (Record, error) {
	var (
		r       Record
		deleted int64
	)
	vals := make([]any, len(s.Fields))
	dest := make([]any, 0, 4+len(vals))
	dest = append(dest, &r.ID, &r.HLC, &r.Seq, &deleted)
	for i := range vals {
		dest = append(dest, &vals[i])
	}
	if err := sc.Scan(dest...); err != nil {
		return Record{}, err
	}
	r.Entity = s.Name
	r.Deleted = deleted != 0
	r.Data = s.FromDB(vals)
	return r, nil
}

// GetRecord loads one record, including tombstones.
func GetRecord(ctx context.Context, q db.Querier, s *entity.Spec, ledgerID, id string) (Record, error) {
	row := q.QueryRowContext(ctx,
		"SELECT "+selectList(s)+" FROM "+s.Name+" WHERE ledger_id = ? AND id = ?", ledgerID, id)
	r, err := scanRecord(s, row)
	if db.IsNoRows(err) {
		return Record{}, ErrNotFound
	}
	return r, err
}

// PutRecord inserts or replaces a record. The caller has already validated
// the data and allocated seq.
func PutRecord(ctx context.Context, q db.Querier, s *entity.Spec, ledgerID string, r Record) error {
	cols := s.Columns()
	all := append([]string{"ledger_id", "id", "hlc", "seq", "deleted", "server_updated_at"}, cols...)
	updates := make([]string, 0, len(all)-2)
	for _, c := range all[2:] {
		updates = append(updates, c+" = excluded."+c)
	}
	query := "INSERT INTO " + s.Name + " (" + strings.Join(all, ", ") + ") VALUES (" +
		placeholders(len(all)) + ") ON CONFLICT (ledger_id, id) DO UPDATE SET " + strings.Join(updates, ", ")
	args := append([]any{ledgerID, r.ID, r.HLC, r.Seq, boolInt(r.Deleted), time.Now().UnixMilli()}, s.DBArgs(r.Data)...)
	_, err := q.ExecContext(ctx, query, args...)
	return err
}

// ChangesSince returns up to limit records with since < seq <= upper across all
// entity types, ordered by seq. hasMore reports whether more exist.
func ChangesSince(ctx context.Context, q db.Querier, ledgerID string, since, upper int64, limit int) (out []Record, hasMore bool, err error) {
	for _, s := range entity.All() {
		rows, err := q.QueryContext(ctx,
			"SELECT "+selectList(s)+" FROM "+s.Name+" WHERE ledger_id = ? AND seq > ? AND seq <= ? ORDER BY seq LIMIT ?",
			ledgerID, since, upper, limit+1)
		if err != nil {
			return nil, false, fmt.Errorf("pull %s: %w", s.Name, err)
		}
		for rows.Next() {
			r, err := scanRecord(s, rows)
			if err != nil {
				rows.Close()
				return nil, false, err
			}
			out = append(out, r)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, false, err
		}
	}
	// seq is unique per ledger across all tables, so a merge by seq gives
	// the global order. Each table returned at most limit+1 rows, so the
	// first limit+1 merged rows are exactly the global first limit+1.
	sort.Slice(out, func(i, j int) bool { return out[i].Seq < out[j].Seq })
	if len(out) > limit {
		return out[:limit], true, nil
	}
	return out, false, nil
}

// ListOptions controls ListRecords.
type ListOptions struct {
	After          string // keyset cursor: return ids greater than this
	Limit          int
	IncludeDeleted bool
}

// ListRecords returns one entity type's records ordered by id (keyset paged).
func ListRecords(ctx context.Context, q db.Querier, s *entity.Spec, ledgerID string, opt ListOptions) ([]Record, error) {
	query := "SELECT " + selectList(s) + " FROM " + s.Name + " WHERE ledger_id = ? AND id > ?"
	if !opt.IncludeDeleted {
		query += " AND deleted = 0"
	}
	query += " ORDER BY id LIMIT ?"
	rows, err := q.QueryContext(ctx, query, ledgerID, opt.After, opt.Limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Record
	for rows.Next() {
		r, err := scanRecord(s, rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// EachRecord streams every record (seq <= upper) of every entity in a ledger, in
// dependency order, without loading them all into memory.
func EachRecord(ctx context.Context, q db.Querier, ledgerID string, upper int64, fn func(Record) error) error {
	for _, s := range entity.All() {
		rows, err := q.QueryContext(ctx,
			"SELECT "+selectList(s)+" FROM "+s.Name+" WHERE ledger_id = ? AND seq <= ? ORDER BY seq", ledgerID, upper)
		if err != nil {
			return err
		}
		for rows.Next() {
			r, err := scanRecord(s, rows)
			if err == nil {
				err = fn(r)
			}
			if err != nil {
				rows.Close()
				return err
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
	}
	return nil
}

func placeholders(n int) string {
	return strings.TrimSuffix(strings.Repeat("?, ", n), ", ")
}

func boolInt(b bool) int64 {
	if b {
		return 1
	}
	return 0
}
