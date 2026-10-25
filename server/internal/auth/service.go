package auth

import (
	"context"
	"errors"
	"net/mail"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/store"
)

// Errors.
var (
	ErrInvalidCredentials = errors.New("invalid email or password")
	ErrSignupClosed       = errors.New("sign-up is disabled on this server; ask the administrator for an account")
	ErrEmailTaken         = errors.New("an account with this email already exists")
	ErrInvalidEmail       = errors.New("email address is not valid")
	ErrInvalidName        = errors.New("name must be 1 to 100 characters")
	ErrUnauthenticated    = errors.New("not signed in or session expired")
	ErrSSOClosed          = errors.New("this server has no account for that identity; ask the administrator to invite you")
	ErrSSOAccount         = errors.New("this account signs in through your identity provider and has no password here")
	ErrSSOMismatch        = errors.New("this account is already linked to a different identity at your provider; ask the administrator if you believe that is wrong")
	ErrAccountDisabled    = errors.New("this account is disabled")
)

// NoPassword is stored as the password hash of accounts created through single
// sign-on. It is not a valid hash, so no password ever matches it.
const NoPassword = "!sso"

// AbsoluteSessionTTL caps how long a session may live however often it is
// used. The sliding expiry can keep an actively used session alive much
// longer; this forces a fresh sign-in at least once a year so a stolen token
// cannot work forever.
const AbsoluteSessionTTL = 365 * 24 * time.Hour

// Service implements registration, login and session validation.
type Service struct {
	DB          *db.DB
	AllowSignup bool
	SessionTTL  time.Duration
	Now         func() time.Time

	dummyOnce sync.Once
	dummyHash string
	// firstUser guards the "the first account becomes admin" decision. Two
	// concurrent sign-ups on a fresh server would otherwise both count zero
	// users and both become admin.
	firstUser sync.Mutex
}

// NewService returns an auth service with defaults.
func NewService(d *db.DB, allowSignup bool, ttl time.Duration) *Service {
	if ttl <= 0 {
		ttl = 90 * 24 * time.Hour
	}
	return &Service{DB: d, AllowSignup: allowSignup, SessionTTL: ttl, Now: time.Now}
}

// SignupAllowed reports whether a new user may register now. The first user
// can always register (and becomes admin), so a fresh server is usable.
func (s *Service) SignupAllowed(ctx context.Context) (bool, error) {
	if s.AllowSignup {
		return true, nil
	}
	n, err := store.CountUsers(ctx, s.DB)
	return n == 0, err
}

// CreateUser validates input and creates a user. The first user becomes admin.
func (s *Service) CreateUser(ctx context.Context, email, name, password string) (store.User, error) {
	email = store.NormalizeEmail(email)
	addr, err := mail.ParseAddress(email)
	if err != nil || addr.Address != email || len(email) > 254 {
		return store.User{}, ErrInvalidEmail
	}
	name = strings.TrimSpace(name)
	if name == "" {
		name, _, _ = strings.Cut(email, "@")
	}
	if utf8.RuneCountInString(name) > 100 {
		return store.User{}, ErrInvalidName
	}
	if err := CheckPasswordPolicy(password); err != nil {
		return store.User{}, err
	}
	hash, err := HashPassword(password)
	if err != nil {
		return store.User{}, err
	}
	now := s.Now().UnixMilli()
	u := store.User{ID: ids.NewV7(), Email: email, Name: name, PasswordHash: hash, CreatedAt: now, UpdatedAt: now}
	err = s.DB.InTx(ctx, func(tx *db.Tx) error {
		s.firstUser.Lock()
		defer s.firstUser.Unlock()
		n, err := store.CountUsers(ctx, tx)
		if err != nil {
			return err
		}
		u.IsAdmin = n == 0
		return store.CreateUser(ctx, tx, u)
	})
	if errors.Is(err, store.ErrDuplicate) {
		return store.User{}, ErrEmailTaken
	}
	return u, err
}

// Register creates a user if sign-up is allowed and returns a new session.
func (s *Service) Register(ctx context.Context, email, name, password, device string) (store.User, string, error) {
	ok, err := s.SignupAllowed(ctx)
	if err != nil {
		return store.User{}, "", err
	}
	if !ok {
		return store.User{}, "", ErrSignupClosed
	}
	u, err := s.CreateUser(ctx, email, name, password)
	if err != nil {
		return store.User{}, "", err
	}
	token, err := s.newSession(ctx, u.ID, device)
	return u, token, err
}

