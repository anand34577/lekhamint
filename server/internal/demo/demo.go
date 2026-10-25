// Package demo fills a fresh server with a realistic sample household: six
// months of salary, rent, groceries, bills, a trip, loans to friends, budgets
// and rules. It powers `lekhamint demo`, the in-browser demo and the
// screenshots, so every number a visitor sees comes from the real server code.
//
// The data is generated from a fixed seed relative to Today, so two runs on
// the same day produce the same book.
package demo

import (
	"context"
	"fmt"
	"maps"
	"math"
	"math/rand/v2"
	"slices"
	"sort"
	"time"

	"github.com/anand34577/lekhamint/server/internal/auth"
	"github.com/anand34577/lekhamint/server/internal/entity"
	"github.com/anand34577/lekhamint/server/internal/ids"
	"github.com/anand34577/lekhamint/server/internal/ledger"
	"github.com/anand34577/lekhamint/server/internal/store"
	"github.com/anand34577/lekhamint/server/internal/syncer"
)

// Sign-in details shown on the demo's login page.
const (
	Email    = "demo@example.com"
	Password = "demo1234"
	Name     = "Aarav Sharma"

	partnerEmail = "priya@example.com"
	partnerName  = "Priya Sharma"
)

// Deps are the services the seed writes through.
type Deps struct {
	Auth    *auth.Service
	Ledgers *ledger.Service
	Sync    *syncer.Service
}

// Seed creates the demo users and their household book. Today is the last
// day with transactions; months is how far back history goes (default 12).
func Seed(ctx context.Context, d Deps, today time.Time, months int) (store.Ledger, error) {
	if months <= 0 {
		months = 12
	}
	user, err := d.Auth.CreateUser(ctx, Email, Name, Password)
	if err != nil {
		return store.Ledger{}, fmt.Errorf("create demo user: %w", err)
	}
	if _, err := d.Auth.CreateUser(ctx, partnerEmail, partnerName, Password); err != nil {
		return store.Ledger{}, fmt.Errorf("create demo partner: %w", err)
	}
	l, _, err := d.Ledgers.Create(ctx, user.ID, ledger.CreateInput{Name: "Household", BaseCurrency: "INR", SeedDefaultCategories: true})
	if err != nil {
		return store.Ledger{}, fmt.Errorf("create demo book: %w", err)
	}
	if _, err := d.Ledgers.AddMember(ctx, l.ID, user.ID, partnerEmail, store.RoleEditor); err != nil {
		return store.Ledger{}, fmt.Errorf("share demo book: %w", err)
	}
	cats, err := categoryIDs(ctx, d.Sync, l.ID)
	if err != nil {
		return store.Ledger{}, err
	}
	g := &gen{cats: cats, today: day(today), rng: rand.New(rand.NewPCG(2026, 10))}
	g.start = g.today.AddDate(0, -months, 0)
	g.start = time.Date(g.start.Year(), g.start.Month(), 1, 0, 0, 0, 0, time.UTC)
	g.build()
	for chunk := range slices.Chunk(g.ops, 400) {
		if _, err := d.Sync.Write(ctx, l.ID, chunk); err != nil {
			return store.Ledger{}, fmt.Errorf("write demo data: %w", err)
		}
	}
	return d.Ledgers.Get(ctx, l.ID, user.ID)
}

// categoryIDs maps category names (from the default set) to their IDs.
func categoryIDs(ctx context.Context, s *syncer.Service, ledgerID string) (map[string]string, error) {
	recs, err := store.ListRecords(ctx, s.DB, entity.Categories, ledgerID, store.ListOptions{Limit: 1000})
	if err != nil {
		return nil, err
	}
	out := make(map[string]string, len(recs))
	for _, r := range recs {
		if n, ok := r.Data["name"].(string); ok {
			out[n] = r.ID
		}
	}
	return out, nil
}

func day(t time.Time) time.Time { return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC) }

const istOffset = 330 // minutes; the sample household lives in Bengaluru

type gen struct {
	cats         map[string]string
	today, start time.Time
	rng          *rand.Rand
	ops          []syncer.WriteOp

	acc  map[string]string
	tags map[string]string
	// Card spending per month (YYYY-MM), paid off from the bank the next month.
	cardSpend map[string]int64
	// Everyday cash and wallet flows, topped up from the bank when they run low.
	pocket map[string]int64
}

func (g *gen) id() string { return ids.NewV7() }

