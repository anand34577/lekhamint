// Package ledger manages ledgers (books), their members and access control.
package ledger

import (
	"context"
	"errors"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/seed"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
)

// Access levels, from least to most privileged.
type Access int

const (
	Read Access = iota
	Write
	Own
)

// Errors.
var (
	ErrForbidden    = errors.New("you do not have permission for this ledger")
	ErrNotFound     = errors.New("ledger not found")
	ErrInvalid      = errors.New("invalid ledger")
	ErrLastOwner    = errors.New("a ledger must keep at least one owner")
	ErrUserNotFound = errors.New("no user with that email on this server")
	ErrInvalidRole  = errors.New("role must be owner, editor or viewer")
)

var currencyRe = regexp.MustCompile(`^[A-Z0-9]{3,10}$`)

// Service is the ledger use-case layer.
type Service struct {
	DB   *db.DB
	Sync *syncer.Service
}

// Authorize returns the user's role if it grants at least need. Non-members
// get ErrNotFound, so ledger IDs cannot be probed.
func (s *Service) Authorize(ctx context.Context, ledgerID, userID string, need Access) (string, error) {
	role, err := store.MemberRole(ctx, s.DB, ledgerID, userID)
	if errors.Is(err, store.ErrNotFound) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", err
	}
	if level(role) < need {
		return role, ErrForbidden
	}
	return role, nil
}

func level(role string) Access {
	switch role {
	case store.RoleOwner:
		return Own
	case store.RoleEditor:
		return Write
	}
	return Read
}

// CreateInput is the input to Create.
type CreateInput struct {
	ID                    string `json:"id"`
	Name                  string `json:"name"`
	BaseCurrency          string `json:"base_currency"`
	SeedDefaultCategories bool   `json:"seed_default_categories"`
}

// Create registers a ledger owned by userID. Clients create ledgers offline
// and send their own ID; repeating the call is idempotent for members.
// created reports whether a new ledger was made.
func (s *Service) Create(ctx context.Context, userID string, in CreateInput) (l store.Ledger, created bool, err error) {
	if in.ID == "" {
		in.ID = ids.NewV7()
	}
	id, ok := ids.Normalize(in.ID)
	if !ok {
		return store.Ledger{}, false, errors.Join(ErrInvalid, errors.New("id must be a UUID"))
	}
	name, cur, err := validate(in.Name, in.BaseCurrency)
	if err != nil {
		return store.Ledger{}, false, err
	}
	now := time.Now().UnixMilli()
	l = store.Ledger{ID: id, Name: name, BaseCurrency: cur, CreatedBy: userID, CreatedAt: now, UpdatedAt: now, Role: store.RoleOwner}
	err = s.DB.InTx(ctx, func(tx *db.Tx) error {
		if err := store.CreateLedger(ctx, tx, l); err != nil {
			return err
		}
		return store.AddMember(ctx, tx, store.Member{LedgerID: id, UserID: userID, Role: store.RoleOwner, CreatedAt: now})
	})
	if errors.Is(err, store.ErrDuplicate) {
		if _, aerr := s.Authorize(ctx, id, userID, Read); aerr != nil {
			// Not a member: answer as for any unknown ledger, so IDs cannot be probed.
			return store.Ledger{}, false, aerr
		}
		existing, err := s.Get(ctx, id, userID)
		return existing, false, err
	}
	if err != nil {
		return store.Ledger{}, false, err
	}
	if in.SeedDefaultCategories {
		ops, err := seed.DefaultCategories()
		if err != nil {
			return l, true, err
		}
		if _, err := s.Sync.Write(ctx, id, ops); err != nil {
			return l, true, err
		}
		if l, err = s.Get(ctx, id, userID); err != nil {
			return l, true, err
		}
	}
	return l, true, nil
}

// Get returns a ledger with the caller's role.
func (s *Service) Get(ctx context.Context, ledgerID, userID string) (store.Ledger, error) {
	role, err := s.Authorize(ctx, ledgerID, userID, Read)
	if err != nil {
		return store.Ledger{}, err
	}
	l, err := store.GetLedger(ctx, s.DB, ledgerID)
	if errors.Is(err, store.ErrNotFound) {
		return store.Ledger{}, ErrNotFound
	}
	l.Role = role
	return l, err
}

