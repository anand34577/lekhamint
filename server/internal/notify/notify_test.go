package notify_test

import (
	"bufio"
	"context"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/anand34577/lekhamint/server/internal/entity"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/notify"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/testutil"
)

func TestMoney(t *testing.T) {
	for _, c := range []struct {
		minor int64
		code  string
		want  string
	}{
		{125000, "INR", "INR 1,250.00"}, {5, "USD", "USD 0.05"}, {-123456789, "EUR", "EUR -1,234,567.89"},
		{5000, "JPY", "JPY 5,000"}, {1500, "KWD", "KWD 1.500"},
	} {
		if got := notify.Money(c.minor, c.code); got != c.want {
			t.Errorf("Money(%d, %s) = %q, want %q", c.minor, c.code, got, c.want)
		}
	}
}

// smtpSink is a plain SMTP server that records the message it receives.
func smtpSink(t *testing.T) (addr string, msg func() string) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	var mu sync.Mutex
	var got string
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		r := bufio.NewReader(c)
		say := func(s string) { io.WriteString(c, s+"\r\n") }
		say("220 test ESMTP")
		var data strings.Builder
		inData := false
		for {
			line, err := r.ReadString('\n')
			if err != nil {
				return
			}
			line = strings.TrimRight(line, "\r\n")
			if inData {
				if line == "." {
					inData = false
					mu.Lock()
					got = data.String()
					mu.Unlock()
					say("250 queued")
					continue
				}
				data.WriteString(line + "\n")
				continue
			}
			switch cmd := strings.ToUpper(line); {
			case strings.HasPrefix(cmd, "EHLO"), strings.HasPrefix(cmd, "HELO"):
				say("250 test")
			case strings.HasPrefix(cmd, "MAIL"), strings.HasPrefix(cmd, "RCPT"):
				say("250 ok")
			case cmd == "DATA":
				inData = true
				say("354 go")
			case cmd == "QUIT":
				say("221 bye")
				return
			default:
				say("250 ok")
			}
		}
	}()
	return ln.Addr().String(), func() string { mu.Lock(); defer mu.Unlock(); return got }
}

func TestEmailOverPlainSMTP(t *testing.T) {
	addr, msg := smtpSink(t)
	host, port, _ := net.SplitHostPort(addr)
	var p int
	for _, ch := range port {
		p = p*10 + int(ch-'0')
	}
	n := &notify.Notifier{SMTP: &notify.SMTPConfig{Host: host, Port: p, From: "LekhaMint <no-reply@example.com>", Security: "none"}}
	// A subject cannot smuggle in extra headers.
	err := n.Email(context.Background(), "asha@example.com", "Due soon: Rent\r\nBcc: evil@example.com", "Rent is due.")
	if err != nil {
		t.Fatal(err)
	}
	got := msg()
	for _, want := range []string{"To: <asha@example.com>", "Subject: LekhaMint: Due soon: Rent  Bcc: evil@example.com", "Rent is due."} {
		if !strings.Contains(got, want) {
			t.Errorf("message missing %q:\n%s", want, got)
		}
	}
	if strings.Contains(got, "\nBcc:") {
		t.Errorf("header injection got through:\n%s", got)
	}
}

func TestEmailNotConfigured(t *testing.T) {
	if err := (*notify.Notifier)(nil).Email(context.Background(), "a@example.com", "x", "y"); err != notify.ErrNoEmail {
		t.Fatalf("err = %v", err)
	}
}

