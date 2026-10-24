package entity

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

const (
	acc1 = "0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a01"
	acc2 = "0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a02"
)

func validTx() map[string]any {
	return map[string]any{
		"type": "expense", "account_id": acc1, "amount": json.Number("12345"),
		"occurred_at": json.Number("1729771234567"), "local_date": "2026-10-24",
	}
}

func fields(t *testing.T, err error) map[string]string {
	t.Helper()
	var ve *ValidationError
	if !errors.As(err, &ve) {
		t.Fatalf("want ValidationError, got %v", err)
	}
	return ve.Fields
}

func TestTransactionDefaults(t *testing.T) {
	d, err := Transactions.Normalize(validTx())
	if err != nil {
		t.Fatal(err)
	}
	if d["status"] != "cleared" || d["is_adjustment"] != false || d["tz_offset_min"] != int64(0) {
		t.Fatalf("defaults not applied: %v", d)
	}
	if list, ok := d["tag_ids"].([]string); !ok || len(list) != 0 {
		t.Fatalf("tag_ids default = %#v", d["tag_ids"])
	}
	if d["amount"] != int64(12345) {
		t.Fatalf("amount = %#v", d["amount"])
	}
}

func TestTransactionRules(t *testing.T) {
	cases := map[string]struct {
		mod   func(m map[string]any)
		field string
	}{
		"zero amount":          {func(m map[string]any) { m["amount"] = json.Number("0") }, "amount"},
		"fractional amount":    {func(m map[string]any) { m["amount"] = 12.5 }, "amount"},
		"bad type":             {func(m map[string]any) { m["type"] = "gift" }, "type"},
		"bad date":             {func(m map[string]any) { m["local_date"] = "2026-02-30" }, "local_date"},
		"missing account":      {func(m map[string]any) { delete(m, "account_id") }, "account_id"},
		"transfer without dst": {func(m map[string]any) { m["type"] = "transfer" }, "to_account_id"},
		"transfer to self": {func(m map[string]any) {
			m["type"], m["to_account_id"], m["to_amount"] = "transfer", acc1, json.Number("5")
		}, "to_account_id"},
		"expense with dst":       {func(m map[string]any) { m["to_account_id"] = acc2 }, "to_account_id"},
		"half foreign amount":    {func(m map[string]any) { m["original_amount"] = json.Number("500") }, "original_currency"},
		"multi-line payee":       {func(m map[string]any) { m["payee"] = "a\nb" }, "payee"},
		"tag not uuid":           {func(m map[string]any) { m["tag_ids"] = []any{"x"} }, "tag_ids"},
		"tz offset out of range": {func(m map[string]any) { m["tz_offset_min"] = json.Number("900") }, "tz_offset_min"},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			m := validTx()
			c.mod(m)
			_, err := Transactions.Normalize(m)
			if _, ok := fields(t, err)[c.field]; !ok {
				t.Fatalf("want error on %s, got %v", c.field, err)
			}
		})
	}
}

func TestValidTransfer(t *testing.T) {
	m := validTx()
	m["type"], m["to_account_id"], m["to_amount"] = "transfer", acc2, json.Number("150")
	if _, err := Transactions.Normalize(m); err != nil {
		t.Fatal(err)
	}
}

func TestUnknownFieldsIgnoredAndTextTrimmed(t *testing.T) {
	m := validTx()
	m["field_from_the_future"] = 1
	m["payee"] = "  Big Bazaar  "
	m["note"] = ""
	d, err := Transactions.Normalize(m)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := d["field_from_the_future"]; ok {
		t.Fatal("unknown field kept")
	}
	if d["payee"] != "Big Bazaar" || d["note"] != nil {
		t.Fatalf("payee=%q note=%v", d["payee"], d["note"])
	}
}

func TestTagListDeduplicatedAndLowercased(t *testing.T) {
	m := validTx()
	m["tag_ids"] = []any{strings.ToUpper(acc1), acc1, acc2}
	d, err := Transactions.Normalize(m)
	if err != nil {
		t.Fatal(err)
	}
	if got := d["tag_ids"].([]string); len(got) != 2 || got[0] != acc1 {
		t.Fatalf("tag_ids = %v", got)
	}
}

func TestBudgetCustomNeedsEndDate(t *testing.T) {
	_, err := Budgets.Normalize(map[string]any{
		"name": "Trip", "amount": json.Number("100000"), "currency": "INR", "period": "custom", "start_date": "2026-11-01",
	})
	if _, ok := fields(t, err)["end_date"]; !ok {
		t.Fatal(err)
	}
}

func TestExchangeRateDecimal(t *testing.T) {
	base := map[string]any{"base": "USD", "quote": "INR", "effective_date": "2026-10-24"}
	for rate, ok := range map[string]bool{"83.1245": true, "1": true, "0": false, "0.000": false, "-1": false, "1e5": false, "83.": false} {
		base["rate"] = rate
		_, err := ExchangeRates.Normalize(base)
		if (err == nil) != ok {
			t.Errorf("rate %q: err=%v", rate, err)
		}
	}
}

func TestDBRoundTrip(t *testing.T) {
	m := validTx()
	m["tag_ids"] = []any{acc2}
	m["is_adjustment"] = true
	d, err := Transactions.Normalize(m)
	if err != nil {
		t.Fatal(err)
	}
	back := Transactions.FromDB(Transactions.DBArgs(d))
	if back["is_adjustment"] != true || back["tag_ids"].([]string)[0] != acc2 || back["amount"] != int64(12345) {
		t.Fatalf("round trip lost data: %v", back)
	}
}

func TestRegistryOrder(t *testing.T) {
	names := Names()
	idx := map[string]int{}
	for i, n := range names {
		idx[n] = i
	}
	if idx["accounts"] > idx["transactions"] || idx["transactions"] > idx["transaction_splits"] {
		t.Fatalf("dependency order broken: %v", names)
	}
}

func TestRuleNeedsAnActionAndOrderedBounds(t *testing.T) {
	base := func() map[string]any { return map[string]any{"pattern": "  Swiggy  "} }
	if _, err := Rules.Normalize(base()); err == nil {
		t.Fatal("rule without an action accepted")
	}
	m := base()
	m["set_payee"] = "Swiggy"
	d, err := Rules.Normalize(m)
	if err != nil || d["pattern"] != "Swiggy" || d["enabled"] != true || d["match_field"] != "payee" {
		t.Fatalf("d=%v err=%v", d, err)
	}
	m["min_amount"], m["max_amount"] = json.Number("500"), json.Number("100")
	if _, ok := fields(t, func() error { _, err := Rules.Normalize(m); return err }())["max_amount"]; !ok {
		t.Fatal("inverted bounds accepted")
	}
	m = base()
	m["add_tag_ids"] = []any{acc1}
	m["match_type"] = "regex"
	if _, ok := fields(t, func() error { _, err := Rules.Normalize(m); return err }())["match_type"]; !ok {
		t.Fatal("unknown match type accepted")
	}
}
