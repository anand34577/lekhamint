package httpapi

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/entity"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
)

// --- password reset by email ---

// passwordResetReady reports whether this server can email reset links: it
// needs email set up and its public address to build the link from.
func (s *Server) passwordResetReady() bool {
	return s.PublicURL != "" && s.notifier().HasEmail()
}

// forgotPassword emails a one-hour reset link. It answers the same way
// whether or not the email has an account, so it cannot be used to find out
// who uses this server.
func (s *Server) forgotPassword(w http.ResponseWriter, r *http.Request) {
	if s.OIDCOnly {
		s.fail(w, r, errPasswordOff)
		return
	}
	if !s.passwordResetReady() {
		writeError(w, http.StatusNotImplemented, "reset_unavailable",
			"this server cannot send reset emails; ask the administrator to reset your password (lekhamint user passwd)", nil)
		return
	}
	var in struct {
		Email string `json:"email"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	u, token, ok, err := s.Auth.RequestPasswordReset(r.Context(), in.Email)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if ok {
		link := s.PublicURL + "/#/reset?token=" + url.QueryEscape(token)
		body := "Someone asked to reset the password for your LekhaMint account (" + u.Email + ").\n\n" +
			"Open this link within an hour to choose a new password:\n" + link + "\n\n" +
			"If this was not you, ignore this email: your password stays the same and the link stops working in an hour."
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
			defer cancel()
			if err := s.notifier().Email(ctx, u.Email, "Reset your password", "Hi "+u.Name+",\n\n"+body); err != nil {
				s.Log.Warn("password reset email not sent", "err", err)
			}
		}()
	}
	writeJSON(w, http.StatusAccepted, map[string]any{"sent": true})
}

// resetPassword sets a new password from a reset link and signs out everywhere.
func (s *Server) resetPassword(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Token    string `json:"token"`
		Password string `json:"new_password"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	u, err := s.Auth.ResetPassword(r.Context(), strings.TrimSpace(in.Token), in.Password)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.securityNotice(u, "Password reset", "The password for your account was just reset with an emailed link, and every device was signed out. If this was not you, contact the administrator now.")
	writeJSON(w, http.StatusOK, map[string]any{"reset": true})
}

// --- two-step sign-in ---

func (s *Server) totpStatus(w http.ResponseWriter, r *http.Request) {
	u := currentUser(r)
	writeJSON(w, http.StatusOK, map[string]any{"enabled": u.TOTPEnabled, "recovery_codes_left": auth.RecoveryCodesLeft(u.RecoveryCodes)})
}

func (s *Server) totpSetup(w http.ResponseWriter, r *http.Request) {
	secret, link, err := s.Auth.StartTOTP(r.Context(), currentUser(r), "LekhaMint")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"secret": secret, "otpauth_url": link})
}

func (s *Server) totpEnable(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Code string `json:"code"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	u, err := store.UserByID(r.Context(), s.DB, currentUser(r).ID) // the secret was saved after this session loaded the user
	if err != nil {
		s.fail(w, r, err)
		return
	}
	codes, err := s.Auth.EnableTOTP(r.Context(), u, currentSession(r).ID, in.Code)
	if err != nil {
		s.failOTP(w, r, err)
		return
	}
	s.securityNotice(u, "Two-step sign-in turned on", "Signing in to your account now also needs a code from your authenticator app. Other devices were signed out. If this was not you, contact the administrator now.")
	writeJSON(w, http.StatusOK, map[string]any{"enabled": true, "recovery_codes": codes})
}

func (s *Server) totpDisable(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Password string `json:"password"`
		Code     string `json:"code"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	u := currentUser(r)
	if err := s.Auth.DisableTOTP(r.Context(), u, in.Password, in.Code); err != nil {
		s.failOTP(w, r, err)
		return
	}
	if u.TOTPEnabled {
		s.securityNotice(u, "Two-step sign-in turned off", "Signing in to your account no longer needs a code from your authenticator app. If this was not you, change your password and contact the administrator now.")
	}
	writeJSON(w, http.StatusOK, map[string]any{"enabled": false})
}