func (g *gen) add(entity, id string, data map[string]any) {
	data["created_at"] = g.today.UnixMilli()
	if entity == "exchange_rates" || entity == "transaction_splits" {
		delete(data, "created_at")
	}
	g.ops = append(g.ops, syncer.WriteOp{Entity: entity, ID: id, Data: data, MustNotExist: true})
}

func (g *gen) cat(name string) any {
	if id, ok := g.cats[name]; ok {
		return id
	}
	return nil
}

func rupees(n float64) int64 { return int64(n*100 + 0.5) }

// between returns a random amount in [lo, hi] rupees, rounded to a whole rupee.
func (g *gen) between(lo, hi int) int64 { return int64(lo+g.rng.IntN(hi-lo+1)) * 100 }

func (g *gen) tx(date time.Time, typ, account string, amount int64, category, payee string, extra map[string]any) string {
	id := g.id()
	if v, ok := extra["id"].(string); ok {
		id = v
		delete(extra, "id")
	}
	hour := 8 + g.rng.IntN(13)
	at := date.Add(time.Duration(hour)*time.Hour + time.Duration(g.rng.IntN(60))*time.Minute - istOffset*time.Minute)
	data := map[string]any{
		"type": typ, "account_id": g.acc[account], "amount": amount, "local_date": date.Format(time.DateOnly),
		"occurred_at": at.UnixMilli(), "tz_offset_min": int64(istOffset), "status": "cleared", "tag_ids": []string{},
	}
	if category != "" {
		data["category_id"] = g.cat(category)
	}
	if payee != "" {
		data["payee"] = payee
	}
	maps.Copy(data, extra)
	if account == "ICICI Credit Card" && typ == "expense" {
		g.cardSpend[date.Format("2006-01")] += amount
	}
	if _, ok := g.pocket[account]; ok && typ == "expense" {
		g.pocket[account] -= amount
	}
	g.add("transactions", id, data)
	return id
}

func (g *gen) transfer(date time.Time, from, to string, amount int64, note string) {
	g.tx(date, "transfer", from, amount, "", "", map[string]any{"to_account_id": g.acc[to], "to_amount": amount, "note": note})
}

// each calls fn for every date from start to today.
func (g *gen) each(fn func(d time.Time)) {
	for d := g.start; !d.After(g.today); d = d.AddDate(0, 0, 1) {
		fn(d)
	}
}

func (g *gen) pick(options ...string) string { return options[g.rng.IntN(len(options))] }

func (g *gen) chance(p float64) bool { return g.rng.Float64() < p }

func (g *gen) build() {
	g.acc, g.tags, g.cardSpend = map[string]string{}, map[string]string{}, map[string]int64{}
	g.pocket = map[string]int64{"Cash": 0, "Paytm Wallet": 0}
	g.accounts()
	g.tagsAndRules()
	g.recurring()
	g.daily()
	g.trip()
	g.festive()
	g.oneOffs()
	g.cardPayments()
	g.debts()
	g.loansAndDeposits()
	g.budgets()
	g.rates()
	// Writes go in dependency order: everything a transaction points at first.
	order := map[string]int{"accounts": 0, "tags": 1, "rules": 2, "recurring_rules": 3, "debts": 4, "loans": 4, "deposits": 4, "transactions": 5, "transaction_splits": 6, "budgets": 7, "exchange_rates": 8}
	sort.SliceStable(g.ops, func(i, j int) bool { return order[g.ops[i].Entity] < order[g.ops[j].Entity] })
}

func (g *gen) accounts() {
	type a struct {
		name, typ, cur, color, icon string
		opening                     int64
		limit                       any
	}
	list := []a{
		{"HDFC Savings", "bank", "INR", "#1565C0", "bank", rupees(184_250), nil},
		{"ICICI Credit Card", "credit_card", "INR", "#C62828", "card", 0, rupees(250_000)},
		{"Cash", "cash", "INR", "#2E7D32", "cash", rupees(8_500), nil},
		{"Paytm Wallet", "wallet", "INR", "#00ACC1", "wallet", rupees(1_800), nil},
		{"SBI Emergency Fund", "savings", "INR", "#6A1B9A", "savings", rupees(300_000), nil},
		{"Mutual Funds", "investment", "INR", "#EF6C00", "invest", rupees(420_000), nil},
		{"Wise USD", "bank", "USD", "#00897B", "bank", rupees(640), nil},
		{"Car Loan", "loan", "INR", "#546E7A", "car", 0, nil}, // opening balance set by loansAndDeposits
		{"SBI FD 2027", "deposit", "INR", "#0F766E", "savings", rupees(150_000), nil},
		{"HDFC Senior FD", "deposit", "INR", "#00796B", "savings", rupees(100_000), nil},
		{"Post Office RD", "deposit", "INR", "#4DB6AC", "savings", 0, nil},
	}
	for i, x := range list {
		id := g.id()
		g.acc[x.name] = id
		data := map[string]any{"name": x.name, "type": x.typ, "currency": x.cur, "opening_balance": x.opening,
			"color": x.color, "icon": x.icon, "sort_order": int64(i)}
		if x.limit != nil {
			data["credit_limit"] = x.limit
			data["statement_day"] = int64(5)
			data["due_day"] = int64(25)
		}
		g.add("accounts", id, data)
	}
}

