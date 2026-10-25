package store

import (
	"context"

	"github.com/anand34577/lekhamint/server/internal/db"
)

// Blob is an attachment file, stored once per ledger by its SHA-256.
type Blob struct {
	LedgerID  string `json:"ledger_id"`
	SHA256    string `json:"sha256"`
	Mime      string `json:"mime"`
	Size      int64  `json:"size"`
	Data      []byte `json:"data,omitempty"`
	CreatedAt int64  `json:"created_at"`
}

// PutBlob stores a file. Storing the same content again is a no-op.
func PutBlob(ctx context.Context, q db.Querier, b Blob) error {
	_, err := q.ExecContext(ctx, `INSERT INTO blobs (ledger_id, sha256, mime, size, data, created_at) VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT (ledger_id, sha256) DO NOTHING`, b.LedgerID, b.SHA256, b.Mime, b.Size, b.Data, b.CreatedAt)
	return err
}

// GetBlob loads a file with its content.
func GetBlob(ctx context.Context, q db.Querier, ledgerID, sha string) (Blob, error) {
	b := Blob{LedgerID: ledgerID, SHA256: sha}
	err := q.QueryRowContext(ctx, "SELECT mime, size, data, created_at FROM blobs WHERE ledger_id = ? AND sha256 = ?", ledgerID, sha).
		Scan(&b.Mime, &b.Size, &b.Data, &b.CreatedAt)
	if db.IsNoRows(err) {
		return Blob{}, ErrNotFound
	}
	return b, err
}

// HasBlob reports whether a ledger already stores a file.
func HasBlob(ctx context.Context, q db.Querier, ledgerID, sha string) (bool, error) {
	var n int
	err := q.QueryRowContext(ctx, "SELECT COUNT(*) FROM blobs WHERE ledger_id = ? AND sha256 = ?", ledgerID, sha).Scan(&n)
	return n > 0, err
}

// LedgerBlobBytes is the total size of a ledger's files.
func LedgerBlobBytes(ctx context.Context, q db.Querier, ledgerID string) (int64, error) {
	var n int64
	err := q.QueryRowContext(ctx, "SELECT CAST(COALESCE(SUM(size), 0) AS BIGINT) FROM blobs WHERE ledger_id = ?", ledgerID).Scan(&n)
	return n, err
}

// DeleteOrphanBlobs removes files created before the cutoff that no
// attachment record (live or in the Trash) points to any more. Files in the
// Trash are kept so restoring a transaction brings its receipt back.
func DeleteOrphanBlobs(ctx context.Context, q db.Querier, before int64) (int64, error) {
	res, err := q.ExecContext(ctx, `DELETE FROM blobs WHERE created_at < ? AND NOT EXISTS (
		SELECT 1 FROM attachments a WHERE a.ledger_id = blobs.ledger_id AND a.sha256 = blobs.sha256)`, before)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// EachBlob streams every file of a ledger (for dumps).
func EachBlob(ctx context.Context, q db.Querier, ledgerID string, fn func(Blob) error) error {
	rows, err := q.QueryContext(ctx, "SELECT sha256, mime, size, data, created_at FROM blobs WHERE ledger_id = ? ORDER BY sha256", ledgerID)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		b := Blob{LedgerID: ledgerID}
		if err := rows.Scan(&b.SHA256, &b.Mime, &b.Size, &b.Data, &b.CreatedAt); err != nil {
			return err
		}
		if err := fn(b); err != nil {
			return err
		}
	}
	return rows.Err()
}
