package notify

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/anand34577/lekhamint/server/internal/db"
	"github.com/anand34577/lekhamint/server/internal/entity"
	"github.com/anand34577/lekhamint/server/internal/report"
	"github.com/anand34577/lekhamint/server/internal/store"
)

// Event kinds a person can switch on or off.
const (
	EventSecurity = "security"
	EventDue      = "due"
	EventBudget   = "budget"
)

// Dispatcher delivers a message to a person on the channels they chose.
type Dispatcher struct {
	DB       *db.DB
	Notifier *Notifier
	Reports  *report.Service
	Log      *slog.Logger
	// ReminderHour is the hour of day reminders go out; negative disables them.
	ReminderHour int
	Now          func() time.Time
}

func (d *Dispatcher) now() time.Time {
	if d.Now != nil {
		return d.Now()
	}
	return time.Now()
}

func (d *Dispatcher) wants(pr store.Prefs, event string) bool {
	switch event {
	case EventSecurity:
		return pr.EvSecurity
	case EventDue:
		return pr.EvDue
	case EventBudget:
		return pr.EvBudget
	case EventDigest:
		return pr.EvDigest
	}
	return false
}

// Send delivers to every channel the person enabled for event. It reports an error
// only when no channel succeeded, so one broken channel does not hide a working one.
func (d *Dispatcher) Send(ctx context.Context, u store.User, pr store.Prefs, event, subject, body string) error {
	if d == nil || d.Notifier == nil || !d.wants(pr, event) {
		return nil
	}
	var errs []string
	sent := 0
	if pr.NotifyEmail && d.Notifier.HasEmail() {
		if err := d.Notifier.Email(ctx, u.Email, subject, "Hi "+firstName(u.Name)+",\n\n"+body+"\n\n-- "+d.Notifier.name()); err != nil {
			errs = append(errs, "email: "+err.Error())
		} else {
			sent++
		}
	}
	if pr.NotifyGotify && d.Notifier.HasGotify() && pr.GotifyToken != "" {
		prio := 4
		if event == EventSecurity {
			prio = 7
		}
		if err := d.Notifier.GotifyMessage(ctx, pr.GotifyToken, subject, body, prio); err != nil {
			errs = append(errs, "gotify: "+err.Error())
		} else {
			sent++
		}
	}
	if sent == 0 && len(errs) > 0 {
		return fmt.Errorf("%s", strings.Join(errs, "; "))
	}
	return nil
}

// NotifyUser loads the person's preferences and sends; failures are logged. It is
// meant for fire-and-forget use from request handlers (call it in a goroutine).
func (d *Dispatcher) NotifyUser(u store.User, event, subject, body string) {
	if d == nil || d.Notifier == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	pr, err := store.GetPrefs(ctx, d.DB, u.ID)
	if err != nil {
		d.Log.Warn("notification preferences unavailable", "err", err)
		return
	}
	if err := d.Send(ctx, u, pr, event, subject, body); err != nil {
		d.Log.Warn("notification not delivered", "event", event, "err", err)
	}
}

func firstName(name string) string {
	if f := strings.Fields(name); len(f) > 0 {
		return f[0]
	}
	return "there"
}