func (g *gen) tagsAndRules() {
	for _, t := range []struct{ name, color string }{{"Goa trip", "#00ACC1"}, {"Work", "#3949AB"}, {"Festive", "#F4511E"}, {"Family", "#8E24AA"}} {
		id := g.id()
		g.tags[t.name] = id
		g.add("tags", id, map[string]any{"name": t.name, "color": t.color})
	}
	rules := []map[string]any{
		{"name": "Swiggy and Zomato", "pattern": "swiggy", "set_category_id": g.cat("Food delivery"), "set_payee": "Swiggy"},
		{"name": "Zomato", "pattern": "zomato", "set_category_id": g.cat("Food delivery"), "set_payee": "Zomato"},
		{"name": "Ride-hailing", "pattern": "uber", "set_category_id": g.cat("Taxi & ride-hailing"), "set_payee": "Uber"},
		{"name": "Amazon", "pattern": "amzn", "match_type": "starts_with", "set_payee": "Amazon"},
		{"name": "Groceries", "pattern": "bigbasket", "set_category_id": g.cat("Groceries"), "set_payee": "BigBasket"},
		{"name": "Client lunches", "pattern": "client", "match_field": "note", "add_tag_ids": []string{g.tags["Work"]}},
	}
	for i, r := range rules {
		r["sort_order"] = int64(i)
		g.add("rules", g.id(), r)
	}
}

type rule struct {
	id, name, typ, account, to, category, payee, mode string
	amount                                            int64
	day                                               int
	vary                                              int // ± rupees each month (bills)
}

// recurring creates the monthly rules and their past occurrences, with the
// same occurrence IDs the apps would use, so nothing is recorded twice.
func (g *gen) recurring() {
	rules := []rule{
		{name: "Salary", typ: "income", account: "HDFC Savings", category: "Salary", payee: "Acme Technologies", mode: "auto", amount: rupees(142_000), day: 1},
		{name: "Rent", typ: "expense", account: "HDFC Savings", category: "Rent", payee: "Mr. Raghavan (landlord)", mode: "auto", amount: rupees(32_000), day: 5},
		{name: "Mutual fund SIP", typ: "transfer", account: "HDFC Savings", to: "Mutual Funds", mode: "auto", amount: rupees(15_000), day: 7},
		{name: "Emergency fund top-up", typ: "transfer", account: "HDFC Savings", to: "SBI Emergency Fund", mode: "auto", amount: rupees(5_000), day: 2},
		{name: "Electricity", typ: "expense", account: "HDFC Savings", category: "Electricity", payee: "BESCOM", mode: "remind", amount: rupees(2_100), day: 12, vary: 600},
		{name: "Broadband", typ: "expense", account: "ICICI Credit Card", category: "Internet", payee: "Airtel Xstream Fiber", mode: "auto", amount: rupees(1_179), day: 15},
		{name: "Mobile plan", typ: "expense", account: "ICICI Credit Card", category: "Mobile", payee: "Jio", mode: "auto", amount: rupees(349), day: 20},
		{name: "Netflix", typ: "expense", account: "ICICI Credit Card", category: "Subscriptions", payee: "Netflix", mode: "auto", amount: rupees(649), day: 12},
		{name: "Spotify", typ: "expense", account: "ICICI Credit Card", category: "Subscriptions", payee: "Spotify", mode: "auto", amount: rupees(119), day: 18},
		{name: "Gym membership", typ: "expense", account: "HDFC Savings", category: "Fitness", payee: "Cult.fit", mode: "auto", amount: rupees(1_500), day: 3},
		{name: "Maid", typ: "expense", account: "HDFC Savings", category: "Household supplies", payee: "Lakshmi (house help)", mode: "remind", amount: rupees(4_000), day: 28},
	}
	for i := range rules {
		r := &rules[i]
		r.id = g.id()
		start := time.Date(g.start.Year(), g.start.Month(), r.day, 0, 0, 0, 0, time.UTC)
		next := start
		for !next.After(g.today) {
			amount := r.amount
			if r.vary > 0 {
				amount += g.between(-r.vary, r.vary)
			}
			extra := map[string]any{"id": ids.V5(ids.RecurringNamespace, r.id+":"+next.Format(time.DateOnly)), "recurring_rule_id": r.id}
			if r.typ == "transfer" {
				extra["to_account_id"], extra["to_amount"] = g.acc[r.to], amount
			}
			g.tx(next, r.typ, r.account, amount, r.category, r.payee, extra)
			next = next.AddDate(0, 1, 0)
		}
		data := map[string]any{"name": r.name, "type": r.typ, "account_id": g.acc[r.account], "amount": r.amount,
			"frequency": "monthly", "every": int64(1), "start_date": start.Format(time.DateOnly),
			"next_date": next.Format(time.DateOnly), "mode": r.mode}
		if r.category != "" {
			data["category_id"] = g.cat(r.category)
		}
		if r.payee != "" {
			data["payee"] = r.payee
		}
		if r.typ == "transfer" {
			data["to_account_id"], data["to_amount"] = g.acc[r.to], r.amount
		}
		g.add("recurring_rules", r.id, data)
	}
	// A yearly bill that is coming up, so Upcoming has something to remind about.
	due := g.today.AddDate(0, 0, 9)
	g.add("recurring_rules", g.id(), map[string]any{"name": "Car insurance", "type": "expense", "account_id": g.acc["HDFC Savings"],
		"amount": rupees(18_450), "category_id": g.cat("Vehicle insurance"), "payee": "ICICI Lombard", "frequency": "yearly",
		"every": int64(1), "start_date": due.Format(time.DateOnly), "next_date": due.Format(time.DateOnly), "mode": "remind"})
}

