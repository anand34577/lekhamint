package store

import (
	"context"
	"encoding/json"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/entity"
)

// HistoryEntry is one saved version of a record: who wrote it, from which
// device, and what it looked like afterwards. It answers "who changed this?"
// in a shared book.
type HistoryEntry struct {
	Seq       int64       `json:"seq"`
	Entity    string      `json:"entity"`
	RecordID  string      `json:"record_id"`
	HLC       string      `json:"hlc"`
	UserID    string      `json:"user_id"`
	UserName  string      `json:"user_name"`
	UserEmail string      `json:"user_email"`
	Device    string      `json:"device"`
	Deleted   bool        `json:"deleted"`
	Data      entity.Data `json:"data"`
	At        int64       `json:"at"`
}

// AddHistory saves one version. seq is the record's change-feed position,
// which is unique per ledger.
func AddHistory(ctx context.Context, q db.Querier, ledgerID string, e HistoryEntry) error {
	data, err := json.Marshal(e.Data)
	if err != nil {
		return err
	}
	_, err = q.ExecContext(ctx, `INSERT INTO record_history (ledger_id, seq, entity, record_id, hlc, user_id, device, deleted, data, at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (ledger_id, seq) DO NOTHING`,
		ledgerID, e.Seq, e.Entity, e.RecordID, e.HLC, e.UserID, e.Device, boolInt(e.Deleted), string(data), e.At)
	return err
}

// History returns the saved versions of one record, newest first.
func History(ctx context.Context, q db.Querier, ledgerID, entityName, recordID string, limit int) ([]HistoryEntry, error) {
	rows, err := q.QueryContext(ctx, `SELECT h.seq, h.entity, h.record_id, h.hlc, h.user_id, COALESCE(u.name, ''), COALESCE(u.email, ''),
			h.device, h.deleted, h.data, h.at
		FROM record_history h LEFT JOIN users u ON u.id = h.user_id
		WHERE h.ledger_id = ? AND h.record_id = ? AND h.entity = ? ORDER BY h.seq DESC LIMIT ?`,
		ledgerID, recordID, entityName, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []HistoryEntry{}
	for rows.Next() {
		var (
			e       HistoryEntry
			deleted int64
			data    string
		)
		if err := rows.Scan(&e.Seq, &e.Entity, &e.RecordID, &e.HLC, &e.UserID, &e.UserName, &e.UserEmail, &e.Device, &deleted, &data, &e.At); err != nil {
			return nil, err
		}
		e.Deleted = deleted != 0
		if err := json.Unmarshal([]byte(data), &e.Data); err != nil {
			e.Data = entity.Data{}
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// VersionAt returns a record as it stood at Unix ms at (server time), from the change
// history. ok is false when the history does not reach back that far (imports are not
// recorded, and old versions are pruned).
func VersionAt(ctx context.Context, q db.Querier, ledgerID, entityName, recordID string, at int64) (entity.Data, bool, error) {
	var data string
	err := q.QueryRowContext(ctx, `SELECT data FROM record_history WHERE ledger_id = ? AND record_id = ? AND entity = ? AND at <= ?
		ORDER BY seq DESC LIMIT 1`, ledgerID, recordID, entityName, at).Scan(&data)
	if db.IsNoRows(err) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	var d entity.Data
	if err := json.Unmarshal([]byte(data), &d); err != nil {
		return nil, false, nil
	}
	return d, true, nil
}

// PruneHistory removes versions saved before the cutoff (Unix ms).
func PruneHistory(ctx context.Context, q db.Querier, before int64) (int64, error) {
	res, err := q.ExecContext(ctx, "DELETE FROM record_history WHERE at < ?", before)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// PurgeTombstones permanently removes records deleted before the cutoff
// (server time, Unix ms) from every ledger, and records per ledger the highest
// change-feed position removed. A device whose sync cursor is below that
// position missed a delete it can no longer be told about, so its next pull is
// answered with "resync required" (see PurgedSeq). Returns rows removed.
func PurgeTombstones(ctx context.Context, d *db.DB, before, now int64) (int64, error) {
	var total int64
	err := d.InTx(ctx, func(tx *db.Tx) error {
		maxSeq := map[string]int64{}
		for _, s := range entity.All() {
			rows, err := tx.QueryContext(ctx, "SELECT ledger_id, MAX(seq) FROM "+s.Name+" WHERE deleted = 1 AND server_updated_at < ? GROUP BY ledger_id", before)
			if err != nil {
				return err
			}
			for rows.Next() {
				var lid string
				var seq int64
				if err := rows.Scan(&lid, &seq); err != nil {
					rows.Close()
					return err
				}
				if seq > maxSeq[lid] {
					maxSeq[lid] = seq
				}
			}
			rows.Close()
			if err := rows.Err(); err != nil {
				return err
			}
			res, err := tx.ExecContext(ctx, "DELETE FROM "+s.Name+" WHERE deleted = 1 AND server_updated_at < ?", before)
			if err != nil {
				return err
			}
			n, _ := res.RowsAffected()
			total += n
		}
		for lid, seq := range maxSeq {
			if _, err := tx.ExecContext(ctx, `INSERT INTO ledger_purges (ledger_id, purged_seq, purged_at) VALUES (?, ?, ?)
				ON CONFLICT (ledger_id) DO UPDATE SET purged_seq = CASE WHEN excluded.purged_seq > ledger_purges.purged_seq
					THEN excluded.purged_seq ELSE ledger_purges.purged_seq END, purged_at = excluded.purged_at`, lid, seq, now); err != nil {
				return err
			}
		}
		return nil
	})
	return total, err
}

// PurgedSeq is the highest change-feed position whose tombstones were removed
// from a ledger (0 if none were).
func PurgedSeq(ctx context.Context, q db.Querier, ledgerID string) (int64, error) {
	var seq int64
	err := q.QueryRowContext(ctx, "SELECT purged_seq FROM ledger_purges WHERE ledger_id = ?", ledgerID).Scan(&seq)
	if db.IsNoRows(err) {
		return 0, nil
	}
	return seq, err
}
