// Package notify sends messages to people by email (SMTP) and Gotify.
//
// Both channels are configured by the server administrator. Gotify's server
// address is fixed in the server configuration (a user supplies only their own
// application token), so a user cannot make the server call arbitrary hosts.
package notify

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/mail"
	"net/smtp"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// SMTPConfig describes the outgoing mail server.
type SMTPConfig struct {
	Host     string
	Port     int
	Username string
	Password string
	From     string // "LekhaMint <no-reply@example.com>" or a bare address
	// Security is "starttls" (default, usually port 587), "tls" (implicit TLS,
	// usually port 465) or "none" (plain, only for a trusted local relay).
	Security string
	// SkipVerify accepts a mail server certificate that is self-signed or
	// issued for another name (an internal relay). Traffic is still encrypted.
	SkipVerify bool
	// Hello is the name sent in EHLO; empty uses the sender's domain.
	Hello string
}

// GotifyConfig points at a Gotify server.
type GotifyConfig struct {
	URL string
}

// Notifier sends messages. A nil channel in Config is simply unavailable.
type Notifier struct {
	SMTP   *SMTPConfig
	Gotify *GotifyConfig
	Client *http.Client
	// Dial lets tests replace the network; nil uses a normal dialer.
	Dial func(ctx context.Context, network, addr string) (net.Conn, error)
	// AppName appears in subjects and Gotify titles.
	AppName string
}

// Errors returned for unusable channels.
var (
	ErrNoEmail  = errors.New("email is not set up on this server (LEKHAMINT_SMTP_HOST)")
	ErrNoGotify = errors.New("Gotify is not set up on this server (LEKHAMINT_GOTIFY_URL)")
)

// Info describes the channels for /meta without revealing secrets.
func (n *Notifier) Info() map[string]any {
	out := map[string]any{"email": false, "gotify": false}
	if n == nil {
		return out
	}
	if n.SMTP != nil {
		out["email"] = true
	}
	if n.Gotify != nil {
		out["gotify"] = true
		if u, err := url.Parse(n.Gotify.URL); err == nil {
			out["gotify_host"] = u.Host
		}
	}
	return out
}

func (n *Notifier) name() string {
	if n.AppName != "" {
		return n.AppName
	}
	return "LekhaMint"
}

// HasEmail and HasGotify report which channels exist.
func (n *Notifier) HasEmail() bool  { return n != nil && n.SMTP != nil }
func (n *Notifier) HasGotify() bool { return n != nil && n.Gotify != nil }

// Email sends a plain-text message.
func (n *Notifier) Email(ctx context.Context, to, subject, body string) error {
	if !n.HasEmail() {
		return ErrNoEmail
	}
	c := n.SMTP
	toAddr, err := mail.ParseAddress(to)
	if err != nil {
		return fmt.Errorf("recipient address: %w", err)
	}
	from, err := mail.ParseAddress(c.From)
	if err != nil {
		return fmt.Errorf("sender address: %w", err)
	}
	// Header values never contain line breaks, so a name or subject cannot inject headers.
	clean := func(s string) string { return strings.NewReplacer("\r", " ", "\n", " ").Replace(s) }
	var msg bytes.Buffer
	fmt.Fprintf(&msg, "From: %s\r\nTo: %s\r\nSubject: %s\r\nDate: %s\r\nMessage-ID: <%d.%s>\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n",
		from.String(), toAddr.String(), mimeHeader(clean(n.name()+": "+subject)), time.Now().Format(time.RFC1123Z),
		time.Now().UnixNano(), hostOf(from.Address))
	msg.WriteString(strings.ReplaceAll(strings.ReplaceAll(body, "\r\n", "\n"), "\n", "\r\n"))
	msg.WriteString("\r\n")

	addr := net.JoinHostPort(c.Host, strconv.Itoa(c.Port))
	dial := n.Dial
	if dial == nil {
		d := &net.Dialer{Timeout: 15 * time.Second}
		dial = d.DialContext
	}
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	conn, err := dial(ctx, "tcp", addr)
	if err != nil {
		return fmt.Errorf("connect to mail server: %w", err)
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	}
	tlsCfg := &tls.Config{ServerName: c.Host, MinVersion: tls.VersionTLS12, InsecureSkipVerify: c.SkipVerify} //nolint:gosec // opt-in for internal relays
	if c.Security == "tls" {
		conn = tls.Client(conn, tlsCfg)
	}
	cl, err := smtp.NewClient(conn, c.Host)
	if err != nil {
		conn.Close()
		return fmt.Errorf("mail server greeting: %w", err)
	}
	defer cl.Close()
	// Many relays reject the default "localhost" greeting.
	hello := c.Hello
	if hello == "" {
		hello = hostOf(from.Address)
	}
	if err := cl.Hello(hello); err != nil {
		return fmt.Errorf("mail server greeting: %w", err)
	}
	if c.Security == "" || c.Security == "starttls" {
		if ok, _ := cl.Extension("STARTTLS"); !ok {
			return errors.New("mail server does not offer STARTTLS; set LEKHAMINT_SMTP_SECURITY to tls or none")
		}
		if err := cl.StartTLS(tlsCfg); err != nil {
			return fmt.Errorf("start TLS: %w", err)
		}
	}
	if c.Username != "" {
		// A relay that does not offer login (trusted by address) is used as it is
		// when security is none; otherwise a missing login is a configuration error.
		offered, mechs := cl.Extension("AUTH")
		switch {
		case offered:
			if err := cl.Auth(pickAuth(mechs, c.Username, c.Password)); err != nil {
				return fmt.Errorf("mail login: %w", err)
			}
		case c.Security != "none":
			return errors.New("mail server does not offer login; remove LEKHAMINT_SMTP_USER or use a server that does")
		}
	}
	if err := cl.Mail(from.Address); err != nil {
		return fmt.Errorf("sender rejected: %w", err)
	}
	if err := cl.Rcpt(toAddr.Address); err != nil {
		return fmt.Errorf("recipient rejected: %w", err)
	}
	w, err := cl.Data()
	if err != nil {
		return err
	}
	if _, err := w.Write(msg.Bytes()); err != nil {
		return err
	}
	if err := w.Close(); err != nil {
		return err
	}
	return cl.Quit()
}