// daily adds the everyday spending: groceries, food, coffee, rides and fuel.
func (g *gen) daily() {
	g.each(func(d time.Time) {
		if g.pocket["Cash"] < rupees(800) {
			g.transfer(d, "HDFC Savings", "Cash", rupees(3_000), "ATM withdrawal")
			g.pocket["Cash"] += rupees(3_000)
		}
		if g.pocket["Paytm Wallet"] < rupees(500) {
			g.transfer(d, "HDFC Savings", "Paytm Wallet", rupees(2_000), "Wallet top-up")
			g.pocket["Paytm Wallet"] += rupees(2_000)
		}
		weekend := d.Weekday() == time.Saturday || d.Weekday() == time.Sunday
		if g.chance(0.22) {
			payee := g.pick("BigBasket", "Zepto", "Blinkit", "DMart", "Namdhari's Fresh")
			acct := g.pick("HDFC Savings", "ICICI Credit Card", "HDFC Savings")
			if payee == "DMart" && g.chance(0.5) {
				g.split(d, acct, payee)
			} else {
				g.tx(d, "expense", acct, g.between(240, 1_800), "Groceries", payee, nil)
			}
		}
		if g.chance(0.22) {
			payee := g.pick("Swiggy", "Zomato", "Swiggy")
			g.tx(d, "expense", g.pick("ICICI Credit Card", "Paytm Wallet", "HDFC Savings"), g.between(220, 940), "Food delivery", payee, nil)
		}
		if weekend && g.chance(0.3) {
			payee := g.pick("Toit Brewpub", "Truffles", "MTR", "Meghana Foods", "Byg Brewski", "The Fatty Bao")
			extra := map[string]any{}
			if g.chance(0.3) {
				extra["tag_ids"] = []string{g.tags["Family"]}
				extra["note"] = "Dinner with family"
			}
			g.tx(d, "expense", g.pick("ICICI Credit Card", "HDFC Savings"), g.between(900, 3_800), "Dining out", payee, extra)
		}
		if !weekend && g.chance(0.06) {
			g.tx(d, "expense", "ICICI Credit Card", g.between(1_200, 2_800), "Dining out", g.pick("Social", "Smoke House Deli"),
				map[string]any{"note": "Client lunch", "tag_ids": []string{g.tags["Work"]}})
		}
		if g.chance(0.25) {
			g.tx(d, "expense", g.pick("Cash", "Paytm Wallet"), g.between(90, 420), "Coffee & snacks", g.pick("Third Wave Coffee", "Blue Tokai", "Starbucks", "Chai Point"), nil)
		}
		if !weekend && g.chance(0.4) {
			g.tx(d, "expense", g.pick("HDFC Savings", "Paytm Wallet"), g.between(120, 560), "Taxi & ride-hailing", g.pick("Uber", "Ola", "Rapido"), nil)
		}
		if !weekend && g.chance(0.25) {
			g.tx(d, "expense", "Cash", g.between(30, 60), "Public transport", "Namma Metro", nil)
		}
		if g.chance(0.15) {
			g.tx(d, "expense", "Cash", g.between(80, 300), "Groceries", "Vegetable vendor", nil)
		}
		if d.Day() == 6 || d.Day() == 21 {
			g.tx(d, "expense", "ICICI Credit Card", g.between(1_800, 3_200), "Fuel", g.pick("Indian Oil", "HP Petrol", "Shell"), nil)
		}
		if d.Day() == 14 {
			g.tx(d, "expense", "HDFC Savings", g.between(380, 1_900), "Pharmacy", "Apollo Pharmacy", nil)
		}
		if d.Day() == 25 && g.chance(0.6) {
			g.tx(d, "expense", "ICICI Credit Card", g.between(500, 1_400), "Movies & events", "PVR INOX", nil)
		}
		if d.Day() == 9 && g.chance(0.7) {
			g.tx(d, "expense", "Cash", g.between(300, 700), "Salon & grooming", "Bounce Salon", nil)
		}
		if d.Day()%11 == 0 && g.chance(0.6) {
			g.tx(d, "expense", "ICICI Credit Card", g.between(450, 4_800), g.pick("Clothing", "Electronics", "Home & furniture", "Books"),
				g.pick("Amazon", "Myntra", "Flipkart", "IKEA", "Decathlon"), nil)
		}
	})
}