func (s *Server) totpRecoveryCodes(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Code string `json:"code"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	codes, err := s.Auth.NewRecoveryCodesFor(r.Context(), currentUser(r), in.Code)
	if err != nil {
		s.failOTP(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"recovery_codes": codes})
}

// failOTP reports a wrong code on a signed-in request as 400, not 401, so the
// apps do not mistake it for an ended session and sign the person out.
func (s *Server) failOTP(w http.ResponseWriter, r *http.Request, err error) {
	if errors.Is(err, auth.ErrInvalidOTP) {
		writeError(w, http.StatusBadRequest, "invalid_otp", err.Error(), nil)
		return
	}
	s.fail(w, r, err)
}

// --- attachment files ---

// attachmentTypes are the files accepted as attachments: photos and PDFs.
var attachmentTypes = map[string]bool{
	"image/jpeg": true, "image/png": true, "image/webp": true, "image/gif": true,
	"image/heic": true, "image/heif": true, "application/pdf": true,
}

// sniffType decides a file's type from its content, not from what the client
// claims, so a page or script cannot be stored as a "receipt".
func sniffType(data []byte, claimed string) (string, bool) {
	if t := http.DetectContentType(data); attachmentTypes[t] {
		return t, true
	}
	// Go does not recognise HEIC/HEIF (iPhone photos); trust the claim only
	// when the file has the ISO media "ftyp" box those formats start with.
	claimed = strings.ToLower(strings.TrimSpace(strings.Split(claimed, ";")[0]))
	if (claimed == "image/heic" || claimed == "image/heif") && len(data) > 12 && string(data[4:8]) == "ftyp" {
		return claimed, true
	}
	return "", false
}

// maxAttachment is the size limit for one attachment file (10 MB by default).
func (s *Server) maxAttachment() int64 {
	if s.MaxAttachment > 0 {
		return s.MaxAttachment
	}
	return 10 << 20
}

// putBlob stores an attachment file and returns its SHA-256, which the
// attachment record then refers to. The same file uploaded twice is stored once.
func (s *Server) putBlob(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Write)
	if !ok {
		return
	}
	limit := s.maxAttachment()
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, limit))
	if err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			s.fail(w, r, badReq(fmt.Sprintf("the file is larger than %d MB", limit>>20)))
			return
		}
		s.fail(w, r, badReq("could not read the file"))
		return
	}
	if len(data) == 0 {
		s.fail(w, r, badReq("the file is empty"))
		return
	}
	mime, ok := sniffType(data, r.Header.Get("Content-Type"))
	if !ok {
		s.fail(w, r, badReq("only photos (JPEG, PNG, WebP, GIF, HEIC) and PDF files can be attached"))
		return
	}
	sum := sha256.Sum256(data)
	b := store.Blob{LedgerID: lid, SHA256: hex.EncodeToString(sum[:]), Mime: mime, Size: int64(len(data)), Data: data, CreatedAt: time.Now().UnixMilli()}
	if err := store.PutBlob(r.Context(), s.DB, b); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"sha256": b.SHA256, "mime": b.Mime, "size": b.Size})
}

// getBlob serves an attachment file to members of the ledger.
func (s *Server) getBlob(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Read)
	if !ok {
		return
	}
	sha := strings.ToLower(r.PathValue("sha"))
	if !entity.IsSHA256(sha) {
		s.fail(w, r, badReq("not a file reference"))
		return
	}
	b, err := store.GetBlob(r.Context(), s.DB, lid, sha)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	h := w.Header()
	h.Set("Content-Type", b.Mime)
	h.Set("Content-Length", fmt.Sprint(len(b.Data)))
	// Files never change (they are named by their hash), so the browser may keep them.
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; sandbox")
	disp := "inline"
	if r.URL.Query().Get("download") == "1" {
		disp = "attachment"
	}
	name := strings.Map(func(c rune) rune {
		if c < 32 || c == '"' || c == '\\' || c > 126 {
			return '_'
		}
		return c
	}, r.URL.Query().Get("name"))
	if name == "" {
		name = sha[:12]
	}
	h.Set("Content-Disposition", disp+`; filename="`+name+`"`)
	_, _ = w.Write(b.Data)
}

// --- change history ---

func (s *Server) recordHistory(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Read)
	if !ok {
		return
	}
	spec, ok := entitySpec(w, r, s)
	if !ok {
		return
	}
	id, ok := recordID(w, r, s)
	if !ok {
		return
	}
	list, err := store.History(r.Context(), s.DB, lid, spec.Name, id, 100)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"history": list})
}

// --- exchange rates from a public source ---

// fxNamespace makes fetched rates deterministic per ledger, pair and date, so
// fetching twice on one day updates the same record instead of adding another.
var fxNamespace = [16]byte{0x3c, 0x9e, 0x5a, 0x10, 0x7d, 0x42, 0x4f, 0x8b, 0x91, 0x0c, 0x2e, 0x6a, 0xb3, 0x58, 0xd4, 0x17}

// fetchRates downloads today's reference rates (European Central Bank, through
// Frankfurter by default) for the currencies asked for, and saves them as
// exchange rates marked "imported". It runs only when someone presses the
// button: nothing is fetched in the background.
func (s *Server) fetchRates(w http.ResponseWriter, r *http.Request) {
	lid, ok := s.authorize(w, r, ledger.Write)
	if !ok {
		return
	}
	if s.FXURL == "" {
		writeError(w, http.StatusNotImplemented, "fx_unavailable", "fetching exchange rates is turned off on this server; type them in instead", nil)
		return
	}
	var in struct {
		Base   string   `json:"base"`
		Quotes []string `json:"quotes"`
	}
	if err := decode(w, r, smallBody, &in); err != nil {
		s.fail(w, r, err)
		return
	}
	in.Base = strings.ToUpper(strings.TrimSpace(in.Base))
	if !entity.ValidCurrency(in.Base) {
		s.fail(w, r, badReq("base must be a currency code such as INR"))
		return
	}
	var quotes []string
	for _, q := range in.Quotes {
		q = strings.ToUpper(strings.TrimSpace(q))
		if entity.ValidCurrency(q) && q != in.Base {
			quotes = append(quotes, q)
		}
	}
	if len(quotes) == 0 || len(quotes) > 50 {
		s.fail(w, r, badReq("list 1 to 50 other currencies to fetch"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	u := s.FXURL + "/latest?from=" + url.QueryEscape(in.Base) + "&to=" + url.QueryEscape(strings.Join(quotes, ","))
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	req.Header.Set("Accept", "application/json")
	client := s.HTTPClient
	if client == nil {
		client = http.DefaultClient
	}
	res, err := client.Do(req)
	if err != nil {
		writeError(w, http.StatusBadGateway, "fx_failed", "could not reach the exchange rate source: "+err.Error(), nil)
		return
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if res.StatusCode != http.StatusOK {
		writeError(w, http.StatusBadGateway, "fx_failed", fmt.Sprintf("the exchange rate source answered %d; it may not know one of these currencies", res.StatusCode), nil)
		return
	}
	var out struct {
		Base  string                 `json:"base"`
		Date  string                 `json:"date"`
		Rates map[string]json.Number `json:"rates"`
	}
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	if err := dec.Decode(&out); err != nil || out.Date == "" || len(out.Rates) == 0 {
		writeError(w, http.StatusBadGateway, "fx_failed", "the exchange rate source sent something unexpected", nil)
		return
	}
	if _, err := time.Parse(time.DateOnly, out.Date); err != nil {
		writeError(w, http.StatusBadGateway, "fx_failed", "the exchange rate source sent a bad date", nil)
		return
	}
	var ops []syncer.WriteOp
	saved := map[string]string{}
	missing := []string{}
	for _, q := range quotes {
		v, ok := out.Rates[q]
		rat, okRat := new(big.Rat).SetString(string(v))
		if !ok || !okRat || rat.Sign() <= 0 {
			missing = append(missing, q)
			continue
		}
		// Plain decimal with up to 10 places ("83.2105"), never exponent form.
		rate := strings.TrimRight(strings.TrimRight(rat.FloatString(10), "0"), ".")
		if strings.Trim(rate, "0.") == "" {
			missing = append(missing, q)
			continue
		}
		ops = append(ops, syncer.WriteOp{
			Entity: "exchange_rates", ID: ids.V5(fxNamespace, lid+":"+in.Base+":"+q+":"+out.Date),
			Data: map[string]any{"base": in.Base, "quote": q, "rate": rate, "effective_date": out.Date, "source": "imported"},
		})
		saved[q] = rate
	}
	if len(ops) > 0 {
		if _, err := s.Sync.Write(r.Context(), lid, ops); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"base": in.Base, "date": out.Date, "rates": saved, "missing": missing, "source": "European Central Bank reference rates via " + hostOf(s.FXURL)})
}

func hostOf(raw string) string {
	if u, err := url.Parse(raw); err == nil && u.Host != "" {
		return u.Host
	}
	return raw
}