// plainAuth is PLAIN authentication that does not insist on TLS: the
// connection is already encrypted by STARTTLS or implicit TLS, or the
// administrator chose "none" for a local relay.
type plainAuth struct{ user, pass string }

func (a *plainAuth) Start(*smtp.ServerInfo) (string, []byte, error) {
	return "PLAIN", []byte("\x00" + a.user + "\x00" + a.pass), nil
}
func (a *plainAuth) Next(_ []byte, more bool) ([]byte, error) {
	if more {
		return nil, errors.New("unexpected server challenge")
	}
	return nil, nil
}

// pickAuth chooses PLAIN when the server lists it, else LOGIN (older relays,
// some Microsoft servers), else PLAIN anyway.
func pickAuth(mechs, user, pass string) smtp.Auth {
	m := " " + strings.ToUpper(mechs) + " "
	if !strings.Contains(m, " PLAIN ") && strings.Contains(m, " LOGIN ") {
		return &loginAuth{user: user, pass: pass}
	}
	return &plainAuth{user: user, pass: pass}
}

// loginAuth is the LOGIN mechanism: the server asks for the user, then the password.
type loginAuth struct{ user, pass string }

func (a *loginAuth) Start(*smtp.ServerInfo) (string, []byte, error) { return "LOGIN", nil, nil }
func (a *loginAuth) Next(challenge []byte, more bool) ([]byte, error) {
	if !more {
		return nil, nil
	}
	switch strings.ToLower(strings.TrimSpace(string(challenge))) {
	case "username:", "user name", "username":
		return []byte(a.user), nil
	case "password:", "password":
		return []byte(a.pass), nil
	}
	return nil, errors.New("unexpected server challenge")
}

func hostOf(addr string) string {
	if _, h, ok := strings.Cut(addr, "@"); ok {
		return h
	}
	return "localhost"
}

func mimeHeader(s string) string {
	for _, r := range s {
		if r > 126 {
			return mimeEncode(s)
		}
	}
	return s
}

// GotifyMessage posts a message. [token] is the person's own application token.
// Priority follows Gotify's scale: 0 min, 4 normal, 8 high.
func (n *Notifier) GotifyMessage(ctx context.Context, token, title, message string, priority int) error {
	if !n.HasGotify() {
		return ErrNoGotify
	}
	token = strings.TrimSpace(token)
	if token == "" {
		return errors.New("no Gotify application token saved")
	}
	body, _ := json.Marshal(map[string]any{"title": title, "message": message, "priority": priority,
		"extras": map[string]any{"client::display": map[string]string{"contentType": "text/plain"}}})
	base := strings.TrimRight(n.Gotify.URL, "/")
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, base+"/message", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	// The token goes in a header, never in the URL, so it stays out of access logs.
	req.Header.Set("X-Gotify-Key", token)
	client := n.Client
	if client == nil {
		client = &http.Client{Timeout: 25 * time.Second}
	}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("reach Gotify: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
			return errors.New("Gotify rejected the application token")
		}
		return fmt.Errorf("Gotify answered %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return nil
}