// Login checks credentials and returns a new session token. Unknown emails
// cost the same hashing time as wrong passwords, so timing does not reveal
// which emails have accounts.
func (s *Service) Login(ctx context.Context, email, password, device string) (store.User, string, error) {
	u, err := store.UserByEmail(ctx, s.DB, email)
	if errors.Is(err, store.ErrNotFound) {
		_, _ = VerifyPassword(s.dummy(), password)
		return store.User{}, "", ErrInvalidCredentials
	}
	if err != nil {
		return store.User{}, "", err
	}
	ok, err := VerifyPassword(u.PasswordHash, password)
	if err != nil || !ok || u.Disabled {
		return store.User{}, "", ErrInvalidCredentials
	}
	token, err := s.newSession(ctx, u.ID, device)
	return u, token, err
}

// SSOLogin signs in the person an identity provider vouched for. subject is
// the provider's "sub" claim for that person. An existing account with the
// same email is used only if it is not bound to a different subject: the
// first SSO sign-in binds the account to that subject, and every later one
// must present the same subject, so another identity bearing the same email
// cannot take the account over. Otherwise an account is created when
// autoCreate allows it. The first user of a fresh server becomes admin.
func (s *Service) SSOLogin(ctx context.Context, email, name, subject, device string, autoCreate bool) (store.User, string, error) {
	email = store.NormalizeEmail(email)
	u, err := store.UserByEmail(ctx, s.DB, email)
	switch {
	case err == nil:
		if u.SSOSub != "" {
			if u.SSOSub != subject {
				return store.User{}, "", ErrSSOMismatch
			}
		} else if err := store.BindUserSSO(ctx, s.DB, u.ID, subject); err != nil {
			return store.User{}, "", err
		}
	case errors.Is(err, store.ErrNotFound):
		n, cerr := store.CountUsers(ctx, s.DB)
		if cerr != nil {
			return store.User{}, "", cerr
		}
		if !autoCreate && n > 0 {
			return store.User{}, "", ErrSSOClosed
		}
		addr, perr := mail.ParseAddress(email)
		if perr != nil || addr.Address != email || len(email) > 254 {
			return store.User{}, "", ErrInvalidEmail
		}
		name = strings.TrimSpace(name)
		if name == "" {
			name, _, _ = strings.Cut(email, "@")
		}
		if utf8.RuneCountInString(name) > 100 {
			name = string([]rune(name)[:100])
		}
		now := s.Now().UnixMilli()
		u = store.User{ID: ids.NewV7(), Email: email, Name: name, PasswordHash: NoPassword, SSOSub: subject, CreatedAt: now, UpdatedAt: now}
		err = s.DB.InTx(ctx, func(tx *db.Tx) error {
			s.firstUser.Lock()
			defer s.firstUser.Unlock()
			count, err := store.CountUsers(ctx, tx)
			if err != nil {
				return err
			}
			u.IsAdmin = count == 0
			return store.CreateUser(ctx, tx, u)
		})
		if errors.Is(err, store.ErrDuplicate) { // created by a parallel sign-in
			u, err = store.UserByEmail(ctx, s.DB, email)
			if err == nil {
				// Someone else just created it; apply the same binding rules.
				if u.SSOSub != "" && u.SSOSub != subject {
					return store.User{}, "", ErrSSOMismatch
				} else if u.SSOSub == "" {
					err = store.BindUserSSO(ctx, s.DB, u.ID, subject)
				}
			}
		}
		if err != nil {
			return store.User{}, "", err
		}
	default:
		return store.User{}, "", err
	}
	if u.Disabled {
		return store.User{}, "", ErrAccountDisabled
	}
	token, err := s.newSession(ctx, u.ID, device)
	return u, token, err
}

func (s *Service) dummy() string {
	s.dummyOnce.Do(func() { s.dummyHash, _ = HashPassword("timing-equaliser-not-a-password") })
	return s.dummyHash
}

