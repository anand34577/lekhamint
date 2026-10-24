// Package syncer implements the ledger change feed: client pushes with
// last-writer-wins conflict resolution, gap-free pulls, server-stamped writes
// for the REST API, and streaming imports. See docs/05-sync-protocol.md.
package syncer

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/entity"
	"github.com/anand34577/lekhamint/server/internal/hlc"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/store"
)

// Limits.
const (
	MaxPushBatch     = 1000
	DefaultPullLimit = 500
	MaxPullLimit     = 2000
	DefaultMaxSkew   = 24 * time.Hour
)

// Rejection codes.
const (
	CodeUnknownEntity = "unknown_entity"
	CodeInvalidID     = "invalid_id"
	CodeInvalidHLC    = "invalid_hlc"
	CodeHLCInFuture   = "hlc_in_future"
	CodeValidation    = "validation"
)

// Change is one client-side write.
type Change struct {
	Entity  string         `json:"entity"`
	ID      string         `json:"id"`
	HLC     string         `json:"hlc"`
	Deleted bool           `json:"deleted"`
	Data    map[string]any `json:"data"`
}

// Rejection explains why a change was not applied. The client should surface
// it to the user rather than retry.
type Rejection struct {
	Entity  string            `json:"entity"`
	ID      string            `json:"id"`
	Error   string            `json:"error"`
	Message string            `json:"message"`
	Fields  map[string]string `json:"fields,omitempty"`
}

// PushResult is the outcome of a push.
type PushResult struct {
	Accepted  []string       `json:"accepted"`
	Conflicts []store.Record `json:"conflicts"`
	Rejected  []Rejection    `json:"rejected"`
	Cursor    int64          `json:"cursor"`
}

// PullResult is one page of the change feed.
type PullResult struct {
	Changes    []store.Record `json:"changes"`
	NextCursor int64          `json:"next_cursor"`
	HasMore    bool           `json:"has_more"`
	ServerTime int64          `json:"server_time"`
}

// Service is safe for concurrent use.
type Service struct {
	DB      *db.DB
	Clock   *hlc.Clock
	MaxSkew time.Duration
	Now     func() time.Time
}

// New returns a Service with a fresh server clock.
func New(d *db.DB) *Service {
	return &Service{DB: d, Clock: hlc.NewClock(""), MaxSkew: DefaultMaxSkew, Now: time.Now}
}

type outcome int

const (
	accepted outcome = iota
	conflicted
	rejected
)

// apply validates one change and writes it if it wins. seq is the ledger's
// change-feed position and is advanced for every write.
func (s *Service) apply(ctx context.Context, q db.Querier, ledgerID string, ch Change, seq *int64) (outcome, store.Record, *Rejection) {
	reject := func(code, msg string, fields map[string]string) (outcome, store.Record, *Rejection) {
		return rejected, store.Record{}, &Rejection{Entity: ch.Entity, ID: ch.ID, Error: code, Message: msg, Fields: fields}
	}
	spec, ok := entity.Lookup(ch.Entity)
	if !ok {
		return reject(CodeUnknownEntity, fmt.Sprintf("unknown entity %q", ch.Entity), nil)
	}
	id, ok := ids.Normalize(ch.ID)
	if !ok {
		return reject(CodeInvalidID, "id must be a UUID", nil)
	}
	ch.ID = id
	ts, err := hlc.Parse(ch.HLC)
	if err != nil {
		return reject(CodeInvalidHLC, "hlc is malformed", nil)
	}
	if limit := s.Now().Add(s.MaxSkew).UnixMilli(); ts.Wall > limit {
		return reject(CodeHLCInFuture, "device clock is too far ahead; fix the date and time settings", nil)
	}

	existing, err := store.GetRecord(ctx, q, spec, ledgerID, id)
	found := err == nil
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return reject("internal", err.Error(), nil)
	}
	if found {
		switch {
		case existing.HLC == ch.HLC:
			// Same write pushed again (for example after a lost response).
			return accepted, existing, nil
		case existing.HLC > ch.HLC:
			return conflicted, existing, nil
		}
	}

	// Merge the incoming fields over the stored ones, so fields an older
	// client does not know about are preserved.
	merged := map[string]any{}
	if found {
		for k, v := range existing.Data {
			merged[k] = v
		}
	}
	for k, v := range ch.Data {
		merged[k] = v
	}

	var data entity.Data
	if ch.Deleted {
		data = spec.NormalizeLenient(merged)
	} else {
		data, err = spec.Normalize(merged)
		if err != nil {
			var ve *entity.ValidationError
			if errors.As(err, &ve) {
				return reject(CodeValidation, ve.Error(), ve.Fields)
			}
			return reject(CodeValidation, err.Error(), nil)
		}
	}

	*seq++
	rec := store.Record{Entity: spec.Name, ID: id, HLC: ch.HLC, Seq: *seq, Deleted: ch.Deleted, Data: data}
	if err := store.PutRecord(ctx, q, spec, ledgerID, rec); err != nil {
		*seq--
		return reject("internal", err.Error(), nil)
	}
	s.Clock.Observe(ch.HLC)
	return accepted, rec, nil
}