// Run sends reminders once a day, at ReminderHour, until ctx ends. Each reminder
// is recorded in notify_log first, so a restart never sends one twice.
func (d *Dispatcher) Run(ctx context.Context) {
	if d == nil || d.Notifier == nil || d.ReminderHour < 0 || (!d.Notifier.HasEmail() && !d.Notifier.HasGotify()) {
		return
	}
	lastDay := ""
	tick := time.NewTicker(10 * time.Minute)
	defer tick.Stop()
	for {
		now := d.now()
		if day := now.Format("2006-01-02"); day != lastDay && now.Hour() >= d.ReminderHour {
			lastDay = day
			if err := d.RunOnce(ctx, now); err != nil {
				d.Log.Warn("reminders failed", "err", err)
			}
			_ = store.PruneNotices(ctx, d.DB, now.AddDate(0, 0, -90).UnixMilli())
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}

const dueLeadDays = 3

// RunOnce looks for bills due soon and budgets near or over their limit for every
// person who has a channel on, and sends what has not been sent yet.
func (d *Dispatcher) RunOnce(ctx context.Context, now time.Time) error {
	prefs, err := store.EachPrefs(ctx, d.DB)
	if err != nil {
		return err
	}
	today := now.Format("2006-01-02")
	for _, pr := range prefs {
		if !pr.EvDue && !pr.EvBudget && !pr.EvDigest {
			continue
		}
		u, err := store.UserByID(ctx, d.DB, pr.UserID)
		if err != nil || u.Disabled {
			continue
		}
		ledgers, err := store.LedgersForUser(ctx, d.DB, u.ID)
		if err != nil {
			d.Log.Warn("reminders: ledgers", "err", err)
			continue
		}
		for _, l := range ledgers {
			if pr.EvDue {
				d.dueBills(ctx, u, pr, l, now)
				d.dueCards(ctx, u, pr, l, now)
			}
			if pr.EvBudget {
				d.budgetAlerts(ctx, u, pr, l, today)
			}
		}
		if pr.EvDigest {
			d.weeklyDigests(ctx, u, pr, ledgers, now)
		}
	}
	return nil
}

// once sends a message unless the same key was already sent; a failed send is released
// so the next run tries again.
func (d *Dispatcher) once(ctx context.Context, u store.User, pr store.Prefs, event, key, subject, body string) {
	first, err := store.ClaimNotice(ctx, d.DB, u.ID, key)
	if err != nil || !first {
		return
	}
	if err := d.Send(ctx, u, pr, event, subject, body); err != nil {
		_ = store.ReleaseNotice(ctx, d.DB, u.ID, key)
		d.Log.Warn("reminder not delivered", "event", event, "err", err)
	}
}

func (d *Dispatcher) dueBills(ctx context.Context, u store.User, pr store.Prefs, l store.Ledger, now time.Time) {
	recs, err := store.ListRecords(ctx, d.DB, entity.RecurringRules, l.ID, store.ListOptions{Limit: 1000})
	if err != nil {
		return
	}
	today := now.Format("2006-01-02")
	horizon := now.AddDate(0, 0, dueLeadDays).Format("2006-01-02")
	for _, r := range recs {
		next, _ := r.Data["next_date"].(string)
		mode, _ := r.Data["mode"].(string)
		paused, _ := r.Data["paused"].(bool)
		if next == "" || paused || mode == "auto" || next > horizon {
			continue
		}
		name, _ := r.Data["name"].(string)
		if name == "" {
			name, _ = r.Data["payee"].(string)
		}
		if name == "" {
			name = "A recurring payment"
		}
		amount, _ := r.Data["amount"].(int64)
		typ, _ := r.Data["type"].(string)
		when := "is due on " + next
		subject := "Due soon: " + name
		switch {
		case next < today:
			when, subject = "was due on "+next, "Overdue: "+name
		case next == today:
			when, subject = "is due today", "Due today: "+name
		}
		verb := "payment"
		if typ == "income" {
			verb = "income"
		}
		d.once(ctx, u, pr, EventDue, "due:"+r.ID+":"+next, subject,
			fmt.Sprintf("%s (%s, %s) %s in %q.", name, verb, Money(amount, l.BaseCurrency), when, l.Name))
	}
}

// NextDueDate is the first date on or after today that falls on day of the
// month, using the last day of months that are too short.
func NextDueDate(today time.Time, day int) time.Time {
	at := func(y int, m time.Month) time.Time {
		last := time.Date(y, m+1, 0, 0, 0, 0, 0, time.UTC).Day()
		return time.Date(y, m, min(day, last), 0, 0, 0, 0, time.UTC)
	}
	t := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, time.UTC)
	if c := at(t.Year(), t.Month()); !c.Before(t) {
		return c
	}
	n := t.AddDate(0, 1, 0)
	return at(n.Year(), n.Month())
}

// dueCards reminds about credit cards whose payment date is near and that still owe money.
func (d *Dispatcher) dueCards(ctx context.Context, u store.User, pr store.Prefs, l store.Ledger, now time.Time) {
	if d.Reports == nil {
		return
	}
	recs, err := store.ListRecords(ctx, d.DB, entity.Accounts, l.ID, store.ListOptions{Limit: 1000})
	if err != nil {
		return
	}
	bals, err := d.Reports.Balances(ctx, l.ID, "")
	if err != nil {
		return
	}
	owed := map[string]report.Balance{}
	for _, b := range bals {
		owed[b.AccountID] = b
	}
	for _, r := range recs {
		day, _ := r.Data["due_day"].(int64)
		if r.Deleted || day < 1 {
			continue
		}
		if archived, _ := r.Data["archived"].(bool); archived {
			continue
		}
		b, ok := owed[r.ID]
		if !ok || b.Type != "credit_card" || b.Balance >= 0 {
			continue
		}
		due := NextDueDate(now, int(day))
		days := int(due.Sub(time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC)).Hours() / 24)
		if days > dueLeadDays {
			continue
		}
		when, subject := "is due on "+due.Format("2006-01-02"), "Card bill due soon: "+b.Name
		if days == 0 {
			when, subject = "is due today", "Card bill due today: "+b.Name
		}
		d.once(ctx, u, pr, EventDue, "card:"+r.ID+":"+due.Format("2006-01-02"), subject,
			fmt.Sprintf("%s owes %s, and the payment %s in %q.", b.Name, Money(-b.Balance, b.Currency), when, l.Name))
	}
}