// split records a DMart run split between groceries and household supplies.
func (g *gen) split(d time.Time, acct, payee string) {
	food, home := g.between(1_400, 3_400), g.between(500, 1_800)
	id := g.tx(d, "expense", acct, food+home, "", payee, map[string]any{"note": "Monthly stock-up"})
	g.add("transaction_splits", g.id(), map[string]any{"transaction_id": id, "category_id": g.cat("Groceries"), "amount": food, "sort_order": int64(0)})
	g.add("transaction_splits", g.id(), map[string]any{"transaction_id": id, "category_id": g.cat("Household supplies"), "amount": home, "sort_order": int64(1)})
}

// trip is a long weekend in Goa about two months ago.
func (g *gen) trip() {
	d := g.today.AddDate(0, -2, 0)
	for d.Weekday() != time.Friday {
		d = d.AddDate(0, 0, 1)
	}
	tag := map[string]any{"tag_ids": []string{g.tags["Goa trip"]}}
	with := func(note string) map[string]any { return map[string]any{"tag_ids": tag["tag_ids"], "note": note} }
	g.tx(d.AddDate(0, 0, -21), "expense", "ICICI Credit Card", rupees(14_620), "Flights & trains", "IndiGo", with("BLR → GOI return, 2 people"))
	g.tx(d.AddDate(0, 0, -18), "expense", "ICICI Credit Card", rupees(16_800), "Hotels", "Taj Holiday Village", with("3 nights"))
	g.tx(d, "expense", "Cash", rupees(2_400), "Activities", "Scooter rental", with(""))
	g.tx(d.AddDate(0, 0, 1), "expense", "ICICI Credit Card", rupees(4_350), "Dining out", "Thalassa", with("Sunset dinner"))
	g.tx(d.AddDate(0, 0, 1), "expense", "Cash", rupees(1_800), "Activities", "Grande Island boat trip", with(""))
	g.tx(d.AddDate(0, 0, 2), "expense", "ICICI Credit Card", rupees(2_980), "Dining out", "Gunpowder", with(""))
	g.tx(d.AddDate(0, 0, 2), "expense", "Paytm Wallet", rupees(650), "Coffee & snacks", "Artjuna Cafe", with(""))
}