// Push applies a batch of client changes atomically. Invalid changes are
// rejected individually; database errors abort the whole batch.
func (s *Service) Push(ctx context.Context, ledgerID string, changes []Change) (PushResult, error) {
	if len(changes) > MaxPushBatch {
		return PushResult{}, fmt.Errorf("at most %d changes per push", MaxPushBatch)
	}
	res := PushResult{Accepted: []string{}, Conflicts: []store.Record{}, Rejected: []Rejection{}}
	err := s.DB.InTx(ctx, func(tx *db.Tx) error {
		seq, err := store.LockLedgerSeq(ctx, tx, ledgerID)
		if err != nil {
			return err
		}
		start := seq
		for _, ch := range changes {
			out, rec, rej := s.apply(ctx, tx, ledgerID, ch, &seq)
			switch out {
			case accepted:
				res.Accepted = append(res.Accepted, rec.ID)
			case conflicted:
				res.Conflicts = append(res.Conflicts, rec)
			case rejected:
				if rej.Error == "internal" {
					return errors.New(rej.Message)
				}
				res.Rejected = append(res.Rejected, *rej)
			}
		}
		res.Cursor = seq
		if seq != start {
			return store.SetLedgerSeq(ctx, tx, ledgerID, seq)
		}
		return nil
	})
	return res, err
}

// Pull returns changes after cursor since. Only rows at or below the
// ledger's committed seq are read; that makes the page gap-free even while
// writers are active, because a ledger's seq and its rows commit together.
func (s *Service) Pull(ctx context.Context, ledgerID string, since int64, limit int) (PullResult, error) {
	if limit <= 0 || limit > MaxPullLimit {
		limit = DefaultPullLimit
	}
	l, err := store.GetLedger(ctx, s.DB, ledgerID)
	if err != nil {
		return PullResult{}, err
	}
	changes, more, err := store.ChangesSince(ctx, s.DB, ledgerID, since, l.Seq, limit)
	if err != nil {
		return PullResult{}, err
	}
	next := since
	if len(changes) > 0 {
		next = changes[len(changes)-1].Seq
	} else if l.Seq > since {
		next = l.Seq
	}
	if changes == nil {
		changes = []store.Record{}
	}
	return PullResult{Changes: changes, NextCursor: next, HasMore: more, ServerTime: s.Now().UnixMilli()}, nil
}

// WriteOp is a server-side write used by the REST API and seeding.
type WriteOp struct {
	Entity  string
	ID      string // empty: generate a UUIDv7
	Data    map[string]any
	Deleted bool
	// MustExist / MustNotExist guard updates and creates. SkipIfExists turns
	// a create into a no-op when the record exists, even in the Trash.
	// MustExistDeleted restores a record from the Trash.
	MustExist, MustNotExist, SkipIfExists, MustExistDeleted bool
}

// WriteResult reports what happened to one WriteOp.
type WriteResult struct {
	Entity string        `json:"entity"`
	ID     string        `json:"id"`
	Status string        `json:"status"` // created, updated, deleted, restored, skipped
	Record *store.Record `json:"record,omitempty"`
}

// Errors returned by Write.
var (
	ErrExists = errors.New("record already exists")
)

// RejectionError wraps a rejected server-side write.
type RejectionError struct{ Rejection }

func (e *RejectionError) Error() string { return e.Message }

// Write applies server-stamped writes atomically. Any invalid op aborts the
// batch with a *RejectionError, store.ErrNotFound or ErrExists.
func (s *Service) Write(ctx context.Context, ledgerID string, ops []WriteOp) ([]store.Record, error) {
	results, err := s.WriteResults(ctx, ledgerID, ops)
	if err != nil {
		return nil, err
	}
	out := make([]store.Record, 0, len(results))
	for _, r := range results {
		if r.Record != nil {
			out = append(out, *r.Record)
		}
	}
	return out, nil
}

