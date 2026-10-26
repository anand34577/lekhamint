package backup

import (
	"bufio"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/entity"
	"github.com/anand34577/lekhamint/server/internal/store"
)

// Dump format identifiers.
const (
	DumpFormat = "kosh-dump"
	// DumpFormatVersion 2 adds "blob" lines (attachment files) and two-step
	// sign-in secrets on users. Version 3 adds "prefs" (notification choices and
	// the personal assistant key) and "setting" lines (app settings). Older dumps still restore.
	DumpFormatVersion = 3
)

// dumpLine is one NDJSON line. Exactly one payload field is set.
type dumpLine struct {
	Type string `json:"type"` // header | user | prefs | setting | ledger | member | record | blob | footer

	Format        string `json:"format,omitempty"`
	FormatVersion int    `json:"format_version,omitempty"`
	SchemaVersion int    `json:"schema_version,omitempty"`
	CreatedAt     int64  `json:"created_at,omitempty"`

	User     *dumpUser      `json:"user,omitempty"`
	Ledger   *store.Ledger  `json:"ledger,omitempty"`
	Member   *store.Member  `json:"member,omitempty"`
	LedgerID string         `json:"ledger_id,omitempty"`
	Record   *store.Record  `json:"record,omitempty"`
	Blob     *store.Blob    `json:"blob,omitempty"`
	Prefs    *dumpPrefs     `json:"prefs,omitempty"`
	Setting  *store.Setting `json:"setting,omitempty"`

	Count int64 `json:"count,omitempty"` // footer: number of lines before it
}

// dumpUser includes the password hash and SSO binding, which store.User hides
// from JSON.
type dumpUser struct {
	store.User
	PasswordHash  string `json:"password_hash"`
	SSOSub        string `json:"sso_sub"`
	TOTPSecret    string `json:"totp_secret,omitempty"`
	RecoveryCodes string `json:"recovery_codes,omitempty"`
}

// dumpPrefs includes the fields store.Prefs hides from JSON: whose they are and the secrets.
type dumpPrefs struct {
	store.Prefs
	UserID      string `json:"user_id"`
	GotifyToken string `json:"gotify_token,omitempty"`
	AiAPIKey    string `json:"ai_api_key,omitempty"`
}

// Dump writes the whole server (users, ledgers, members, records) as
// gzip-compressed NDJSON. Memory use is constant. Sessions are not included:
// users sign in again after a restore.
func Dump(ctx context.Context, d *db.DB, w io.Writer) error {
	gz := gzip.NewWriter(w)
	bw := bufio.NewWriterSize(gz, 64<<10)
	enc := json.NewEncoder(bw)
	var count int64
	emit := func(l dumpLine) error { count++; return enc.Encode(l) }

	schema, err := d.SchemaVersion(ctx)
	if err != nil {
		return err
	}
	if err := emit(dumpLine{Type: "header", Format: DumpFormat, FormatVersion: DumpFormatVersion,
		SchemaVersion: schema, CreatedAt: time.Now().UnixMilli()}); err != nil {
		return err
	}
	if err := store.EachUser(ctx, d, func(u store.User) error {
		return emit(dumpLine{Type: "user", User: &dumpUser{User: u, PasswordHash: u.PasswordHash, SSOSub: u.SSOSub,
			TOTPSecret: u.TOTPSecret, RecoveryCodes: u.RecoveryCodes}})
	}); err != nil {
		return err
	}
	prefs, err := store.AllPrefs(ctx, d)
	if err != nil {
		return err
	}
	for _, p := range prefs {
		if err := emit(dumpLine{Type: "prefs", Prefs: &dumpPrefs{Prefs: p, UserID: p.UserID, GotifyToken: p.GotifyToken, AiAPIKey: p.AiAPIKey}}); err != nil {
			return err
		}
	}
	if err := store.EachSetting(ctx, d, func(st store.Setting) error {
		return emit(dumpLine{Type: "setting", Setting: &st})
	}); err != nil {
		return err
	}
	var ledgers []store.Ledger
	if err := store.EachLedger(ctx, d, func(l store.Ledger) error {
		ledgers = append(ledgers, l)
		return emit(dumpLine{Type: "ledger", Ledger: &l})
	}); err != nil {
		return err
	}
	if err := store.EachMember(ctx, d, func(m store.Member) error {
		return emit(dumpLine{Type: "member", Member: &m})
	}); err != nil {
		return err
	}
	for _, l := range ledgers {
		// Bounded by the seq read above, so each ledger is a consistent snapshot
		// that matches the ledger line's seq.
		if err := store.EachRecord(ctx, d, l.ID, l.Seq, func(r store.Record) error {
			return emit(dumpLine{Type: "record", LedgerID: l.ID, Record: &r})
		}); err != nil {
			return err
		}
		if err := store.EachBlob(ctx, d, l.ID, func(b store.Blob) error {
			return emit(dumpLine{Type: "blob", LedgerID: l.ID, Blob: &b})
		}); err != nil {
			return err
		}
	}
	if err := enc.Encode(dumpLine{Type: "footer", Count: count}); err != nil {
		return err
	}
	if err := bw.Flush(); err != nil {
		return err
	}
	return gz.Close()
}