func TestReminders(t *testing.T) {
	ctx := context.Background()
	d := testutil.NewDB(t)
	now := time.Date(2026, 10, 25, 9, 0, 0, 0, time.UTC)
	u := store.User{ID: ids.NewV7(), Email: "a@example.com", Name: "Asha Rao", PasswordHash: "x", CreatedAt: 1, UpdatedAt: 1}
	if err := store.CreateUser(ctx, d, u); err != nil {
		t.Fatal(err)
	}
	led := store.Ledger{ID: ids.NewV7(), Name: "Home", BaseCurrency: "INR", CreatedBy: u.ID, CreatedAt: 1, UpdatedAt: 1}
	if err := store.CreateLedger(ctx, d, led); err != nil {
		t.Fatal(err)
	}
	if err := store.AddMember(ctx, d, store.Member{LedgerID: led.ID, UserID: u.ID, Role: store.RoleOwner}); err != nil {
		t.Fatal(err)
	}
	rule := func(name, next, mode string, paused bool) {
		data := entity.Data{"name": name, "type": "expense", "account_id": "acc", "amount": int64(1250000), "frequency": "monthly",
			"every": int64(1), "start_date": "2026-01-01", "mode": mode, "next_date": next, "paused": paused}
		if err := store.PutRecord(ctx, d, entity.RecurringRules, led.ID, store.Record{ID: ids.NewV7(), HLC: "h", Seq: 1, Data: data}); err != nil {
			t.Fatal(err)
		}
	}
	rule("Rent", "2026-10-27", "remind", false)        // due in two days: reminded
	rule("Gym", "2026-11-20", "remind", false)         // far away
	rule("Netflix", "2026-10-26", "auto", false)       // posts itself
	rule("Old magazine", "2026-10-26", "remind", true) // paused

	var mu sync.Mutex
	var bodies []string
	gotify := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		mu.Lock()
		bodies = append(bodies, string(b))
		mu.Unlock()
		io.WriteString(w, "{}")
	}))
	defer gotify.Close()
	disp := &notify.Dispatcher{DB: d, Notifier: &notify.Notifier{Gotify: &notify.GotifyConfig{URL: gotify.URL}}, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if err := store.PutPrefs(ctx, d, store.Prefs{UserID: u.ID, NotifyGotify: true, GotifyToken: "t", EvDue: true, EvBudget: true, EvSecurity: true}); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ { // the second run must not repeat the reminder
		if err := disp.RunOnce(ctx, now); err != nil {
			t.Fatal(err)
		}
	}
	if len(bodies) != 1 || !strings.Contains(bodies[0], "Due soon: Rent") || !strings.Contains(bodies[0], "INR 12,500.00") {
		t.Fatalf("messages = %v", bodies)
	}
	// Turning the event off stops reminders for new items.
	if err := store.PutPrefs(ctx, d, store.Prefs{UserID: u.ID, NotifyGotify: true, GotifyToken: "t", EvDue: false}); err != nil {
		t.Fatal(err)
	}
	rule("Water", "2026-10-26", "remind", false)
	_ = disp.RunOnce(ctx, now)
	if len(bodies) != 1 {
		t.Fatalf("a switched-off event still sent: %v", bodies)
	}
}

func TestClaimIsExclusive(t *testing.T) {
	ctx := context.Background()
	d := testutil.NewDB(t)
	u := store.User{ID: ids.NewV7(), Email: "c@example.com", Name: "C", PasswordHash: "x", CreatedAt: 1, UpdatedAt: 1}
	if err := store.CreateUser(ctx, d, u); err != nil {
		t.Fatal(err)
	}
	if ok, err := store.ClaimNotice(ctx, d, u.ID, "k"); err != nil || !ok {
		t.Fatalf("first claim = %v, %v", ok, err)
	}
	if ok, _ := store.ClaimNotice(ctx, d, u.ID, "k"); ok {
		t.Fatal("a claim must be exclusive")
	}
	_ = store.ReleaseNotice(ctx, d, u.ID, "k")
	if ok, _ := store.ClaimNotice(ctx, d, u.ID, "k"); !ok {
		t.Fatal("a released claim can be taken again")
	}
}

func TestPrefsDefaultsAndRoundTrip(t *testing.T) {
	ctx := context.Background()
	d := testutil.NewDB(t)
	u := store.User{ID: ids.NewV7(), Email: "b@example.com", Name: "B", PasswordHash: "x", CreatedAt: 1, UpdatedAt: 1}
	if err := store.CreateUser(ctx, d, u); err != nil {
		t.Fatal(err)
	}
	p, err := store.GetPrefs(ctx, d, u.ID)
	if err != nil || !p.EvSecurity || !p.EvDue || !p.EvBudget || p.NotifyEmail || p.NotifyGotify {
		t.Fatalf("defaults = %+v, %v", p, err)
	}
	p.NotifyEmail, p.GotifyToken, p.EvDue = true, "secret", false
	if err := store.PutPrefs(ctx, d, p); err != nil {
		t.Fatal(err)
	}
	p.EvBudget = false // a second save replaces the first
	if err := store.PutPrefs(ctx, d, p); err != nil {
		t.Fatal(err)
	}
	got, _ := store.GetPrefs(ctx, d, u.ID)
	if !got.NotifyEmail || got.GotifyToken != "secret" || got.EvDue || got.EvBudget || !got.EvSecurity {
		t.Fatalf("saved = %+v", got)
	}
}