// WriteResults is Write with a per-op outcome, including skipped ops.
func (s *Service) WriteResults(ctx context.Context, ledgerID string, ops []WriteOp) ([]WriteResult, error) {
	out := make([]WriteResult, 0, len(ops))
	err := s.DB.InTx(ctx, func(tx *db.Tx) error {
		seq, err := store.LockLedgerSeq(ctx, tx, ledgerID)
		if err != nil {
			return err
		}
		start := seq
		for _, op := range ops {
			spec, ok := entity.Lookup(op.Entity)
			if !ok {
				return &RejectionError{Rejection{Entity: op.Entity, Error: CodeUnknownEntity, Message: "unknown entity"}}
			}
			if op.ID == "" {
				op.ID = ids.NewV7()
			}
			id, ok := ids.Normalize(op.ID)
			if !ok {
				return &RejectionError{Rejection{Entity: op.Entity, ID: op.ID, Error: CodeInvalidID, Message: "id must be a UUID"}}
			}
			existing, err := store.GetRecord(ctx, tx, spec, ledgerID, id)
			found := err == nil
			switch {
			case err != nil && !errors.Is(err, store.ErrNotFound):
				return err
			case op.SkipIfExists && found:
				out = append(out, WriteResult{Entity: spec.Name, ID: id, Status: "skipped"})
				continue
			case op.MustExist && (!found || existing.Deleted):
				return store.ErrNotFound
			case op.MustExistDeleted && (!found || !existing.Deleted):
				return store.ErrNotFound
			case op.MustNotExist && found:
				return ErrExists
			}
			if found {
				s.Clock.Observe(existing.HLC) // our stamp must beat the stored one
			}
			ch := Change{Entity: op.Entity, ID: id, HLC: s.Clock.Now(), Deleted: op.Deleted, Data: op.Data}
			res, rec, rej := s.apply(ctx, tx, ledgerID, ch, &seq)
			switch res {
			case rejected:
				if rej.Error == "internal" {
					return errors.New(rej.Message)
				}
				return &RejectionError{*rej}
			case conflicted:
				return fmt.Errorf("unexpected conflict writing %s %s", op.Entity, id)
			}
			status := "created"
			switch {
			case op.Deleted:
				status = "deleted"
			case found && existing.Deleted:
				status = "restored"
			case found:
				status = "updated"
			}
			out = append(out, WriteResult{Entity: spec.Name, ID: id, Status: status, Record: &rec})
		}
		if seq != start {
			return store.SetLedgerSeq(ctx, tx, ledgerID, seq)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return out, nil
}

// ImportResult summarises an import.
type ImportResult struct {
	Accepted  int         `json:"accepted"`
	Skipped   int         `json:"skipped_newer_exists"`
	Rejected  []Rejection `json:"rejected"`
	Cursor    int64       `json:"cursor"`
	Truncated bool        `json:"rejected_truncated,omitempty"`
}

// Import merges a stream of changes (from a backup) in a single transaction,
// with the same rules as Push. next returns ok=false at the end of the stream.
// A stream error rolls everything back.
func (s *Service) Import(ctx context.Context, ledgerID string, next func() (Change, bool, error)) (ImportResult, error) {
	res := ImportResult{Rejected: []Rejection{}}
	err := s.DB.InTx(ctx, func(tx *db.Tx) error {
		seq, err := store.LockLedgerSeq(ctx, tx, ledgerID)
		if err != nil {
			return err
		}
		start := seq
		for {
			ch, ok, err := next()
			if err != nil {
				return err
			}
			if !ok {
				break
			}
			out, _, rej := s.apply(ctx, tx, ledgerID, ch, &seq)
			switch out {
			case accepted:
				res.Accepted++
			case conflicted:
				res.Skipped++
			case rejected:
				if rej.Error == "internal" {
					return errors.New(rej.Message)
				}
				if len(res.Rejected) < 100 {
					res.Rejected = append(res.Rejected, *rej)
				} else {
					res.Truncated = true
				}
			}
		}
		res.Cursor = seq
		if seq != start {
			return store.SetLedgerSeq(ctx, tx, ledgerID, seq)
		}
		return nil
	})
	return res, err
}