// festive is Diwali shopping in the weeks before today.
func (g *gen) festive() {
	tag := []string{g.tags["Festive"]}
	g.tx(g.today.AddDate(0, 0, -12), "expense", "ICICI Credit Card", rupees(8_499), "Clothing", "FabIndia", map[string]any{"tag_ids": tag, "note": "Diwali clothes"})
	g.tx(g.today.AddDate(0, 0, -6), "expense", "HDFC Savings", rupees(3_200), "Gifts", "Anand Sweets", map[string]any{"tag_ids": tag, "note": "Sweet boxes for neighbours"})
	g.tx(g.today.AddDate(0, 0, -3), "expense", "Cash", rupees(1_500), "Home & furniture", "Diyas and lights", map[string]any{"tag_ids": tag})
}

func (g *gen) oneOffs() {
	t := g.today
	// A laptop on the card, with part of it returned later.
	g.tx(t.AddDate(0, -3, -4), "expense", "ICICI Credit Card", rupees(6_999), "Electronics", "Amazon", map[string]any{"note": "Noise-cancelling headphones"})
	g.tx(t.AddDate(0, -3, 5), "income", "ICICI Credit Card", rupees(6_999), "Refunds & reimbursements", "Amazon", map[string]any{"note": "Headphones returned"})
	// Freelance work paid in dollars, then moved home.
	g.tx(t.AddDate(0, -1, -8), "income", "Wise USD", rupees(450), "Business & freelance", "Upwork client", map[string]any{"note": "Landing page redesign"})
	g.tx(t.AddDate(0, -4, -2), "income", "Wise USD", rupees(300), "Business & freelance", "Upwork client", nil)
	g.tx(t.AddDate(0, -1, 2), "transfer", "Wise USD", rupees(500), "", "", map[string]any{"to_account_id": g.acc["HDFC Savings"], "to_amount": rupees(41_890), "note": "Moved to INR"})
	g.tx(t.AddDate(0, -2, -10), "expense", "Wise USD", rupees(24), "Software", "Figma", map[string]any{"tag_ids": []string{g.tags["Work"]}})
	// Interest and cashback.
	for m := 0; m < 6; m += 3 {
		g.tx(t.AddDate(0, -m, -t.Day()+1), "income", "SBI Emergency Fund", g.between(5_200, 5_600), "Interest", "SBI", nil)
	}
	g.tx(t.AddDate(0, 0, -15), "income", "ICICI Credit Card", rupees(1_240), "Cashback & rewards", "ICICI rewards", nil)
	g.tx(t.AddDate(0, -2, 3), "expense", "HDFC Savings", rupees(4_800), "Doctor", "Manipal Hospital", map[string]any{"tag_ids": []string{g.tags["Family"]}, "note": "Dad's check-up"})
	g.tx(t.AddDate(0, -1, 11), "expense", "HDFC Savings", rupees(2_100), "Vehicle service", "Maruti Suzuki service", nil)
	g.tx(t.AddDate(0, -4, 6), "expense", "HDFC Savings", rupees(3_999), "Courses", "Coursera", map[string]any{"tag_ids": []string{g.tags["Work"]}})
	g.tx(t.AddDate(0, 0, -20), "expense", "HDFC Savings", rupees(2_500), "Charity", "Akshaya Patra", nil)
	// Two things still waiting for a category.
	g.tx(t.AddDate(0, 0, -2), "expense", "HDFC Savings", rupees(450), "", "UPI/RAMESH KUMAR", nil)
	g.tx(t.AddDate(0, 0, -1), "expense", "ICICI Credit Card", rupees(1_299), "", "POS 4521 DECATHLON", nil)
	// Today: a pending card charge.
	g.tx(t, "expense", "ICICI Credit Card", rupees(486), "Food delivery", "Swiggy", map[string]any{"status": "pending"})
}

// cardPayments pays each month's card spending from the bank on the 22nd of
// the next month, so the card balance stays realistic.
func (g *gen) cardPayments() {
	for m := g.start; ; m = m.AddDate(0, 1, 0) {
		due := time.Date(m.Year(), m.Month()+1, 22, 0, 0, 0, 0, time.UTC)
		if due.After(g.today) {
			return
		}
		if spent := g.cardSpend[m.Format("2006-01")]; spent > 0 {
			g.tx(due, "transfer", "HDFC Savings", spent, "", "", map[string]any{"to_account_id": g.acc["ICICI Credit Card"], "to_amount": spent, "note": "Credit card bill"})
		}
	}
}