// A trusted relay with no login: stale credentials must not stop mail when security is none,
// but are an error when the connection is meant to be secure.
func TestEmailRelayWithoutLogin(t *testing.T) {
	send := func(security string) error {
		addr, _ := smtpSink(t)
		host, port, _ := net.SplitHostPort(addr)
		p, _ := strconv.Atoi(port)
		n := &notify.Notifier{SMTP: &notify.SMTPConfig{Host: host, Port: p, Username: "u", Password: "p", From: "no-reply@example.com", Security: security}}
		return n.Email(context.Background(), "asha@example.com", "Hi", "Body")
	}
	if err := send("none"); err != nil {
		t.Fatalf("none: %v", err)
	}
	if err := send("starttls"); err == nil {
		t.Fatal("starttls without STARTTLS support should fail")
	}
}

func TestNextDueDate(t *testing.T) {
	d := func(s string) time.Time { v, _ := time.Parse("2006-01-02", s); return v }
	cases := []struct {
		today string
		day   int
		want  string
	}{
		{"2026-10-25", 28, "2026-10-28"},
		{"2026-10-25", 25, "2026-10-25"}, // today counts
		{"2026-10-26", 25, "2026-11-25"},
		{"2026-02-10", 31, "2026-02-28"}, // short month uses its last day
		{"2026-12-30", 5, "2027-01-05"},
		{"2026-01-31", 31, "2026-01-31"},
		{"2026-02-01", 30, "2026-02-28"},
	}
	for _, c := range cases {
		if got := notify.NextDueDate(d(c.today), c.day).Format("2006-01-02"); got != c.want {
			t.Errorf("NextDueDate(%s, %d) = %s, want %s", c.today, c.day, got, c.want)
		}
	}
}

func TestCardDueReminder(t *testing.T) {
	ctx := context.Background()
	d := testutil.NewDB(t)
	now := time.Date(2026, 10, 25, 9, 0, 0, 0, time.UTC)
	u := store.User{ID: ids.NewV7(), Email: "k@example.com", Name: "K", PasswordHash: "x", CreatedAt: 1, UpdatedAt: 1}
	if err := store.CreateUser(ctx, d, u); err != nil {
		t.Fatal(err)
	}
	led := store.Ledger{ID: ids.NewV7(), Name: "Home", BaseCurrency: "INR", CreatedBy: u.ID, CreatedAt: 1, UpdatedAt: 1}
	if err := store.CreateLedger(ctx, d, led); err != nil {
		t.Fatal(err)
	}
	if err := store.AddMember(ctx, d, store.Member{LedgerID: led.ID, UserID: u.ID, Role: store.RoleOwner}); err != nil {
		t.Fatal(err)
	}
	card := func(name string, owed int64, due int64) {
		data := entity.Data{"name": name, "type": "credit_card", "currency": "INR", "opening_balance": -owed, "due_day": due}
		if due == 0 {
			delete(data, "due_day")
		}
		if err := store.PutRecord(ctx, d, entity.Accounts, led.ID, store.Record{ID: ids.NewV7(), HLC: "h", Seq: 1, Data: data}); err != nil {
			t.Fatal(err)
		}
	}
	card("Amex", 4500000, 27)    // due in two days, owes: reminded
	card("Paid off", 0, 27)      // nothing owed
	card("Far away", 100000, 12) // next due 12 Nov
	card("No date", 100000, 0)   // no due day set

	var mu sync.Mutex
	var bodies []string
	gotify := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		mu.Lock()
		bodies = append(bodies, string(b))
		mu.Unlock()
		io.WriteString(w, "{}")
	}))
	defer gotify.Close()
	disp := &notify.Dispatcher{DB: d, Reports: &report.Service{DB: d}, Notifier: &notify.Notifier{Gotify: &notify.GotifyConfig{URL: gotify.URL}}, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if err := store.PutPrefs(ctx, d, store.Prefs{UserID: u.ID, NotifyGotify: true, GotifyToken: "t", EvDue: true}); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if err := disp.RunOnce(ctx, now); err != nil {
			t.Fatal(err)
		}
	}
	if len(bodies) != 1 || !strings.Contains(bodies[0], "Card bill due soon: Amex") || !strings.Contains(bodies[0], "INR 45,000.00") {
		t.Fatalf("messages = %v", bodies)
	}
}