func (s *Service) newSession(ctx context.Context, userID, device string) (string, error) {
	device = strings.TrimSpace(device)
	if device == "" {
		device = "Unknown device"
	}
	if utf8.RuneCountInString(device) > 100 {
		device = string([]rune(device)[:100])
	}
	token, hash, err := NewToken()
	if err != nil {
		return "", err
	}
	now := s.Now()
	err = store.CreateSession(ctx, s.DB, store.Session{
		ID: ids.NewV7(), UserID: userID, TokenHash: hash, DeviceName: device,
		CreatedAt: now.UnixMilli(), LastUsedAt: now.UnixMilli(), ExpiresAt: now.Add(s.SessionTTL).UnixMilli(),
	})
	return token, err
}

// Authenticate resolves a bearer token to its user and session. Sessions
// slide: each use (at most once a minute) pushes the expiry forward.
func (s *Service) Authenticate(ctx context.Context, token string) (store.User, store.Session, error) {
	if !strings.HasPrefix(token, TokenPrefix) {
		return store.User{}, store.Session{}, ErrUnauthenticated
	}
	sess, err := store.SessionByTokenHash(ctx, s.DB, HashToken(token))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			err = ErrUnauthenticated
		}
		return store.User{}, store.Session{}, err
	}
	now := s.Now()
	if now.UnixMilli() > sess.ExpiresAt || now.Sub(time.UnixMilli(sess.CreatedAt)) > AbsoluteSessionTTL {
		return store.User{}, store.Session{}, ErrUnauthenticated
	}
	u, err := store.UserByID(ctx, s.DB, sess.UserID)
	if err != nil || u.Disabled {
		return store.User{}, store.Session{}, ErrUnauthenticated
	}
	if now.UnixMilli()-sess.LastUsedAt > time.Minute.Milliseconds() {
		sess.LastUsedAt, sess.ExpiresAt = now.UnixMilli(), now.Add(s.SessionTTL).UnixMilli()
		_ = store.TouchSession(ctx, s.DB, sess.ID, sess.LastUsedAt, sess.ExpiresAt)
	}
	return u, sess, nil
}

// ChangePassword verifies the current password, sets a new one and signs out
// every other session.
func (s *Service) ChangePassword(ctx context.Context, u store.User, sessionID, current, next string) error {
	if u.PasswordHash == NoPassword {
		return ErrSSOAccount
	}
	ok, err := VerifyPassword(u.PasswordHash, current)
	if err != nil || !ok {
		return ErrInvalidCredentials
	}
	return s.SetPassword(ctx, u.ID, sessionID, next)
}

// SetPassword sets a password without checking the old one (admin/CLI use)
// and revokes all sessions except keepSessionID.
func (s *Service) SetPassword(ctx context.Context, userID, keepSessionID, password string) error {
	if err := CheckPasswordPolicy(password); err != nil {
		return err
	}
	hash, err := HashPassword(password)
	if err != nil {
		return err
	}
	return s.DB.InTx(ctx, func(tx *db.Tx) error {
		if err := store.SetPassword(ctx, tx, userID, hash); err != nil {
			return err
		}
		return store.DeleteUserSessions(ctx, tx, userID, keepSessionID)
	})
}

// DeleteAccount permanently deletes a user after checking their password.
// Ledgers they alone own are deleted with all their data; they are removed
// from ledgers that have another owner. Returns the IDs of deleted ledgers.
func (s *Service) DeleteAccount(ctx context.Context, u store.User, password string) ([]string, error) {
	if u.PasswordHash == NoPassword {
		// Single sign-on accounts have no password; typing their email confirms the deletion.
		if store.NormalizeEmail(password) != u.Email {
			return nil, ErrInvalidCredentials
		}
	} else if ok, err := VerifyPassword(u.PasswordHash, password); err != nil || !ok {
		return nil, ErrInvalidCredentials
	}
	var err error
	var deleted []string
	err = s.DB.InTx(ctx, func(tx *db.Tx) error {
		ids, err := store.SoleOwnedLedgers(ctx, tx, u.ID)
		if err != nil {
			return err
		}
		for _, id := range ids {
			if err := store.DeleteLedger(ctx, tx, id); err != nil {
				return err
			}
		}
		deleted = ids
		return store.DeleteUser(ctx, tx, u.ID)
	})
	return deleted, err
}