func (g *gen) debts() {
	t := g.today
	lent := g.id()
	g.add("debts", lent, map[string]any{"person": "Rohan", "direction": "lent", "amount": rupees(15_000), "currency": "INR",
		"due_date": t.AddDate(0, 1, 0).Format(time.DateOnly), "note": "For his bike repair"})
	g.tx(t.AddDate(0, 0, -40), "expense", "HDFC Savings", rupees(15_000), "", "Rohan", map[string]any{"debt_id": lent, "note": "Lent for bike repair"})
	g.tx(t.AddDate(0, 0, -10), "income", "HDFC Savings", rupees(5_000), "", "Rohan", map[string]any{"debt_id": lent, "note": "Part repayment"})

	borrowed := g.id()
	g.add("debts", borrowed, map[string]any{"person": "Dad", "direction": "borrowed", "amount": rupees(20_000), "currency": "INR",
		"due_date": t.AddDate(0, 2, 0).Format(time.DateOnly), "note": "Help with the car insurance"})
	g.tx(t.AddDate(0, 0, -25), "income", "HDFC Savings", rupees(20_000), "", "Dad", map[string]any{"debt_id": borrowed})

	settled := g.id()
	g.add("debts", settled, map[string]any{"person": "Neha", "direction": "lent", "amount": rupees(3_000), "currency": "INR", "closed": true, "note": "Concert tickets"})
	g.tx(t.AddDate(0, -3, 0), "expense", "HDFC Savings", rupees(3_000), "", "Neha", map[string]any{"debt_id": settled})
	g.tx(t.AddDate(0, -2, -20), "income", "HDFC Savings", rupees(3_000), "", "Neha", map[string]any{"debt_id": settled})
}

func (g *gen) budgets() {
	start := g.start.Format(time.DateOnly)
	for _, b := range []struct {
		name   string
		amount int64
		cats   []string
		roll   bool
	}{
		{"Monthly spending", rupees(1_05_000), nil, false},
		{"Food & dining", rupees(24_000), []string{"Food"}, false},
		{"Getting around", rupees(11_000), []string{"Transport"}, false},
		{"Shopping", rupees(15_000), []string{"Shopping"}, false},
		{"Fun", rupees(4_000), []string{"Entertainment"}, false},
	} {
		catIDs := []string{}
		for _, c := range b.cats {
			if id, ok := g.cats[c]; ok {
				catIDs = append(catIDs, id)
			}
		}
		g.add("budgets", g.id(), map[string]any{"name": b.name, "amount": b.amount, "currency": "INR", "period": "monthly",
			"start_date": start, "category_ids": catIDs, "rollover": b.roll})
	}
}

func (g *gen) rates() {
	g.add("exchange_rates", g.id(), map[string]any{"base": "USD", "quote": "INR", "rate": "83.62", "effective_date": g.start.Format(time.DateOnly)})
	g.add("exchange_rates", g.id(), map[string]any{"base": "USD", "quote": "INR", "rate": "84.07", "effective_date": g.today.AddDate(0, -1, 0).Format(time.DateOnly)})
}