// Update renames a ledger or changes its base currency (owners only).
func (s *Service) Update(ctx context.Context, ledgerID, userID string, name, baseCurrency *string) (store.Ledger, error) {
	if _, err := s.Authorize(ctx, ledgerID, userID, Own); err != nil {
		return store.Ledger{}, err
	}
	cur, err := s.Get(ctx, ledgerID, userID)
	if err != nil {
		return store.Ledger{}, err
	}
	n, c := cur.Name, cur.BaseCurrency
	if name != nil {
		n = *name
	}
	if baseCurrency != nil {
		c = *baseCurrency
	}
	if n, c, err = validate(n, c); err != nil {
		return store.Ledger{}, err
	}
	if err := store.UpdateLedger(ctx, s.DB, ledgerID, n, c); err != nil {
		return store.Ledger{}, err
	}
	return s.Get(ctx, ledgerID, userID)
}

// Delete permanently removes a ledger and all its data (owners only).
func (s *Service) Delete(ctx context.Context, ledgerID, userID string) error {
	if _, err := s.Authorize(ctx, ledgerID, userID, Own); err != nil {
		return err
	}
	return store.DeleteLedger(ctx, s.DB, ledgerID)
}

// AddMember shares a ledger with another user by email (owners only).
func (s *Service) AddMember(ctx context.Context, ledgerID, userID, email, role string) (store.Member, error) {
	if _, err := s.Authorize(ctx, ledgerID, userID, Own); err != nil {
		return store.Member{}, err
	}
	if !store.ValidRole(role) {
		return store.Member{}, ErrInvalidRole
	}
	u, err := store.UserByEmail(ctx, s.DB, email)
	if errors.Is(err, store.ErrNotFound) {
		return store.Member{}, ErrUserNotFound
	}
	if err != nil {
		return store.Member{}, err
	}
	m := store.Member{LedgerID: ledgerID, UserID: u.ID, Email: u.Email, Name: u.Name, Role: role, CreatedAt: time.Now().UnixMilli()}
	err = s.DB.InTx(ctx, func(tx *db.Tx) error {
		if err := store.AddMember(ctx, tx, m); err != nil {
			return err
		}
		return s.ensureOwner(ctx, tx, ledgerID)
	})
	return m, err
}

// RemoveMember removes a member. Owners can remove anyone; anyone can leave.
func (s *Service) RemoveMember(ctx context.Context, ledgerID, userID, targetID string) error {
	need := Own
	if targetID == userID {
		need = Read
	}
	if _, err := s.Authorize(ctx, ledgerID, userID, need); err != nil {
		return err
	}
	return s.DB.InTx(ctx, func(tx *db.Tx) error {
		if err := store.RemoveMember(ctx, tx, ledgerID, targetID); err != nil {
			return err
		}
		return s.ensureOwner(ctx, tx, ledgerID)
	})
}

// Members lists a ledger's members.
func (s *Service) Members(ctx context.Context, ledgerID, userID string) ([]store.Member, error) {
	if _, err := s.Authorize(ctx, ledgerID, userID, Read); err != nil {
		return nil, err
	}
	return store.ListMembers(ctx, s.DB, ledgerID)
}

func (s *Service) ensureOwner(ctx context.Context, q db.Querier, ledgerID string) error {
	n, err := store.CountOwners(ctx, q, ledgerID)
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrLastOwner
	}
	return nil
}

func validate(name, currency string) (string, string, error) {
	name = strings.TrimSpace(name)
	if name == "" || utf8.RuneCountInString(name) > 100 {
		return "", "", errors.Join(ErrInvalid, errors.New("name must be 1 to 100 characters"))
	}
	currency = strings.ToUpper(strings.TrimSpace(currency))
	if !currencyRe.MatchString(currency) {
		return "", "", errors.Join(ErrInvalid, errors.New("base_currency must be a currency code such as INR"))
	}
	return name, currency, nil
}