func (d *Dispatcher) budgetAlerts(ctx context.Context, u store.User, pr store.Prefs, l store.Ledger, today string) {
	if d.Reports == nil {
		return
	}
	progress, err := d.Reports.Budgets(ctx, l.ID, today)
	if err != nil || len(progress) == 0 {
		return
	}
	recs, err := store.ListRecords(ctx, d.DB, entity.Budgets, l.ID, store.ListOptions{Limit: 1000})
	if err != nil {
		return
	}
	type meta struct {
		name  string
		alert int64
	}
	byID := map[string]meta{}
	for _, r := range recs {
		name, _ := r.Data["name"].(string)
		alert, _ := r.Data["alert_percent"].(int64)
		if alert <= 0 {
			alert = 80
		}
		byID[r.ID] = meta{name, alert}
	}
	for _, b := range progress {
		m, ok := byID[b.BudgetID]
		if !ok || !b.Active || b.Limit <= 0 {
			continue
		}
		pct := b.Spent * 100 / b.Limit
		switch {
		case pct >= 100:
			d.once(ctx, u, pr, EventBudget, "budget:"+b.BudgetID+":"+b.Window.Start+":100", "Over budget: "+m.name,
				fmt.Sprintf("%s has spent %s of its %s limit (%d%%) in %q. Window: %s to %s.", m.name, Money(b.Spent, b.Currency), Money(b.Limit, b.Currency), pct, l.Name, b.Window.Start, b.Window.End))
		case pct >= m.alert:
			d.once(ctx, u, pr, EventBudget, "budget:"+b.BudgetID+":"+b.Window.Start+":alert", fmt.Sprintf("Budget at %d%%: %s", pct, m.name),
				fmt.Sprintf("%s has used %d%% of its limit: %s of %s in %q. About %s per day is left for %d more days.", m.name, pct, Money(b.Spent, b.Currency), Money(b.Limit, b.Currency), l.Name, Money(b.PerDayLeft, b.Currency), b.DaysLeft))
		}
	}
}

// Money formats minor units for a message, e.g. "INR 1,250.00".
func Money(minor int64, code string) string {
	digits := 2
	switch code {
	case "JPY", "KRW", "VND":
		digits = 0
	case "KWD", "BHD", "OMR":
		digits = 3
	case "BTC":
		digits = 8
	}
	neg := minor < 0
	if neg {
		minor = -minor
	}
	pow := int64(1)
	for i := 0; i < digits; i++ {
		pow *= 10
	}
	whole := fmt.Sprintf("%d", minor/pow)
	var grouped []string
	for len(whole) > 3 {
		grouped = append([]string{whole[len(whole)-3:]}, grouped...)
		whole = whole[:len(whole)-3]
	}
	grouped = append([]string{whole}, grouped...)
	s := strings.Join(grouped, ",")
	if digits > 0 {
		s += fmt.Sprintf(".%0*d", digits, minor%pow)
	}
	if neg {
		s = "-" + s
	}
	return code + " " + s
}
