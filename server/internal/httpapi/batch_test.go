package httpapi_test

import (
	"net/http"
	"testing"
	"time"

	"github.com/kosh-app/kosh/server/internal/ids"
)

func TestBatchImportRulesBudgetsAndConversion(t *testing.T) {
	base := newServer(t, true)
	c := signUp(t, base, "batch@example.com", http.StatusCreated)
	lid := ids.NewV7()
	c.do("POST", "/api/v1/ledgers", map[string]any{"id": lid, "name": "B", "base_currency": "INR"}, http.StatusCreated)
	L := "/api/v1/ledgers/" + lid

	bank, usd, food := ids.NewV7(), ids.NewV7(), ids.NewV7()
	tx := func(id, acct string, amount int64, day string) map[string]any {
		return map[string]any{"op": "create_if_missing", "entity": "transactions", "id": id, "data": map[string]any{
			"type": "expense", "account_id": acct, "amount": amount, "category_id": food,
			"local_date": day, "occurred_at": time.Now().UnixMilli(),
		}}
	}
	t1, t2 := ids.NewV7(), ids.NewV7()
	ops := []any{
		map[string]any{"op": "create", "entity": "accounts", "id": bank, "data": map[string]any{"name": "Bank", "type": "bank", "currency": "INR"}},
		map[string]any{"op": "create", "entity": "accounts", "id": usd, "data": map[string]any{"name": "USD", "type": "bank", "currency": "USD"}},
		map[string]any{"op": "create", "entity": "categories", "id": food, "data": map[string]any{"name": "Food", "kind": "expense"}},
		tx(t1, bank, 120000, "2026-10-02"),
		tx(t2, usd, 2500, "2026-10-03"),
	}
	res := c.do("POST", L+"/batch", map[string]any{"ops": ops}, http.StatusOK)["results"].([]any)
	if len(res) != 5 || res[3].(map[string]any)["status"] != "created" {
		t.Fatalf("results = %v", res)
	}

	// Re-running the import skips existing rows, even after the user edits or deletes them.
	c.do("PATCH", L+"/records/transactions/"+t1, map[string]any{"data": map[string]any{"amount": 130000}}, http.StatusOK)
	c.do("DELETE", L+"/records/transactions/"+t2, nil, http.StatusOK)
	again := c.do("POST", L+"/batch", map[string]any{"ops": []any{tx(t1, bank, 120000, "2026-10-02"), tx(t2, usd, 2500, "2026-10-03")}}, http.StatusOK)
	for _, r := range again["results"].([]any) {
		if r.(map[string]any)["status"] != "skipped" {
			t.Fatalf("re-import = %v", again["_raw"])
		}
	}
	got := c.do("GET", L+"/records/transactions/"+t1, nil, http.StatusOK)["record"].(map[string]any)["data"].(map[string]any)
	if num(got["amount"]) != 130000 {
		t.Fatalf("user edit overwritten: %v", got)
	}

	// One invalid op rolls back the whole batch.
	bad := c.do("POST", L+"/batch", map[string]any{"ops": []any{
		tx(ids.NewV7(), bank, 100, "2026-10-04"),
		map[string]any{"op": "create", "entity": "transactions", "data": map[string]any{"type": "expense"}},
	}}, http.StatusUnprocessableEntity)
	if bad["error"].(map[string]any)["code"] != "validation" {
		t.Fatalf("bad batch = %v", bad["_raw"])
	}
	list := c.do("GET", L+"/transactions?from=2026-10-04&to=2026-10-04", nil, http.StatusOK)["transactions"].([]any)
	if len(list) != 0 {
		t.Fatal("partial batch was written")
	}
	// Restore and update of a missing record are 404; unknown op is 400.
	c.do("POST", L+"/batch", map[string]any{"ops": []any{map[string]any{"op": "restore", "entity": "transactions", "id": t2}}}, http.StatusOK)
	c.do("POST", L+"/batch", map[string]any{"ops": []any{map[string]any{"op": "update", "entity": "transactions", "id": ids.NewV7(), "data": map[string]any{}}}}, http.StatusNotFound)
	c.do("POST", L+"/batch", map[string]any{"ops": []any{map[string]any{"op": "upsert", "entity": "transactions"}}}, http.StatusBadRequest)

	// Rules are a synced entity with validation.
	c.do("POST", L+"/records/rules", map[string]any{"data": map[string]any{"pattern": "swiggy", "set_category_id": food}}, http.StatusCreated)
	c.do("POST", L+"/records/rules", map[string]any{"data": map[string]any{"pattern": "swiggy"}}, http.StatusUnprocessableEntity)
	rules := c.do("GET", L+"/records/rules", nil, http.StatusOK)["records"].([]any)
	if d := rules[0].(map[string]any)["data"].(map[string]any); d["match_field"] != "payee" || d["match_type"] != "contains" || d["enabled"] != true {
		t.Fatalf("rule defaults = %v", d)
	}

	// Budget progress.
	c.do("POST", L+"/records/budgets", map[string]any{"data": map[string]any{"name": "Food", "amount": 500000, "currency": "INR",
		"period": "monthly", "start_date": "2026-10-01", "category_ids": []any{food}}}, http.StatusCreated)
	bp := c.do("GET", L+"/reports/budgets?date=2026-10-24", nil, http.StatusOK)["budgets"].([]any)[0].(map[string]any)
	if num(bp["spent"]) != 130000 || num(bp["limit"]) != 500000 || num(bp["days_left"]) != 8 {
		t.Fatalf("budget = %v", bp)
	}
	c.do("GET", L+"/reports/budgets?date=24-10-2026", nil, http.StatusBadRequest)

	// Summary converted to INR: $25.00 at 83.5 = ₹2,087.50.
	c.do("POST", L+"/records/exchange_rates", map[string]any{"data": map[string]any{"base": "USD", "quote": "INR", "rate": "83.5", "effective_date": "2026-10-01"}}, http.StatusCreated)
	sum := c.do("GET", L+"/reports/summary?from=2026-10-01&to=2026-10-31&convert_to=INR", nil, http.StatusOK)
	conv := sum["converted"].(map[string]any)
	if num(conv["expense"]) != 130000+208750 || len(conv["missing_rates"].([]any)) != 0 {
		t.Fatalf("converted = %v", sum["_raw"])
	}
	c.do("GET", L+"/reports/summary?from=2026-10-01&to=2026-10-31&convert_to=rupees", nil, http.StatusBadRequest)

	// Tags report.
	tag := c.do("POST", L+"/records/tags", map[string]any{"data": map[string]any{"name": "Diwali"}}, http.StatusCreated)["record"].(map[string]any)["id"].(string)
	c.do("PATCH", L+"/records/transactions/"+t1, map[string]any{"data": map[string]any{"tag_ids": []any{tag}}}, http.StatusOK)
	tags := c.do("GET", L+"/reports/tags?from=2026-10-01&to=2026-10-31", nil, http.StatusOK)["tags"].([]any)
	if len(tags) != 1 || num(tags[0].(map[string]any)["expense"]) != 130000 {
		t.Fatalf("tags = %v", tags)
	}

	// Payees report: shape and limit validation.
	if _, ok := c.do("GET", L+"/reports/payees?from=2026-10-01&to=2026-10-31&limit=5", nil, http.StatusOK)["payees"].([]any); !ok {
		t.Fatal("payees missing")
	}
	c.do("GET", L+"/reports/payees?from=2026-10-01&to=2026-10-31&limit=0", nil, http.StatusBadRequest)

	// Amount filters take int64 minor units, beyond 32-bit range.
	c.do("GET", L+"/transactions?min_amount=1&max_amount=900000000000000", nil, http.StatusOK)
	c.do("GET", L+"/transactions?min_amount=-5", nil, http.StatusBadRequest)

	// Viewers cannot batch.
	viewer := signUp(t, base, "viewer@example.com", http.StatusCreated)
	c.do("POST", L+"/members", map[string]any{"email": "viewer@example.com", "role": "viewer"}, http.StatusOK)
	viewer.do("POST", L+"/batch", map[string]any{"ops": []any{tx(ids.NewV7(), bank, 1, "2026-10-05")}}, http.StatusForbidden)
}