// loansAndDeposits adds a running car loan with real EMI splits (same maths as
// web/src/lib/loans.ts), two fixed deposits and a recurring deposit.
func (g *gen) loansAndDeposits() {
	const rateNum, rateDen = 925, 100 // 9.25% a year
	principal, months := rupees(500_000), 60
	loanStart := time.Date(g.start.Year(), g.start.Month()-8, 10, 0, 0, 0, 0, time.UTC)
	firstDue := loanStart.AddDate(0, 1, 0)
	r := float64(rateNum) / float64(rateDen) / 1200
	f := math.Pow(1+r, float64(months))
	emi := int64(math.Round(float64(principal) * r * f / (f - 1)))
	interest := func(b int64) int64 { d := int64(rateDen) * 1200; return (b*rateNum*2 + d) / (2 * d) }
	due := func(n int) time.Time { return addMonthsClamped(firstDue, n) }
	// Instalments before the book starts are only counted; later ones are recorded.
	bal, paidBefore := principal, 0
	for ; due(paidBefore).Before(g.start); paidBefore++ {
		bal -= emi - interest(bal)
	}
	loanAcct := g.acc["Car Loan"]
	for i := range g.ops {
		if g.ops[i].Entity == "accounts" && g.ops[i].ID == loanAcct {
			g.ops[i].Data["opening_balance"] = -bal
		}
	}
	loan := g.id()
	g.add("loans", loan, map[string]any{"name": "Car Loan", "lender": "HDFC Bank", "kind": "vehicle", "account_id": loanAcct, "currency": "INR",
		"principal": principal, "annual_rate": "9.25", "rate_type": "fixed", "start_date": loanStart.Format(time.DateOnly),
		"first_due_date": firstDue.Format(time.DateOnly), "tenure_months": int64(months), "paid_before": int64(paidBefore),
		"pay_from_account_id": g.acc["HDFC Savings"], "interest_category_id": g.cat("Interest & fees"), "remind_days": int64(3),
		"note": "Maruti Swift, loan no. ending 4471"})
	for n := paidBefore; !due(n).After(g.today); n++ {
		in := interest(bal)
		pr := emi - in
		d := due(n)
		g.tx(d, "transfer", "HDFC Savings", pr, "", "HDFC Bank", map[string]any{"to_account_id": loanAcct, "to_amount": pr, "loan_id": loan, "part": "principal", "note": "Car Loan EMI · principal"})
		g.tx(d, "expense", "HDFC Savings", in, "Interest & fees", "HDFC Bank", map[string]any{"loan_id": loan, "part": "interest", "note": "Car Loan EMI · interest"})
		bal -= pr
	}

	// A cumulative FD maturing in about two months.
	fdStart := g.today.AddDate(-2, 2, 0)
	g.add("deposits", g.id(), map[string]any{"name": "SBI FD 2027", "institution": "SBI", "kind": "fd", "account_id": g.acc["SBI FD 2027"], "currency": "INR",
		"amount": rupees(150_000), "annual_rate": "7.5", "start_date": fdStart.Format(time.DateOnly), "maturity_date": fdStart.AddDate(2, 0, 0).Format(time.DateOnly),
		"compounding": "quarterly", "payout": "cumulative", "remind_days": int64(7), "note": "Receipt 00231"})
	// A quarterly-payout FD for the parents; payouts land in the bank.
	sfStart := g.start.AddDate(0, -2, 0)
	sf := g.id()
	g.add("deposits", sf, map[string]any{"name": "HDFC Senior FD", "institution": "HDFC Bank", "kind": "fd", "account_id": g.acc["HDFC Senior FD"], "currency": "INR",
		"amount": rupees(100_000), "annual_rate": "7.25", "start_date": sfStart.Format(time.DateOnly), "maturity_date": sfStart.AddDate(3, 0, 0).Format(time.DateOnly),
		"compounding": "quarterly", "payout": "quarterly", "payout_account_id": g.acc["HDFC Savings"], "tax_percent": "10", "remind_days": int64(7)})
	for q := 1; ; q++ {
		d := sfStart.AddDate(0, 3*q, 0)
		if d.After(g.today) {
			break
		}
		if d.Before(g.start) {
			continue
		}
		g.tx(d, "income", "HDFC Savings", rupees(1_812.50), "Interest", "HDFC Bank", map[string]any{"deposit_id": sf, "part": "interest", "note": "HDFC Senior FD · interest"})
	}
	// A recurring deposit of ₹3,000 a month, started ten months ago.
	rdStart := time.Date(g.today.Year(), g.today.Month()-10, 5, 0, 0, 0, 0, time.UTC)
	rd := g.id()
	g.add("deposits", rd, map[string]any{"name": "Post Office RD", "institution": "India Post", "kind": "rd", "account_id": g.acc["Post Office RD"], "currency": "INR",
		"amount": rupees(3_000), "annual_rate": "6.7", "start_date": rdStart.Format(time.DateOnly), "maturity_date": rdStart.AddDate(2, 0, 0).Format(time.DateOnly),
		"compounding": "quarterly", "payout": "cumulative", "remind_days": int64(3)})
	for n := 0; !rdStart.AddDate(0, n, 0).After(g.today); n++ {
		g.tx(rdStart.AddDate(0, n, 0), "transfer", "HDFC Savings", rupees(3_000), "", "India Post", map[string]any{"to_account_id": g.acc["Post Office RD"], "to_amount": rupees(3_000), "deposit_id": rd, "part": "principal", "note": fmt.Sprintf("Post Office RD · instalment %d", n+1)})
	}
}

// addMonthsClamped adds n months to t, landing on the last day of shorter months (like java.time).
func addMonthsClamped(t time.Time, n int) time.Time {
	first := time.Date(t.Year(), t.Month()+time.Month(n), 1, 0, 0, 0, 0, time.UTC)
	last := first.AddDate(0, 1, -1).Day()
	d := t.Day()
	if d > last {
		d = last
	}
	return time.Date(first.Year(), first.Month(), d, 0, 0, 0, 0, time.UTC)
}