// RestoreStats summarises a dump restore.
type RestoreStats struct {
	Users, Ledgers, Members, Records int64
}

// RestoreDump loads a dump into an empty database in one transaction. Any
// error, including a missing footer (a truncated file), rolls everything back.
func RestoreDump(ctx context.Context, d *db.DB, r io.Reader) (RestoreStats, error) {
	var st RestoreStats
	gz, err := gzip.NewReader(r)
	if err != nil {
		return st, fmt.Errorf("not a gzip file: %w", err)
	}
	defer gz.Close()
	dec := json.NewDecoder(bufio.NewReaderSize(gz, 64<<10))
	dec.UseNumber()

	err = d.InTx(ctx, func(tx *db.Tx) error {
		if n, err := store.CountUsers(ctx, tx); err != nil || n > 0 {
			if err != nil {
				return err
			}
			return errors.New("target database is not empty; restore a dump only into a fresh database")
		}
		var lines int64
		sawHeader, sawFooter := false, false
		for {
			var l dumpLine
			if err := dec.Decode(&l); err == io.EOF {
				break
			} else if err != nil {
				return fmt.Errorf("line %d: %w", lines+1, err)
			}
			if sawFooter {
				return errors.New("data after footer")
			}
			if !sawHeader && l.Type != "header" {
				return errors.New("missing header: not a LekhaMint dump")
			}
			switch l.Type {
			case "header":
				if l.Format != DumpFormat || l.FormatVersion < 1 || l.FormatVersion > DumpFormatVersion {
					return fmt.Errorf("unsupported dump (format %q v%d)", l.Format, l.FormatVersion)
				}
				if l.SchemaVersion > db.LatestSchemaVersion() {
					return fmt.Errorf("dump schema v%d is newer than this binary", l.SchemaVersion)
				}
				sawHeader = true
			case "user":
				u := l.User.User
				u.PasswordHash = l.User.PasswordHash
				u.SSOSub = l.User.SSOSub
				u.TOTPSecret, u.RecoveryCodes = l.User.TOTPSecret, l.User.RecoveryCodes
				if u.TOTPSecret == "" {
					u.TOTPEnabled = false
				}
				if err := store.CreateUser(ctx, tx, u); err != nil {
					return fmt.Errorf("user %s: %w", u.Email, err)
				}
				st.Users++
			case "prefs":
				if l.Prefs == nil {
					return errors.New("prefs line without prefs")
				}
				p := l.Prefs.Prefs
				p.UserID, p.GotifyToken, p.AiAPIKey = l.Prefs.UserID, l.Prefs.GotifyToken, l.Prefs.AiAPIKey
				if err := store.PutPrefs(ctx, tx, p); err != nil {
					return fmt.Errorf("preferences of %s: %w", p.UserID, err)
				}
			case "setting":
				if l.Setting == nil || !store.ValidSettingKey(l.Setting.Key) || !json.Valid(l.Setting.Value) {
					return errors.New("bad setting line")
				}
				if err := store.RestoreSetting(ctx, tx, *l.Setting); err != nil {
					return fmt.Errorf("setting %s: %w", l.Setting.Key, err)
				}
			case "ledger":
				if err := store.CreateLedger(ctx, tx, *l.Ledger); err != nil {
					return fmt.Errorf("ledger %s: %w", l.Ledger.ID, err)
				}
				st.Ledgers++
			case "member":
				if err := store.AddMember(ctx, tx, *l.Member); err != nil {
					return fmt.Errorf("member: %w", err)
				}
				st.Members++
			case "record":
				if err := restoreRecord(ctx, tx, l.LedgerID, l.Record); err != nil {
					return err
				}
				st.Records++
			case "blob":
				if l.Blob == nil {
					return errors.New("blob line without blob")
				}
				b := *l.Blob
				b.LedgerID = l.LedgerID
				if err := store.PutBlob(ctx, tx, b); err != nil {
					return fmt.Errorf("file %s: %w", b.SHA256, err)
				}
			case "footer":
				if l.Count != lines {
					return fmt.Errorf("dump is incomplete: footer says %d lines, read %d", l.Count, lines)
				}
				sawFooter = true
				continue
			default:
				return fmt.Errorf("unknown line type %q", l.Type)
			}
			lines++
		}
		if !sawFooter {
			return errors.New("dump is truncated (no footer); nothing was restored")
		}
		return nil
	})
	return st, err
}

func restoreRecord(ctx context.Context, q db.Querier, ledgerID string, r *store.Record) error {
	if r == nil {
		return errors.New("record line without record")
	}
	spec, ok := entity.Lookup(r.Entity)
	if !ok {
		return fmt.Errorf("record %s: unknown entity %q", r.ID, r.Entity)
	}
	raw := map[string]any(r.Data)
	if r.Deleted {
		r.Data = spec.NormalizeLenient(raw)
	} else {
		data, err := spec.Normalize(raw)
		if err != nil {
			return fmt.Errorf("record %s %s: %w", r.Entity, r.ID, err)
		}
		r.Data = data
	}
	return store.PutRecord(ctx, q, spec, ledgerID, *r)
}
