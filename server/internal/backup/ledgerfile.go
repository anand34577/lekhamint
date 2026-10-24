// Package backup implements server snapshots, full-server dumps and per-ledger
// JSON exports and imports. See docs/06-backup-restore.md.
package backup

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/entity"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
)

// Ledger export format identifiers.
const (
	LedgerFormat        = "kosh-ledger"
	LedgerFormatVersion = 1
)

type exportRecord struct {
	ID      string      `json:"id"`
	HLC     string      `json:"hlc"`
	Deleted bool        `json:"deleted"`
	Data    entity.Data `json:"data"`
}

// ExportLedger streams a ledger as JSON (the kosh-ledger format) without
// holding all records in memory. Records are bounded by the ledger's seq at
// the start, so the export is a consistent point-in-time view.
func ExportLedger(ctx context.Context, d *db.DB, l store.Ledger, w io.Writer) error {
	bw := bufio.NewWriterSize(w, 64<<10)
	schema, err := d.SchemaVersion(ctx)
	if err != nil {
		return err
	}
	l.Role = ""
	head, err := json.Marshal(map[string]any{
		"format": LedgerFormat, "format_version": LedgerFormatVersion, "schema_version": schema,
		"exported_at": time.Now().UnixMilli(), "ledger": l,
	})
	if err != nil {
		return err
	}
	// Re-open the header object to append "entities".
	bw.Write(head[:len(head)-1])
	bw.WriteString(`,"entities":{`)
	current, first := "", true
	enc := json.NewEncoder(bw)
	err = store.EachRecord(ctx, d, l.ID, l.Seq, func(r store.Record) error {
		if r.Entity != current {
			if current != "" {
				bw.WriteString("],")
			}
			fmt.Fprintf(bw, "%q:[", r.Entity)
			current, first = r.Entity, true
		}
		if !first {
			bw.WriteByte(',')
		}
		first = false
		return enc.Encode(exportRecord{ID: r.ID, HLC: r.HLC, Deleted: r.Deleted, Data: r.Data})
	})
	if err != nil {
		return err
	}
	if current != "" {
		bw.WriteString("]")
	}
	bw.WriteString("}}\n")
	return bw.Flush()
}

// LedgerReader streams changes out of a kosh-ledger JSON document.
type LedgerReader struct {
	dec        *json.Decoder
	sawFormat  bool
	inEntities bool
	inArray    bool
	entity     string
	done       bool
}

// NewLedgerReader starts reading a kosh-ledger document.
func NewLedgerReader(r io.Reader) (*LedgerReader, error) {
	dec := json.NewDecoder(r)
	dec.UseNumber()
	if err := expectDelim(dec, '{'); err != nil {
		return nil, err
	}
	return &LedgerReader{dec: dec}, nil
}

// ErrBadLedgerFile is returned for documents that are not kosh-ledger files.
var ErrBadLedgerFile = errors.New("not a LekhaMint ledger export")

// Next returns the next change. ok is false at the end of the document.
func (lr *LedgerReader) Next() (ch syncer.Change, ok bool, err error) {
	dec := lr.dec
	for !lr.done {
		switch {
		case lr.inArray:
			if dec.More() {
				var rec exportRecord
				if err := dec.Decode(&rec); err != nil {
					return ch, false, fmt.Errorf("%s record: %w", lr.entity, err)
				}
				return syncer.Change{Entity: lr.entity, ID: rec.ID, HLC: rec.HLC, Deleted: rec.Deleted, Data: rec.Data}, true, nil
			}
			if err := expectDelim(dec, ']'); err != nil {
				return ch, false, err
			}
			lr.inArray = false
		case lr.inEntities:
			if dec.More() {
				name, err := stringToken(dec)
				if err != nil {
					return ch, false, err
				}
				if err := expectDelim(dec, '['); err != nil {
					return ch, false, err
				}
				lr.entity, lr.inArray = name, true
				continue
			}
			if err := expectDelim(dec, '}'); err != nil {
				return ch, false, err
			}
			lr.inEntities = false
		default:
			if !dec.More() {
				if err := expectDelim(dec, '}'); err != nil {
					return ch, false, err
				}
				lr.done = true
				if !lr.sawFormat {
					return ch, false, ErrBadLedgerFile
				}
				break
			}
			key, err := stringToken(dec)
			if err != nil {
				return ch, false, err
			}
			switch key {
			case "format":
				var f string
				if err := dec.Decode(&f); err != nil || f != LedgerFormat {
					return ch, false, ErrBadLedgerFile
				}
				lr.sawFormat = true
			case "format_version":
				var v int
				if err := dec.Decode(&v); err != nil || v < 1 || v > LedgerFormatVersion {
					return ch, false, fmt.Errorf("unsupported ledger export version %d", v)
				}
			case "entities":
				if !lr.sawFormat {
					return ch, false, ErrBadLedgerFile
				}
				if err := expectDelim(dec, '{'); err != nil {
					return ch, false, err
				}
				lr.inEntities = true
			default:
				var skip json.RawMessage
				if err := dec.Decode(&skip); err != nil {
					return ch, false, err
				}
			}
		}
	}
	return ch, false, nil
}

func expectDelim(dec *json.Decoder, want json.Delim) error {
	t, err := dec.Token()
	if err != nil {
		return fmt.Errorf("read JSON: %w", err)
	}
	if d, ok := t.(json.Delim); !ok || d != want {
		return fmt.Errorf("malformed JSON: expected %q", want)
	}
	return nil
}

func stringToken(dec *json.Decoder) (string, error) {
	t, err := dec.Token()
	if err != nil {
		return "", fmt.Errorf("read JSON: %w", err)
	}
	s, ok := t.(string)
	if !ok {
		return "", errors.New("malformed JSON: expected a key")
	}
	return s, nil
}
